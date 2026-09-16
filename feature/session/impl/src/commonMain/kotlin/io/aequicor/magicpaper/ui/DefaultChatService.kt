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
    private val researchSearch: SearchEngine? = null,
    private val archiveClock: () -> Long = Id::now,
    private val archiveTicks: Flow<Unit> = sessionArchiveTicks(),
    val usage: UsageLedger? = null,
    private val sourceAccess: ResearchSourceAccess = ResearchSourceAccess(),
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
                original.copy(resources = (original.resources + files).distinctBy { it.url.ifEmpty { it.id } },
                    questionResources = (original.questionResources + sites).distinctBy { it.url.ifEmpty { it.id } },
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
        restoreChatRuns(sessions)
        // An orphaned queue is restored as data. A running checkpoint resumes its own
        // queue on completion; otherwise the next explicit request starts it.
        observeAutoArchive()
    }

    private suspend fun restoreChatRuns(sessions: List<ChatSession>) {
        for (session in sessions) {
            val request = session.pendingRun ?: continue
            if (request.intent != ExecutionIntent.RUN || session.archived) continue
            val response = session.messages.firstOrNull { it.id == request.responseId }
            if (response != null) {
                updateChat(session.id) { latest ->
                    if (latest.pendingRun?.messageId == request.messageId) latest.copy(pendingRun = null) else latest
                }
                continue
            }
            startChat(request.prompt, request.attachments, session.id, request,
                clearDraft = false, acquireComputerAccess = false)
        }
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
        _state.update { state ->
            val root = state.sessions.firstOrNull { it.id == id }
            val selected = state.sessions.firstOrNull { it.id == root?.selectedQuestionId && it.researchChatId == root.id } ?: root
            state.copy(current = selected, busy = selected?.id in chatJobs.value)
        }
    }
    // Research has no pinned-message surface. Keep the lifecycle contract without
    // launching hidden summary requests or rescanning all histories on streamed updates.
    override fun setVisible(visible: Boolean) = Unit
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
    private suspend fun researchChange(operation: String, questionId: String? = state.value.current?.id, block: suspend () -> Unit): Result<Unit> = scope.async {
        val fields = mapOf("operationId" to Id.new(), "sessionId" to questionId.orEmpty())
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

    override suspend fun addWebsite(questionId: String, url: String, scope: ResearchResourceScope): Result<Unit> {
        val normalized = researchUrl(url) ?: return Result.failure(IllegalArgumentException("Введите ссылку на сайт: https://…"))
        return researchChange("source.add", questionId) {
            addResearchResources(questionId, scope, listOf(ResearchResource(Id.new(), normalized, normalized)))
        }
    }

    override suspend fun searchResources(query: String): Result<List<SearchHit>> {
        if (query.isBlank()) return Result.failure(IllegalArgumentException("Введите поисковый запрос."))
        val search = researchSearch ?: return Result.failure(IllegalStateException("Поиск не настроен."))
        val settings = state.value.settings
        if (!search.isConfigured(settings)) return Result.failure(IllegalStateException("Поиск не настроен."))
        return try {
            val result = withTimeout(15_000) { search.searchWithDiagnostics(query.trim(), settings, limit = 8) }
            if (result.hits.isEmpty() && result.issues.isNotEmpty()) {
                Result.failure(IllegalStateException("Поиск недоступен. Повторите попытку."))
            } else Result.success(result.hits.mapNotNull { hit -> researchUrl(hit.url)?.let { hit.copy(url = it) } }
                .distinctBy { it.url })
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Поиск не ответил. Повторите попытку."))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "research.source.search.failed", failure)
            Result.failure(IllegalStateException("Поиск недоступен. Повторите попытку."))
        }
    }

    override suspend fun addSearchResult(questionId: String, hit: SearchHit, scope: ResearchResourceScope): Result<Unit> {
        val normalized = researchUrl(hit.url) ?: return Result.failure(IllegalArgumentException("Источник содержит неверную ссылку."))
        return researchChange("source.search-result.add", questionId) {
            addResearchResources(questionId, scope, listOf(
                ResearchResource(Id.new(), hit.title.ifBlank { normalized }, normalized, snippet = hit.snippet)))
        }
    }

    override suspend fun addResources(questionId: String, attachments: List<Attachment>, scope: ResearchResourceScope): Result<Unit> =
        researchChange("files.add", questionId) { addResearchResources(questionId, scope, attachments.map {
            ResearchResource(it.id, it.name, attachment = it)
        }) }

    private suspend fun researchQuestion(questionId: String): ChatSession {
        check(questionId !in deletingSessions)
        pendingCreations[questionId]?.await()
        val question = checkNotNull(chats.session(questionId))
        check(question.researchChatId !in deletingSessions)
        return question
    }

    /** Called under researchEdits: user changes and late discoveries share one ordering. */
    private suspend fun addResearchResources(questionId: String, scope: ResearchResourceScope, additions: List<ResearchResource>) {
        val question = researchQuestion(questionId)
        val target = if (scope == ResearchResourceScope.SHARED) question.researchChatId else questionId
        updateChat(target) { stored ->
            val keys = additions.map { it.key }.toSet()
            val urls = additions.map { it.url }.toSet()
            if (scope == ResearchResourceScope.SHARED) stored.copy(
                resources = (stored.resources + additions).distinctBy { it.key },
                questionResources = stored.questionResources.filterNot { it.key in keys },
                excludedResourceUrls = stored.excludedResourceUrls - urls)
            else stored.copy(questionResources = (stored.questionResources + additions).distinctBy { it.key },
                excludedQuestionResourceUrls = stored.excludedQuestionResourceUrls - urls)
        }
        updateChat(questionId) { stored ->
            val keys = additions.map { it.key }.toSet()
            stored.copy(disabledResourceKeys = stored.disabledResourceKeys - keys,
                questionResources = if (scope == ResearchResourceScope.SHARED)
                    stored.questionResources.filterNot { it.key in keys } else stored.questionResources)
        }
    }

    private suspend fun attachQuestionFiles(questionId: String, attachments: List<Attachment>) = researchEdits.withLock {
        addResearchResources(questionId, ResearchResourceScope.QUESTION, attachments.map {
            ResearchResource(it.id, it.name, attachment = it)
        })
    }

    override suspend fun setResourceEnabled(questionId: String, resourceKey: String, enabled: Boolean): Result<Unit> =
        setResourcesEnabled(questionId, setOf(resourceKey), enabled)

    override suspend fun setResourcesEnabled(questionId: String, resourceKeys: Set<String>, enabled: Boolean): Result<Unit> =
        researchChange("source.selection", questionId) {
            val question = researchQuestion(questionId)
            val root = checkNotNull(chats.session(question.researchChatId))
            val availableKeys = (root.resources + question.questionResources).mapTo(mutableSetOf()) { it.key }
            require(availableKeys.containsAll(resourceKeys))
            updateChat(questionId) { it.copy(disabledResourceKeys = if (enabled) it.disabledResourceKeys - resourceKeys
                else it.disabledResourceKeys + resourceKeys) }
        }

    override suspend fun shareResource(questionId: String, resourceId: String): Result<Unit> = researchChange("source.share", questionId) {
        val question = researchQuestion(questionId)
        val source = question.questionResources.first { it.id == resourceId }
        // Save the shared copy first. A failed local cleanup cannot lose the source; rendering deduplicates it.
        updateChat(question.researchChatId) { it.copy(resources = (it.resources + source).distinctBy { resource -> resource.key },
            excludedResourceUrls = it.excludedResourceUrls - source.url) }
        updateChat(questionId) { it.copy(questionResources = it.questionResources.filterNot { resource -> resource.key == source.key }) }
    }

    override suspend fun removeResource(questionId: String, resourceId: String, scope: ResearchResourceScope): Result<Unit> =
        researchChange("source.remove", questionId) {
            val question = researchQuestion(questionId)
            val target = if (scope == ResearchResourceScope.SHARED) question.researchChatId else questionId
            updateChat(target) { stored ->
                val resources = if (scope == ResearchResourceScope.SHARED) stored.resources else stored.questionResources
                val removed = resources.firstOrNull { it.id == resourceId }
                val remaining = resources.filterNot { it.id == resourceId }
                val urls = listOfNotNull(removed?.url?.takeIf { it.isNotEmpty() })
                if (scope == ResearchResourceScope.SHARED) stored.copy(resources = remaining,
                    excludedResourceUrls = stored.excludedResourceUrls + urls)
                else stored.copy(questionResources = remaining,
                    excludedQuestionResourceUrls = stored.excludedQuestionResourceUrls + urls)
            }
        }

    private suspend fun rememberQuestionSources(questionId: String, sources: List<SearchHit>, shared: Boolean = false) =
        researchEdits.withLock {
            if (sources.isEmpty() || questionId in deletingSessions) return@withLock
            val question = researchQuestion(questionId)
            val root = checkNotNull(chats.session(question.researchChatId))
            val excluded = root.excludedResourceUrls + question.excludedQuestionResourceUrls
            val additions = sources.mapNotNull { hit -> researchUrl(hit.url)?.takeUnless { it in excluded }?.let { url ->
                ResearchResource(Id.new(), hit.title.ifBlank { url }, url, discovered = true, snippet = hit.snippet)
            } }.filterNot { resource -> root.resources.any { it.key == resource.key } }
            // Discovery never re-enables a source the user disabled during this run.
            updateChat(if (shared) root.id else questionId) { stored ->
                if (shared) stored.copy(resources = (stored.resources + additions).distinctBy { it.key })
                else stored.copy(questionResources = (stored.questionResources + additions).distinctBy { it.key })
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
        _state.update { state -> state.copy(sessions = rest, drafts = state.drafts - id, sourceReadProblems = state.sourceReadProblems - id,
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
            nativeSessionId = "", pendingRun = null, pendingActivity = emptyList(), queuedPrompts = emptyList(), acquireComputerAccess = false,
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
                if (visible.isNotEmpty()) attachQuestionFiles(session.id, visible)
                queuedComputerRequests.update { it + (request.messageId to session.id) }
                draft.clearIfUnchanged(version)
                startNextChat(session.id)
            }
        } else startChat(text, attachments)
    }

    override fun sendFollowUp(sessionId: String, messageId: String, question: String) {
        val session = _state.value.current?.takeIf { it.id == sessionId } ?: return
        if (session.id in chatJobs.value || session.pendingRun != null || session.queuedPrompts.isNotEmpty() ||
            session.id in controllingSessions || session.id in changingHistory) return
        val answer = session.messages.lastOrNull()?.takeIf { it.id == messageId && it.role == ChatRole.AGENT } ?: return
        if (question !in answer.researchReply().followUps) return
        AppLog.info("chat", "follow-up.selected", mapOf("sessionId" to sessionId, "messageId" to messageId))
        startChat(question, emptyList(), targetId = sessionId, clearDraft = false)
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
        val recorder = CodingRunRecorder()
        val previousActivity = if (resumed != null) session.pendingActivity else emptyList()
        val sharedDiscovery = _state.value.sessions.filter { it.researchChatId == session.researchChatId }
            .all { question -> question.messages.none { it.role == ChatRole.USER && it.id != request.messageId } }
        var lastDraftUpdate = 0L
        fun publishDraft(force: Boolean = true) {
            val now = Id.now()
            if (!force && now - lastDraftUpdate < 80) return
            lastDraftUpdate = now
            val draft = recorder.draft(active = true)
            _state.update { it.copy(drafts = it.drafts + (session.id to draft.copy(steps = previousActivity + draft.steps))) }
        }
        val operationFields = mapOf("sessionId" to session.id, "requestId" to userMessage.id)
        publishDraft()
        AppLog.info("chat", "send.started", operationFields)
        val job = scope.launch(workerDispatcher + io.aequicor.magicpaper.domain.UsageOwner(io.aequicor.magicpaper.domain.UsageScope.chat(session.id)), start = CoroutineStart.LAZY) {
            try {
                creation?.await()
                val accepted = updateChat(session.id) { latest -> latest.copy(
                    engine = updated.engine, layoutProjectId = updated.layoutProjectId,
                    title = updated.title, updatedAt = updated.updatedAt, pendingRun = request, archived = false,
                    pendingActivity = previousActivity,
                    queuedPrompts = latest.queuedPrompts.filterNot { it.messageId == request.messageId },
                    messages = if (latest.messages.any { it.id == userMessage.id }) latest.messages else latest.messages + userMessage) }
                if (resumed == null && visible.isNotEmpty()) {
                    attachQuestionFiles(session.id, visible)
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
                    val researchRequest = researchRequest(trimmed)
                    val storedQuestion = checkNotNull(chats.session(session.id))
                    val storedRoot = checkNotNull(chats.session(session.researchChatId))
                    val autoSearch = researchRequest.autoSearch(
                        hasHistory = historyBefore.isNotEmpty(),
                        hasSources = storedQuestion.availableResearchResources(storedRoot).isNotEmpty(),
                        hasAttachments = visible.isNotEmpty())
                    AppLog.debug("chat", "research.search.decision", operationFields + mapOf(
                        "automatic" to autoSearch.toString(), "sourceTask" to researchRequest.sourceTask.toString()))
                    if (resumed == null && autoSearch && researchSearch?.isConfigured(settings) == true) {
                        val searchId = "research-search:${request.messageId}"
                        recorder.apply(CodingEvent.ToolStarted("web.search", "", searchId,
                            title = if (sharedDiscovery) "Ищу общие источники" else "Ищу источники для вопроса"))
                        publishDraft()
                        val result = discoverResearchSources(session.id, trimmed, settings, operationFields, sharedDiscovery)
                        recorder.apply(CodingEvent.ToolFinished("web.search", result.isFailure, searchId,
                            title = result.fold({ "Найдено источников: ${it.size}" }, { "Поиск недоступен" })))
                        publishDraft()
                    }
                    if (resumed == null && researchRequest.urls.isNotEmpty()) researchEdits.withLock {
                        val direct = researchRequest.urls.map { url ->
                            (storedRoot.resources + storedQuestion.questionResources).firstOrNull { it.url == url }
                                ?: ResearchResource(Id.new(), url, url)
                        }
                        addResearchResources(session.id, ResearchResourceScope.QUESTION, direct)
                    }
                    val question = checkNotNull(chats.session(session.id))
                    val root = checkNotNull(chats.session(session.researchChatId))
                    val candidates = researchRequest.sources(question.availableResearchResources(root), visible, includeNewLinks = false)
                    val readId = "research-read:${request.messageId}"
                    val checksWebSources = candidates.any { it.url.isNotBlank() }
                    if (checksWebSources) {
                        recorder.apply(CodingEvent.ToolStarted("web.read", "", readId,
                            title = if (researchRequest.sourceTask) "Читаю указанные источники" else "Проверяю доступность источников"))
                        publishDraft()
                    }
                    val sourceChecks = sourceAccess.check(candidates).associateBy { it.resource.key }.toMutableMap()
                    fun publishSourceProblems() {
                        val problems = sourceChecks.values.filter { it.problem != null }.associate { it.resource.key to it.problem!! }
                        _state.update { it.copy(sourceReadProblems = it.sourceReadProblems + (session.id to problems)) }
                    }
                    publishSourceProblems()
                    val availableResources = sourceChecks.values.toList().readableSources()
                    val unreadableCount = sourceChecks.values.count { it.problem != null }
                    if (checksWebSources) {
                        recorder.apply(CodingEvent.ToolFinished("web.read", false, readId,
                            title = "Прочитано источников: ${availableResources.count { it.url.isNotBlank() }}" +
                                if (unreadableCount > 0) "; недоступно: $unreadableCount" else ""))
                        publishDraft()
                    }
                    val initialSources = availableResources.mapNotNull { resource -> resource.url.takeIf { it.isNotEmpty() }?.let {
                        SearchHit(resource.title, it, resource.snippet)
                    } }
                    val researchAttachments = (visible + availableResources.mapNotNull { it.attachment }).distinctBy { it.id }
                    var finished = false
                    var sources = initialSources
                    var outputAttachments = emptyList<Attachment>()
                    val recovering = resumed != null && session.pendingRun?.messageId == resumed.messageId
                    val userPrompt = if (recovering) "Продолжи незавершённую работу в этой сессии. Сначала проверь сохранённый контекст, " +
                        "результаты команд и состояние файлов; не повторяй завершённые действия.\n\n" + trimmed else trimmed
                    val excluded = sourceChecks.values.toList().unavailableSourceContext(allowSearch = !researchRequest.sourceTask)
                    val prompt = if (excluded.isEmpty()) userPrompt else "$excluded\n\nВопрос пользователя:\n$userPrompt"
                    runtime.runChat(accepted.copy(acquireComputerAccess = acquireComputerAccess, resources = availableResources), prompt, requestProfile, researchAttachments).collect { event ->
                        if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                            updateChat(session.id) { it.copy(nativeSessionId = event.sessionId) }
                        }
                        recorder.apply(event)
                        publishDraft(event !is CodingEvent.TextDelta && event !is CodingEvent.ThinkingDelta && event !is CodingEvent.ToolProgress)
                        if (event is CodingEvent.ToolStarted || event is CodingEvent.ToolFinished) {
                            updateChat(session.id) { it.copy(pendingActivity = previousActivity + recorder.draft(true).steps.researchActivity()) }
                        }
                        val candidatesFound = event.researchSources().filter { hit ->
                            !researchRequest.sourceTask || researchUrl(hit.url)?.let { "url:$it" in sourceChecks } == true
                        }
                        val unchecked = candidatesFound.mapNotNull { hit -> researchUrl(hit.url)?.let { url ->
                            ResearchResource(Id.new(), hit.title, url)
                        } }.filterNot { it.key in sourceChecks }
                        sourceAccess.check(unchecked).forEach { sourceChecks[it.resource.key] = it }
                        if (unchecked.isNotEmpty()) publishSourceProblems()
                        val found = candidatesFound.filter { hit ->
                            val checked = researchUrl(hit.url)?.let { sourceChecks["url:$it"] }
                            checked != null && checked.problem == null
                        }.map { it.copy(snippet = "") }
                        if (found.isNotEmpty()) {
                            sources = (sources + found).distinctBy { it.url }
                            rememberQuestionSources(session.id, found, sharedDiscovery && !researchRequest.sourceTask)
                        }
                        if (event is CodingEvent.FinalText) { outputAttachments = event.attachments }
                        if (event is CodingEvent.Finished) finished = true
                    }
                    val response = recorder.message(Id.new(), Id.now())
                    check(finished && !response.failed) { "Движок не завершил ответ" }
                    val unavailable = sourceChecks.values.filter { it.problem != null }
                    val citesUnavailable = researchReferences(response.text).any { hit ->
                        val checked = sourceChecks["url:${hit.url}"]
                        checked?.problem != null || (researchRequest.sourceTask && checked == null)
                    }
                    val verifiedAnswer = if (citesUnavailable) {
                        AppLog.error("chat", "answer.unreadable-citation", operationFields)
                        "Ответ содержит ссылки на непрочитанные страницы, поэтому его не удалось подтвердить. " +
                            "Повторите запрос по доступным источникам или добавьте текст нужного материала файлом."
                    } else response.text
                    val readNotice = if (unavailable.isEmpty()) "" else unavailable.joinToString("\n", prefix =
                        "\n\nНедоступные источники исключены из ссылок ответа. Можно повторить запрос для новой проверки:\n") {
                        "- ${it.resource.title}: ${it.problem}."
                    }
                    val reply = researchReply(verifiedAnswer)
                    SessionAnswer(reply.text + readNotice, sources, outputAttachments, previousActivity + response.steps.researchActivity(), reply.followUps)
                }
                val agentMessage = ChatMessage(
                    id = request.responseId.ifBlank { Id.new() },
                    role = ChatRole.AGENT,
                    text = answer.text,
                    createdAt = Id.now(),
                    sources = answer.sources,
                    attachments = answer.attachments,
                    researchActivity = answer.activity,
                    followUps = answer.followUps,
                )
                updateChat(session.id) { latest -> latest.copy(messages = latest.messages.filterNot { it.id == agentMessage.id } + agentMessage, updatedAt = Id.now(), pendingRun = null, pendingActivity = emptyList()) }
                _state.update { it.copy(drafts = it.drafts - session.id) }
                AppLog.info("chat", "send.completed", operationFields)
            } catch (e: CancellationException) {
                runtime.abort(session.id)
                AppLog.info("chat", "send.cancelled", operationFields)
                throw e
            }
            catch (e: Exception) {
                try { updateChat(session.id) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP)) } }
                catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { AppLog.error("chat", "checkpoint.failed", failure, operationFields) }
                recorder.apply(CodingEvent.Failed("Не удалось завершить исследование"))
                AppLog.error("chat", "send.failed", e, operationFields); _state.update { it.copy(notice = "Не удалось завершить отправку. Проверьте подключение и повторите попытку.") } }
            finally {
                try {
                    withContext(NonCancellable) {
                        if (state.value.sessions.firstOrNull { it.id == session.id }?.pendingRun != null && session.id !in deletingSessions) {
                            val stopped = recorder.message(request.responseId, Id.now())
                            val activity = previousActivity + stopped.steps.researchActivity()
                            _state.update { it.copy(drafts = it.drafts + (session.id to CodingDraft(
                                steps = previousActivity + stopped.steps, failedMessage = if (stopped.failed) "Не удалось завершить исследование" else null))) }
                            try { updateChat(session.id) { it.copy(pendingActivity = activity) } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                AppLog.error("chat", "activity.save.failed", failure, operationFields)
                                _state.update { it.copy(notice = "Не удалось сохранить ход исследования. Можно продолжить вопрос.") }
                            }
                        }
                    }
                } finally {
                    chatJobs.update { it - session.id }
                    _state.update { it.copy(busy = it.current?.id in chatJobs.value) }
                    withContext(NonCancellable + Dispatchers.Main.immediate) { startNextChat(session.id) }
                }
            }
        }
        chatJobs.update { it + (session.id to job) }
        job.start()
    }

    private suspend fun discoverResearchSources(questionId: String, query: String, settings: AppSettings,
        fields: Map<String, String>, shared: Boolean): Result<List<SearchHit>> {
        val search = checkNotNull(researchSearch)
        try {
            val result = withTimeout(15_000) { search.searchWithDiagnostics(query, settings, limit = 5) }
            if (result.hits.isEmpty() && result.issues.isNotEmpty()) {
                AppLog.info("chat", "research.search.unavailable", fields + mapOf("issueCount" to result.issues.size.toString()))
            } else {
                rememberQuestionSources(questionId, result.hits, shared)
                return Result.success(result.hits)
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.info("chat", "research.search.timeout", fields)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "research.search.failed", failure, fields)
        }
        val message = "Поиск недоступен. Исследование продолжится по доступным источникам."
        _state.update { it.copy(notice = message) }
        return Result.failure(IllegalStateException(message))
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
        archiveObserver = null
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
