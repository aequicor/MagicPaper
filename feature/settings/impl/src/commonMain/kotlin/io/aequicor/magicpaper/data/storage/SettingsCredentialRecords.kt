package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Secret hydration is a live projection operation, never part of journal replay. */
internal class SettingsCredentialRecords(private val store: KeyValueStore, private val secrets: SecretStore,
    private val json: Json, private val modelLimits: ModelLimitCatalog?) {
    private val credentials = CredentialRecords(secrets, store, "settings-owner-credential-cleanup")

    suspend fun encode(settings: AppSettings): SettingsRecord {
        settings.agentLimits.validate()
        val encoded = credentials.encode(json.encodeToJsonElement(AppSettings.serializer(), settings) as JsonObject, SETTINGS_FIELDS)
        return SettingsRecord(json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(encoded - REFS)), references(encoded))
    }

    suspend fun encode(profile: LlmProfile): SettingsProfileRecord {
        val encoded = credentials.encode(json.encodeToJsonElement(LlmProfile.serializer(), profile) as JsonObject, PROFILE_FIELDS)
        return SettingsProfileRecord(json.decodeFromJsonElement(LlmProfile.serializer(), JsonObject(encoded - REFS)), references(encoded)["apiKey"])
    }

    suspend fun hydrate(record: SettingsRecord): AppSettings = json.decodeFromJsonElement(AppSettings.serializer(),
        credentials.hydrate(raw(record), SETTINGS_FIELDS))

    suspend fun hydrate(record: SettingsProfileRecord): LlmProfile = json.decodeFromJsonElement(LlmProfile.serializer(),
        credentials.hydrate(raw(record), PROFILE_FIELDS)).withCatalogLimits(modelLimits)

    suspend fun legacy(): SettingsMachine.Fact.Initialized {
        var settings = JsonSettingsRepository(store, json, secrets).load()
        var profiles = JsonLlmProfileRepository(store, json, secrets, modelLimits).load().map { it.migrateModelLibrary() }
        if (profiles.isEmpty()) ProfileMigrator.legacyProfile(settings)?.let { legacy ->
            profiles = listOf(legacy.migrateModelLibrary())
            settings = settings.copy(activeLlmProfileId = legacy.id)
        }
        if (settings.defaultModel == null) {
            val chosen = profiles.firstOrNull { it.id == settings.activeLlmProfileId && it.enabled && it.configured }
                ?: profiles.firstOrNull { it.enabled && it.configured }
            chosen?.let { settings = settings.copy(defaultModel = ModelSelection(it.id, it.modelId, it.effortSelectionFor())) }
        }
        val old = buildList {
            store.read("settings")?.let { add(json.parseToJsonElement(it) as JsonObject) }
            store.read("llm_profiles")?.let { addAll((json.parseToJsonElement(it) as JsonArray).map { row -> row as JsonObject }) }
        }
        val initial = SettingsMachine.Fact.Initialized(encode(settings), profiles.map { encode(it) },
            JsonModelDossierRepository(store, json).dossiers(), io.aequicor.magicpaper.util.Id.new())
        credentials.stageCleanup(old)
        return initial
    }

    fun stagePrevious(state: SettingsMachine.State) { credentials.stageCleanup(records(state)) }

    /** The journal already committed. A failed cache/secret cleanup cannot roll back its projection. */
    suspend fun checkpoint(state: SettingsMachine.State) {
        try {
            store.write("settings", raw(state.settings).toString())
            store.write("llm_profiles", JsonArray(state.profiles.values.map { raw(it.record) }).toString())
            store.write("model-dossiers", json.encodeToString(ListSerializer(ModelDossier.serializer()), state.dossiers.values.map { it.dossier }))
            credentials.clean(records(state))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: StorageException) {
            if (failure.committed) throw failure
            throw StorageException("checkpoint settings", StorageException.Kind.CLEANUP, failure, committed = true)
        } catch (failure: Exception) { throw StorageException("checkpoint settings", StorageException.Kind.CLEANUP, failure, committed = true) }
    }

    private fun records(state: SettingsMachine.State): List<JsonObject> = listOf(raw(state.settings)) +
        listOfNotNull(state.change?.next?.let(::raw)) + state.profiles.values.map { raw(it.record) }
    private fun raw(record: SettingsRecord): JsonObject = JsonObject(
        (json.encodeToJsonElement(AppSettings.serializer(), record.value) as JsonObject) - SETTINGS_FIELDS +
            (REFS to JsonObject(record.credentials.mapValues { JsonPrimitive(it.value) })))
    private fun raw(record: SettingsProfileRecord): JsonObject = JsonObject(
        (json.encodeToJsonElement(LlmProfile.serializer(), record.value) as JsonObject) - PROFILE_FIELDS +
            (REFS to JsonObject(record.credential?.let { mapOf("apiKey" to JsonPrimitive(it)) }.orEmpty())))
    private fun references(record: JsonObject) = (record[REFS] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
    private companion object {
        const val REFS = "secretReferences"
        val SETTINGS_FIELDS = setOf("queritApiKey", "queritContentApiKey", "googleApiKey", "llmApiKey")
        val PROFILE_FIELDS = setOf("apiKey")
    }
}
