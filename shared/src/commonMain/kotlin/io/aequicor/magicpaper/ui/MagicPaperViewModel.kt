package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import androidx.lifecycle.ViewModel
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.MAX_ATTACHMENTS_PER_MESSAGE
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingRunRecorder
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.DocRepository
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.OpenAiSubscriptionService
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.PlanningRepository
import io.aequicor.magicpaper.domain.ProfileBundle
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.ProfileMigrator
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.ProjectDirPicker
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.asMeta
import io.aequicor.magicpaper.domain.chatVisible
import io.aequicor.magicpaper.domain.codingStatusOf
import io.aequicor.magicpaper.plugins.PluginRegistry
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * ViewModel-презентер: оркестрирует домен и состояние экрана.
 * Зависимости приходят через конструктор (DIP).
 */
class MagicPaperViewModel(
    private val agent: MagicAgent,
    private val chats: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val docs: DocRepository,
    private val registry: PluginRegistry,
    private val bridge: ProfileBridge,
    private val store: KeyValueStore,
    private val json: Json,
    private val skills: io.aequicor.magicpaper.domain.SkillRepository? = null,
    private val planning: PlanningRepository? = null,
    private val codingRuntime: CodingRuntime? = null,
    private val codingProjects: CodingProjectRepository? = null,
    private val dirPicker: ProjectDirPicker? = null,
    private val modelDirectory: ModelDirectory? = null,
    private val gateway: LlmGateway? = null,
    private val dossierResearcher: DossierResearcher? = null,
    private val filePicker: FilePicker? = null,
    private val openAiSubscription: OpenAiSubscriptionService? = null,
    val planningChat: PlanningChatService? = null,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Активные прогоны по идентификаторам кодинг-сессий (параллельно в разных сессиях). */
    private val codingJobs = mutableMapOf<String, Job>()

    init {
        scope.launch { bootstrap() }
        planningChat?.let { service -> scope.launch {
            service.changes.collect {
                val repo = codingProjects ?: return@collect
                val project = _state.value.coding.current ?: return@collect
                val stored = repo.sessions(project.id)
                val old = _state.value.coding.sessions.associateBy { it.session.id }
                val sessions = stored.map { session ->
                    val previous = old[session.id] ?: CodingSessionUi(session)
                    if (session.stageId != null || session.planningMode || service.store.plans.value.any { it.parentSessionId == session.id })
                        previous.copy(session = session, messages = repo.messages(project.id, session.id),
                            running = service.store.plans.value.firstOrNull { it.id == session.planId }?.let { p -> p.intent == ExecutionIntent.RUN && p.milestones.any { it.id == session.stageId && it.status == MilestoneStatus.ACTIVE && it.attempts.lastOrNull()?.error?.requiresUser != true } } == true || service.drafts.value[session.id]?.active == true || codingJobs[session.id]?.isActive == true)
                    else previous.copy(session = session)
                }
                _state.update { state -> state.copy(coding = state.coding.copy(sessions = sessions + state.coding.sessions.filter { it.session.projectId != project.id })) }
            }
        } }

    }

    private suspend fun bootstrap() {
        val settings = settingsRepo.load()
        val states = settingsRepo.pluginStates().associateBy { it.id }
        var sessions = chats.sessions()
        val projects = codingProjects?.all().orEmpty()
        // Одноразовая миграция: старая «одна модель» становится профилем подключения.
        var migratedSettings = migrateLegacyModel(settings)
        val profiles = profileRepo.load().map { it.migrateModelLibrary() }
        profiles.forEach { profileRepo.save(it) }
        if (migratedSettings.defaultModel == null) {
            val main = profiles.firstOrNull { it.id == migratedSettings.activeLlmProfileId && it.configured }
                ?: profiles.firstOrNull { it.configured }
            if (main != null) {
                migratedSettings = migratedSettings.copy(defaultModel = ModelSelection(main.id, main.modelId, main.effortSelectionFor()))
                settingsRepo.save(migratedSettings)
            }
        }
        sessions = sessions.map { session ->
            if (session.modelSelection != null) session else {
                val request = ProfileResolver.resolve(session, migratedSettings, profiles)
                session.copy(modelSelection = request?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) })
                    .also { chats.save(it) }
            }
        }
        _state.update {
            it.copy(
                settings = migratedSettings,
                plugins = registry.all(),
                pluginStates = states,
                sessions = sessions,
                current = sessions.firstOrNull(),
                docsArticles = docs.articles(),
                storageInfo = store.description,
                showWelcome = !migratedSettings.onboardingDone,
                llmProfiles = profiles,
                modelDescriptions = planning?.dossiers().orEmpty(),
                openAiSubscription = it.openAiSubscription.copy(available = openAiSubscription != null),
                codingPanelPlugin = resolveCodingPanel(states),
                coding = it.coding.copy(
                    projects = projects,
                    current = projects.firstOrNull(),
                    projectStatuses = codingStatusSnapshot(projects),
                ),
            )
        }
        projects.firstOrNull()?.let { project -> openCodingProject(project.id) }
        codingRuntime?.let { runtime ->
            _state.update { it.copy(coding = it.coding.copy(runtime = runtime.status())) }
        }
        if (openAiSubscription != null && profiles.any { it.provider == ProviderType.OPENAI_SUBSCRIPTION }) {
            refreshOpenAiSubscription()
        }
    }

    /** Сводные статусы всех проектов (по первой загрузке, без активных прогонов). */
    private suspend fun codingStatusSnapshot(projects: List<CodingProject>): Map<String, CodingSessionStatus> {
        val repo = codingProjects ?: return emptyMap()
        return projects.associate { project ->
            val statuses = repo.sessions(project.id).map { session ->
                codingStatusOf(repo.messages(project.id, session.id))
            }
            project.id to aggregateCodingStatus(statuses)
        }
    }

    /**
     * Пересчёт сводного статуса проекта: активные прогоны (WORKING/WAITING
     * по живой фазе), остальные — по журналам из хранилища.
     */
    private suspend fun refreshProjectStatus(projectId: String) {
        val repo = codingProjects ?: return
        val statuses = repo.sessions(projectId).map { session ->
            val active = codingJobs[session.id]
            if (active != null && active.isActive) {
                _state.value.coding.sessions.firstOrNull { it.session.id == session.id }?.status
                    ?: CodingSessionStatus.WORKING
            } else {
                codingStatusOf(repo.messages(projectId, session.id))
            }
        }
        _state.update {
            it.copy(
                coding = it.coding.copy(
                    projectStatuses = it.coding.projectStatuses +
                        (projectId to aggregateCodingStatus(statuses)),
                ),
            )
        }
    }

    /**
     * Перенос легаси-тройки (Base URL/ключ/модель) в первый профиль подключения.
     * Срабатывает один раз: когда профилей ещё нет, а старая тройка заполнена.
     */
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
    fun codingProfileOf(session: CodingSession): LlmProfile? {
        val s = _state.value
        if (session.planningMode) return session.modelSelection?.let { ProfileResolver.selection(it, s.availableLlmProfiles) } ?: ProfileResolver.resolve(null as ChatSession?, s.settings, s.availableLlmProfiles)
        return ProfileResolver.coding(session, s.coding.projects.firstOrNull { it.id == session.projectId }, s.settings, s.availableLlmProfiles)
    }

    // ---- Навигация -------------------------------------------------------

    fun openModelsSettings() = _state.update { it.copy(screen = Screen.SETTINGS, modelsSettingsOpen = true) }
    fun closeModelsSettings() = _state.update { it.copy(modelsSettingsOpen = false) }

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun toggleSessionsPanel() = _state.update { it.copy(sessionsPanelOpen = !it.sessionsPanelOpen) }

    /**
     * Выбрать файлы для вложения. Лимиты и список принятых файлов решает платформа,
     * здесь добавляем сообщения о причинах отказа (нет пикера / переполнен лимит).
     */
    fun pickAttachments(alreadyAttached: Int, onResult: (List<Attachment>) -> Unit) {
        val picker = filePicker
        if (picker == null || !picker.supported) {
            _state.update { it.copy(notice = "Вложения на этой платформе пока не поддерживаются.") }
            return
        }
        scope.launch {
            val picked = picker.pickFiles()
            val room = MAX_ATTACHMENTS_PER_MESSAGE - alreadyAttached
            val accepted = picked.take(room)
            onResult(accepted.map { Attachment.fromBytes(it.name, it.mimeType, it.bytes) })
            if (picked.size > room) {
                _state.update {
                    it.copy(notice = "Не больше $MAX_ATTACHMENTS_PER_MESSAGE вложений на сообщение.")
                }
            }
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- Чат --------------------------------------------------------------

    fun newSession() {
        val now = Id.now()
        val session = ChatSession(
            id = Id.new(),
            title = "Новый свиток",
            createdAt = now,
            updatedAt = now,
            modelSelection = ProfileResolver.favoriteDefault(_state.value.settings, _state.value.availableLlmProfiles),
        )
        _state.update {
                it.copy(
                    sessions = listOf(session) + it.sessions,
                    current = session,
                    sessionsPanelOpen = false,
                )
        }
        scope.launch { chats.save(session) }
    }

    fun selectSession(id: String) {
        scope.launch {
            val session = chats.session(id) ?: return@launch
            _state.update { it.copy(current = session, sessionsPanelOpen = false) }
        }
    }

    fun deleteSession(id: String) {
        scope.launch {
            chats.delete(id)
            val rest = chats.sessions()
            _state.update {
                it.copy(
                    sessions = rest,
                    current = if (it.current?.id == id) rest.firstOrNull() else it.current,
                )
            }
        }
    }

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        val visible = attachments.chatVisible()
        if (trimmed.isEmpty() && visible.isEmpty()) return
        val s = _state.value
        if (s.busy) return
        val settings = s.settings
        val session = s.current ?: run {
            newSession()
            _state.value.current ?: return
        }

        val userMessage = ChatMessage(
            id = Id.new(),
            role = ChatRole.USER,
            text = trimmed,
            createdAt = Id.now(),
            attachments = visible,
        )
        val historyBefore = session.messages
        val updated = session.copy(
            messages = session.messages + userMessage,
            title = if (session.messages.isEmpty()) trimmed.take(40) else session.title,
            updatedAt = Id.now(),
        )
        val requestProfile = ProfileResolver.resolve(updated, settings, _state.value.availableLlmProfiles)
        val operationalProfile = ProfileResolver.resolve(null as ChatSession?, settings, _state.value.availableLlmProfiles)
        scope.launch {
            chats.save(updated)
            _state.update { st ->
                st.copy(
                    current = updated,
                    sessions = st.sessions.map { if (it.id == updated.id) updated else it },
                    busy = true,
                )
            }
            val answer = agent.answer(historyBefore, trimmed, settings, requestProfile, attachments = visible, operationalProfile = operationalProfile)
            val agentMessage = ChatMessage(
                id = Id.new(),
                role = ChatRole.AGENT,
                text = answer.text,
                createdAt = Id.now(),
                sources = answer.sources,
            )
            val latest = _state.value.sessions.firstOrNull { it.id == updated.id } ?: updated
            val final = updated.copy(
                modelSelection = latest.modelSelection,
                llmProfileId = latest.llmProfileId,
                messages = updated.messages + agentMessage,
                updatedAt = Id.now(),
            )
            chats.save(final)
            _state.update { st ->
                st.copy(
                    current = if (st.current?.id == final.id) final else st.current,
                    sessions = st.sessions.map { if (it.id == final.id) final else it },
                    busy = false,
                )
            }
        }
    }

    // ---- Настройки ---------------------------------------------------------

    /** Завершение ознакомительного тура: сохранить черновик и впустить в приложение.
     * [onboardingProfile] — профиль, созданный на шаге «источник магии» (если настроен). */
    fun finishOnboarding(settings: AppSettings, onboardingProfile: LlmProfile? = null) {
        scope.launch {
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
            _state.update {
                it.copy(
                    settings = done,
                    llmProfiles = if (withProfile) profileRepo.load() else it.llmProfiles,
                    showWelcome = false,
                    screen = Screen.CHAT,
                )
            }
        }
    }

    /** Вернуться к туториалу (кнопка в настройках). */
    fun restartOnboarding() = _state.update { it.copy(showWelcome = true) }

    fun saveSettings(settings: AppSettings) {
        scope.launch {
            settingsRepo.save(settings)
            _state.update { it.copy(settings = settings, notice = "Настройки сохранены.") }
        }
    }

    // ---- Магические источники (профили подключения) -----------------------

    /** Сохранить профиль (создание или обновление) и обновить состояние.
     * Первый сохранённый профиль становится активным автоматически. */
    fun saveLlmProfile(profile: LlmProfile) {
        scope.launch {
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
                    editingLlmProfileId = null,
                    modelsSettingsOpen = true,
                    notice = "Источник «${profile.name}» сохранён.",
                )
            }
        }
    }

    /** Удалить профиль; если он был активным — активным станет первый оставшийся. */
    fun deleteLlmProfile(id: String) {
        scope.launch {
            profileRepo.delete(id)
            val profiles = profileRepo.load()
            val settings = _state.value.settings
            val newActive = if (settings.activeLlmProfileId == id) {
                profiles.firstOrNull()?.id.orEmpty()
            } else {
                settings.activeLlmProfileId
            }
            val updated = settings.copy(activeLlmProfileId = newActive, defaultModel = settings.defaultModel?.takeUnless { it.profileId == id })
            settingsRepo.save(updated)
            // Снимаем переопределения свитков, ссылающиеся на удалённый профиль.
            _state.value.sessions.filter { it.llmProfileId == id }.forEach { session ->
                val cleared = session.copy(llmProfileId = null)
                chats.save(cleared)
            }
            // То же для кодинг-сессий (переопределение источника агента).
            val codingRepo = codingProjects
            _state.value.coding.sessions.filter { it.session.llmProfileId == id }.forEach { item ->
                val cleared = item.session.copy(llmProfileId = null)
                codingRepo?.saveSession(cleared)
                updateCodingSession(item.session.id) { it.copy(session = cleared) }
            }
            bootstrap()
            _state.update { it.copy(notice = "Источник удалён.") }
        }
    }

    fun selectChatModel(selection: ModelSelection) {
        if (_state.value.availableLlmProfiles.none { it.id == selection.profileId && selection.modelId in it.displayModels }) return
        if (_state.value.current == null) newSession()
        val session = _state.value.current ?: return
        val updated = session.copy(modelSelection = selection, llmProfileId = selection.profileId)
        _state.update { st -> st.copy(current = updated, sessions = st.sessions.map { if (it.id == updated.id) updated else it }) }
        scope.launch { chats.save(updated) }
    }

    fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean = false) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val profile = ProfileResolver.selection(selection, _state.value.availableLlmProfiles) ?: return
        if ((!ui.session.planningMode || forProject) && !profile.supportsCoding) return
        val updated = ui.session.copy(modelSelection = selection, llmProfileId = selection.profileId)
        updateCodingSession(sessionId) { it.copy(session = updated) }
        scope.launch {
            if (updated.stageId != null && updated.planId != null) {
                val effort = EffortSelection.ofOrNull(ModelDefaults.capability(profile).resolveEffort(selection.effort).level)
                planningChat?.store?.update(updated.planId) { plan ->
                    plan.copy(milestones = plan.milestones.map { stage ->
                        if (stage.id != updated.stageId) stage else stage.copy(
                            agentProfileId = selection.profileId,
                            agentModelId = selection.modelId,
                            assignment = StageAssignment(selection.profileId, selection.modelId, effort, effort,
                                manual = true, displayName = profile.modelName(selection.modelId)),
                        )
                    })
                }
            }
            codingProjects?.saveSession(updated)
        }
        if (forProject) {
            val project = _state.value.coding.projects.firstOrNull { it.id == updated.projectId } ?: return
            val next = project.copy(modelSelection = selection)
            _state.update { st -> st.copy(coding = st.coding.copy(projects = st.coding.projects.map { if (it.id == next.id) next else it }, current = st.coding.current?.let { if (it.id == next.id) next else it })) }
            scope.launch { codingProjects?.save(next) }
        }
    }

    fun setDefaultModel(selection: ModelSelection) {
        if (ProfileResolver.selection(selection, _state.value.availableLlmProfiles) == null) return
        val updated = _state.value.settings.copy(defaultModel = selection, activeLlmProfileId = selection.profileId)
        _state.update { it.copy(settings = updated) }
        scope.launch { settingsRepo.save(updated) }
    }

    fun updateModelLibrary(profile: LlmProfile) {
        val old = _state.value.llmProfiles.firstOrNull { it.id == profile.id }
        val removedDefault = old?.variants?.firstOrNull { it.id == profile.modelId && profile.variants.none { variant -> variant.id == it.id } }
        val next = profile.copy(modelLibraryVersion = 1, modelId = removedDefault?.sourceModelId ?: profile.modelId)
        _state.update { st -> st.copy(llmProfiles = st.llmProfiles.map { if (it.id == next.id) next else it }) }
        scope.launch { profileRepo.save(next) }
    }

    fun refreshModelCatalog(id: String) {
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

    fun saveModelDescription(dossier: ModelDossier) {
        scope.launch {
            planning?.saveDossier(dossier.copy(id = dossier.id.ifBlank { Id.new() }, source = DossierSource.USER, updatedAt = Id.now()))
            _state.update { it.copy(modelDescriptions = planning?.dossiers().orEmpty()) }
        }
    }

    fun generateModelDescriptions() {
        val researcher = dossierResearcher ?: return
        if (_state.value.descriptionsGenerating) return
        val snapshot = _state.value
        val judge = ProfileResolver.resolve(null as ChatSession?, snapshot.settings, snapshot.availableLlmProfiles)
        if (judge == null) { _state.update { it.copy(notice = "Сначала выберите модель по умолчанию.") }; return }
        val targets = snapshot.availableLlmProfiles.flatMap { p -> p.displayModels.map { p.forModel(it) } }
        if (targets.isEmpty()) { _state.update { it.copy(notice = "Добавьте избранные модели.") }; return }
        _state.update { it.copy(descriptionsGenerating = true) }
        scope.launch {
            var failures = 0
            try {
                targets.forEachIndexed { index, target ->
                    _state.update { it.copy(descriptionsProgress = "${index + 1}/${targets.size} · ${target.modelName(target.selectionKey)}") }
                    val dossier = researcher.research(target, judge, snapshot.settings)
                    if (dossier.source == DossierSource.HEURISTIC) failures++
                    else {
                        // Do not replace an edit made while research was in flight.
                        val before = snapshot.modelDescriptions.firstOrNull { it.profileId == target.id && it.modelId == target.selectionKey }
                        val current = planning?.dossiers()?.firstOrNull { it.profileId == target.id && it.modelId == target.selectionKey }
                        if (current == before) planning?.saveDossier(dossier.copy(id = current?.id ?: Id.new(), updatedAt = Id.now()))
                    }
                    _state.update { it.copy(modelDescriptions = planning?.dossiers().orEmpty()) }
                }
                _state.update { it.copy(notice = if (failures == 0) "Описания созданы." else "Не удалось создать описания для $failures моделей. Сохранённые описания оставлены.") }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = "Не удалось сохранить описания: ${e.message}") } }
            finally { _state.update { it.copy(descriptionsGenerating = false, descriptionsProgress = null) } }
        }
    }

    fun toggleModelSwitcher(open: Boolean) = _state.update { it.copy(modelSwitcherOpen = open) }

    /** Перейти к редактированию профиля на экране настроек. */
    fun editLlmProfile(id: String) {
        _state.update {
            it.copy(
                screen = Screen.SETTINGS,
                editingLlmProfileId = id,
                modelSwitcherOpen = false,
                editorModels = emptyList(),
                editorModelsError = null,
                editorModelsLoading = false,
            )
        }
    }

    /** Закрыть редактор профиля без сохранения. */
    fun closeLlmProfileEditor() = _state.update {
        it.copy(editingLlmProfileId = null, editorModels = emptyList(), editorModelsError = null)
    }

    /** Перечитать аккаунт и лимиты подписки из desktop Codex app-server. */
    fun refreshOpenAiSubscription(refreshToken: Boolean = false) {
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
    fun startOpenAiSubscriptionLogin() {
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

    fun cancelOpenAiSubscriptionLogin() {
        val service = openAiSubscription ?: return
        val login = _state.value.openAiSubscription.login ?: return
        scope.launch {
            runCatching { service.cancelLogin(login.id) }
            _state.update {
                it.copy(openAiSubscription = it.openAiSubscription.copy(signingIn = false, login = null))
            }
        }
    }

    fun logoutOpenAiSubscription() {
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

    fun openAiSubscriptionSignedIn(): Boolean =
        _state.value.openAiSubscription.account?.signedIn == true

    /** Загрузить список моделей, доступных у провайдера черновика профиля. */
    fun fetchModels(draft: LlmProfile) {
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

    /** Проверка подключения: тестовый запрос к модели профиля. */
    fun testConnection(draft: LlmProfile) {
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

    fun togglePlugin(id: String, enabled: Boolean) {
        scope.launch {
            val current = settingsRepo.pluginStates().associateBy { it.id }.toMutableMap()
            val existing = current[id]
            current[id] = existing?.copy(enabled = enabled) ?: PluginState(id, enabled)
            settingsRepo.savePluginStates(current.values.toList())
            _state.update { it.copy(pluginStates = current, codingPanelPlugin = resolveCodingPanel(current)) }
        }
    }

    /** Первый включённый плагин с панелью кодинг-сессии (расширяемость без знания о нём). */
    private fun resolveCodingPanel(states: Map<String, PluginState>): io.aequicor.magicpaper.plugins.CodingSessionPanel? {
        return registry.all()
            .filterIsInstance<io.aequicor.magicpaper.plugins.CodingSessionPanel>()
            .firstOrNull { panel ->
                // Нет записи состояния = включён (как на экране плагинов).
                val id = (panel as io.aequicor.magicpaper.plugins.MagicPlugin).id
                states[id]?.enabled ?: true
            }
    }

    /** Переключение вкладки кодинг-сессии: диалог с агентом или панель плагина. */
    fun setCodingSessionMode(mode: io.aequicor.magicpaper.ui.CodingSessionMode) =
        _state.update { it.copy(coding = it.coding.copy(sessionMode = mode)) }

    // ---- Документация --------------------------------------------------------

    fun setDocsQuery(query: String) {
        _state.update { it.copy(docsQuery = query) }
        scope.launch {
            val articles = if (query.isBlank()) {
                docs.articles()
            } else {
                docs.search(query, limit = 10).map { it.article }
            }
            _state.update { it.copy(docsArticles = articles) }
        }
    }

    // ---- Профиль --------------------------------------------------------------

    fun exportProfile() {
        scope.launch {
            val s = _state.value
            val bundle = ProfileBundle(
                exportedAt = Id.now(),
                settings = s.settings,
                plugins = s.pluginStates.values.toList(),
                sessions = chats.sessions(),
                skills = skills?.all().orEmpty(),
                llmProfiles = s.llmProfiles,
                modelDescriptions = planning?.dossiers().orEmpty(),
            )
            val encoded = json.encodeToString(ProfileBundle.serializer(), bundle)
            val ok = bridge.export(encoded)
            _state.update {
                it.copy(notice = if (ok) "Профиль экспортирован." else "Не удалось экспортировать профиль.")
            }
        }
    }

    fun importProfile() {
        scope.launch {
            val raw = bridge.import()
            if (raw == null) {
                _state.update { it.copy(notice = "Импорт отменён или недоступен на этой платформе.") }
                return@launch
            }
            val bundle = runCatching { json.decodeFromString(ProfileBundle.serializer(), raw) }.getOrElse {
                _state.update { st -> st.copy(notice = "Файл профиля повреждён.") }
                return@launch
            }
            settingsRepo.save(bundle.settings)
            settingsRepo.savePluginStates(bundle.plugins)
            bundle.sessions.forEach { chats.save(it) }
            bundle.skills.forEach { skill -> skills?.save(skill) }
            bundle.llmProfiles.forEach { profile -> profileRepo.save(profile.migrateModelLibrary()) }
            bundle.modelDescriptions.forEach { planning?.saveDossier(it) }
            bootstrap()
            _state.update { it.copy(notice = "Профиль импортирован.") }
        }
    }

    fun wipeAll() {
        scope.launch {
            chats.wipe()
            settingsRepo.wipe()
            skills?.wipe()
            profileRepo.replaceAll(emptyList())
            planning?.wipe()
            _state.update {
                it.copy(
                    current = null,
                    sessions = emptyList(),
                    settings = AppSettings(),
                    pluginStates = emptyMap(),
                    llmProfiles = emptyList(),
                    notice = "Все данные удалены.",
                )
            }
        }
    }

    // ---- Проекты и код ------------------------------------------------------

    /** Подготовка движка: автоустановка изолированных зависимостей. */
    fun prepareCodingRuntime() {
        val runtime = codingRuntime ?: return
        if (_state.value.coding.installing) return
        _state.update { it.copy(coding = it.coding.copy(installing = true)) }
        scope.launch {
            runtime.ensureReady().collect { status ->
                _state.update {
                    it.copy(
                        coding = it.coding.copy(
                            runtime = status,
                            installing = status.phase == RuntimePhase.CHECKING ||
                                status.phase == RuntimePhase.INSTALLING,
                        )
                    )
                }
            }
        }
    }

    /** Полное удаление изолированных зависимостей движка (останавливая все прогоны). */
    fun uninstallCodingRuntime() {
        val runtime = codingRuntime ?: return
        scope.launch {
            runtime.abortAll()
            runtime.uninstall()
            codingJobs.values.forEach { it.cancel() }
            codingJobs.clear()
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        runtime = runtime.status(),
                        installing = false,
                        sessions = it.coding.sessions.map { s ->
                            s.copy(running = false, draft = io.aequicor.magicpaper.domain.CodingDraft())
                        },
                    ),
                    notice = "Зависимости движка удалены из папки данных.",
                )
            }
        }
    }

    /** Новый проект: выбор папки нативным диалогом. */
    fun addCodingProject() {
        val repo = codingProjects ?: return
        val picker = dirPicker ?: return
        scope.launch {
            val path = picker.pickDirectory() ?: return@launch
            // Повторное добавление той же папки — просто выбираем существующий проект.
            val existing = repo.all().firstOrNull { it.path == path }
            if (existing != null) {
                openCodingProject(existing.id)
                return@launch
            }
            val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
            val project = CodingProject(
                id = Id.new(),
                name = name,
                path = path,
                createdAt = Id.now(),
            )
            repo.save(project)
            _state.update { it.copy(coding = it.coding.copy(projects = repo.all())) }
            openCodingProject(project.id)
        }
    }

    /** Открыть проект: загрузить его кодинг-сессии с журналами. */
    fun selectCodingProject(id: String) {
        scope.launch { openCodingProject(id) }
    }

    /** Открыть проект: список сессий с журналами; чужие активные прогоны сохраняются. */
    private suspend fun openCodingProject(projectId: String) {
        val repo = codingProjects ?: return
        val project = repo.all().firstOrNull { it.id == projectId } ?: return
        val loaded = repo.sessions(projectId).map { session ->
            CodingSessionUi(
                session = session,
                messages = repo.messages(projectId, session.id),
                running = codingJobs[session.id]?.isActive == true,
            )
        }
        _state.update { st ->
            // Сессии других проектов с живыми прогонами не выбрасываем — они
            // обновляют статус фоном; на экране фильтруются по current.id.
            val loadedIds = loaded.map { it.session.id }.toSet()
            val carried = st.coding.sessions.filter {
                it.running && it.session.projectId != projectId && it.session.id !in loadedIds
            }
            st.copy(
                coding = st.coding.copy(
                    current = project,
                    sessions = loaded + carried,
                    currentSessionId = st.coding.currentSessionId
                        .takeIf { it != null && it in loadedIds }
                        ?: loaded.firstOrNull()?.session?.id,
                ),
            )
        }
    }

    fun deleteCodingProject(id: String) {
        val repo = codingProjects ?: return
        scope.launch {
            // Прерываем прогоны всех сессий удаляемого проекта.
            repo.sessions(id).forEach { session ->
                codingRuntime?.abort(session.id)
                codingJobs.remove(session.id)?.cancel()
            }
            repo.delete(id)
            val rest = repo.all()
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        projects = rest,
                        // Сессии остальных проектов остаются в состоянии (живые прогоны).
                        sessions = it.coding.sessions.filter { s -> s.session.projectId != id },
                        currentSessionId = null,
                        projectStatuses = it.coding.projectStatuses - id,
                    ),
                )
            }
            val current = _state.value.coding
            if (current.current?.id == id) {
                rest.firstOrNull()?.let { openCodingProject(it.id) }
                    ?: _state.update {
                        it.copy(coding = it.coding.copy(current = null, sessions = emptyList(), currentSessionId = null))
                    }
            }
        }
    }

    // ---- Кодинг-сессии проекта ------------------------------------------------

    /** Новая кодинг-сессия в текущем проекте: отдельный контекст и журнал. */
    fun addCodingSession() {
        val repo = codingProjects ?: return
        val project = _state.value.coding.current ?: return
        scope.launch {
            val ordinal = repo.sessions(project.id).size + 1
            val session = CodingSession(
                id = Id.new(),
                projectId = project.id,
                name = "Сессия $ordinal",
                createdAt = Id.now(),
                modelSelection = project.modelSelection ?: ProfileResolver.favoriteDefault(_state.value.settings, _state.value.availableLlmProfiles, coding = true),
            )
            repo.saveSession(session)
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        sessions = it.coding.sessions + CodingSessionUi(session = session),
                        currentSessionId = session.id,
                    ),
                )
            }
            refreshProjectStatus(project.id)
        }
    }

    fun openPlanningChat() {
        scope.launch {
            val service = planningChat ?: return@launch
            val plan = service.store.plans().firstOrNull()
            _state.update { it.copy(screen = Screen.CODING) }
            if (plan != null) { openCodingProject(plan.projectId); selectCodingSession(plan.parentSessionId) }
        }
    }

    fun selectCodingSession(id: String) {
        _state.update { it.copy(coding = it.coding.copy(currentSessionId = id)) }
    }

    /** Удалить сессию с её журналом (активный прогон прерывается). */
    fun deleteAllCodingSessions(projectId: String) {
        val repo = codingProjects ?: return
        scope.launch {
            repo.sessions(projectId).forEach { session ->
                codingRuntime?.abort(session.id)
                codingJobs.remove(session.id)?.let { job -> job.cancel(); job.join() }
            }
            if (planningChat != null) planningChat.deleteProjectSessions(projectId)
            else repo.sessions(projectId).forEach { repo.deleteSession(projectId, it.id) }
            _state.update { state ->
                val remaining = state.coding.sessions.filterNot { it.session.projectId == projectId }
                state.copy(coding = state.coding.copy(sessions = remaining,
                    currentSessionId = state.coding.currentSessionId?.takeIf { id -> remaining.any { it.session.id == id } },
                    projectStatuses = state.coding.projectStatuses - projectId))
            }
            refreshProjectStatus(projectId)
        }
    }

    fun deleteCodingSession(id: String) {
        val repo = codingProjects ?: return
        val coding = _state.value.coding
        val target = coding.sessions.firstOrNull { it.session.id == id } ?: return
        scope.launch {
            codingRuntime?.abort(id)
            codingJobs.remove(id)?.cancel()
            repo.deleteSession(target.session.projectId, id)
            val rest = coding.sessions.filterNot { it.session.id == id }
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        sessions = rest,
                        currentSessionId = if (it.coding.currentSessionId == id) {
                            rest.firstOrNull { s -> s.session.projectId == target.session.projectId }?.session?.id
                        } else {
                            it.coding.currentSessionId
                        },
                    ),
                )
            }
            refreshProjectStatus(target.session.projectId)
        }
    }

    fun sendCodingPrompt(text: String) {
        val coding = _state.value.coding
        val session = coding.currentSession ?: return
        sendCodingPromptTo(session.session.id, text)
    }

    /**
     * Запрос кодинг-агенту в произвольной сессии (можно ответить агенту
     * из фоновой сессии, не переключаясь на неё).
     * Прогоны разных сессий (в том числе разных проектов) идут параллельно.
     */
    fun sendCodingPromptTo(sessionId: String, text: String, attachments: List<Attachment> = emptyList()) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session
        if (planningChat != null && selected != null && (selected.planningMode || selected.stageId != null)) {
            planningChat.send(selected, text); return
        }
        val runtime = codingRuntime ?: return
        val repo = codingProjects ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val coding = _state.value.coding
        val ui = coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val session = ui.session
        val project = coding.projects.firstOrNull { it.id == session.projectId } ?: return
        if (codingJobs[session.id]?.isActive == true) return

        val userMessage = CodingMessage(
            id = Id.new(),
            role = CodingRole.USER,
            text = trimmed,
            createdAt = Id.now(),
            attachments = attachments.map { it.asMeta() },
        )
        val recorder = CodingRunRecorder()
        val requestProfile = codingProfileOf(session)
        val job = scope.launch {
            val history = repo.messages(project.id, session.id) + userMessage
            if (planningChat != null) planningChat.append(project.id, session.id, userMessage) else repo.saveMessages(project.id, session.id, history)
            updateCodingSession(session.id) {
                it.copy(messages = history, running = true, draft = recorder.draft(active = true))
            }
            var piSessionId = session.piSessionId
            runtime.run(project, session, trimmed, requestProfile, attachments).collect { event ->
                if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                    piSessionId = event.sessionId
                }
                recorder.apply(event)
                updateCodingSession(session.id) { it.copy(draft = recorder.draft(active = true)) }
            }
            val agentMessage = recorder.message(Id.new(), Id.now())
            // Перечитываем журнал: за время прогона его никто не должен был менять,
            // но история берётся из хранилища, а не из снимка — источник истины один.
            val finalLog = repo.messages(project.id, session.id) + agentMessage
            if (planningChat != null) planningChat.append(project.id, session.id, agentMessage) else repo.saveMessages(project.id, session.id, finalLog)
            val latestSession = _state.value.coding.sessions.firstOrNull { it.session.id == session.id }?.session ?: session
            val updatedSession = latestSession.copy(piSessionId = piSessionId)
            if (piSessionId != session.piSessionId) repo.saveSession(updatedSession)
            codingJobs.remove(session.id)
            updateCodingSession(session.id) {
                it.copy(
                    session = updatedSession,
                    messages = finalLog,
                    running = false,
                    draft = io.aequicor.magicpaper.domain.CodingDraft(),
                )
            }
            // Прогон фоновой (не открытой) сессии закончился — запись больше не нужна.
            if (project.id != _state.value.coding.current?.id) {
                _state.update {
                    it.copy(coding = it.coding.copy(sessions = it.coding.sessions.filterNot { s -> s.session.id == session.id }))
                }
            }
            refreshProjectStatus(project.id)
        }
        codingJobs[session.id] = job
    }

    /** Прервать прогон текущей сессии (процесс её агента). */
    fun abortCodingRun() {
        val id = _state.value.coding.currentSession?.session?.id ?: return
        abortCodingSession(id)
    }

    fun abortCodingSession(sessionId: String) {
        // Процесс убивает рантайм; поток событий сам выдаст Failed+Finished,
        // и прогон корректно закроет журнал (статус станет жёлтым).
        codingRuntime?.abort(sessionId)
    }

    /** Точечное обновление сессии в состоянии (по id, где бы она ни лежала). */
    private fun updateCodingSession(sessionId: String, transform: (CodingSessionUi) -> CodingSessionUi) {
        _state.update { st ->
            st.copy(
                coding = st.coding.copy(
                    sessions = st.coding.sessions.map {
                        if (it.session.id == sessionId) transform(it) else it
                    },
                ),
            )
        }
    }

    override fun onCleared() {
        openAiSubscription?.close()
        super.onCleared()
    }
}
