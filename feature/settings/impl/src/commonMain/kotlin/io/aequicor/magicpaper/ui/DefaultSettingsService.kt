package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.data.storage.logPersistenceFailure
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.OpenAiSubscriptionService
import io.aequicor.magicpaper.domain.ProfileBundle
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.ProfileMigrator
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.domain.withGeneratedMedia
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json


/** Owns saved configuration and provider operations; routes are component outputs. */
class DefaultSettingsService(
    private val configuration: io.aequicor.magicpaper.data.storage.DefaultSettingsConfiguration,
    private val chats: ChatRepository,
    private val bridge: ProfileBridge,
    private val store: KeyValueStore,
    private val json: Json,
    private val skills: SkillRepository? = null,
    private val skillCommands: SkillCommands? = null,
    private val modelDirectory: ModelDirectory? = null,
    private val gateway: LlmGateway? = null,
    private val dossierResearcher: DossierResearcher? = null,
    private val openAiSubscription: OpenAiSubscriptionService? = null,
    private val searchConnectionChecker: SearchConnectionChecker? = null,
    val usage: UsageLedger,
    private val clearCodingOverrides: suspend (String) -> Unit = {},
    private val onDataChanged: suspend () -> Unit = {},
    private val onProfileSaved: (String) -> Unit = {},
    private val clearApplicationData: suspend (ApplicationDataReset) -> Unit = {},
    private val finishApplicationReset: suspend () -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val mediaGeneration: MediaGenerationService? = null,
    private val mediaStore: io.aequicor.magicpaper.data.storage.MediaStore = io.aequicor.magicpaper.data.storage.UnavailableMediaStore,
    private val chatHistory: ChatHistoryCommands,
    private val pluginPreferences: io.aequicor.magicpaper.plugins.PluginPreferences,
) : SettingsService {
    init { require((skills == null) == (skillCommands == null)) { "Skill reads and commands must share one owner" } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val profileAvailabilityMutex = Mutex()
    val drafts = SettingsDrafts(draftRepository, scope, json)
    private val _state = MutableStateFlow(SettingsState())
    override val state: StateFlow<SettingsState> = _state.asStateFlow()
    private var mediaStateJob: kotlinx.coroutines.Job? = null
    private var configurationJob: kotlinx.coroutines.Job? = null
    private fun observeMedia() {
        if (mediaStateJob?.isActive == true) return
        mediaGeneration?.let { service -> scope.launch {
            service.state.collect { connections -> _state.update { it.copy(mediaConnections = connections, mediaSupported = mediaStore.available) } }
        }.also { mediaStateJob = it } }
    }
    override suspend fun start() {
        observeMedia()
        configuration.start()
        reflectConfiguration()
        if (configuration.state.value.unknown) _state.update { it.copy(
            notice = "Применение настроек не завершено. Повторите сохранение прежнего выбора.") }
        if (configurationJob?.isActive != true) configurationJob = scope.launch {
            configuration.state.collect { saved ->
                if (saved.initialized) {
                    reflectConfiguration()
                }
            }
        }
        val profiles = configuration.profiles()
        drafts.allowProfiles(profiles.map { it.id })
        _state.update { it.copy(storageInfo = store.description,
            showWelcome = !it.settings.onboardingDone,
            openAiSubscription = it.openAiSubscription.copy(available = openAiSubscription != null)) }
    }
    private fun reflectConfiguration() {
        val saved = configuration.state.value
        if (saved.initialized) _state.update { it.copy(settings = saved.settings, llmProfiles = saved.profiles,
            modelDescriptions = saved.dossiers) }
    }
    override fun dismissNotice() { _state.update { it.copy(notice = null) } }

    /**
     * Разрешённый профиль кодинг-сессии: переопределение сессии важнее глобального;
     * порядок тот же, что у чата (см. [ProfileResolver]).
     */
    override suspend fun checkSearchConnection(connection: SearchConnection, draft: AppSettings): SearchConnectionResult =
        searchConnectionChecker?.check(connection, draft)
            ?: SearchConnectionResult(false, "Проверка подключения недоступна.")

    override fun finishOnboarding(settings: AppSettings, onboardingProfile: LlmProfile?) {
        val draftPoint = drafts.capture(SettingsDrafts.WELCOME)
        scope.launch {
            try {
                val withProfile = onboardingProfile != null && onboardingProfile.configured &&
                    (onboardingProfile.provider != ProviderType.OPENAI_SUBSCRIPTION || openAiSubscriptionSignedIn())
                if (withProfile) configuration.saveProfile(onboardingProfile.migrateModelLibrary(), configuration.state.value.profileRefs[onboardingProfile.id])
                val done = settings.copy(
                    onboardingDone = true,
                    activeLlmProfileId = if (withProfile && settings.activeLlmProfileId.isBlank()) {
                        onboardingProfile.id
                    } else {
                        settings.activeLlmProfileId
                    },
                )
                configuration.changeSettings(done).getOrThrow()
                clearSavedDraft(draftPoint)
                _state.update {
                    it.copy(
                        settings = done,
                        llmProfiles = if (withProfile) configuration.profiles() else it.llmProfiles,
                        showWelcome = false,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("finish_onboarding", failure, "Не удалось сохранить настройки. Повторите действие.") }
        }
    }

    /** Вернуться к туториалу (кнопка в настройках). */
    override fun restartOnboarding() = _state.update { it.copy(showWelcome = true) }

    /** The overview draft does not own automation policy; never replay its old permissions. */
    internal fun saveOverviewSettings(settings: AppSettings) = saveSettings(settings.copy(
        computerAccess = _state.value.settings.computerAccess, applicationAccess = _state.value.settings.applicationAccess,
        media = _state.value.settings.media))

    override fun saveComputerAccess(computer: ComputerAccess, application: ComputerAccess) =
        saveSettings(_state.value.settings.copy(computerAccess = computer, applicationAccess = application), clearOverviewDraft = false)

    override fun saveSettings(settings: AppSettings) = saveSettings(settings, clearOverviewDraft = true)

    private fun saveSettings(settings: AppSettings, clearOverviewDraft: Boolean) {
        if (_state.value.settingsSaving) return
        _state.update { it.copy(settingsSaving = true) }
        AppLog.info("SettingsService", "save_settings_requested")
        val draftPoint = if (clearOverviewDraft) drafts.capture(SettingsDrafts.SETTINGS) else null
        scope.launch {
            try {
            val applied = try {
                configuration.changeSettings(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                persistenceFailed("save_settings", error, "Не удалось сохранить настройки. Повторите действие.")
                return@launch
            }
            val notice = applied.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
                logPersistenceFailure("SettingsService", "apply_limits_failed", it)
                "Настройки сохранены. Не удалось применить ограничения."
            }
                ?: "Настройки сохранены."
            val cleared = clearSavedDraft(draftPoint)
            _state.update { it.copy(settings = settings, notice = if (cleared) notice else it.notice) }
            mediaGeneration?.refreshAvailability()
            } finally { _state.update { it.copy(settingsSaving = false) } }
        }
    }

    // ---- Магические источники (профили подключения) -----------------------

    /** Сохранить профиль (создание или обновление) и обновить состояние.
     * Первый сохранённый профиль становится активным автоматически. */
    override fun saveLlmProfile(profile: LlmProfile) {
        AppLog.info("SettingsService", "save_profile_requested")
        if (drafts.isProfileDeleted(profile.id)) {
            _state.update { it.copy(notice = "Источник уже удалён.") }
            return
        }
        val expected = configuration.state.value.profileRefs[profile.id]
        val draftPoint = drafts.capture(SettingsDrafts.profileKey(profile.id))
        scope.launch {
            try {
                configuration.saveProfile(profile.copy(modelLibraryVersion = 1), expected)
                val profiles = configuration.profiles()
                val updated = configuration.settings()
                _state.update {
                    it.copy(
                        settings = updated,
                        llmProfiles = profiles,
                        notice = "Источник «${profile.name}» сохранён.",
                    )
                }
                onProfileSaved(profile.id)
                clearSavedDraft(draftPoint)
                mediaGeneration?.refreshAvailability()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_profile", failure, "Не удалось сохранить источник. Повторите действие.") }
        }
    }

    /** Удалить профиль; если он был активным — активным станет первый оставшийся. */

    override fun deleteLlmProfile(id: String) {
        AppLog.info("SettingsService", "delete_profile_requested")
        val expected = configuration.state.value.profileRefs[id] ?: return
        scope.launch {
            try {
                var credentialCleanupFailed = false
                try { configuration.deleteProfile(expected) }
                catch (failure: StorageException) {
                    if (!failure.committed) throw failure
                    logPersistenceFailure("SettingsService", "profile_credential_cleanup_failed", failure)
                    credentialCleanupFailed = true
                }
                val draftCleanupFailed = try { drafts.removeProfile(id); false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { logPersistenceFailure("SettingsService", "profile_draft_cleanup_failed", failure); true }
                val profiles = if (credentialCleanupFailed) _state.value.llmProfiles.filterNot { it.id == id } else configuration.profiles()
                val updatedSettings = configuration.settings()
                chatHistory.unlinkProfile(id)
                clearCodingOverrides(id)
                if (credentialCleanupFailed) _state.update { it.copy(settings = updatedSettings, llmProfiles = profiles) } else start()
                onDataChanged()
                _state.update { it.copy(notice = if (draftCleanupFailed || credentialCleanupFailed) "Источник удалён. Не удалось завершить очистку. Повторите действие." else "Источник удалён.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("delete_profile", failure, "Не удалось завершить удаление источника. Повторите действие.") }
        }
    }
    override fun setDefaultModel(selection: ModelSelection) {
        if (ProfileResolver.selection(selection, _state.value.availableLlmProfiles) == null) return
        scope.launch {
            try { configuration.setDefaultModel(selection); reflectConfiguration() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("select_default_model", failure, "Не удалось сохранить модель по умолчанию. Повторите действие.") }
        }
    }

    override fun setLlmProfileEnabled(id: String, enabled: Boolean) {
        val profile = _state.value.llmProfiles.firstOrNull { it.id == id } ?: return
        if (profile.enabled == enabled) return
        val expected = configuration.state.value.profileRefs[id] ?: return
        scope.launch {
            profileAvailabilityMutex.withLock {
                try {
                    configuration.setProfileEnabled(expected, enabled)
                    reflectConfiguration()
                    mediaGeneration?.refreshAvailability()
                    onDataChanged()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    persistenceFailed("set_profile_enabled", failure, "Не удалось изменить доступность поставщика. Повторите действие.")
                }
            }
        }
    }

    override fun updateModelLibrary(profile: LlmProfile) {
        val old = _state.value.llmProfiles.firstOrNull { it.id == profile.id }
        val removedDefault = old?.variants?.firstOrNull { it.id == profile.modelId && profile.variants.none { variant -> variant.id == it.id } }
        val next = profile.copy(modelLibraryVersion = 1, modelId = removedDefault?.sourceModelId ?: profile.modelId)
        val expected = configuration.state.value.profileRefs[profile.id] ?: return
        scope.launch {
            try { configuration.saveProfile(next, expected); reflectConfiguration() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("update_model_library", failure, "Не удалось сохранить модели. Повторите действие.") }
        }
    }

    override fun refreshModelCatalog(id: String) {
        val expected = configuration.state.value.profileRefs[id] ?: return
        if (id in _state.value.catalogRefreshing) return
        _state.update { it.copy(catalogRefreshing = it.catalogRefreshing + id) }
        scope.launch {
            try {
                val result = configuration.refreshCatalog(expected, SettingsCatalogKind.MODELS)
                reflectConfiguration()
                if (!result.applied) _state.update { it.copy(notice = "Источник изменился. Повторите обновление каталога.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(notice = "Не удалось обновить каталог. Повторите действие.") }
            } finally { _state.update { it.copy(catalogRefreshing = it.catalogRefreshing - id) } }
        }
    }

    override fun saveModelDescription(dossier: ModelDossier) {
        val expected = configuration.state.value.profileRefs[dossier.profileId] ?: return
        val version = configuration.state.value.dossierVersions[SettingsDossierKey(dossier.profileId, dossier.modelId)]
        scope.launch {
            try {
                configuration.saveDossier(dossier.copy(id = dossier.id.ifBlank { Id.new() }, source = DossierSource.USER,
                    updatedAt = Id.now()), expected, version)
                reflectConfiguration()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_model_description", failure, "Не удалось сохранить описание. Повторите действие.") }
        }
    }

    internal fun saveVariantDraft(profile: LlmProfile, model: String, onSaved: () -> Unit) {
        val point = drafts.capture(SettingsDrafts.variantKey(profile.id, model))
        val expected = configuration.state.value.profileRefs[profile.id] ?: return
        scope.launch {
            try {
                val updated = profile.copy(modelLibraryVersion = 1)
                configuration.saveProfile(updated, expected)
                reflectConfiguration()
                clearSavedDraft(point)
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_variant", failure, "Не удалось сохранить вариант. Повторите действие.") }
        }
    }

    internal fun saveDescriptionDraft(dossier: ModelDossier, onSaved: () -> Unit) {
        val point = drafts.capture(SettingsDrafts.descriptionKey(dossier.profileId, dossier.modelId))
        val expected = configuration.state.value.profileRefs[dossier.profileId] ?: return
        val version = configuration.state.value.dossierVersions[SettingsDossierKey(dossier.profileId, dossier.modelId)]
        scope.launch {
            try {
                configuration.saveDossier(dossier.copy(id = dossier.id.ifBlank { Id.new() }, source = DossierSource.USER,
                    updatedAt = Id.now()), expected, version)
                reflectConfiguration()
                clearSavedDraft(point)
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_description", failure, "Не удалось сохранить описание. Повторите действие.") }
        }
    }

    private suspend fun clearSavedDraft(point: SettingsDrafts.SavePoint?): Boolean {
        try { drafts.saved(point); return true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logPersistenceFailure("SettingsService", "clear_saved_draft_failed", failure)
            _state.update { it.copy(notice = "Данные сохранены. Не удалось завершить очистку черновика.") }
            return false
        }
    }

    private fun persistenceFailed(event: String, failure: Exception, message: String) {
        logPersistenceFailure("SettingsService", event, failure)
        _state.update { it.copy(notice = if (failure is StorageException && failure.committed)
            "Данные сохранены. Не удалось завершить очистку. Повторите действие." else message) }
    }

    override fun generateModelDescriptions() {
        if (_state.value.descriptionsGenerating) return
        val captured = configuration.state.value
        val snapshot = _state.value.copy(settings = captured.settings, llmProfiles = captured.profiles)
        val judge = ProfileResolver.resolve(null as ChatSession?, snapshot.settings, snapshot.availableLlmProfiles)
        if (judge == null) { _state.update { it.copy(notice = "Сначала выберите модель по умолчанию.") }; return }
        val judgeRef = captured.profileRefs[judge.id] ?: return
        val targets = snapshot.availableLlmProfiles.flatMap { profile -> profile.displayModels.map { profile.forModel(it) } }
        if (targets.isEmpty()) { _state.update { it.copy(notice = "Добавьте избранные модели.") }; return }
        // Capture every reference before dispatch: later profiles/settings/edits cannot be silently adopted.
        val requests = targets.map { target ->
            SettingsDescriptionRequest(Id.new(), checkNotNull(captured.profileRefs[target.id]), judgeRef,
                target.selectionKey, captured.settingsVersion,
                captured.dossierVersions[SettingsDossierKey(target.id, target.selectionKey)],
                judgeModelId = judge.selectionKey, judgeEffort = judge.effort)
        }
        _state.update { it.copy(descriptionsGenerating = true, descriptionsErrors = emptyList(),
            descriptionsContext = "${judge.shortLabel} · ${judge.completionEngineLabel}. Поиск: ${snapshot.settings.descriptionSearchLabel()}") }
        scope.launch {
            var failures = 0
            try {
                requests.forEachIndexed { index, request ->
                    val target = targets[index]
                    val label = "${index + 1}/${targets.size} · ${target.modelName(target.selectionKey)}"
                    _state.update { it.copy(descriptionsProgress = label) }
                    val saved = try {
                        configuration.researchDescription(request) { progress ->
                            _state.update { it.copy(descriptionsProgress = "$label\n$progress") }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { false } // The configuration owner records and reports provider/storage failure.
                    if (!saved) {
                        failures++
                        _state.update { it.copy(descriptionsErrors = it.descriptionsErrors +
                            "${target.shortLabel}: не удалось обновить описание. Прежнее описание сохранено.") }
                    }
                    reflectConfiguration()
                }
                _state.update { it.copy(notice = if (failures == 0) "Описания созданы по интернет-источникам." else
                    "Не удалось создать описания для $failures моделей. Прежние описания сохранены. Повторите запрос.") }
            } finally { _state.update { it.copy(descriptionsGenerating = false, descriptionsProgress = null) } }
        }
    }

    // Ephemeral projection identity: a late refresh/login must not overwrite a later logout/cancel.
    private var subscriptionOperation = 0L

    override fun refreshOpenAiSubscription(refreshToken: Boolean) {
        val service = openAiSubscription ?: return
        if (_state.value.openAiSubscription.let { it.loading || it.signingIn }) return
        val operation = ++subscriptionOperation
        _state.update { it.copy(openAiSubscription = it.openAiSubscription.copy(loading = true, error = null)) }
        scope.launch {
            try {
                val account = service.account(refreshToken)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(account = account))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                providerFailed("subscription_refresh", failure)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(error = "Не удалось обновить состояние подписки. Повторите запрос."))
                }
            } finally { if (operation == subscriptionOperation) _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(loading = false))
            } }
        }
    }

    /** Запустить OAuth в браузере; URL открывает UI через LocalUriHandler. */
    override fun startOpenAiSubscriptionLogin() {
        val service = openAiSubscription ?: return
        if (_state.value.openAiSubscription.let { it.signingIn || it.loading }) return
        val operation = ++subscriptionOperation
        _state.update { it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = true, login = null, error = null)) }
        scope.launch {
            try {
                val login = service.startLogin()
                if (operation != subscriptionOperation) return@launch
                _state.update { it.copy(openAiSubscription = it.openAiSubscription.copy(login = login)) }
                val account = service.awaitLogin(login.id)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(account = account))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                providerFailed("subscription_login", failure)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(error = "Не удалось завершить вход. Повторите действие."))
                }
            } finally { if (operation == subscriptionOperation) _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null))
            } }
        }
    }

    override fun cancelOpenAiSubscriptionLogin() {
        val service = openAiSubscription ?: return
        val login = _state.value.openAiSubscription.login ?: return
        val operation = ++subscriptionOperation
        scope.launch {
            try {
                service.cancelLogin(login.id)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null, error = null))
                }
            } catch (cancelled: CancellationException) {
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null))
                }
                throw cancelled
            }
            catch (failure: Exception) {
                providerFailed("subscription_login_cancel", failure)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(error = "Не удалось отменить вход. Повторите действие."))
                }
            }
        }
    }

    override fun logoutOpenAiSubscription() {
        val service = openAiSubscription ?: return
        val operation = ++subscriptionOperation
        _state.update { it.copy(openAiSubscription = it.openAiSubscription.copy(loading = true, error = null)) }
        scope.launch {
            try {
                service.logout()
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(account = null, login = null, signingIn = false))
                }
            } catch (cancelled: CancellationException) {
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null))
                }
                throw cancelled
            }
            catch (failure: Exception) {
                providerFailed("subscription_logout", failure)
                if (operation == subscriptionOperation) _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(error = "Не удалось выйти из аккаунта. Повторите действие."))
                }
            } finally { if (operation == subscriptionOperation) _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(loading = false))
            } }
        }
    }

    override fun openAiSubscriptionSignedIn(): Boolean =
        _state.value.openAiSubscription.account?.signedIn == true

    /** Загрузить список моделей, доступных у провайдера черновика профиля. */
    override fun fetchModels(draft: LlmProfile) {
        val directory = modelDirectory
        if (directory == null) {
            _state.update { it.copy(editorModelsError = "Каталог моделей недоступен на этой платформе.") }
            return
        }
        if (!draft.connectionConfigured) {
            val message = if (draft.provider == io.aequicor.magicpaper.domain.ProviderType.OPENAI_SUBSCRIPTION) {
                "Войдите в ChatGPT и укажите модель."
            } else "Укажите адрес поставщика, затем повторите."
            _state.update { it.copy(editorModelsError = message) }
            return
        }
        if (_state.value.editorModelsLoading) return
        _state.update { it.copy(editorModelsLoading = true, editorModelsError = null) }
        scope.launch {
            try {
                val models = directory.models(draft)
                _state.update { it.copy(editorModels = models, editorModelsFor = "${draft.id}:${draft.provider}:${draft.baseUrl}") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                providerFailed("draft_catalog", failure)
                _state.update { it.copy(editorModelsError = failure.providerReason(
                    "Не удалось загрузить список моделей. Повторите запрос.")) }
            } finally { _state.update { it.copy(editorModelsLoading = false) } }
        }
    }

    /** Обновить параметры вариантов из каталога провайдера (если они не переопределены вручную). */
    override fun refreshProfileParameters(profileId: String) {
        val captured = configuration.state.value
        val expected = captured.profileRefs[profileId] ?: return
        val profile = captured.profiles.firstOrNull { it.id == profileId } ?: return
        if (!profile.connectionConfigured) {
            _state.update { it.copy(editorModelsError = "Укажите адрес поставщика, затем повторите.") }
            return
        }
        if (_state.value.editorModelsLoading) return
        _state.update { it.copy(editorModelsLoading = true, editorModelsError = null) }
        scope.launch {
            try {
                val result = configuration.refreshCatalog(expected, SettingsCatalogKind.PARAMETERS)
                reflectConfiguration()
                if (result.applied) _state.update { it.copy(editorModels = result.models,
                    editorModelsFor = "${profile.id}:${profile.provider}:${profile.baseUrl}",
                    notice = "Параметры актуализированы из каталога поставщика ✓") }
                else _state.update { it.copy(editorModelsError = "Источник изменился. Повторите обновление параметров.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.update { it.copy(editorModelsError = "Не удалось обновить параметры. Повторите действие.") }
            } finally { _state.update { it.copy(editorModelsLoading = false) } }
        }
    }

    /** Проверка подключения: тестовый запрос к модели профиля. */

    override fun testConnection(draft: LlmProfile) {
        val testGateway = gateway
        if (testGateway == null) {
            _state.update { it.copy(editorModelsError = "Проверка недоступна на этой платформе.") }
            return
        }
        if (!draft.configured) {
            val message = if (draft.provider == io.aequicor.magicpaper.domain.ProviderType.OPENAI_SUBSCRIPTION) {
                "Войдите в ChatGPT и укажите модель."
            } else "Укажите Base URL и имя модели, затем повторите."
            _state.update { it.copy(editorModelsError = message) }
            return
        }
        if (_state.value.connectionTesting) return
        _state.update { it.copy(connectionTesting = true, editorModelsError = null) }
        scope.launch {
            try {
                testGateway.complete(draft, listOf(LlmMessage(LlmChatRole.USER, "Скажи одно слово: ✦")))
                _state.update { it.copy(notice = "Подключение работает ✓") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                providerFailed("connection_check", failure)
                _state.update { it.copy(editorModelsError = failure.providerReason(
                    "Проверка не удалась. Проверьте подключение и повторите запрос."), notice = null) }
            } finally { _state.update { it.copy(connectionTesting = false) } }
        }
    }

    private fun providerFailed(operation: String, failure: Exception) {
        val rejection = failure.transportRejection()
        AppLog.error("SettingsService", operation + "_failed", IllegalStateException("Provider operation failed"),
            mapOf("causeType" to (failure::class.simpleName ?: "Failure")) + (rejection?.logFields() ?: emptyMap()))
    }

    /**
     * Причина отказа провайдера словами приложения: у человека должно появиться действие
     * (сменить адрес подключения, модель или ключ), а не общий совет повторить запрос.
     * Тело ответа провайдера в поле не попадает.
     */
    private fun Exception.providerReason(fallback: String): String = transportRejection()?.safeReason() ?: fallback

    override fun exportProfile() {
        scope.launch {
            try {
            val s = _state.value
            val operations = mediaGeneration?.operations?.value.orEmpty()
            val sessions = chats.sessions().map { it.withGeneratedMedia(operations) }
            val bundle = ProfileBundle(
                exportedAt = Id.now(),
                settings = configuration.settings().copy(computerAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF,
                    applicationAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF),
                plugins = pluginPreferences.exportPreferences(),
                sessions = sessions,
                generatedAssets = exportMediaAssets(sessions, mediaStore),
                skills = skills?.all().orEmpty(),
                llmProfiles = configuration.profiles(),
                modelDescriptions = configuration.dossiers(),
                usage = usage.exportArchive(),
            )
            val encoded = withContext(Dispatchers.Default) { json.encodeToString(ProfileBundle.serializer(), bundle) }
            val ok = bridge.export(encoded)
            _state.update {
                it.copy(notice = if (ok) "Профиль экспортирован." else "Не удалось экспортировать профиль.")
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                logPersistenceFailure("SettingsService", "export_failed", failure)
                _state.update { it.copy(notice = "Не удалось экспортировать профиль. Проверьте доступность сохранённых файлов и повторите действие.") }
            }
        }
    }

    override fun importProfile() {
        if (_state.value.settingsSaving) return
        // Capture before the file picker or any provider work can suspend. A reset or an edit
        // while the dialog is open must not be overwritten by this old import request.
        val skillsRevision = skillCommands?.catalog?.value?.revision
        _state.update { it.copy(settingsSaving = true) }
        scope.launch {
            try {
            val raw = bridge.import()
            if (raw == null) {
                _state.update { it.copy(notice = "Импорт отменён или недоступен на этой платформе.") }
                return@launch
            }
            val bundle = try { withContext(Dispatchers.Default) { json.decodeFromString(ProfileBundle.serializer(), raw) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                logPersistenceFailure("SettingsService", "import_decode_failed", failure)
                _state.update { st -> st.copy(notice = "Файл профиля повреждён. Выберите другой файл.") }
                return@launch
            }
            // Revoke before importing any other data, including a suspended/failed usage write.
            // Imported data cannot authorize this machine or retain an active lease.
            val importedSettings = bundle.settings.copy(computerAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF,
                applicationAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF)
            importMediaAssets(bundle, mediaStore)
            configuration.changeSettings(importedSettings).getOrThrow()
            usage.replace(bundle.usage)
            pluginPreferences.importPreferences(bundle.plugins)
            chatHistory.importNotebooks(bundle.sessions)
            if (skillCommands != null) skillCommands.importSkills(bundle.skills, checkNotNull(skillsRevision))
            configuration.importModels(bundle.llmProfiles, bundle.modelDescriptions)
            start()
            onDataChanged()
            _state.update { it.copy(notice = "Профиль импортирован.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                logPersistenceFailure("SettingsService", "import_failed", error)
                _state.update { it.copy(notice = "Не удалось завершить импорт. Часть данных могла сохраниться. Проверьте настройки и повторите импорт.") }
            } finally { _state.update { it.copy(settingsSaving = false) } }
        }
    }


    override fun wipeAll() = reset(ApplicationDataReset.ALL)
    override fun resetSessions() = reset(ApplicationDataReset.SESSIONS)

    private fun reset(erase: ApplicationDataReset) {
        scope.launch {
            var failure: Throwable? = null
            fun retain(next: Throwable) {
                val previous = failure
                if (previous == null) failure = next
                else if (next !== previous) {
                    if (next is CancellationException && previous !is CancellationException) {
                        next.addSuppressed(previous)
                        failure = next
                    } else previous.addSuppressed(next)
                }
            }
            try {
                clearApplicationData(erase)
                val everything = erase == ApplicationDataReset.ALL
                // Settings drafts, the usage ledger, plugin preferences and configuration are what a sessions reset keeps.
                if (everything) { drafts.forget(); usage.clear() }
                chatHistory.wipeHistory()
                if (everything) { pluginPreferences.clearPreferences(); configuration.clearAfterReset() }
                start()
            } catch (error: Throwable) { retain(error) }
            // Every participant must leave reset even if clearing failed or was cancelled.
            withContext(NonCancellable) {
                try { finishApplicationReset() } catch (cleanup: Throwable) { retain(cleanup) }
            }
            try { currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) { retain(cancelled) }
            if (failure == null) try { onDataChanged() } catch (refresh: Throwable) { retain(refresh) }
            when (val error = failure) {
                null -> _state.update { it.copy(notice = if (erase == ApplicationDataReset.ALL) "Все данные удалены."
                    else "Проекты, сессии и чаты удалены.") }
                is CancellationException -> {
                    if (error.suppressedExceptions.isNotEmpty()) {
                        AppLog.error("SettingsService", "reset_cleanup_failed",
                            IllegalStateException("Reset cleanup failed after cancellation"),
                            mapOf("failures" to error.suppressedExceptions.joinToString { it::class.simpleName ?: "Failure" }))
                        _state.update { it.copy(notice = "Не удалось завершить удаление данных. Повторите действие.") }
                    }
                    throw error
                }
                is Exception -> {
                    logPersistenceFailure("SettingsService", "reset_failed", error)
                    _state.update { it.copy(notice = "Не удалось завершить удаление данных. Повторите действие.") }
                }
                else -> throw error
            }
        }
    }
    override fun prepareProfileEditor() { _state.update { it.copy(editorModels = emptyList(), editorModelsError = null, editorModelsLoading = false) } }

    override suspend fun readMediaAsset(asset: MediaAsset): ByteArray = mediaStore.read(asset)
    override suspend fun mediaAssetPath(asset: MediaAsset): String? = mediaStore.localPath(asset)
    override suspend fun recoverMediaResult(mediaId: String): GeneratedMedia? = mediaGeneration?.recoverMedia(mediaId)

    override fun saveMediaSelection(kind: MediaKind, selection: MediaModelSelection?, verify: Boolean) {
        if (_state.value.settingsSaving) return
        if (selection != null && (selection.profileId.isBlank() || selection.modelId.isBlank() || selection.baseUrl.isBlank())) {
            _state.update { it.copy(notice = "Выберите подключение, модель и адрес сервера.") }
            return
        }
        val point = drafts.capture(SettingsDrafts.mediaKey(kind))
        _state.update { it.copy(settingsSaving = true) }
        scope.launch {
            try {
                val updated = configuration.saveMediaSelection(kind, selection)
                _state.update { it.copy(settings = updated) }
                clearSavedDraft(point)
                mediaGeneration?.refreshAvailability()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                persistenceFailed("save_media_selection", failure, "Не удалось сохранить модель. Повторите действие.")
                return@launch
            } finally { _state.update { it.copy(settingsSaving = false) } }
            if (verify && selection != null) {
                val service = mediaGeneration
                if (service == null || !mediaStore.available) {
                    _state.update { it.copy(notice = "Генерация медиа недоступна на этой платформе.") }
                    return@launch
                }
                try {
                    val profile = configuration.profiles().firstOrNull { it.id == selection.profileId }
                    if (profile == null || !profile.enabled) {
                        _state.update { it.copy(notice = "Выбранное подключение недоступно.") }
                    } else service.check(kind, selection, profile)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    logPersistenceFailure("SettingsService", "check_media_failed", failure)
                    _state.update { it.copy(notice = "Не удалось завершить проверку. Повторите действие.") }
                }
            }
        }
    }
    /** Drain this application's writers while retaining its reusable supervisor. */
    suspend fun prepareForReset() {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it != caller }?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.forEach { it.join() }
        resetDrafts()
    }

    /** Called after the application has cancelled and joined the draft writers. */
    suspend fun resetDrafts() { drafts.forget() }

    override suspend fun close() { try { drafts.awaitSaved() } finally { scope.cancel() } }
}
