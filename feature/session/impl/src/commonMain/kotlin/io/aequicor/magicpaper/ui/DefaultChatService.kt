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
import io.aequicor.magicpaper.domain.GatewaySessionRuntime
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch


/** Owns chat execution across component destruction and navigation. */
class DefaultChatService(
    private val runtime: CodingRuntime,
    private val chats: ChatRepository,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    override val requestPins: RequestPinService?,
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onOpenSession: (String) -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val draftBlobs: io.aequicor.magicpaper.data.storage.DraftBlobStore = io.aequicor.magicpaper.data.storage.InMemoryDraftBlobStore(),
    private val layoutAgent: LayoutChatAgent? = null,
    private val layoutProject: (String?) -> CodingProject? = { null },
    private val archiveClock: () -> Long = Id::now,
    private val archiveTicks: Flow<Unit> = sessionArchiveTicks(),
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
    private val sessionLocks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private suspend fun updateChat(id: String, change: (ChatSession) -> ChatSession): ChatSession {
        sessionLocks.update { if (id in it) it else it + (id to Mutex()) }
        return sessionLocks.value.getValue(id).withLock {
            check(id !in deletedDraftSessionIds) { "Чат удалён" }
            val latest = withContext(workerDispatcher) { chats.session(id) } ?: error("Чат не найден")
            val saved = change(latest)
            if (saved != latest) withContext(workerDispatcher) { chats.save(saved) }
            _state.update { state -> state.copy(
                sessions = state.sessions.map { if (it.id == id) saved else it },
                current = if (state.current?.id == id) saved else state.current) }
            saved
        }
    }
    override val state: StateFlow<ChatState> = _state.asStateFlow()
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) {
        _state.update { it.copy(settings = settings, llmProfiles = profiles, subscriptionAvailable = subscriptionAvailable, subscriptionSignedIn = subscriptionSignedIn) }
    }
    override suspend fun start() {
        observePins()
        val settings = settingsRepo.load()
        val profiles = profileRepo.load()
        val stored = chats.sessions()
        val sessions = stored.map { original ->
            val session = if (original.researchParentId != null || original.researchResourcesInitialized) original else {
                val history = stored.filter { it.researchChatId == original.id }.flatMap { it.messages }
                val files = history.flatMap { it.attachments }.map { ResearchResource(it.id, it.name, attachment = it) }
                val sites = history.flatMap { it.sources }.mapNotNull { hit -> researchUrl(hit.url)?.let {
                    if (it in original.excludedResourceUrls) null else ResearchResource(Id.new(), hit.title, it, discovered = true, snippet = hit.snippet)
                } }
                original.copy(resources = (original.resources + files + sites).distinctBy { it.url.ifEmpty { it.id } },
                    researchResourcesInitialized = true).also { chats.save(it) }
            }
            if (session.modelSelection != null) session else {
                val profile = ProfileResolver.resolve(session, settings, profiles)
                session.copy(modelSelection = profile?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) })
                    .also { chats.save(it) }
            }
        }
        _state.update { it.copy(settings = settings, llmProfiles = profiles, sessions = sessions,
            current = it.current?.let { selected -> sessions.firstOrNull { s -> s.id == selected.id } }) }
        observeAutoArchive()
    }
    private var archiveObserver: Job? = null
    private fun observeAutoArchive() {
        if (archiveObserver != null) return
        archiveObserver = scope.launch {
            combine(state.map { it.sessions }.distinctUntilChanged(), archiveTicks.onStart { emit(Unit) }) { sessions, _ -> sessions }
                .collect { sessions ->
                    sessions.filter { it.archiveDue(archiveClock()) }.forEach { session ->
                        changeArchive(session.id, archived = true, automatic = true)
                    }
                }
        }
    }
    override fun archiveSession(id: String) { scope.launch { changeArchive(id, archived = true) } }
    override fun restoreSession(id: String) { scope.launch { changeArchive(id, archived = false) } }
    private suspend fun changeArchive(id: String, archived: Boolean, automatic: Boolean = false) {
        if (id in deletingSessions || id in changingHistory || id in controllingSessions) return
        try {
            pendingCreations[id]?.await()
            val saved = updateChat(id) { latest ->
                when {
                    id in deletingSessions || archived && (id in chatJobs.value || latest.pendingRun != null || latest.queuedPrompts.isNotEmpty()) -> latest
                    automatic && !latest.archiveDue(archiveClock()) -> latest
                    else -> latest.copy(archived = archived, archiveRestoredAt = if (archived) latest.archiveRestoredAt else archiveClock())
                }
            }
            if (saved.archived == archived) AppLog.info("chat", if (archived) "session.archived" else "session.unarchived", mapOf("sessionId" to id))
            else if (!automatic) _state.update { it.copy(notice = "Дождитесь завершения запроса перед архивацией чата.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "session.archive.failed", failure, mapOf("sessionId" to id))
            _state.update { it.copy(notice = if (automatic) "Не удалось архивировать чат. Повторная попытка будет выполнена автоматически."
                else "Не удалось изменить архив чата. Повторите попытку.") }
        }
    }
    private var selectionGeneration = 0L
    override fun activate(id: String?) {
        selectionGeneration++
        visible.value = true
        _state.update { state ->
            val root = state.sessions.firstOrNull { it.id == id }
            val selected = state.sessions.firstOrNull { it.id == root?.selectedQuestionId && it.researchChatId == root.id } ?: root
            state.copy(current = selected, busy = selected?.id in chatJobs.value)
        }
    }
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
        selectionGeneration++
        val now = Id.now()
        val session = ChatSession(
            id = Id.new(),
            title = "Новое исследование",
            createdAt = now,
            updatedAt = now,
            engine = _state.value.settings.defaultCodingEngine,
            modelSelection = ProfileResolver.favoriteDefault(_state.value.settings, _state.value.availableLlmProfiles),
            researchResourcesInitialized = true,
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
        activate(id)
        onOpenSession(id)
    }

    /** Durable edits belong to the application service, even when their screen disappears. */
    private val researchEdits = Mutex()
    private suspend fun researchChange(operation: String, block: suspend () -> Unit): Result<Unit> = scope.async {
        val fields = mapOf("operationId" to Id.new(), "sessionId" to state.value.notebook?.id.orEmpty())
        try {
            researchEdits.withLock { block() }
            AppLog.info("chat", "research.$operation.completed", fields)
            Result.success(Unit)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.error("chat", "research.$operation.failed", error, fields)
            val message = "Не удалось сохранить изменение. Повторите попытку."
            _state.update { it.copy(notice = message) }
            Result.failure(IllegalStateException(message))
        }
    }.await()

    override suspend fun newQuestion(): Result<Unit> {
        val root = state.value.notebook ?: return Result.failure(IllegalStateException("Сначала создайте чат."))
        val generation = ++selectionGeneration
        val model = state.value.current?.modelSelection ?: root.modelSelection
        return researchChange("question.create") {
            check(root.id !in deletingSessions)
            pendingCreations[root.id]?.await()
            val now = Id.now()
            val question = ChatSession(Id.new(), "Новый вопрос", now, now,
                researchParentId = root.id, engine = root.engine, modelSelection = model,
                llmProfileId = root.llmProfileId)
            chats.save(question)
            _state.update { it.copy(sessions = it.sessions + question) }
            updateChat(root.id) { it.copy(selectedQuestionId = question.id) }
            if (selectionGeneration == generation) _state.update { it.copy(current = question, busy = false) }
            AppLog.info("chat", "question.created", mapOf("sessionId" to root.id, "questionId" to question.id))
        }
    }

    override suspend fun selectQuestion(id: String): Result<Unit> {
        val root = state.value.notebook ?: return Result.failure(IllegalStateException("Чат не найден."))
        val generation = ++selectionGeneration
        return researchChange("question.select") {
            require(state.value.sessions.any { it.id == id && it.researchChatId == root.id })
            check(root.id !in deletingSessions && id !in deletingSessions)
            pendingCreations[root.id]?.await()
            updateChat(root.id) { it.copy(selectedQuestionId = id) }
            if (selectionGeneration == generation) _state.update { latest ->
                latest.copy(current = latest.sessions.first { it.id == id }, busy = id in chatJobs.value)
            }
        }
    }

    override suspend fun addWebsite(chatId: String, url: String): Result<Unit> {
        val normalized = researchUrl(url) ?: return Result.failure(IllegalArgumentException("Введите ссылку на сайт: https://…"))
        return researchChange("source.add") {
            check(chatId !in deletingSessions)
            pendingCreations[chatId]?.await()
            updateChat(chatId) { root ->
                require(root.researchParentId == null)
                root.copy(resources = if (root.resources.any { it.url == normalized }) root.resources else
                    root.resources + ResearchResource(Id.new(), normalized, normalized),
                    excludedResourceUrls = root.excludedResourceUrls - normalized)
            }
        }
    }

    override suspend fun addResources(chatId: String, attachments: List<Attachment>): Result<Unit> = researchChange("files.add") {
        check(chatId !in deletingSessions)
        pendingCreations[chatId]?.await()
        shareFiles(chatId, attachments)
    }

    private suspend fun shareFiles(chatId: String, attachments: List<Attachment>) {
        updateChat(chatId) { root ->
            require(root.researchParentId == null)
            root.copy(resources = (root.resources + attachments.map {
                ResearchResource(it.id, it.name, attachment = it)
            }).distinctBy { it.id })
        }
    }

    override suspend fun removeResource(chatId: String, resourceId: String): Result<Unit> = researchChange("source.remove") {
        check(chatId !in deletingSessions)
        updateChat(chatId) { root ->
            require(root.researchParentId == null)
            val removed = root.resources.firstOrNull { it.id == resourceId }
            root.copy(resources = root.resources.filterNot { it.id == resourceId },
                excludedResourceUrls = root.excludedResourceUrls + listOfNotNull(removed?.url?.takeIf { it.isNotEmpty() }))
        }
    }

    private suspend fun rememberSources(chatId: String, sources: List<SearchHit>) {
        if (sources.isEmpty() || chatId in deletingSessions) return
        updateChat(chatId) { root ->
            val additions = sources.mapNotNull { hit -> researchUrl(hit.url)?.let { url ->
                if (url in root.excludedResourceUrls) null else ResearchResource(Id.new(), hit.title.ifBlank { url }, url,
                    discovered = true, snippet = hit.snippet)
            } }
            root.copy(resources = (root.resources + additions).distinctBy { if (it.url.isNotEmpty()) it.url else it.id })
        }
    }

    override fun deleteSession(id: String) {
        val initialIds = state.value.sessions.filter { it.researchParentId == id }.map { it.id } + id
        if (initialIds.any { it in changingHistory || it in deletingSessions }) return
        AppLog.info("chat", "session.delete", mapOf("sessionId" to id))
        deletingSessions.addAll(initialIds)
        scope.launch {
            try {
                researchEdits.withLock {
                    // A question whose creation was already accepted must join the deletion, too.
                    val ids = state.value.sessions.filter { it.researchParentId == id }.map { it.id } + id
                    deletingSessions.addAll(ids)
                    try { ids.forEach { deleteStoredQuestion(it, ids) } }
                    finally { deletingSessions.removeAll(ids.toSet()) }
                }
            } finally { deletingSessions.removeAll(initialIds.toSet()) }
        }
    }

    private suspend fun deleteStoredQuestion(id: String, deletingIds: List<String>) {
        chatJobs.value[id]?.cancelAndJoin()
        pendingCreations[id]?.await()
        queuedComputerRequests.update { requests -> requests.filterValues { it != id } }
        chats.session(id)?.let { runtime.deleteChatSession(it) }
        sessionLocks.update { if (id in it) it else it + (id to Mutex()) }
        sessionLocks.value.getValue(id).withLock {
            chats.delete(id)
            deletedDraftSessionIds += id
        }
        requestPins?.remove(PinConversation(id))
        composerSessions.remove("chat:$id")?.revoke()
        try { draftRepository.remove("chat:$id") }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            AppLog.error("chat", "draft.remove.failed", error, mapOf("sessionId" to id))
            _state.update { it.copy(notice = "Чат удалён. Не удалось удалить черновик.") }
        }
        val rest = chats.sessions()
        _state.update { state -> state.copy(sessions = rest,
            current = if (state.current?.id == id) rest.firstOrNull { it.researchParentId == null && it.id !in deletingIds } else state.current,
            busy = if (state.current?.id == id) false else state.busy) }
    }

    // Per-session jobs for regular chat (not coding sessions, which use codingJobs)
    private val chatJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    // requestId -> sessionId, only for requests accepted in this application lifetime.
    private val queuedComputerRequests = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun removeChatJob(sessionId: String): Job? = chatJobs.getAndUpdate { it - sessionId }[sessionId]

    private val deletingSessions = mutableSetOf<String>()
    private val controllingSessions = mutableSetOf<String>()
    private val changingHistory = mutableSetOf<String>()

    private suspend fun <T> changeHistory(sessionId: String, operation: String, block: suspend () -> T): Result<T> = scope.async {
        if (!changingHistory.add(sessionId)) return@async Result.failure(IllegalStateException("Дождитесь сохранения истории."))
        try {
            check(sessionId !in deletingSessions)
            pendingCreations[sessionId]?.await()
            val session = chats.session(sessionId) ?: error("Session missing")
            if (operation != "fork") {
                check(sessionId !in chatJobs.value && sessionId !in controllingSessions)
                check(session.queuedPrompts.isEmpty() && session.pendingRun?.intent != ExecutionIntent.RUN)
                runtime.reconcile(sessionId)
            }
            block().let { result ->
                AppLog.info("chat", "history.$operation", mapOf("sessionId" to sessionId))
                Result.success(result)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "history.$operation.failed", failure, mapOf("sessionId" to sessionId))
            val message = "Не удалось изменить историю. Завершите текущий запрос и повторите попытку."
            _state.update { it.copy(notice = message) }
            Result.failure(IllegalStateException(message))
        } finally {
            changingHistory.remove(sessionId)
            startNextChat(sessionId)
        }
    }.await()

    override suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit> = changeHistory(sessionId, "edit") {
        val saved = updateChat(sessionId) { session ->
            val message = session.messages.first { it.id == messageId }
            require(message.role == ChatRole.USER && (text.isNotBlank() || message.attachments.isNotEmpty()))
            val edited = message.copy(text = text.trim())
            session.copy(messages = session.messages.through(messageId) { it.id }.dropLast(1) + edited,
                nativeSessionId = "", pendingRun = null, updatedAt = Id.now())
        }
        val message = saved.messages.last()
        changingHistory.remove(sessionId)
        startChat(message.text, message.attachments, sessionId,
            CodingRunCheckpoint(message.id, message.text, message.attachments, responseId = Id.new()), clearDraft = false)
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit> = changeHistory(sessionId, "delete") {
        updateChat(sessionId) { session ->
            require(session.messages.any { it.id == messageId })
            session.copy(messages = session.messages.filterNot { it.id == messageId }, nativeSessionId = "",
                pendingRun = null, updatedAt = Id.now())
        }
        Unit
    }

    override suspend fun forkSession(sessionId: String, throughMessageId: String?): Result<String> = changeHistory(sessionId, "fork") {
        val source = checkNotNull(chats.session(sessionId))
        val now = Id.now()
        val fork = source.copy(id = Id.new(), title = "${source.title} — форк", createdAt = now, updatedAt = now,
            messages = source.messages.through(throughMessageId) { it.id }.map { it.copy(id = Id.new()) },
            nativeSessionId = "", pendingRun = null, queuedPrompts = emptyList(), acquireComputerAccess = false,
            archived = false, archiveRestoredAt = null,
            researchParentId = null, selectedQuestionId = null,
            resources = chats.session(source.researchChatId)?.resources.orEmpty())
        chats.save(fork)
        _state.update { it.copy(sessions = listOf(fork) + it.sessions) }
        onOpenSession(fork.id)
        fork.id
    }

    override fun send(text: String, attachments: List<Attachment>) {
        val session = _state.value.current
        if (session?.id in changingHistory) return
        if (session != null && (session.id in chatJobs.value || session.pendingRun != null)) {
            val visible = attachments.chatVisible()
            if ((text.isBlank() && visible.isEmpty()) || session.id in deletingSessions || session.researchChatId in deletingSessions) return
            val draft = composerDraft(session.id)
            val version = draft.state.value.version
            val request = CodingRunCheckpoint(Id.new(), text.trim(), visible, responseId = Id.new())
            scope.launch {
                pendingCreations[session.id]?.await()
                updateChat(session.id) { it.copy(queuedPrompts = it.queuedPrompts + request) }
                if (visible.isNotEmpty()) shareFiles(session.researchChatId, visible)
                queuedComputerRequests.update { it + (request.messageId to session.id) }
                draft.clearIfUnchanged(version)
                startNextChat(session.id)
            }
        } else startChat(text, attachments)
    }

    private fun startNextChat(sessionId: String) {
        if (!scope.isActive) return
        if (sessionId in chatJobs.value || sessionId in deletingSessions || sessionId in controllingSessions || sessionId in changingHistory) return
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.pendingRun != null) return
        session.queuedPrompts.firstOrNull()?.let { request ->
            startChat(request.prompt, request.attachments, sessionId, request, clearDraft = false,
                acquireComputerAccess = queuedComputerRequests.value[request.messageId] == sessionId)
        }
    }

    override fun pause() {
        val session = _state.value.current ?: return
        if (!controllingSessions.add(session.id)) return
        scope.launch {
            try {
                updateChat(session.id) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true)) }
                runtime.abort(session.id)
                chatJobs.value[session.id]?.cancelAndJoin()
                runtime.reconcile(session.id)
            } finally { controllingSessions.remove(session.id) }
        }
    }

    override fun resume(text: String, attachments: List<Attachment>) {
        val session = _state.value.current ?: return
        if (session.id in changingHistory) return
        if (session.id in chatJobs.value || session.id in controllingSessions || session.id in deletingSessions) return
        val request = session.pendingRun ?: return
        val revised = request.copy(prompt = request.prompt + if (text.isBlank()) "" else "\n\nУточнение пользователя: " + text.trim(),
            attachments = (request.attachments + attachments.chatVisible()).distinctBy { it.id }, intent = ExecutionIntent.RUN, stoppedByUser = false)
        startChat(revised.prompt, revised.attachments, session.id, revised)
    }

    override fun clarify(text: String, attachments: List<Attachment>) {
        val session = _state.value.current ?: return
        if (session.id in changingHistory) return
        if (text.isBlank() && attachments.isEmpty()) return
        if (session.id !in chatJobs.value) { resume(text, attachments); return }
        if (!controllingSessions.add(session.id)) return
        val draft = composerDraft(session.id)
        val version = draft.state.value.version
        scope.launch {
            try {
                val accepted = updateChat(session.id) { latest ->
                    val request = checkNotNull(latest.pendingRun) { "Запуск ещё не готов принять уточнение" }
                    latest.copy(pendingRun = request.copy(prompt = request.prompt + "\n\nУточнение пользователя: " + text.trim(),
                        attachments = (request.attachments + attachments.chatVisible()).distinctBy { it.id }, intent = ExecutionIntent.STOP))
                }
                draft.clearIfUnchanged(version)
                runtime.abort(session.id)
                chatJobs.value[session.id]?.cancelAndJoin()
                runtime.reconcile(session.id)
                val request = checkNotNull(accepted.pendingRun).copy(intent = ExecutionIntent.RUN, stoppedByUser = false)
                startChat(request.prompt, request.attachments, session.id, request, clearDraft = false)
            } finally { controllingSessions.remove(session.id) }
        }
    }

    private fun startChat(text: String, attachments: List<Attachment>, targetId: String? = null,
        resumed: CodingRunCheckpoint? = null, clearDraft: Boolean = true, acquireComputerAccess: Boolean = true) {

        val trimmed = text.trim()
        val visible = attachments.chatVisible()
        if (trimmed.isEmpty() && visible.isEmpty()) return
        val settings = _state.value.settings
        val composer = composerDraft(targetId ?: _state.value.current?.id)
        val draftVersion = composer.state.value.version
        val session = (targetId?.let { id -> _state.value.sessions.firstOrNull { it.id == id } } ?: _state.value.current) ?: run {
            newSession()
            _state.value.current ?: return
        }
        // Allow parallel chat sessions - each session has its own job
        if (session.id in chatJobs.value || session.id in deletingSessions || session.researchChatId in deletingSessions || session.id in changingHistory) return

        val request = resumed ?: CodingRunCheckpoint(Id.new(), trimmed, visible, responseId = Id.new())
        val userMessage = ChatMessage(
            id = request.messageId,
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
        val historyBefore = session.messages.filterNot { it.id == request.messageId }
        val updated = session.copy(
            engine = session.engine ?: settings.defaultCodingEngine,
            messages = if (session.messages.any { it.id == userMessage.id }) session.messages else session.messages + userMessage,
            pendingRun = request,
            layoutProjectId = session.layoutProjectId ?: capturedProject?.id,
            title = if (session.messages.isEmpty()) trimmed.take(40) else session.title,
            updatedAt = Id.now(), archived = false,
        )
        val requestProfile = ProfileResolver.resolve(updated, settings, _state.value.availableLlmProfiles)
        val operationalProfile = ProfileResolver.resolve(null as ChatSession?, settings, _state.value.availableLlmProfiles)
        _state.update { st ->
            st.copy(
                current = if (st.current?.id == session.id) updated else st.current,
                busy = st.current?.id == session.id || st.busy,
                sessions = st.sessions.map { if (it.id == updated.id) updated else it },
            )
        }
        val operationFields = mapOf("sessionId" to session.id, "requestId" to userMessage.id)
        AppLog.info("chat", "send.started", operationFields)
        val job = scope.launch(workerDispatcher + io.aequicor.magicpaper.domain.UsageOwner(io.aequicor.magicpaper.domain.UsageScope.chat(session.id)), start = CoroutineStart.LAZY) {
            try {
                creation?.await()
                val accepted = updateChat(session.id) { latest -> latest.copy(
                    engine = updated.engine, layoutProjectId = updated.layoutProjectId,
                    title = updated.title, updatedAt = updated.updatedAt, pendingRun = request, archived = false,
                    queuedPrompts = latest.queuedPrompts.filterNot { it.messageId == request.messageId },
                    messages = if (latest.messages.any { it.id == userMessage.id }) latest.messages else latest.messages + userMessage) }
                if (resumed == null && visible.isNotEmpty()) {
                    shareFiles(session.researchChatId, visible)
                }
                if (session.researchParentId != null) updateChat(session.researchChatId) { it.copy(updatedAt = updated.updatedAt) }
                queuedComputerRequests.update { it - request.messageId }
                if (resumed != null) runtime.reconcile(session.id)
                // The accepted message is durable; a newer draft typed during send remains intact.
                try { if (clearDraft) withContext(Dispatchers.Main.immediate) { composer.clearIfUnchanged(draftVersion) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: io.aequicor.magicpaper.data.storage.StorageException) {
                    logPersistenceFailure("chat", "draft.clear.failed", error, mapOf("sessionId" to session.id, "requestId" to userMessage.id))
                    _state.update { it.copy(notice = if (error.committed) "Сообщение сохранено. Не удалось удалить временные данные." else "Сообщение сохранено. Не удалось очистить черновик.") }
                }
                val answer = if (layoutRequest != null && layoutAgent != null) {
                    layoutAgent.answer(layoutRequest.project, session.id, userMessage.id, trimmed, historyBefore, requestProfile, visible)
                } else {
                    val shared = checkNotNull(chats.session(session.researchChatId)).resources
                    val researchAttachments = (visible + shared.mapNotNull { it.attachment }).distinctBy { it.id }
                    val recorder = CodingRunRecorder()
                    var finished = false
                    var sources = emptyList<SearchHit>()
                    var outputAttachments = emptyList<Attachment>()
                    val recovering = resumed != null && session.pendingRun?.messageId == resumed.messageId
                    val prompt = if (recovering) "Продолжи незавершённую работу в этой сессии. Сначала проверь сохранённый контекст, " +
                        "результаты команд и состояние файлов; не повторяй завершённые действия.\n\n" + trimmed else trimmed
                    runtime.runChat(accepted.copy(acquireComputerAccess = acquireComputerAccess, resources = shared), prompt, requestProfile, researchAttachments).collect { event ->
                        if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                            updateChat(session.id) { it.copy(nativeSessionId = event.sessionId) }
                        }
                        recorder.apply(event)
                        val found = event.researchSources()
                        if (found.isNotEmpty()) {
                            sources = (sources + found).distinctBy { it.url }
                            rememberSources(session.researchChatId, found)
                        }
                        if (event is CodingEvent.FinalText) { outputAttachments = event.attachments }
                        if (event is CodingEvent.Finished) finished = true
                    }
                    val response = recorder.message(Id.new(), Id.now())
                    check(finished && !response.failed) { "Движок не завершил ответ" }
                    SessionAnswer(response.text, sources, outputAttachments)
                }
                val agentMessage = ChatMessage(
                    id = request.responseId.ifBlank { Id.new() },
                    role = ChatRole.AGENT,
                    text = answer.text,
                    createdAt = Id.now(),
                    sources = answer.sources,
                    attachments = answer.attachments,
                )
                updateChat(session.id) { latest -> latest.copy(messages = latest.messages.filterNot { it.id == agentMessage.id } + agentMessage, updatedAt = Id.now(), pendingRun = null) }
                AppLog.info("chat", "send.completed", operationFields)
            } catch (e: CancellationException) { runtime.abort(session.id); AppLog.info("chat", "send.cancelled", operationFields); throw e }
            catch (e: Exception) {
                try { updateChat(session.id) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP)) } }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { AppLog.error("chat", "checkpoint.failed", failure, operationFields) }
                AppLog.error("chat", "send.failed", e, operationFields); _state.update { it.copy(notice = "Не удалось завершить отправку. Проверьте подключение и повторите попытку.") } }
            finally {
                chatJobs.update { it - session.id }
                _state.update { it.copy(busy = it.current?.id in chatJobs.value) }
                withContext(NonCancellable + Dispatchers.Main.immediate) { startNextChat(session.id) }
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
        val creation = pendingCreations[session.id]
        scope.launch {
            creation?.await()
            updateChat(session.id) { it.copy(modelSelection = selection, llmProfileId = selection.profileId) }
        }
    }

    override fun selectChatEngine(engine: CodingEngine) {
        if (_state.value.current == null) newSession()
        val session = _state.value.current ?: return
        if (session.messages.isNotEmpty() || session.pendingRun != null || session.nativeSessionId.isNotBlank()) return
        val settings = _state.value.settings.copy(defaultCodingEngine = engine)
        val updated = session.copy(engine = engine)
        _state.update { state -> state.copy(
            settings = settings,
            current = updated,
            sessions = state.sessions.map { if (it.id == updated.id) updated else it },
        ) }
        val creation = pendingCreations[session.id]
        scope.launch {
            try {
                creation?.await()
                updateChat(session.id) { latest ->
                    check(latest.messages.isEmpty() && latest.pendingRun == null && latest.nativeSessionId.isBlank()) {
                        "Backend cannot be changed after execution starts"
                    }
                    latest.copy(engine = engine)
                }
                settingsRepo.save(settings)
                AppLog.info("chat", "engine.selected", mapOf("sessionId" to session.id, "backend" to engine.name))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("chat", "engine.select.failed", failure, mapOf("sessionId" to session.id, "backend" to engine.name))
                _state.update { it.copy(notice = "Не удалось сохранить выбор движка. Повторите попытку.") }
            }
        }
    }


    /** Drain this application's writers while retaining its reusable supervisor. */
    suspend fun prepareForReset() {
        queuedComputerRequests.value = emptyMap()
        deletingSessions.addAll(_state.value.sessions.map { it.id })
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it != caller }?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.forEach { it.join() }
        resetDrafts()
        pinObserver = null
        archiveObserver = null
        visible.value = false
        chatJobs.value = emptyMap()
        pendingCreations.clear()
        _state.value = ChatState()
        deletingSessions.clear()
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
