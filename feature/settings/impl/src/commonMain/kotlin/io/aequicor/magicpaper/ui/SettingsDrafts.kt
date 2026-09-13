package io.aequicor.magicpaper.ui

import androidx.compose.runtime.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.screens.AgentLimitsDraft
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Raw field strings are preserved even when they cannot yet become a valid domain value. */
@Serializable
internal data class SettingsFormDraft(
    val settings: AppSettings? = null,
    val profile: LlmProfile? = null,
    val agentLimits: AgentLimitsDraft = AgentLimitsDraft(),
    val fields: Map<String, String> = emptyMap(),
    val extras: Map<String, String> = emptyMap(),
    val page: Int = 0,
)

/** One controller per application keeps the same entity draft across multiple navigation visits. */
class SettingsDrafts(private val repository: DraftRepository, private val scope: CoroutineScope, private val json: Json) {
    private val sessions = mutableMapOf<String, DraftSession<SettingsFormDraft>>()
    private var deletedProfileIds by mutableStateOf(emptySet<String>())
    fun isProfileDeleted(id: String?) = id in deletedProfileIds
    fun allowProfiles(ids: Collection<String>) { deletedProfileIds -= ids.toSet() }
    internal fun session(key: String, initial: SettingsFormDraft): DraftSession<SettingsFormDraft> = sessions.getOrPut(key) {
        DraftSession(repository, key, SettingsFormDraft.serializer(), initial, scope, json,
            redact = { it.copy(settings = it.settings?.withoutSecrets(), profile = it.profile?.copy(apiKey = "")) },
            extractSecrets = { draft -> buildMap {
                draft.settings?.let { settings ->
                    put("querit", settings.queritApiKey); put("queritContent", settings.queritContentApiKey)
                    put("google", settings.googleApiKey); put("legacyLlm", settings.llmApiKey)
                }
                draft.profile?.let { put("profile", it.apiKey) }
            }.filterValues { it.isNotEmpty() } },
            hydrateSecrets = { draft, secrets -> draft.copy(
                settings = draft.settings?.copy(queritApiKey = secrets["querit"].orEmpty(),
                    queritContentApiKey = secrets["queritContent"].orEmpty(), googleApiKey = secrets["google"].orEmpty(),
                    llmApiKey = secrets["legacyLlm"].orEmpty()),
                profile = draft.profile?.copy(apiKey = secrets["profile"].orEmpty())) })
    }
    internal fun settings(initial: AppSettings) = session(SETTINGS, SettingsFormDraft(settings = initial, agentLimits = AgentLimitsDraft.from(initial.agentLimits)))
    internal fun profile(initial: LlmProfile) = session(profileKey(initial.id), SettingsFormDraft(profile = initial))
    internal fun welcome(initial: AppSettings) = session(WELCOME, SettingsFormDraft(settings = initial,
        profile = LlmProfile(id = Id.new(), name = "Мой источник", createdAt = Id.now())))
    internal fun variant(profile: LlmProfile, model: String): DraftSession<SettingsFormDraft> {
        val existing = profile.variants.firstOrNull { it.id == model }
        val options = existing?.options ?: profile.providerOptions(model)
        return session(variantKey(profile.id, model), SettingsFormDraft(fields = mapOf(
            "name" to (existing?.name ?: "${profile.modelName(model)} · свой вариант"),
            "temperature" to options.temperature?.toString().orEmpty(), "topP" to options.topP?.toString().orEmpty(),
            "maxTokens" to if (options.sendMaxTokens) options.maxTokens.toString() else "",
            "contextLimit" to options.contextLimit.toString(), "timeout" to options.timeoutSeconds.toString(),
            "history" to options.contextMessages.toString(), "prompt" to options.systemPromptOverride),
            extras = options.extraParameters.mapValues { it.value.toString() }))
    }
    internal fun description(profile: LlmProfile, model: String, dossier: ModelDossier?) = session(descriptionKey(profile.id, model),
        SettingsFormDraft(fields = mapOf("strengths" to dossier?.strengths.orEmpty(), "limitations" to dossier?.limitations.orEmpty(),
            "rating" to (dossier?.rating ?: 0).toString())))

