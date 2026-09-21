package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Journal values contain opaque credential references, never their plaintext values. */
@Serializable
data class SettingsRecord(val value: AppSettings, val credentials: Map<String, String> = emptyMap()) {
    fun valid(): Boolean = value.queritApiKey.isEmpty() && value.queritContentApiKey.isEmpty() &&
        value.googleApiKey.isEmpty() && value.llmApiKey.isEmpty() &&
        credentials.keys.all { it in setOf("queritApiKey", "queritContentApiKey", "googleApiKey", "llmApiKey") } &&
        credentials.values.all { it.isNotBlank() }
}

@Serializable
data class SettingsProfileRecord(val value: LlmProfile, val credential: String? = null) {
    fun valid(): Boolean = value.id.isNotBlank() && value.apiKey.isEmpty() && credential?.isBlank() != true
}

@Serializable data class SettingsProfileRef(val id: String, val version: String)
@Serializable data class SettingsDossierKey(val profileId: String, val modelId: String)
@Serializable data class SettingsProfileEntry(val record: SettingsProfileRecord, val version: String)
@Serializable data class SettingsDossierEntry(val dossier: ModelDossier, val version: String)
@Serializable enum class SettingsCatalogKind { MODELS, PARAMETERS }
@Serializable data class SettingsCatalogRequest(val id: String, val profile: SettingsProfileRef, val kind: SettingsCatalogKind)
@Serializable data class SettingsDescriptionRequest(val id: String, val profile: SettingsProfileRef,
    val judge: SettingsProfileRef, val modelId: String, val settingsVersion: String, val dossierVersion: String?,
    val judgeModelId: String = "", val judgeEffort: EffortSelection = EffortSelection.Default)
@Serializable enum class SettingsChangePhase { PREPARING, APPLYING }
@Serializable data class SettingsChange(val id: String, val attemptId: String, val previousVersion: String,
    val next: SettingsRecord, val phase: SettingsChangePhase = SettingsChangePhase.PREPARING, val unknown: Boolean = false)

