package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** A detached UI projection; no caller receives the machine's mutable collection instances. */
data class SettingsConfigurationSnapshot(
    val initialized: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val profiles: List<LlmProfile> = emptyList(),
    val dossiers: List<ModelDossier> = emptyList(),
    val profileRefs: Map<String, SettingsProfileRef> = emptyMap(),
    val dossierVersions: Map<SettingsDossierKey, String> = emptyMap(),
    val settingsVersion: String = "",
    val unknown: Boolean = false,
    val unfinishedChange: String? = null,
    val interruptedRequests: Set<String> = emptySet(),
)
data class SettingsCatalogRefresh(val applied: Boolean, val models: List<ModelDefaults.DiscoveredModel>)

/** Application-owned configuration writer. Restoration never interprets recorded effects. */
class DefaultSettingsConfiguration(
    store: KeyValueStore,
    events: EventJournal,
    secrets: SecretStore,
    private val json: Json,
    modelLimits: ModelLimitCatalog? = null,
    private val directory: ModelDirectory? = null,
    private val researcher: DossierResearcher? = null,
    private val runtime: SettingsRuntimeParticipant? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SettingsCommands {
    private val lock = Mutex()
    private val journal = SettingsInputJournal(store, events, json)
    private val credentials = SettingsCredentialRecords(store, secrets, json, modelLimits)
    private val _state = MutableStateFlow(SettingsConfigurationSnapshot())
    val state: StateFlow<SettingsConfigurationSnapshot> = _state.asStateFlow()
    private var loaded = false

    suspend fun start() = owned { ensureLoaded() }

    /** Explicit recovery reloads evidence; it does not resume a catalog, model call or policy effect. */
    suspend fun reload() = owned { loaded = false; ensureLoaded() }

    suspend fun settings(): AppSettings = owned { ensureLoaded(); credentials.hydrate(journal.state.settings) }
    suspend fun profiles(): List<LlmProfile> = owned { ensureLoaded(); journal.state.profiles.values.map { credentials.hydrate(it.record) } }
    suspend fun dossiers(): List<ModelDossier> = owned { ensureLoaded(); journal.state.dossiers.values.map { copy(it.dossier) } }

    override suspend fun runtimePolicy(): SettingsRuntimePolicy = owned {
        if (!loaded) ensureLoaded()
        if (journal.state.persistenceUnknown || journal.state.change != null) SettingsRuntimePolicy.Unconfirmed
        else SettingsRuntimePolicy.Confirmed(credentials.hydrate(journal.state.settings))
    }

    private suspend fun <T> owned(block: suspend () -> T): T = withContext(dispatcher) { lock.withLock { block() } }

    private suspend fun ensureLoaded() {
        if (loaded) {
            check(!journal.state.persistenceUnknown) { "Требуется повторная загрузка настроек" }
            return
        }
        try {
            journal.restore(credentials::legacy)
            publish()
            loaded = true
            credentials.checkpoint(journal.state)
        } catch (failure: Throwable) {
            exposeFailure("restore", failure)
            throw failure
        }
    }

    private suspend fun publish() {
        val saved = journal.state
        val (settings, profiles) = try {
            credentials.hydrate(saved.settings) to saved.profiles.values.map { credentials.hydrate(it.record) }
        } catch (failure: Throwable) {
            journal.markUnknown()
            throw failure
        }
        _state.value = SettingsConfigurationSnapshot(true, settings, profiles,
            saved.dossiers.values.map { copy(it.dossier) },
            saved.profiles.keys.associateWith { checkNotNull(saved.profileRef(it)) },
            saved.dossiers.mapValues { it.value.version }, saved.settingsVersion,
            saved.persistenceUnknown || saved.change?.unknown == true,
            saved.change?.id, saved.interrupted.toSet())
    }

    private fun copy(dossier: ModelDossier): ModelDossier = json.decodeFromString(ModelDossier.serializer(),
        json.encodeToString(ModelDossier.serializer(), dossier))
    private fun copy(settings: AppSettings): AppSettings = json.decodeFromString(AppSettings.serializer(),
        json.encodeToString(AppSettings.serializer(), settings))
    private fun copy(profile: LlmProfile): LlmProfile = json.decodeFromString(LlmProfile.serializer(),
        json.encodeToString(LlmProfile.serializer(), profile))

    /** A new explicit command may inspect the journal again, but never replay its effects. */
    private suspend fun prepareCommand() {
        if (journal.state.persistenceUnknown) loaded = false
        ensureLoaded()
    }

    private fun exposeFailure(operation: String, failure: Throwable) {
        AppLog.error("settings.owner", "operation_failed", mapOf("operation" to operation,
            "causeType" to failure::class.simpleName.orEmpty()))
        _state.update { it.copy(unknown = journal.state.persistenceUnknown || journal.state.change?.unknown == true,
            unfinishedChange = journal.state.change?.id, interruptedRequests = journal.state.interrupted.toSet()) }
    }

    private suspend fun commit(input: SettingsMachine.Input): List<SettingsMachine.Effect> {
        // Reject before staging cleanup or writing a private payload.
        SettingsMachine.reduce(journal.state, input).effects.filterIsInstance<SettingsMachine.Effect.Reject>().firstOrNull()
            ?.let { throw SettingsInputRejected(it.reason) }
        credentials.stagePrevious(journal.state)
        val effects = try { journal.commit(input) }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try { publish() } catch (failure: Throwable) { cancelled.addSuppressed(failure) }
            }
            exposeFailure("commit_cancelled", cancelled)
            throw cancelled
        } catch (failure: Throwable) { exposeFailure("commit", failure); throw failure }
        // The configuration is committed even if a cache write or credential cleanup fails.
        try { publish(); credentials.checkpoint(journal.state) }
        catch (failure: Throwable) { exposeFailure("checkpoint", failure); throw failure }
        return effects
    }

    /** A user save revokes old runtime authority before its durable configuration commit. */
    suspend fun changeSettings(settings: AppSettings): Result<Unit> {
        val requested = copy(settings)
        val requestId = Id.new()
        val effects = try { owned {
            prepareCommand()
            val previous = journal.state.change
            if (previous != null) {
                require(previous.unknown && credentials.hydrate(previous.next) == requested) {
                    "Применение предыдущих настроек не завершено; повторите сохранение прежнего выбора"
                }
                commit(SettingsMachine.Intent.RetrySettingsChange(previous.id, requestId))
            } else commit(SettingsMachine.Intent.ChangeSettings(journal.state.settingsVersion, credentials.encode(requested), requestId))
        } } catch (failure: Throwable) {
            markChangeUnknown(requestId, failure)
            throw failure
        }
        return applyChange(effects)
    }

    suspend fun retrySettingsChange(): Result<Unit> {
        val attemptId = Id.new()
        val effects = try { owned {
            prepareCommand()
            val change = checkNotNull(journal.state.change) { "Нет незавершённого применения настроек" }
            commit(SettingsMachine.Intent.RetrySettingsChange(change.id, attemptId))
        } } catch (failure: Throwable) {
            markChangeUnknown(attemptId, failure)
            throw failure
        }
        return applyChange(effects)
    }

    private suspend fun markChangeUnknown(attemptId: String, failure: Throwable) = withContext(NonCancellable) {
        owned {
            val change = journal.state.change
            if (change?.attemptId == attemptId && !journal.state.persistenceUnknown) {
                // A cache failure must not recursively prevent the owner's recovery input.
                try { journal.commit(SettingsMachine.Fact.RuntimeUnknown(change.id, attemptId)); publish() }
                catch (recording: Throwable) { failure.addSuppressed(recording) }
            }
            exposeFailure("settings_change_interrupted", failure)
        }
    }

    private suspend fun applyChange(initial: List<SettingsMachine.Effect>): Result<Unit> {
        var effects = initial
        while (effects.isNotEmpty()) {
            val effect = effects.single()
            val requestId: String
            val attemptId: String
            when (effect) {
                is SettingsMachine.Effect.PrepareRuntime -> { requestId = effect.requestId; attemptId = effect.attemptId }
                is SettingsMachine.Effect.ApplyRuntime -> { requestId = effect.requestId; attemptId = effect.attemptId }
                else -> error("Unexpected settings effect")
            }
            try {
                val values = owned {
                    val change = checkNotNull(journal.state.change)
                    check(change.id == requestId && change.attemptId == attemptId && !change.unknown)
                    credentials.hydrate(journal.state.settings) to credentials.hydrate(change.next)
                }
                if (effect is SettingsMachine.Effect.PrepareRuntime) {
                    runtime?.prepare(values.first, values.second)
                    effects = owned { commit(SettingsMachine.Fact.RuntimePrepared(requestId, attemptId)) }
                } else {
                    runtime?.apply(values.second)
                    effects = owned { commit(SettingsMachine.Fact.RuntimeApplied(requestId, attemptId)) }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    owned {
                        val change = journal.state.change
                        if (change?.id == requestId && change.attemptId == attemptId && !journal.state.persistenceUnknown)
                            try { commit(SettingsMachine.Fact.RuntimeUnknown(requestId, attemptId)) }
                            catch (recording: Throwable) { failure.addSuppressed(recording) }
                        exposeFailure("apply_runtime", failure)
                    }
                }
                if (failure is CancellationException) throw failure
                val committed = owned { journal.state.settingsVersion == requestId }
                if (!committed) throw failure
                return Result.failure(failure)
            }
        }
        return Result.success(Unit)
    }

    override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = owned {
        prepareCommand()
        val current = journal.state
        commit(SettingsMachine.Intent.SaveSettings(current.settingsVersion,
            current.settings.copy(value = current.settings.value.copy(defaultCodingEngine = engine)), Id.new()))
        credentials.hydrate(journal.state.settings)
    }

    suspend fun setDefaultModel(selection: ModelSelection): AppSettings = owned {
        prepareCommand()
        val profiles = journal.state.profiles.values.map { credentials.hydrate(it.record) }
        require(ProfileResolver.selection(selection, profiles.filter { it.enabled }) != null) { "Модель недоступна" }
        val current = journal.state
        commit(SettingsMachine.Intent.SaveSettings(current.settingsVersion, current.settings.copy(value =
            current.settings.value.copy(defaultModel = selection, activeLlmProfileId = selection.profileId)), Id.new()))
        credentials.hydrate(journal.state.settings)
    }

    suspend fun saveMediaSelection(kind: MediaKind, selection: MediaModelSelection?): AppSettings = owned {
        prepareCommand()
        val current = journal.state
        commit(SettingsMachine.Intent.SaveSettings(current.settingsVersion, current.settings.copy(value = current.settings.value.copy(
            media = current.settings.value.media.withSelection(kind, selection))), Id.new()))
        credentials.hydrate(journal.state.settings)
    }

    suspend fun saveProfile(profile: LlmProfile, expected: SettingsProfileRef?) {
        val requested = copy(profile)
        owned {
            prepareCommand()
            require(journal.state.profileRef(requested.id) == expected) { "Источник изменился; повторите сохранение" }
            commit(SettingsMachine.Intent.SaveProfile(expected, credentials.encode(requested.copy(modelLibraryVersion = 1)), Id.new()))
        }
    }

    suspend fun deleteProfile(expected: SettingsProfileRef) = owned {
        prepareCommand()
        commit(SettingsMachine.Intent.DeleteProfile(expected, Id.new()))
        Unit
    }

    suspend fun setProfileEnabled(expected: SettingsProfileRef, enabled: Boolean) = owned {
        prepareCommand()
        require(journal.state.profileRef(expected.id) == expected) { "Источник изменился" }
        val record = journal.state.profiles.getValue(expected.id).record
        commit(SettingsMachine.Intent.SaveProfile(expected, record.copy(value = record.value.copy(enabled = enabled)), Id.new()))
        Unit
    }

    suspend fun saveDossier(dossier: ModelDossier, profile: SettingsProfileRef, expectedVersion: String?) {
        val requested = copy(dossier)
        owned { prepareCommand(); commit(SettingsMachine.Intent.SaveDossier(profile, requested, expectedVersion, Id.new())) }
    }

    /** Import merges the existing exported identities; callers first apply the imported runtime policy. */
    suspend fun importModels(profiles: List<LlmProfile>, dossiers: List<ModelDossier>) {
        val requestedProfiles = profiles.map(::copy)
        val requestedDossiers = dossiers.map(::copy)
        owned {
        prepareCommand()
        val saved = journal.state
        val encoded = requestedProfiles.map { credentials.encode(it.migrateModelLibrary()) }
        val mergedProfiles = saved.profiles.mapValues { it.value.record } + encoded.associateBy { it.value.id }
        val mergedDossiers = saved.dossiers.mapValues { it.value.dossier } + requestedDossiers.associateBy { SettingsDossierKey(it.profileId, it.modelId) }
        commit(SettingsMachine.Intent.Import(saved.settings, mergedProfiles.values.toList(), mergedDossiers.values.toList(), Id.new()))
        }
    }

    /** Called after the application reset coordinator has stopped writers and dropped old journals. */
    suspend fun clearAfterReset() = owned {
        loaded = false
        ensureLoaded()
        commit(SettingsMachine.Intent.Clear(Id.new()))
        Unit
    }

    suspend fun refreshCatalog(expected: SettingsProfileRef, kind: SettingsCatalogKind): SettingsCatalogRefresh {
        val (request, profile) = owned {
            prepareCommand()
            require(journal.state.profileRef(expected.id) == expected) { "Источник изменился" }
            // This is a fresh explicit request; an interrupted read is not resumed.
            journal.state.catalogs.values.filter { it.profile.id == expected.id && it.id in journal.state.interrupted }
                .forEach { commit(SettingsMachine.Fact.OperationFailed(it.id)) }
            val profile = credentials.hydrate(journal.state.profiles.getValue(expected.id).record)
            require(profile.connectionConfigured) { "Укажите адрес поставщика" }
            val request = SettingsCatalogRequest(Id.new(), expected, kind)
            commit(SettingsMachine.Intent.BeginCatalog(request))
            request to profile
        }
        val provider = directory ?: run {
            owned { commit(SettingsMachine.Fact.NeighbourMissing(request.id, "model-directory")) }
            error("Каталог моделей недоступен")
        }
        try {
            val models = provider.models(profile)
            val applied = owned { commit(SettingsMachine.Fact.CatalogLoaded(request,
                models.map { it.metadata ?: ProviderModel(it.id, reasoning = it.declared) }, Id.new()))
                .none { it is SettingsMachine.Effect.ResultDiscarded } }
            return SettingsCatalogRefresh(applied, if (applied) models.toList() else emptyList())
        } catch (failure: Throwable) { operationFailed(request.id, failure); throw failure }
    }

    suspend fun researchDescription(request: SettingsDescriptionRequest, onProgress: (String) -> Unit): Boolean {
        val inputs = owned {
            prepareCommand()
            commit(SettingsMachine.Intent.BeginDescription(request))
            Triple(credentials.hydrate(journal.state.profiles.getValue(request.profile.id).record).forModel(request.modelId),
                credentials.hydrate(journal.state.profiles.getValue(request.judge.id).record).let { judge ->
                    judge.forModel(request.judgeModelId.ifBlank { judge.modelId }, request.judgeEffort)
                }, credentials.hydrate(journal.state.settings))
        }
        val provider = researcher ?: run {
            owned { commit(SettingsMachine.Fact.NeighbourMissing(request.id, "dossier-researcher")) }
            error("Создание описаний недоступно")
        }
        try {
            val dossier = provider.research(inputs.first, inputs.second, inputs.third, onProgress)
            if (dossier.source == DossierSource.HEURISTIC) {
                owned { commit(SettingsMachine.Fact.OperationFailed(request.id)) }
                return false
            }
            return owned {
                val key = SettingsDossierKey(request.profile.id, request.modelId)
                commit(SettingsMachine.Fact.DescriptionLoaded(request, dossier.copy(
                    id = journal.state.dossiers[key]?.dossier?.id ?: Id.new(), updatedAt = Id.now()), Id.new()))
                    .none { it is SettingsMachine.Effect.ResultDiscarded }
            }
        } catch (failure: Throwable) { operationFailed(request.id, failure); throw failure }
    }

    private suspend fun operationFailed(requestId: String, failure: Throwable) = withContext(NonCancellable) {
        owned {
            if (!journal.state.persistenceUnknown) try { commit(SettingsMachine.Fact.OperationFailed(requestId)) }
            catch (recording: Throwable) { failure.addSuppressed(recording) }
            exposeFailure("provider_operation", failure)
        }
    }
}
