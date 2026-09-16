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
import io.aequicor.magicpaper.domain.PlanningRepository
import io.aequicor.magicpaper.domain.ProfileBundle
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.ProfileMigrator
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json


/** Owns saved configuration and provider operations; routes are component outputs. */
class DefaultSettingsService(
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val chats: ChatRepository,
    private val bridge: ProfileBridge,
    private val store: KeyValueStore,
    private val json: Json,
    private val skills: SkillRepository? = null,
    private val planning: PlanningRepository? = null,
    private val modelDirectory: ModelDirectory? = null,
    private val gateway: LlmGateway? = null,
    private val dossierResearcher: DossierResearcher? = null,
    private val openAiSubscription: OpenAiSubscriptionService? = null,
    private val searchConnectionChecker: SearchConnectionChecker? = null,
    val usage: UsageLedger,
    private val applyRuntimeSettings: suspend (AppSettings) -> Result<Unit> = { settingsRepo.save(it); Result.success(Unit) },
    private val clearCodingOverrides: suspend (String) -> Unit = {},
    private val onDataChanged: suspend () -> Unit = {},
    private val onProfileSaved: (String) -> Unit = {},
    private val clearApplicationData: suspend () -> Unit = {},
    private val finishApplicationReset: suspend () -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
) : SettingsService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val drafts = SettingsDrafts(draftRepository, scope, json)
    private val _state = MutableStateFlow(SettingsState())
    override val state: StateFlow<SettingsState> = _state.asStateFlow()
    override suspend fun start() {
        var settings = migrateLegacyModel(settingsRepo.load())
        val profiles = profileRepo.load().map { it.migrateModelLibrary() }
        drafts.allowProfiles(profiles.map { it.id })
        profiles.forEach { profileRepo.save(it) }
        if (settings.defaultModel == null) {
            val main = profiles.firstOrNull { it.id == settings.activeLlmProfileId && it.configured } ?: profiles.firstOrNull { it.configured }
            if (main != null) {
                settings = settings.copy(defaultModel = ModelSelection(main.id, main.modelId, main.effortSelectionFor()))
                settingsRepo.save(settings)
            }
        }
        _state.update { it.copy(settings = settings, llmProfiles = profiles, storageInfo = store.description,
            showWelcome = !settings.onboardingDone, modelDescriptions = planning?.dossiers().orEmpty(),
            openAiSubscription = it.openAiSubscription.copy(available = openAiSubscription != null)) }
        if (openAiSubscription != null && profiles.any { it.provider == ProviderType.OPENAI_SUBSCRIPTION }) refreshOpenAiSubscription()
    }
    override fun dismissNotice() { _state.update { it.copy(notice = null) } }
    private suspend fun migrateLegacyModel(settings: AppSettings): AppSettings {
        if (profileRepo.load().isNotEmpty()) return settings
        val legacy = ProfileMigrator.legacyProfile(settings) ?: return settings
        profileRepo.save(legacy)
        val migrated = settings.copy(activeLlmProfileId = legacy.id)
        settingsRepo.save(migrated)
        return migrated
    }

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
                if (withProfile) profileRepo.save(onboardingProfile.migrateModelLibrary())
                val done = settings.copy(
                    onboardingDone = true,
                    activeLlmProfileId = if (withProfile && settings.activeLlmProfileId.isBlank()) {
                        onboardingProfile.id
                    } else {
                        settings.activeLlmProfileId
                    },
                )
                settingsRepo.save(done)
                clearSavedDraft(draftPoint)
                _state.update {
                    it.copy(
                        settings = done,
                        llmProfiles = if (withProfile) profileRepo.load() else it.llmProfiles,
                        showWelcome = false,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("finish_onboarding", failure, "Не удалось сохранить настройки. Повторите действие.") }
        }
    }

    /** Вернуться к туториалу (кнопка в настройках). */
    override fun restartOnboarding() = _state.update { it.copy(showWelcome = true) }

    override fun saveSettings(settings: AppSettings) {
        if (_state.value.settingsSaving) return
        _state.update { it.copy(settingsSaving = true) }
        AppLog.info("SettingsService", "save_settings_requested")
        val draftPoint = drafts.capture(SettingsDrafts.SETTINGS)
        scope.launch {
            try {
            val applied = try {
                applyRuntimeSettings(settings)
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
        val draftPoint = drafts.capture(SettingsDrafts.profileKey(profile.id))
        scope.launch {
            try {
                profileRepo.save(profile.copy(modelLibraryVersion = 1))
                val profiles = profileRepo.load()
                val settings = _state.value.settings
                val updated = if (settings.activeLlmProfileId.isBlank()) {
                    settings.copy(activeLlmProfileId = profile.id, defaultModel = profile.modelId.takeIf { it.isNotBlank() }?.let { ModelSelection(profile.id, it) }).also { settingsRepo.save(it) }
                } else {
                    settings
                }
                _state.update {
                    it.copy(
                        settings = updated,
                        llmProfiles = profiles,
                        notice = "Источник «${profile.name}» сохранён.",
                    )
                }
                onProfileSaved(profile.id)
                clearSavedDraft(draftPoint)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_profile", failure, "Не удалось сохранить источник. Повторите действие.") }
        }
    }

    /** Удалить профиль; если он был активным — активным станет первый оставшийся. */

    override fun deleteLlmProfile(id: String) {
        AppLog.info("SettingsService", "delete_profile_requested")
        scope.launch {
            try {
                var credentialCleanupFailed = false
                try { profileRepo.delete(id) }
                catch (failure: StorageException) {
                    if (!failure.committed) throw failure
                    logPersistenceFailure("SettingsService", "profile_credential_cleanup_failed", failure)
                    credentialCleanupFailed = true
                }
                val draftCleanupFailed = try { drafts.removeProfile(id); false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { logPersistenceFailure("SettingsService", "profile_draft_cleanup_failed", failure); true }
                val profiles = if (credentialCleanupFailed) _state.value.llmProfiles.filterNot { it.id == id } else profileRepo.load()
                val settings = _state.value.settings
                val updatedSettings = settings.copy(
                    activeLlmProfileId = if (settings.activeLlmProfileId == id) profiles.firstOrNull()?.id.orEmpty() else settings.activeLlmProfileId,
                    defaultModel = settings.defaultModel?.takeUnless { it.profileId == id })
                try { settingsRepo.save(updatedSettings) }
                catch (failure: StorageException) {
                    if (!failure.committed) throw failure
                    logPersistenceFailure("SettingsService", "deleted_profile_settings_cleanup_failed", failure)
                    credentialCleanupFailed = true
                }
                chats.sessions().filter { it.llmProfileId == id }.forEach { chats.save(it.copy(llmProfileId = null)) }
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
        val updated = _state.value.settings.copy(defaultModel = selection, activeLlmProfileId = selection.profileId)
        _state.update { it.copy(settings = updated) }
        scope.launch { settingsRepo.save(updated) }
    }

    override fun updateModelLibrary(profile: LlmProfile) {
        val old = _state.value.llmProfiles.firstOrNull { it.id == profile.id }
        val removedDefault = old?.variants?.firstOrNull { it.id == profile.modelId && profile.variants.none { variant -> variant.id == it.id } }
        val next = profile.copy(modelLibraryVersion = 1, modelId = removedDefault?.sourceModelId ?: profile.modelId)
        _state.update { st -> st.copy(llmProfiles = st.llmProfiles.map { if (it.id == next.id) next else it }) }
        scope.launch { profileRepo.save(next) }
    }

    override fun refreshModelCatalog(id: String) {
        val directory = modelDirectory ?: return
        val profile = _state.value.llmProfiles.firstOrNull { it.id == id } ?: return
        if (id in _state.value.catalogRefreshing) return
        _state.update { it.copy(catalogRefreshing = it.catalogRefreshing + id) }
        scope.launch {
            try {
                val models = directory.models(profile)
                val current = _state.value.llmProfiles.firstOrNull { it.id == id } ?: return@launch
                val updated = current.withCatalog(models)
                profileRepo.save(updated)
                _state.update { st -> st.copy(llmProfiles = st.llmProfiles.map { if (it.id == id) updated else it }) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = "Не удалось обновить каталог ${profile.name}: ${e.message}") } }
            finally { _state.update { it.copy(catalogRefreshing = it.catalogRefreshing - id) } }
        }
    }

    override fun saveModelDescription(dossier: ModelDossier) {
        scope.launch {
            planning?.saveDossier(dossier.copy(id = dossier.id.ifBlank { Id.new() }, source = DossierSource.USER, updatedAt = Id.now()))
            _state.update { it.copy(modelDescriptions = planning?.dossiers().orEmpty()) }
        }
    }

    internal fun saveVariantDraft(profile: LlmProfile, model: String, onSaved: () -> Unit) {
        val point = drafts.capture(SettingsDrafts.variantKey(profile.id, model))
        scope.launch {
            try {
                val updated = profile.copy(modelLibraryVersion = 1)
                profileRepo.save(updated)
                _state.update { state -> state.copy(llmProfiles = state.llmProfiles.map { if (it.id == updated.id) updated else it }) }
                clearSavedDraft(point)
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { persistenceFailed("save_variant", failure, "Не удалось сохранить вариант. Повторите действие.") }
        }
    }

    internal fun saveDescriptionDraft(dossier: ModelDossier, onSaved: () -> Unit) {
        val point = drafts.capture(SettingsDrafts.descriptionKey(dossier.profileId, dossier.modelId))
        scope.launch {
            try {
                val repository = planning ?: return@launch
                repository.saveDossier(dossier.copy(id = dossier.id.ifBlank { Id.new() }, source = DossierSource.USER, updatedAt = Id.now()))
                _state.update { it.copy(modelDescriptions = repository.dossiers()) }
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
        val researcher = dossierResearcher ?: return
        if (_state.value.descriptionsGenerating) return
        val snapshot = _state.value
        val judge = ProfileResolver.resolve(null as ChatSession?, snapshot.settings, snapshot.availableLlmProfiles)
        if (judge == null) { _state.update { it.copy(notice = "Сначала выберите модель по умолчанию.") }; return }
        val targets = snapshot.availableLlmProfiles.flatMap { p -> p.displayModels.map { p.forModel(it) } }
        if (targets.isEmpty()) { _state.update { it.copy(notice = "Добавьте избранные модели.") }; return }
        _state.update { it.copy(descriptionsGenerating = true, descriptionsErrors = emptyList(),
            descriptionsContext = "${judge.shortLabel} · ${judge.completionEngineLabel}. Поиск: ${snapshot.settings.descriptionSearchLabel()}") }
        scope.launch {
            var failures = 0
            try {
                targets.forEachIndexed { index, target ->
                    val label = "${index + 1}/${targets.size} · ${target.modelName(target.selectionKey)}"
                    _state.update { it.copy(descriptionsProgress = label) }
                    val dossier = researcher.research(target, judge, snapshot.settings) { progress ->
                        _state.update { it.copy(descriptionsProgress = "$label\n$progress") }
                    }
                    if (dossier.source == DossierSource.HEURISTIC) {
                        failures++
                        _state.update { it.copy(descriptionsErrors = it.descriptionsErrors + "${target.shortLabel}: ${dossier.note}") }
                    }
                    else {
                        // Do not replace an edit made while research was in flight.
                        val before = snapshot.modelDescriptions.firstOrNull { it.profileId == target.id && it.modelId == target.selectionKey }
                        val current = planning?.dossiers()?.firstOrNull { it.profileId == target.id && it.modelId == target.selectionKey }
                        if (current == before) planning?.saveDossier(dossier.copy(id = current?.id ?: Id.new(), updatedAt = Id.now()))
                    }
                    _state.update { it.copy(modelDescriptions = planning?.dossiers().orEmpty()) }
                }
                _state.update { it.copy(notice = if (failures == 0) "Описания созданы по интернет-источникам." else "Не удалось создать описания для $failures моделей. Причины указаны в разделе «Модели»; прежние описания сохранены.") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = "Не удалось сохранить описания: ${e.message}",
                descriptionsErrors = it.descriptionsErrors + "Не удалось сохранить описания: ${e.message}") } }
            finally { _state.update { it.copy(descriptionsGenerating = false, descriptionsProgress = null) } }
        }
    }

    override fun refreshOpenAiSubscription(refreshToken: Boolean) {
        val service = openAiSubscription ?: return
        if (_state.value.openAiSubscription.loading) return
        _state.update {
            it.copy(openAiSubscription = it.openAiSubscription.copy(loading = true, error = null))
        }
        scope.launch {
            val result = runCatching { service.account(refreshToken) }
            _state.update {
                it.copy(
                    openAiSubscription = it.openAiSubscription.copy(
                        loading = false,
                        account = result.getOrNull(),
                        error = result.exceptionOrNull()?.message,
                    ),
                )
            }
        }
    }

    /** Запустить OAuth в браузере; URL открывает UI через LocalUriHandler. */
    override fun startOpenAiSubscriptionLogin() {
        val service = openAiSubscription ?: return
        if (_state.value.openAiSubscription.signingIn) return
        _state.update {
            it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = true, login = null, error = null))
        }
        scope.launch {
            val started = runCatching { service.startLogin() }
            val login = started.getOrNull()
            _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(
                    signingIn = login != null,
                    login = login,
                    error = started.exceptionOrNull()?.message,
                ))
            }
            if (login != null) {
                val completed = runCatching { service.awaitLogin(login.id) }
                _state.update {
                    it.copy(openAiSubscription = it.openAiSubscription.copy(
                        signingIn = false,
                        login = null,
                        account = completed.getOrNull() ?: it.openAiSubscription.account,
                        error = completed.exceptionOrNull()?.message,
                    ))
                }
            }
        }
    }

    override fun cancelOpenAiSubscriptionLogin() {
        val service = openAiSubscription ?: return
        val login = _state.value.openAiSubscription.login ?: return
        scope.launch {
            runCatching { service.cancelLogin(login.id) }
            _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null))
            }
        }
    }

    override fun logoutOpenAiSubscription() {
        val service = openAiSubscription ?: return
        scope.launch {
            val result = runCatching { service.logout() }
            _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(
                    account = if (result.isSuccess) null else it.openAiSubscription.account,
                    error = result.exceptionOrNull()?.message,
                ))
            }
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
        _state.update { it.copy(editorModelsLoading = true, editorModelsError = null, editorModelsFor = "${draft.id}:${draft.provider}:${draft.baseUrl}") }
        scope.launch {
            val result = runCatching { directory.models(draft) }
            _state.update {
                it.copy(
                    editorModelsLoading = false,
                    editorModels = result.getOrDefault(emptyList()),
                    editorModelsError = result.exceptionOrNull()?.let { e ->
                        "Не удалось загрузить список моделей: ${e.message}"
                    },
                )
            }
        }
    }

    /** Обновить параметры вариантов из каталога провайдера (если они не переопределены вручную). */
    override fun refreshProfileParameters(profileId: String) {
        scope.launch {
            val profile = profileRepo.load().firstOrNull { it.id == profileId } ?: return@launch
            val directory = modelDirectory ?: run {
                _state.update { it.copy(editorModelsError = "Каталог моделей недоступен на этой платформе.") }
                return@launch
            }
            if (!profile.connectionConfigured) {
                _state.update { it.copy(editorModelsError = "Укажите адрес поставщика, затем повторите.") }
                return@launch
            }
            _state.update { it.copy(editorModelsLoading = true, editorModelsError = null) }
            val result = runCatching { directory.models(profile) }
            val catalog = result.getOrDefault(emptyList())
            _state.update { it.copy(editorModelsLoading = false, editorModels = catalog, editorModelsFor = "${profile.id}:${profile.provider}:${profile.baseUrl}") }
            if (result.isFailure) {
                _state.update { it.copy(editorModelsError = "Не удалось загрузить каталог: ${result.exceptionOrNull()?.message}") }
                return@launch
            }
            // Обновляем варианты: если параметр равен дефолту — берём из каталога
            val default = AdvancedLlmOptions()
            val updated = profile.copy(variants = profile.variants.map { variant ->
                val fact = catalog.firstOrNull { it.id == variant.sourceModelId }?.metadata ?: return@map variant
                val opts = variant.options
                val newContextLimit = if (opts.contextLimit == default.contextLimit) fact.contextWindow?.takeIf { it > 0 } ?: opts.contextLimit else opts.contextLimit
                val newMaxTokens = if (opts.maxTokens == default.maxTokens && !opts.sendMaxTokens) fact.maxOutputTokens?.takeIf { it > 0 } ?: opts.maxTokens else opts.maxTokens
                variant.copy(options = opts.copy(contextLimit = newContextLimit, maxTokens = newMaxTokens))
            })
            profileRepo.save(updated.copy(modelLibraryVersion = 1))
            val profiles = profileRepo.load()
            _state.update { it.copy(llmProfiles = profiles, notice = "Параметры актуализированы из каталога поставщика ✓") }
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
            val result = runCatching {
                testGateway.complete(draft, listOf(LlmMessage(LlmChatRole.USER, "Скажи одно слово: ✦")))
            }
            _state.update {
                it.copy(
                    connectionTesting = false,
                    editorModelsError = result.exceptionOrNull()?.let { e ->
                        "Проверка не удалась: ${e.message}"
                    },
                    notice = result.fold(
                        onSuccess = { reply -> "Подключение работает ✓ ${reply.take(60)}" },
                        onFailure = { null },
                    ),
                )
            }
        }
    }

    // ---- Плагины ------------------------------------------------------------

    override fun exportProfile() {
        scope.launch {
            val s = _state.value
            val bundle = ProfileBundle(
                exportedAt = Id.now(),
                settings = s.settings.copy(computerAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF,
                    applicationAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF),
                plugins = settingsRepo.pluginStates(),
                sessions = chats.sessions(),
                skills = skills?.all().orEmpty(),
                llmProfiles = s.llmProfiles,
                modelDescriptions = planning?.dossiers().orEmpty(),
                usage = usage.state.value,
            )
            val encoded = json.encodeToString(ProfileBundle.serializer(), bundle)
            val ok = bridge.export(encoded)
            _state.update {
                it.copy(notice = if (ok) "Профиль экспортирован." else "Не удалось экспортировать профиль.")
            }
        }
    }

    override fun importProfile() {
        if (_state.value.settingsSaving) return
        _state.update { it.copy(settingsSaving = true) }
        scope.launch {
            try {
            val raw = bridge.import()
            if (raw == null) {
                _state.update { it.copy(notice = "Импорт отменён или недоступен на этой платформе.") }
                return@launch
            }
            val bundle = runCatching { json.decodeFromString(ProfileBundle.serializer(), raw) }.getOrElse {
                logPersistenceFailure("SettingsService", "import_decode_failed", it)
                _state.update { st -> st.copy(notice = "Файл профиля повреждён. Выберите другой файл.") }
                return@launch
            }
            // Revoke before importing any other data, including a suspended/failed usage write.
            // Imported data cannot authorize this machine or retain an active lease.
            val importedSettings = bundle.settings.copy(computerAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF,
                applicationAccess = io.aequicor.magicpaper.domain.ComputerAccess.OFF)
            applyRuntimeSettings(importedSettings).getOrThrow()
            usage.replace(bundle.usage)
            settingsRepo.savePluginStates(bundle.plugins)
            bundle.sessions.forEach { chats.save(it) }
            bundle.skills.forEach { skill -> skills?.save(skill) }
            bundle.llmProfiles.forEach { profile -> profileRepo.save(profile.migrateModelLibrary()) }
            bundle.modelDescriptions.forEach { planning?.saveDossier(it) }
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


    override fun wipeAll() {
        scope.launch {
            try {
                clearApplicationData()
                drafts.forget()
                usage.clear()
                chats.wipe()
                skills?.wipe()
                profileRepo.replaceAll(emptyList())
                planning?.wipe()
                settingsRepo.wipe()
                start()
                onDataChanged()
                _state.update { it.copy(notice = "Все данные удалены.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                logPersistenceFailure("SettingsService", "reset_failed", error)
                _state.update { it.copy(notice = "Не удалось завершить удаление данных. Повторите действие.") }
            } finally {
                finishApplicationReset()
            }
        }
    }
    override fun prepareProfileEditor() { _state.update { it.copy(editorModels = emptyList(), editorModelsError = null, editorModelsLoading = false) } }
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
