package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class JsonSettingsRepository(
    private val store: KeyValueStore,
    private val json: Json,
    secrets: SecretStore = explicitOrTestSecrets(store),
) : SettingsRepository {
    private val credentials = CredentialRecords(secrets, store, "settings-credential-cleanup")
    private val mutex = Mutex()

    override suspend fun load(): AppSettings = mutex.withLock {
        val raw = store.read(KEY_SETTINGS) ?: run { credentials.clean(emptyList()); return@withLock AppSettings() }
        val original = parse(raw)
        val migrated = credentials.migrate(original, SECRET_FIELDS, "settings")
        val hydrated = credentials.hydrate(migrated, SECRET_FIELDS)
        val settings = json.decodeFromJsonElement(AppSettings.serializer(), hydrated)
        settings.agentLimits.validate()
        // All secret writes and reads succeeded before any ordinary source is scrubbed.
        if (migrated != original) {
            store.write(KEY_SETTINGS, migrated.toString())
            AppLog.info("SettingsCredentials", "migration_committed", mapOf("storageArea" to "settings"))
        }
        credentials.clean(listOf(migrated))
        settings
    }

    suspend fun save(settings: AppSettings) = mutex.withLock {
        settings.agentLimits.validate()
        val previous = store.read(KEY_SETTINGS)?.let(::parse)
        val saved = credentials.encode(json.encodeToJsonElement(AppSettings.serializer(), settings) as JsonObject, SECRET_FIELDS)
        credentials.stageCleanup(listOfNotNull(previous))
        store.write(KEY_SETTINGS, saved.toString())
        credentials.clean(listOf(saved))
    }

    suspend fun wipe() = mutex.withLock {
        val previous = store.read(KEY_SETTINGS)?.let(::parse)
        credentials.stageCleanup(listOfNotNull(previous))
        store.delete(KEY_SETTINGS)
        credentials.clean(emptyList())
    }

    private fun parse(raw: String): JsonObject = try {
        json.parseToJsonElement(raw) as? JsonObject ?: throw StorageException("read settings", StorageException.Kind.CORRUPT)
    } catch (error: CancellationException) { throw error }
    catch (failure: Exception) { throw StorageException("read settings", StorageException.Kind.CORRUPT, failure) }

    private companion object {
        const val KEY_SETTINGS = "settings"
        val SECRET_FIELDS = setOf("queritApiKey", "queritContentApiKey", "googleApiKey", "llmApiKey")
    }
}
