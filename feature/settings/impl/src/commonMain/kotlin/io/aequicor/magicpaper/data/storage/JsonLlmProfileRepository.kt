package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.ModelLimitCatalog
import io.aequicor.magicpaper.domain.withCatalogLimits
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Профили поставщиков. [modelLimits] — необязательный каталог заявленных пределов моделей
 * (например, каталог движка): он дополняет сохранённые факты при чтении, чтобы профиль,
 * записанный до появления источника, не занижал контекст. Объявленное провайдером не перезаписывается.
 */
class JsonLlmProfileRepository(
    private val store: KeyValueStore,
    private val json: Json,
    secrets: SecretStore = explicitOrTestSecrets(store),
    private val modelLimits: ModelLimitCatalog? = null,
) : LlmProfileRepository {
    private val credentials = CredentialRecords(secrets, store, "profiles-credential-cleanup")
    private val mutex = Mutex()

    override suspend fun load(): List<LlmProfile> = mutex.withLock { loadUnlocked() }

    private suspend fun loadUnlocked(): List<LlmProfile> {
        val original = readRecords()
        val migrated = original.map { record ->
            val id = (record["id"] as? JsonPrimitive)?.contentOrNull
                ?: throw StorageException("read profile id", StorageException.Kind.CORRUPT)
            credentials.migrate(compatible(record), SECRET_FIELDS, "profile:$id")
        }
        val profiles = migrated.map { json.decodeFromJsonElement(LlmProfile.serializer(), credentials.hydrate(it, SECRET_FIELDS)) }
            .map { it.withCatalogLimits(modelLimits) }
        if (migrated != original) {
            commit(migrated)
            AppLog.info("ProfileCredentials", "migration_committed", mapOf("storageArea" to "profiles", "count" to profiles.size.toString()))
        }
        credentials.clean(migrated)
        return profiles
    }

    override suspend fun save(profile: LlmProfile) = mutex.withLock {
        saveUnlocked(loadUnlocked().filterNot { it.id == profile.id } + profile)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        saveUnlocked(loadUnlocked().filterNot { it.id == id })
    }

    override suspend fun replaceAll(profiles: List<LlmProfile>) = mutex.withLock { saveUnlocked(profiles) }

    private suspend fun saveUnlocked(profiles: List<LlmProfile>) {
        val previous = readRecords()
        val records = profiles.map { credentials.encode(json.encodeToJsonElement(LlmProfile.serializer(), it) as JsonObject, SECRET_FIELDS) }
        credentials.stageCleanup(previous)
        commit(records)
        credentials.clean(records)
    }

    private fun readRecords(): List<JsonObject> {
        val raw = store.read(KEY_PROFILES) ?: return emptyList()
        return try {
            (json.parseToJsonElement(raw) as JsonArray).map { it as JsonObject }
        } catch (error: CancellationException) { throw error }
        catch (failure: Exception) { throw StorageException("read profiles", StorageException.Kind.CORRUPT, failure) }
    }

    private fun commit(records: List<JsonObject>) {
        if (records.isEmpty()) store.delete(KEY_PROFILES)
        else store.write(KEY_PROFILES, JsonArray(records).toString())
    }

    private fun compatible(record: JsonObject): JsonObject {
        val advanced = record["advanced"] as? JsonObject ?: return record
        return if (advanced["maxTokens"] != JsonNull) record
        else JsonObject(record + ("advanced" to JsonObject(advanced - "maxTokens")))
    }

    private companion object {
        const val KEY_PROFILES = "llm_profiles"
        val SECRET_FIELDS = setOf("apiKey")
    }
}