/** One configuration authority. Provider completions carry the identity captured before dispatch. */
object SettingsMachine : Machine<SettingsMachine.State, SettingsMachine.Input, SettingsMachine.Effect> {
    override val id = MachineId("settings")
    override val space get() = SettingsSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @ConsistentCopyVisibility
    data class State internal constructor(
        val initialized: Boolean = false,
        val settings: SettingsRecord = SettingsRecord(AppSettings()),
        val settingsVersion: String = "",
        val profiles: Map<String, SettingsProfileEntry> = emptyMap(),
        val dossiers: Map<SettingsDossierKey, SettingsDossierEntry> = emptyMap(),
        val profileTombstones: Map<String, String> = emptyMap(),
        val catalogs: Map<String, SettingsCatalogRequest> = emptyMap(),
        val descriptions: Map<String, SettingsDescriptionRequest> = emptyMap(),
        val interrupted: Set<String> = emptySet(),
        val persistenceUnknown: Boolean = false,
        val missingNeighbour: String? = null,
        val change: SettingsChange? = null,
    ) {
        fun profileRef(id: String): SettingsProfileRef? = profiles[id]?.let { SettingsProfileRef(id, it.version) }
    }

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("ChangeSettings") data class ChangeSettings(val expectedVersion: String,
            val next: SettingsRecord, val requestId: String) : Intent
        @Serializable @SerialName("RetrySettingsChange") data class RetrySettingsChange(val requestId: String, val attemptId: String) : Intent
        @Serializable @SerialName("SaveSettings") data class SaveSettings(val expectedVersion: String,
            val settings: SettingsRecord, val version: String) : Intent
        @Serializable @SerialName("SaveProfile") data class SaveProfile(val expected: SettingsProfileRef?,
            val profile: SettingsProfileRecord, val version: String) : Intent
        @Serializable @SerialName("DeleteProfile") data class DeleteProfile(val expected: SettingsProfileRef,
            val version: String) : Intent
        @Serializable @SerialName("SaveDossier") data class SaveDossier(val profile: SettingsProfileRef,
            val dossier: ModelDossier, val expectedVersion: String?, val version: String) : Intent
        @Serializable @SerialName("BeginCatalog") data class BeginCatalog(val request: SettingsCatalogRequest) : Intent
        @Serializable @SerialName("BeginDescription") data class BeginDescription(val request: SettingsDescriptionRequest) : Intent
        @Serializable @SerialName("Import") data class Import(val settings: SettingsRecord,
            val profiles: List<SettingsProfileRecord>, val dossiers: List<ModelDossier>, val version: String) : Intent
        @Serializable @SerialName("Clear") data class Clear(val version: String) : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("RuntimePrepared") data class RuntimePrepared(val requestId: String, val attemptId: String) : Fact
        @Serializable @SerialName("RuntimeApplied") data class RuntimeApplied(val requestId: String, val attemptId: String) : Fact
        @Serializable @SerialName("RuntimeUnknown") data class RuntimeUnknown(val requestId: String, val attemptId: String) : Fact
        @Serializable @SerialName("Initialized") data class Initialized(val settings: SettingsRecord,
            val profiles: List<SettingsProfileRecord>, val dossiers: List<ModelDossier>, val version: String) : Fact
        @Serializable @SerialName("CatalogLoaded") data class CatalogLoaded(val request: SettingsCatalogRequest,
            val models: List<ProviderModel>, val version: String) : Fact
        @Serializable @SerialName("DescriptionLoaded") data class DescriptionLoaded(val request: SettingsDescriptionRequest,
            val dossier: ModelDossier, val version: String) : Fact
        @Serializable @SerialName("OperationFailed") data class OperationFailed(val requestId: String) : Fact
        @Serializable @SerialName("NeighbourMissing") data class NeighbourMissing(val requestId: String, val neighbour: String) : Fact
        @Serializable @SerialName("Interrupted") data object Interrupted : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Reject(val reason: String) : Effect
        data class LoadCatalog(val request: SettingsCatalogRequest) : Effect
        data class ResearchDescription(val request: SettingsDescriptionRequest) : Effect
        data class ResultDiscarded(val requestId: String) : Effect
        data class PrepareRuntime(val requestId: String, val attemptId: String) : Effect
        data class ApplyRuntime(val requestId: String, val attemptId: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial() = State()

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Требуется повторно загрузить настройки")
        if (!state.initialized && input !is Fact.Initialized) return reject("Настройки ещё не загружены")
        if (state.change != null && input is Intent && input !is Intent.RetrySettingsChange)
            return reject("Применение предыдущих настроек не завершено; повторите применение")
        fun valid(settings: SettingsRecord): Boolean {
            if (!settings.valid()) return false
            return try { settings.value.agentLimits.validate(); true } catch (_: IllegalArgumentException) { false }
        }
        fun sameRuntimePolicy(next: AppSettings): Boolean = next.computerAccess == state.settings.value.computerAccess &&
            next.applicationAccess == state.settings.value.applicationAccess && next.agentLimits == state.settings.value.agentLimits
        fun matches(ref: SettingsProfileRef) = state.profileRef(ref.id) == ref
        fun catalogDone(request: SettingsCatalogRequest) = state.copy(catalogs = state.catalogs - request.id,
            interrupted = state.interrupted - request.id)
        fun descriptionDone(request: SettingsDescriptionRequest) = state.copy(descriptions = state.descriptions - request.id,
            interrupted = state.interrupted - request.id)
        fun replace(settings: SettingsRecord, profiles: List<SettingsProfileRecord>, dossiers: List<ModelDossier>, version: String): Transition {
            if (!valid(settings) || profiles.any { !it.valid() } || version.isBlank() ||
                profiles.map { it.value.id }.distinct().size != profiles.size ||
                dossiers.any { it.id.isBlank() || it.profileId.isBlank() } ||
                dossiers.map { SettingsDossierKey(it.profileId, it.modelId) }.distinct().size != dossiers.size)
                return reject("Некорректные настройки")
            val ids = profiles.map { it.value.id }.toSet()
            return Transition(state.copy(initialized = true, settings = settings, settingsVersion = version,
                profiles = profiles.associate { it.value.id to SettingsProfileEntry(it, version) },
                dossiers = dossiers.associate { SettingsDossierKey(it.profileId, it.modelId) to SettingsDossierEntry(it, version) },
                profileTombstones = state.profileTombstones + state.profiles.keys.filter { it !in ids }.associateWith { version },
                catalogs = emptyMap(), descriptions = emptyMap(), interrupted = emptySet(), missingNeighbour = null))
        }
        return when (input) {
            is Fact.Initialized -> if (state.initialized) reject("Настройки уже загружены")
                else replace(input.settings, input.profiles, input.dossiers, input.version)
            is Intent.Import -> if (!sameRuntimePolicy(input.settings.value)) reject("Сначала примените политику импортированных настроек")
                else replace(input.settings, input.profiles, input.dossiers, input.version)
            is Intent.Clear -> if (!sameRuntimePolicy(AppSettings())) reject("Сначала завершите сброс политики")
                else replace(SettingsRecord(AppSettings()), emptyList(), emptyList(), input.version)
            is Intent.ChangeSettings -> {
                if (!valid(input.next) || input.expectedVersion != state.settingsVersion || input.requestId.isBlank())
                    reject("Настройки изменились; повторите сохранение")
                else Transition(state.copy(change = SettingsChange(input.requestId, input.requestId, input.expectedVersion, input.next)),
                    listOf(Effect.PrepareRuntime(input.requestId, input.requestId)))
            }
            is Intent.RetrySettingsChange -> {
                val change = state.change
                if (change == null || change.id != input.requestId || !change.unknown || input.attemptId.isBlank() || input.attemptId == change.attemptId)
                    reject("Нет незавершённого применения настроек")
                else Transition(state.copy(change = change.copy(attemptId = input.attemptId, unknown = false)),
                    listOf(if (change.phase == SettingsChangePhase.PREPARING) Effect.PrepareRuntime(change.id, input.attemptId)
                        else Effect.ApplyRuntime(change.id, input.attemptId)))
            }
            is Fact.RuntimePrepared -> {
                val change = state.change
                if (change == null || change.id != input.requestId || change.attemptId != input.attemptId || change.unknown ||
                    change.phase != SettingsChangePhase.PREPARING || state.settingsVersion != change.previousVersion)
                    reject("Подготовка настроек относится к другой операции")
                else Transition(state.copy(settings = change.next, settingsVersion = change.id,
                    change = change.copy(phase = SettingsChangePhase.APPLYING)), listOf(Effect.ApplyRuntime(change.id, change.attemptId)))
            }
            is Fact.RuntimeApplied -> {
                val change = state.change
                if (change == null || change.id != input.requestId || change.attemptId != input.attemptId || change.unknown ||
                    change.phase != SettingsChangePhase.APPLYING || state.settingsVersion != change.id)
                    reject("Применение настроек относится к другой операции")
                else Transition(state.copy(change = null))
            }
            is Fact.RuntimeUnknown -> {
                val change = state.change
                if (change == null || change.id != input.requestId || change.attemptId != input.attemptId)
                    reject("Неизвестный исход относится к другой операции")
                else Transition(state.copy(change = change.copy(unknown = true)))
            }
            is Intent.SaveSettings -> {
                if (input.expectedVersion != state.settingsVersion || input.version.isBlank() || !valid(input.settings) ||
                    !sameRuntimePolicy(input.settings.value))
                    reject("Настройки изменились; повторите сохранение")
                else {
                    Transition(state.copy(settings = input.settings, settingsVersion = input.version, missingNeighbour = null))
                }
            }
            is Intent.SaveProfile -> {
                val id = input.profile.value.id
                if (!input.profile.valid() || input.version.isBlank() ||
                    (input.expected == null && id in state.profiles) ||
                    (input.expected != null && (input.expected.id != id || !matches(input.expected))))
                    reject("Источник изменился; повторите сохранение")
                else {
                    val settings = state.settings.value
                    val updated = if (settings.activeLlmProfileId.isBlank()) settings.copy(activeLlmProfileId = id,
                        defaultModel = input.profile.value.modelId.takeIf { it.isNotBlank() }?.let { ModelSelection(id, it) }) else settings
                    Transition(state.copy(profiles = state.profiles + (id to SettingsProfileEntry(input.profile, input.version)),
                        settings = state.settings.copy(value = updated),
                        settingsVersion = if (updated == settings) state.settingsVersion else input.version, missingNeighbour = null))
                }
            }
            is Intent.DeleteProfile -> {
                if (!matches(input.expected) || input.version.isBlank()) reject("Источник изменился; повторите удаление")
                else {
                    val profiles = state.profiles - input.expected.id
                    val settings = state.settings.value
                    val updated = settings.copy(activeLlmProfileId = if (settings.activeLlmProfileId == input.expected.id)
                        profiles.keys.firstOrNull().orEmpty() else settings.activeLlmProfileId,
                        defaultModel = settings.defaultModel?.takeUnless { it.profileId == input.expected.id })
                    Transition(state.copy(profiles = profiles, settings = state.settings.copy(value = updated),
                        settingsVersion = if (settings == updated) state.settingsVersion else input.version,
                        profileTombstones = state.profileTombstones + (input.expected.id to input.version),
                        dossiers = state.dossiers.filterKeys { it.profileId != input.expected.id }))
                }
            }
            is Intent.SaveDossier -> {
                val key = SettingsDossierKey(input.dossier.profileId, input.dossier.modelId)
                if (!matches(input.profile) || input.profile.id != key.profileId || input.dossier.id.isBlank() ||
                    input.version.isBlank() || state.dossiers[key]?.version != input.expectedVersion)
                    reject("Описание изменилось; повторите сохранение")
                else Transition(state.copy(dossiers = state.dossiers + (key to SettingsDossierEntry(input.dossier, input.version))))
            }
            is Intent.BeginCatalog -> {
                val request = input.request
                if (!matches(request.profile) || request.id.isBlank() || request.id in state.catalogs ||
                    state.catalogs.values.any { it.profile.id == request.profile.id }) reject("Каталог уже обновляется или источник изменён")
                else Transition(state.copy(catalogs = state.catalogs + (request.id to request), missingNeighbour = null),
                    listOf(Effect.LoadCatalog(request)))
            }
            is Fact.CatalogLoaded -> {
                val request = input.request
                if (state.catalogs[request.id] != request) reject("Ответ каталога не принадлежит запросу")
                else if (!matches(request.profile) || request.id in state.interrupted)
                    Transition(catalogDone(request), listOf(Effect.ResultDiscarded(request.id)))
                else {
                    val previous = state.profiles.getValue(request.profile.id).record
                    val updated = when (request.kind) {
                        SettingsCatalogKind.MODELS -> previous.value.copy(modelCatalog = input.models,
                            modelReasoning = input.models.mapNotNull { model -> model.reasoning?.let { model.id to it } }.toMap())
                        SettingsCatalogKind.PARAMETERS -> previous.value.withProviderParameters(input.models)
                    }
                    if (input.version.isBlank()) reject("Не указана версия каталога") else Transition(catalogDone(request).copy(
                        profiles = state.profiles + (request.profile.id to SettingsProfileEntry(previous.copy(value = updated), input.version))))
                }
            }
            is Intent.BeginDescription -> {
                val request = input.request
                val key = SettingsDossierKey(request.profile.id, request.modelId)
                if (!matches(request.profile) || !matches(request.judge) || request.settingsVersion != state.settingsVersion ||
                    request.dossierVersion != state.dossiers[key]?.version || request.id.isBlank() || request.id in state.descriptions)
                    reject("Настройки модели или описание изменились")
                else Transition(state.copy(descriptions = state.descriptions + (request.id to request), missingNeighbour = null),
                    listOf(Effect.ResearchDescription(request)))
            }
            is Fact.DescriptionLoaded -> {
                val request = input.request
                val key = SettingsDossierKey(request.profile.id, request.modelId)
                if (state.descriptions[request.id] != request) reject("Описание не принадлежит запросу")
                else if (!matches(request.profile) || !matches(request.judge) || request.settingsVersion != state.settingsVersion ||
                    request.dossierVersion != state.dossiers[key]?.version || request.id in state.interrupted)
                    Transition(descriptionDone(request), listOf(Effect.ResultDiscarded(request.id)))
                else if (input.dossier.profileId != request.profile.id || input.dossier.modelId != request.modelId ||
                    input.dossier.id.isBlank() || input.version.isBlank()) reject("Описание относится к другой модели")
                else Transition(descriptionDone(request).copy(dossiers = state.dossiers +
                    (key to SettingsDossierEntry(input.dossier, input.version))))
            }
            is Fact.OperationFailed -> Transition(state.copy(catalogs = state.catalogs - input.requestId,
                descriptions = state.descriptions - input.requestId, interrupted = state.interrupted - input.requestId))
            is Fact.NeighbourMissing -> Transition(state.copy(catalogs = state.catalogs - input.requestId,
                descriptions = state.descriptions - input.requestId, interrupted = state.interrupted - input.requestId,
                missingNeighbour = input.neighbour))
            Fact.Interrupted -> Transition(state.copy(interrupted = state.interrupted + state.catalogs.keys + state.descriptions.keys,
                change = state.change?.copy(unknown = true)))
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }
}

/** Only provider-default parameters are refreshed; explicit overrides remain user owned. */
private fun LlmProfile.withProviderParameters(models: List<ProviderModel>): LlmProfile {
    val defaults = AdvancedLlmOptions()
    return copy(modelLibraryVersion = 1, variants = variants.map { variant ->
        val fact = models.firstOrNull { it.id == variant.sourceModelId } ?: return@map variant
        val old = variant.options
        variant.copy(options = old.copy(contextLimit = if (old.contextLimit == defaults.contextLimit)
            fact.contextWindow?.takeIf { it > 0 } ?: old.contextLimit else old.contextLimit,
            maxTokens = if (old.maxTokens == defaults.maxTokens && !old.sendMaxTokens)
                fact.maxOutputTokens?.takeIf { it > 0 } ?: old.maxTokens else old.maxTokens))
    })
}