    internal suspend fun existingProfile(id: String): LlmProfile? {
        if (isProfileDeleted(id)) return null
        sessions[profileKey(id)]?.let { it.awaitSaved(); return it.state.value.value.profile }
        val record = repository.load(profileKey(id)) ?: return null
        val initial = json.decodeFromString(SettingsFormDraft.serializer(), record.payload)
        val draft = session(profileKey(id), initial)
        draft.awaitSaved()
        return draft.state.value.value.profile?.takeIf { it.id == id }
    }
    internal data class SavePoint(val session: DraftSession<SettingsFormDraft>, val version: Long, val value: SettingsFormDraft)
    internal fun capture(key: String): SavePoint? = sessions[key]?.let { SavePoint(it, it.state.value.version, it.state.value.value) }
    internal suspend fun saved(point: SavePoint?) { point?.let { it.session.clearIfUnchanged(it.version, it.value) } }
    suspend fun awaitSaved() { sessions.values.forEach { it.awaitSaved() } }
    suspend fun removeProfile(id: String) {
        deletedProfileIds += id
        fun belongs(key: String): Boolean = key == profileKey(id) ||
            key.startsWith("settings:variant:" + Json.encodeToString(listOf(id)).dropLast(1) + ",") ||
            key.startsWith("settings:description:" + Json.encodeToString(listOf(id)).dropLast(1) + ",")
        val cached = sessions.keys.filter(::belongs)
        cached.forEach { sessions[it]?.revoke() }
        // Remove the profile fallback first, even if scanning secondary form records fails.
        repository.remove(profileKey(id))
        val allKeys = (cached + repository.keys("settings:").filter(::belongs)).distinct()
        for (key in allKeys) {
            if (key != profileKey(id)) repository.remove(key)
            sessions.remove(key)
        }
        sessions.remove(profileKey(id))
    }
    fun forget() { sessions.clear(); deletedProfileIds = emptySet() }
    companion object {
        internal const val SETTINGS = "settings:overview"
        internal const val WELCOME = "settings:welcome"
        internal fun profileKey(id: String) = "settings:profile:$id"
        internal fun variantKey(id: String, model: String) = "settings:variant:" + Json.encodeToString(listOf(id, model))
        internal fun descriptionKey(id: String, model: String) = "settings:description:" + Json.encodeToString(listOf(id, model))
    }
}

private fun AppSettings.withoutSecrets() = copy(queritApiKey = "", queritContentApiKey = "", googleApiKey = "", llmApiKey = "")

@Composable
internal fun <T, V> DraftSession<T>.field(read: (T) -> V, write: (T, V) -> T): MutableState<V> {
    val snapshot = state.collectAsState()
    return object : MutableState<V> {
        override var value: V
            get() = read(snapshot.value.value)
            set(value) { update { write(it, value) } }
        override fun component1() = value
        override fun component2(): (V) -> Unit = { value = it }
    }
}

@Composable
internal fun DraftSession<SettingsFormDraft>.textField(key: String, fallback: String = "") = field(
    { it.fields[key] ?: fallback }, { draft, value -> draft.copy(fields = draft.fields + (key to value)) })

@Composable
internal fun DraftSaveError(session: DraftSession<SettingsFormDraft>) {
    val snapshot by session.state.collectAsState()
    if (snapshot.error != null) {
        io.aequicor.magicpaper.designsystem.PaperText(
            if (snapshot.error?.committed == true) "Сохранено. Не удалось завершить очистку." else "Не удалось сохранить черновик",
            color = io.aequicor.magicpaper.designsystem.LocalPaperColors.current.error)
        io.aequicor.magicpaper.designsystem.PaperButton("Повторить", session::retry,
            kind = io.aequicor.magicpaper.designsystem.PaperButtonKind.QUIET)
    }
}
