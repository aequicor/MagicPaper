package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.data.storage.logPersistenceFailure

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRepository
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.MagicAgent
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.domain.chatVisible
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch


/** Owns chat execution across component destruction and navigation. */
class DefaultChatService(
    private val agent: MagicAgent,
    private val chats: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    override val requestPins: RequestPinService?,
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onOpenSession: (String) -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val draftBlobs: io.aequicor.magicpaper.data.storage.DraftBlobStore = io.aequicor.magicpaper.data.storage.InMemoryDraftBlobStore(),
    private val layoutProject: (String?) -> CodingProject? = { null },
) : ChatService {
    private val _state = MutableStateFlow(ChatState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("chat", "background.failed", error)
        _state.update { it.copy(notice = "Не удалось выполнить действие с чатом. Повторите попытку.") }
    })
    private val composerSessions = mutableMapOf<String, io.aequicor.magicpaper.data.storage.DraftSession<ComposerDraftData>>()
    private val deletedDraftSessionIds = mutableSetOf<String>()
    fun composerDraft(sessionId: String?): io.aequicor.magicpaper.data.storage.DraftSession<ComposerDraftData> {
        val key = "chat:" + (sessionId ?: "new")
        return composerSessions.getOrPut(key) { composerDraftSession(draftRepository, draftBlobs, key, scope) }
            .also { if (sessionId in deletedDraftSessionIds) it.revoke() }
    }
    override val state: StateFlow<ChatState> = _state.asStateFlow()
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) {
        _state.update { it.copy(settings = settings, llmProfiles = profiles, subscriptionAvailable = subscriptionAvailable, subscriptionSignedIn = subscriptionSignedIn) }
    }
    override suspend fun start() {
        observePins()
        val settings = settingsRepo.load()
        val profiles = profileRepo.load()
        val sessions = chats.sessions().map { session ->
            if (session.modelSelection != null) session else {
                val profile = ProfileResolver.resolve(session, settings, profiles)
                session.copy(modelSelection = profile?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) })
                    .also { chats.save(it) }
            }
        }
        _state.update { it.copy(settings = settings, llmProfiles = profiles, sessions = sessions,
            current = it.current?.let { selected -> sessions.firstOrNull { s -> s.id == selected.id } }) }
    }
    override fun activate(id: String?) { visible.value = true; _state.update { it.copy(current = it.sessions.firstOrNull { session -> session.id == id }, busy = id in chatJobs.value) } }
    private val visible = MutableStateFlow(false)
    private var pinObserver: Job? = null
    override fun setVisible(visible: Boolean) { this.visible.value = visible }
    private fun observePins() {
        val pins = requestPins ?: return
        if (pinObserver != null) return
        pinObserver = scope.launch {
            var previousOpen: PinConversation? = null
            var previousProfile: LlmProfile? = null
            val sources = mutableMapOf<PinConversation, Any>()
            kotlinx.coroutines.flow.combine(state, visible) { current, shown -> current to shown }.collect { (current, shown) ->
                val profile = ProfileResolver.resolve(null as ChatSession?, current.settings, current.availableLlmProfiles)
                    ?.takeIf { it.provider != ProviderType.OPENAI_SUBSCRIPTION || current.subscriptionSignedIn }
                if (profile != previousProfile) sources.clear()
                previousProfile = profile
                val opened = current.current?.takeIf { shown }?.let { PinConversation(it.id) }
                sources.keys.retainAll(current.sessions.map { PinConversation(it.id) }.toSet())
                current.sessions.forEach { stored ->
                    val session = current.current?.takeIf { it.id == stored.id } ?: stored
                    val key = PinConversation(session.id)
                    if (key == opened || pins.isTracking(key)) {
                        val reopened = key == opened && key != previousOpen
                        if (reopened || sources[key] !== session.messages) {
                            pins.sync(key, session.pinMessages(), profile, reopened)
                            sources[key] = session.messages
                        }
                    }
                }
                previousOpen = opened
            }
        }
    }
    override fun dismissNotice() { _state.update { it.copy(notice = null) } }
    private val pendingCreations = mutableMapOf<String, CompletableDeferred<Unit>>()
    override fun newSession() {
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
                    busy = false,

                )
        }
        val saved = CompletableDeferred<Unit>()
        pendingCreations[session.id] = saved
        scope.launch {
            try { chats.save(session); saved.complete(Unit); AppLog.info("chat", "session.created", mapOf("sessionId" to session.id)); onOpenSession(session.id) }
            catch (e: CancellationException) { saved.cancel(e); throw e }
            catch (e: Exception) { AppLog.error("chat", "session.create.failed", e, mapOf("sessionId" to session.id)); saved.completeExceptionally(e); _state.update { it.copy(notice = "Не удалось сохранить чат.") } }
            finally { pendingCreations.remove(session.id) }
        }
    }

    override fun selectSession(id: String) {
        scope.launch {
            val session = chats.session(id) ?: return@launch
            _state.update { it.copy(current = session, busy = id in chatJobs.value) }; onOpenSession(id)
        }
    }

    override fun deleteSession(id: String) {
        AppLog.info("chat", "session.delete", mapOf("sessionId" to id))
        scope.launch {
            removeChatJob(id)?.cancelAndJoin()
            pendingCreations[id]?.await()
            chats.delete(id)
            requestPins?.remove(PinConversation(id))
            deletedDraftSessionIds += id
            composerSessions.remove("chat:$id")?.revoke()
            try { draftRepository.remove("chat:$id") }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { AppLog.error("chat", "draft.remove.failed", error, mapOf("sessionId" to id)); _state.update { it.copy(notice = "Чат удалён. Не удалось удалить черновик.") } }
            val rest = chats.sessions()
            _state.update {
                it.copy(
                    sessions = rest,
                    current = if (it.current?.id == id) rest.firstOrNull() else it.current,
                )
            }
        }
    }

    // Per-session jobs for regular chat (not coding sessions, which use codingJobs)
    private val chatJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    private fun removeChatJob(sessionId: String): Job? = chatJobs.getAndUpdate { it - sessionId }[sessionId]

    override fun send(text: String, attachments: List<Attachment>) {
        val trimmed = text.trim()
        val visible = attachments.chatVisible()
        if (trimmed.isEmpty() && visible.isEmpty()) return
        val settings = _state.value.settings
        val composer = composerDraft(_state.value.current?.id)
        val draftVersion = composer.state.value.version
        val session = _state.value.current ?: run {
            newSession()
            _state.value.current ?: return
        }
        // Allow parallel chat sessions - each session has its own job
        if (session.id in chatJobs.value) return

        val userMessage = ChatMessage(
            id = Id.new(),
            role = ChatRole.USER,
            text = trimmed,
            createdAt = Id.now(),
            attachments = visible,
        )
        val wantsLayout = isLayoutRequest(trimmed, session.layoutProjectId != null)
        // Capture both identity and path before launching work: changing sidebar selection cannot redirect it.
        val capturedProject = if (wantsLayout) layoutProject(session.layoutProjectId) else null
        val layoutRequest = if (wantsLayout) LayoutChatRequest(capturedProject, session.id, userMessage.id) else null
        val creation = pendingCreations[session.id]
        val historyBefore = session.messages
        val updated = session.copy(
            messages = session.messages + userMessage,
            layoutProjectId = session.layoutProjectId ?: capturedProject?.id,
            title = if (session.messages.isEmpty()) trimmed.take(40) else session.title,
            updatedAt = Id.now(),
        )
        val requestProfile = ProfileResolver.resolve(updated, settings, _state.value.availableLlmProfiles)
        val operationalProfile = ProfileResolver.resolve(null as ChatSession?, settings, _state.value.availableLlmProfiles)
        _state.update { st ->
            st.copy(
                current = updated,
                busy = true,
                sessions = st.sessions.map { if (it.id == updated.id) updated else it },
            )
        }
        val operationFields = mapOf("sessionId" to session.id, "requestId" to userMessage.id)
        AppLog.info("chat", "send.started", operationFields)
        val job = scope.launch(workerDispatcher + io.aequicor.magicpaper.domain.UsageOwner(io.aequicor.magicpaper.domain.UsageScope.chat(session.id)), start = CoroutineStart.LAZY) {
            try {
                creation?.await()
                chats.save(updated)
                // The accepted message is durable; a newer draft typed during send remains intact.
                try { withContext(Dispatchers.Main.immediate) { composer.clearIfUnchanged(draftVersion) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: io.aequicor.magicpaper.data.storage.StorageException) {
                    logPersistenceFailure("chat", "draft.clear.failed", error, mapOf("sessionId" to session.id, "requestId" to userMessage.id))
                    _state.update { it.copy(notice = if (error.committed) "Сообщение сохранено. Не удалось удалить временные данные." else "Сообщение сохранено. Не удалось очистить черновик.") }
                }
                val answer = agent.answer(historyBefore, trimmed, settings, requestProfile, attachments = visible, operationalProfile = operationalProfile, layoutRequest = layoutRequest)
                val agentMessage = ChatMessage(
                    id = Id.new(),
                    role = ChatRole.AGENT,
                    text = answer.text,
                    createdAt = Id.now(),
                    sources = answer.sources,
                    attachments = answer.attachments,
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
                    )
                }
                AppLog.info("chat", "send.completed", operationFields)
            } catch (e: CancellationException) { AppLog.info("chat", "send.cancelled", operationFields); throw e }
            catch (e: Exception) { AppLog.error("chat", "send.failed", e, operationFields); _state.update { it.copy(notice = "Не удалось завершить отправку. Проверьте подключение и повторите попытку.") } }
            finally {
                chatJobs.update { it - session.id }
                _state.update { it.copy(busy = it.current?.id in chatJobs.value) }
            }
        }
        chatJobs.update { it + (session.id to job) }
        job.start()
    }

    // ---- Настройки ---------------------------------------------------------

    /** Завершение ознакомительного тура: сохранить черновик и впустить в приложение.
     * [onboardingProfile] — профиль, созданный на шаге «источник магии» (если настроен). */
    override fun selectChatModel(selection: ModelSelection) {
        if (_state.value.availableLlmProfiles.none { it.id == selection.profileId && selection.modelId in it.displayModels }) return
        if (_state.value.current == null) newSession()
        val session = _state.value.current ?: return
        val updated = session.copy(modelSelection = selection, llmProfileId = selection.profileId)
        _state.update { st -> st.copy(current = updated, sessions = st.sessions.map { if (it.id == updated.id) updated else it }) }
        scope.launch { chats.save(updated) }
    }


    /** Drain this application's writers while retaining its reusable supervisor. */
    suspend fun prepareForReset() {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it != caller }?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.forEach { it.join() }
        resetDrafts()
        pinObserver = null
        visible.value = false
        chatJobs.value = emptyMap()
        pendingCreations.clear()
        _state.value = ChatState()
    }

    /** Called after the application has cancelled and joined the draft writers. */
    suspend fun resetDrafts() { composerSessions.values.forEach { it.revoke() }; composerSessions.clear(); deletedDraftSessionIds.clear() }

    override suspend fun close() {
        try { composerSessions.values.forEach { it.awaitSaved() } }
        finally {
            val jobs = scope.coroutineContext[Job]?.children?.toList().orEmpty()
            scope.cancel()
            jobs.joinAll()
        }
    }
}
