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
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.domain.ProfileBundle
import io.aequicor.magicpaper.domain.ProfileBridge
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
    private val docs: DocRepository,
    private val registry: PluginRegistry,
    private val bridge: ProfileBridge,
    private val store: KeyValueStore,
    private val json: Json,
    private val skills: io.aequicor.magicpaper.domain.SkillRepository? = null,
    private val codingRuntime: CodingRuntime? = null,
    private val codingProjects: CodingProjectRepository? = null,
    private val dirPicker: ProjectDirPicker? = null,
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
        _state.update {
            it.copy(
                settings = settings,
                plugins = registry.all(),
                pluginStates = states,
                sessions = sessions,
                current = sessions.firstOrNull(),
                docsArticles = docs.articles(),
                storageInfo = store.description,
                showWelcome = !settings.onboardingDone,
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
            val answer = agent.answer(historyBefore, trimmed, settings)
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

    /** Завершение ознакомительного тура: сохранить черновик и впустить в приложение. */
    fun finishOnboarding(settings: AppSettings) {
        scope.launch {
            val done = settings.copy(onboardingDone = true)
            settingsRepo.save(done)
            _state.update { it.copy(settings = done, showWelcome = false, screen = Screen.CHAT) }
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
            bootstrap()
            _state.update { it.copy(notice = "Профиль импортирован.") }
        }
    }

    fun wipeAll() {
        scope.launch {
            chats.wipe()
            settingsRepo.wipe()
            skills?.wipe()
            _state.update {
                it.copy(
                    current = null,
                    sessions = emptyList(),
                    settings = AppSettings(),
                    pluginStates = emptyMap(),
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
            runtime.run(project, trimmed, _state.value.settings).collect { event ->
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
