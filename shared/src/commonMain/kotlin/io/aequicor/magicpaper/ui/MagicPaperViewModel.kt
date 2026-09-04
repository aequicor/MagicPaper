package io.aequicor.magicpaper.ui

import androidx.lifecycle.ViewModel
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingProjectRepository
import io.aequicor.magicpaper.domain.CodingRole
import io.aequicor.magicpaper.domain.CodingRunRecorder
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.DocRepository
import io.aequicor.magicpaper.domain.EffortLevel
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.ProfileBundle
import io.aequicor.magicpaper.domain.ProfileBridge
import io.aequicor.magicpaper.domain.ProfileMigrator
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.ProjectDirPicker
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.plugins.PluginRegistry
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val codingRuntime: CodingRuntime? = null,
    private val codingProjects: CodingProjectRepository? = null,
    private val dirPicker: ProjectDirPicker? = null,
    private val modelDirectory: ModelDirectory? = null,
    private val gateway: LlmGateway? = null,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        scope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val settings = settingsRepo.load()
        val states = settingsRepo.pluginStates().associateBy { it.id }
        val sessions = chats.sessions()
        val projects = codingProjects?.all().orEmpty()
        // Одноразовая миграция: старая «одна модель» становится профилем подключения.
        val migratedSettings = migrateLegacyModel(settings)
        val profiles = profileRepo.all()
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
                coding = it.coding.copy(
                    projects = projects,
                    current = projects.firstOrNull(),
                ),
            )
        }
        projects.firstOrNull()?.let { project -> loadCodingLog(project.id) }
        codingRuntime?.let { runtime ->
            _state.update { it.copy(coding = it.coding.copy(runtime = runtime.status())) }
        }
    }

    /**
     * Перенос легаси-тройки (Base URL/ключ/модель) в первый профиль подключения.
     * Срабатывает один раз: когда профилей ещё нет, а старая тройка заполнена.
     */
    private suspend fun migrateLegacyModel(settings: AppSettings): AppSettings {
        if (profileRepo.all().isNotEmpty()) return settings
        val legacy = ProfileMigrator.legacyProfile(settings) ?: return settings
        profileRepo.save(legacy)
        val migrated = settings.copy(activeLlmProfileId = legacy.id)
        settingsRepo.save(migrated)
        return migrated
    }

    /** Разрешённый профиль для текущего свитка (см. [ProfileResolver]). */
    private fun resolvedProfile(): LlmProfile? {
        val s = _state.value
        return ProfileResolver.resolve(s.current, s.settings, s.llmProfiles)
    }

    // ---- Навигация -------------------------------------------------------

    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun toggleSessionsPanel() = _state.update { it.copy(sessionsPanelOpen = !it.sessionsPanelOpen) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- Чат --------------------------------------------------------------

    fun newSession() {
        val now = Id.now()
        val session = ChatSession(
            id = Id.new(),
            title = "Новый свиток",
            createdAt = now,
            updatedAt = now,
        )
        scope.launch {
            chats.save(session)
            _state.update {
                it.copy(
                    sessions = listOf(session) + it.sessions,
                    current = session,
                    sessionsPanelOpen = false,
                )
            }
        }
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

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
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
        )
        val historyBefore = session.messages
        val updated = session.copy(
            messages = session.messages + userMessage,
            title = if (session.messages.isEmpty()) trimmed.take(40) else session.title,
            updatedAt = Id.now(),
        )
        scope.launch {
            chats.save(updated)
            _state.update { st ->
                st.copy(
                    current = updated,
                    sessions = st.sessions.map { if (it.id == updated.id) updated else it },
                    busy = true,
                )
            }
            val profile = ProfileResolver.resolve(updated, settings, _state.value.llmProfiles)
            val answer = agent.answer(historyBefore, trimmed, settings, profile)
            val agentMessage = ChatMessage(
                id = Id.new(),
                role = ChatRole.AGENT,
                text = answer.text,
                createdAt = Id.now(),
                sources = answer.sources,
            )
            val final = updated.copy(
                messages = updated.messages + agentMessage,
                updatedAt = Id.now(),
            )
            chats.save(final)
            _state.update { st ->
                st.copy(
                    current = final,
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
            val withProfile = onboardingProfile != null && onboardingProfile.configured
            if (withProfile) profileRepo.save(onboardingProfile)
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
                    llmProfiles = if (withProfile) profileRepo.all() else it.llmProfiles,
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
            profileRepo.save(profile)
            val profiles = profileRepo.all()
            val settings = _state.value.settings
            val updated = if (settings.activeLlmProfileId.isBlank()) {
                settings.copy(activeLlmProfileId = profile.id).also { settingsRepo.save(it) }
            } else {
                settings
            }
            _state.update {
                it.copy(
                    settings = updated,
                    llmProfiles = profiles,
                    editingLlmProfileId = null,
                    notice = "Источник «${profile.name}» сохранён.",
                )
            }
        }
    }

    /** Удалить профиль; если он был активным — активным станет первый оставшийся. */
    fun deleteLlmProfile(id: String) {
        scope.launch {
            profileRepo.delete(id)
            val profiles = profileRepo.all()
            val settings = _state.value.settings
            val newActive = if (settings.activeLlmProfileId == id) {
                profiles.firstOrNull()?.id.orEmpty()
            } else {
                settings.activeLlmProfileId
            }
            val updated = settings.copy(activeLlmProfileId = newActive)
            settingsRepo.save(updated)
            // Снимаем переопределения свитков, ссылающиеся на удалённый профиль.
            _state.value.sessions.filter { it.llmProfileId == id }.forEach { session ->
                val cleared = session.copy(llmProfileId = null)
                chats.save(cleared)
            }
            bootstrap()
            _state.update { it.copy(notice = "Источник удалён.") }
        }
    }

    /** Сделать профиль глобально активным (для всех свитков без переопределения). */
    fun setActiveProfile(id: String) {
        scope.launch {
            val updated = _state.value.settings.copy(activeLlmProfileId = id)
            settingsRepo.save(updated)
            val profile = _state.value.llmProfiles.firstOrNull { it.id == id }
            _state.update {
                it.copy(settings = updated, notice = profile?.let { p -> "Основной источник: ${p.shortLabel}." })
            }
        }
    }

    /** Переопределить профиль только для текущего свитка (или снять переопределение: id = null). */
    fun selectChatProfile(id: String?) {
        val session = _state.value.current ?: return
        scope.launch {
            val updated = session.copy(llmProfileId = id, updatedAt = Id.now())
            chats.save(updated)
            _state.update { st ->
                st.copy(
                    current = updated,
                    sessions = st.sessions.map { if (it.id == updated.id) updated else it },
                )
            }
        }
    }

    /** Быстрая смена уровня усилия профиля (из переключателя в чате). */
    fun setProfileEffort(id: String, effort: EffortLevel) {
        scope.launch {
            val profile = profileRepo.all().firstOrNull { it.id == id } ?: return@launch
            profileRepo.save(profile.copy(effort = effort))
            _state.update { it.copy(llmProfiles = profileRepo.all()) }
        }
    }

    /** Открыт/закрыт ли переключатель модели в чате. */
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

    /** Загрузить список моделей, доступных у провайдера черновика профиля. */
    fun fetchModels(draft: LlmProfile) {
        val directory = modelDirectory
        if (directory == null) {
            _state.update { it.copy(editorModelsError = "Каталог моделей недоступен на этой платформе.") }
            return
        }
        if (!draft.configured) {
            _state.update { it.copy(editorModelsError = "Укажите Base URL и имя модели, затем повторите.") }
            return
        }
        if (_state.value.editorModelsLoading) return
        _state.update { it.copy(editorModelsLoading = true, editorModelsError = null) }
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
            _state.update { it.copy(editorModelsError = "Укажите Base URL и имя модели, затем повторите.") }
            return
        }
        if (_state.value.connectionTesting) return
        _state.update { it.copy(connectionTesting = true, editorModelsError = null) }
        scope.launch {
            val result = runCatching {
                testGateway.complete(draft, listOf(LlmMessage("user", "Скажи одно слово: ✦")))
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
            _state.update { it.copy(pluginStates = current) }
        }
    }

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
            bundle.llmProfiles.forEach { profile -> profileRepo.save(profile) }
            bootstrap()
            _state.update { it.copy(notice = "Профиль импортирован.") }
        }
    }

    fun wipeAll() {
        scope.launch {
            chats.wipe()
            settingsRepo.wipe()
            skills?.wipe()
            profileRepo.wipe()
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

    /** Полное удаление изолированных зависимостей движка. */
    fun uninstallCodingRuntime() {
        val runtime = codingRuntime ?: return
        scope.launch {
            runtime.abort()
            runtime.uninstall()
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        runtime = runtime.status(),
                        installing = false,
                        busy = false,
                        draft = io.aequicor.magicpaper.domain.CodingDraft(),
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
                _state.update {
                    it.copy(coding = it.coding.copy(current = existing, messages = repo.messages(existing.id)))
                }
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
            _state.update {
                it.copy(coding = it.coding.copy(projects = repo.all(), current = project, messages = emptyList()))
            }
        }
    }

    fun selectCodingProject(id: String) {
        val repo = codingProjects ?: return
        scope.launch {
            val project = repo.all().firstOrNull { it.id == id } ?: return@launch
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        current = project,
                        messages = repo.messages(id),
                        draft = io.aequicor.magicpaper.domain.CodingDraft(),
                    )
                )
            }
        }
    }

    fun deleteCodingProject(id: String) {
        val repo = codingProjects ?: return
        scope.launch {
            repo.delete(id)
            val rest = repo.all()
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        projects = rest,
                        current = if (it.coding.current?.id == id) rest.firstOrNull() else it.coding.current,
                        messages = emptyList(),
                    )
                )
            }
            _state.value.coding.current?.let { project -> loadCodingLog(project.id) }
        }
    }

    /** Запрос кодинг-агенту: агент работает в папке выбранного проекта. */
    fun sendCodingPrompt(text: String) {
        val runtime = codingRuntime ?: return
        val repo = codingProjects ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val s = _state.value.coding
        val project = s.current ?: return
        if (s.busy) return

        val userMessage = CodingMessage(
            id = Id.new(),
            role = CodingRole.USER,
            text = trimmed,
            createdAt = Id.now(),
        )
        val recorder = CodingRunRecorder()
        var sessionId = project.piSessionId

        scope.launch {
            val history = repo.messages(project.id) + userMessage
            repo.saveMessages(project.id, history)
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        messages = history,
                        busy = true,
                        draft = recorder.draft(active = true),
                    )
                )
            }
            runtime.run(project, trimmed, resolvedProfile()).collect { event ->
                if (event is io.aequicor.magicpaper.domain.CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                    sessionId = event.sessionId
                }
                recorder.apply(event)
                _state.update { it.copy(coding = it.coding.copy(draft = recorder.draft(active = true))) }
            }
            val agentMessage = recorder.message(Id.new(), Id.now())
            val finalLog = repo.messages(project.id) + agentMessage
            repo.saveMessages(project.id, finalLog)
            if (sessionId != project.piSessionId) {
                repo.save(project.copy(piSessionId = sessionId))
            }
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        messages = finalLog,
                        busy = false,
                        draft = io.aequicor.magicpaper.domain.CodingDraft(),
                        projects = repo.all(),
                        current = repo.all().firstOrNull { p -> p.id == project.id } ?: it.coding.current,
                    )
                )
            }
        }
    }

    fun abortCodingRun() {
        codingRuntime?.abort()
    }

    private fun loadCodingLog(projectId: String) {
        val repo = codingProjects ?: return
        scope.launch {
            _state.update {
                it.copy(coding = it.coding.copy(messages = repo.messages(projectId)))
            }
        }
    }
}
