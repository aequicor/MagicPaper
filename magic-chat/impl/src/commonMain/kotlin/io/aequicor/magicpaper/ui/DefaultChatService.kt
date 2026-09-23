package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.data.storage.logPersistenceFailure

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch


/** Owns chat execution across component destruction and navigation. */
class DefaultChatService(
    private val runtime: ChatBackend,
    private val chats: ChatJournalStore,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    override val requestPins: RequestPinService?,
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onOpenSession: (String) -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val draftBlobs: io.aequicor.magicpaper.data.storage.DraftBlobStore = io.aequicor.magicpaper.data.storage.InMemoryDraftBlobStore(),
    responseExtensions: List<ChatResponseExtension> = emptyList(),
    private val researchSearch: SearchEngine? = null,
    private val archiveClock: () -> Long = Id::now,
    private val archiveTicks: Flow<Unit> = sessionArchiveTicks(),
    val usage: UsageLedger? = null,
    private val sourceAccess: ResearchSourceAccess = ResearchSourceAccess(),
    sourceBrowser: ResearchPageBrowser? = null,
    override val mediaGeneration: MediaGenerationService? = null,
) : ChatService, ChatHistoryCommands {
    private val responseExtensions = responseExtensions.toList()
    private val _state = MutableStateFlow(ChatState(sourceBrowserSupported = sourceBrowser != null))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("chat", "background.failed", mapOf("causeType" to error::class.simpleName.orEmpty()))
        _state.update { it.copy(notice = "Не удалось выполнить действие с чатом. Повторите попытку.") }
    })
    private val composerSessions = mutableMapOf<String, io.aequicor.magicpaper.data.storage.DraftSession<ComposerDraftData>>()
    private val deletedDraftSessionIds = mutableSetOf<String>()
    private val questions = ChatQuestionnaires(runtime, draftRepository, scope) { notice ->
        _state.update { it.copy(notice = notice) }
    }
    override val questionnaires get() = questions.requests
    override val questionnaireDrafts get() = questions.drafts
    override fun updateQuestionnaireDraft(id: String, draft: QuestionnaireDraft) = questions.updateDraft(id, draft)
    override fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>) = questions.submit(id, answers)
    private val browserSources = ResearchBrowserSources(Id::now)
    private val browserRecovery = sourceBrowser?.let { browser -> ResearchBrowserRecovery(browser, scope,
        onState = { value -> _state.update { it.copy(sourceBrowser = value) } },
        onRead = { target, content ->
            val question = _state.value.sessions.firstOrNull { it.id == target.questionId }
            val notebook = _state.value.sessions.firstOrNull { it.id == target.notebookId }
            val exists = question != null && notebook != null && question.researchChatId == notebook.id &&
                (notebook.resources + question.questionResources).any { it.id == target.resource.id && it.key == target.resource.key }
            if (exists) {
                browserSources.put(target, content.text)
                _state.update { state -> state.copy(sourceReadProblems = state.sourceReadProblems.mapValues { (id, problems) ->
                    if (state.sessions.any { it.id == id && it.researchChatId == target.notebookId }) problems - target.resource.key else problems
                }, notice = "Страница прочитана. Она будет использована в следующем ответе по выбранным источникам.") }
            }
            exists
        }, onNotice = { notice -> _state.update { it.copy(notice = notice) } }) }

    override fun openSourceBrowser(questionId: String, resourceKey: String) {
        val question = _state.value.sessions.firstOrNull { it.id == questionId } ?: return
        val notebook = _state.value.sessions.firstOrNull { it.id == question.researchChatId } ?: return
        val resource = (notebook.resources + question.questionResources).firstOrNull { it.key == resourceKey && it.url.isNotBlank() } ?: return
        browserRecovery?.open(ResearchBrowserTarget(notebook.id, questionId, resource))
    }
    override fun readSourceBrowser() { browserRecovery?.read() }
    override fun dismissSourceBrowser() { browserRecovery?.dismiss() }
    fun composerDraft(sessionId: String?): io.aequicor.magicpaper.data.storage.DraftSession<ComposerDraftData> {
        val key = "chat:" + (sessionId ?: "new")
        return composerSessions.getOrPut(key) { composerDraftSession(draftRepository, draftBlobs, key, scope) }
            .also { if (sessionId in deletedDraftSessionIds) it.revoke() }
    }
    private var acceptingCommands = true
    private var journalObserver: Job? = null
    private fun publishJournal() {
        val sessions = chats.states.value.values.flatMap { it.sessions.values }.sortedByDescending { it.updatedAt }
        _state.update { previous ->
            val current = previous.current?.let { selected -> sessions.firstOrNull { it.id == selected.id } }
            previous.copy(sessions = sessions, current = current, busy = current?.let { chats.stateFor(it.id)?.busy(it.id) } == true)
        }
    }
    private suspend fun dispatchChat(id: String, input: ChatMachine.Input, deliverEffects: Boolean = true): ChatMachine.Transition {
        check(input !is ChatMachine.Intent || acceptingCommands) { "Чат закрывается или очищается" }
        val previous = chats.stateFor(id)?.sessions.orEmpty()
        val notebookId = chats.stateFor(id)?.notebookId ?: id
        val transition = chats.dispatch(notebookId, input)
        publishJournal()
        if (deliverEffects) {
            currentCoroutineContext().ensureActive()
            deliverEffects(transition.effects, previous)
        }
        return transition
    }
    private suspend fun deliverEffects(effects: List<ChatMachine.Effect>, previous: Map<String, ChatSession> = emptyMap()) {
        for (effect in effects) when (effect) {
            is ChatMachine.Effect.RunRequest -> if (acceptingCommands) runChatRequest(effect.ref)
            is ChatMachine.Effect.AbortRequest -> {
                runtime.abort(effect.ref.sessionId)
                chatJobs.value[effect.ref.sessionId]?.cancel()
            }
            is ChatMachine.Effect.InspectSavedOutput -> if (acceptingCommands) recoverSavedResponse(effect.ref)
            is ChatMachine.Effect.CleanupDeleted -> effect.sessionIds.forEach { deleteSessionResources(it, previous[it]) }
            is ChatMachine.Effect.RequestAccepted, is ChatMachine.Effect.RequestCompleted -> Unit
            is ChatMachine.Effect.Reject -> error(effect.reason)
        }
    }
    private suspend fun acceptRequest(id: String, input: ChatMachine.Input,
        draft: io.aequicor.magicpaper.data.storage.DraftSession<ComposerDraftData>? = null, version: Long? = null) {
        dispatchChat(id, input)
        if (draft != null && version != null) try { draft.clearIfUnchanged(version) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logPersistenceFailure("chat", "draft.clear.failed", failure, mapOf("sessionId" to id))
            _state.update { it.copy(notice = "Сообщение сохранено. Не удалось очистить черновик.") }
        }
    }
    private fun runRef(id: String): ChatMachine.RunRef? = chats.stateFor(id)?.runs?.get(id)?.ref
    override val state: StateFlow<ChatState> = _state.asStateFlow()
    override fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean) {
        scope.launch { dispatchChat(sessionId, ChatMachine.Intent.SetMediaTool(kind, enabled)) }
    }
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) {
        _state.update { it.copy(settings = settings, llmProfiles = profiles, subscriptionAvailable = subscriptionAvailable, subscriptionSignedIn = subscriptionSignedIn,
            researchSearchLabel = if (researchSearch != null) settings.descriptionSearchLabel() else "") }
    }
    override suspend fun start() {
        questions.start()
        observePinFailures()
        val settings = settingsRepo.load()
        val profiles = profileRepo.load()
        chats.start()
        val stored = chats.sessions()
        for (original in stored) {
            if (original.researchResourcesInitialized && original.modelSelection != null) continue
            val history = stored.filter { it.researchChatId == original.id }.flatMap { it.messages }
            val files = history.flatMap { it.attachments }.map { ResearchResource(it.id, it.name, attachment = it) }
            val sites = history.flatMap { it.sources }.mapNotNull { hit -> researchUrl(hit.url)?.let { url ->
                if (url in original.excludedResourceUrls) null else ResearchResource(Id.new(), hit.title, url, discovered = true, snippet = hit.snippet)
            } }
            val profile = ProfileResolver.resolve(original, settings, profiles)
            dispatchChat(original.id, ChatMachine.Fact.LegacyHydrated(original.id,
                (original.resources + files).distinctBy { it.key }, (original.questionResources + sites).distinctBy { it.key },
                profile?.let { ModelSelection(it.id, it.selectionKey, it.effortSelectionFor()) }))
        }
        _state.update { it.copy(settings = settings, llmProfiles = profiles) }
        publishJournal()
        if (journalObserver == null) journalObserver = scope.launch {
            combine(chats.states, chats.failures) { _, failures -> failures }.collect { failures ->
                publishJournal()
                val id = state.value.current?.researchChatId
                failures[id]?.let { message -> _state.update { it.copy(notice = message) } }
            }
        }
        if (chats.states.value.values.any { it.runs.isNotEmpty() })
            _state.update { it.copy(notice = "Есть незавершённые запросы. Можно проверить сохранённый ответ или оставить запрос без продолжения.") }
        acceptingCommands = true
        observeAutoArchive()
    }

    private var archiveObserver: Job? = null
    private var pinFailureObserver: Job? = null
    private fun observePinFailures() {
        val pins = requestPins ?: return
        if (pinFailureObserver != null) return
        pinFailureObserver = scope.launch {
            combine(pins.failures, state.map { it.current?.id }.distinctUntilChanged()) { failures, id ->
                id to (id?.let { failures[PinConversation(it)] } ?: failures[PinConversation("")])
            }.distinctUntilChanged().collect { (_, failure) ->
                if (failure != null) _state.update { it.copy(notice = failure) }
            }
        }
    }
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
        try { dispatchChat(id, ChatMachine.Intent.Archive(id, archived, archiveClock(), automatic)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "session.archive.failed", mapOf("sessionId" to id) + ("causeType" to failure::class.simpleName.orEmpty()))
            if (!automatic) _state.update { it.copy(notice = "Не удалось изменить архив чата. Дождитесь завершения запроса и повторите попытку.") }
        }
    }
    private var selectionGeneration = 0L
    override fun activate(id: String?) {
        selectionGeneration++
        _state.update { state ->
            val root = state.sessions.firstOrNull { it.id == id }
            val selected = state.sessions.firstOrNull { it.id == root?.selectedQuestionId && it.researchChatId == root.id } ?: root
            state.copy(current = selected, busy = selected?.let { chats.stateFor(it.id)?.busy(it.id) } == true)
        }
    }
    // Research has no pinned-message surface. Keep the lifecycle contract without
    // launching hidden summary requests or rescanning all histories on streamed updates.
    override fun setVisible(visible: Boolean) = Unit
    override fun dismissNotice() { _state.update { it.copy(notice = null) } }
    private val pendingCreations = mutableMapOf<String, CompletableDeferred<Unit>>()
    override fun newSession() {
        val generation = ++selectionGeneration
        val input = ChatMachine.Intent.CreateNotebook(Id.new(), Id.now(),
            ProfileResolver.favoriteDefault(state.value.settings, state.value.availableLlmProfiles))
        val preview = ChatMachine.reduce(ChatMachine.initial(), input).state.notebook!!
        _state.update { it.copy(current = preview, sessions = listOf(preview) + it.sessions, busy = false) }
        // A handle to the in-flight command, not an alternate persisted state or execution guard.
        val saved = CompletableDeferred<Unit>()
        pendingCreations[input.id] = saved
        scope.launch {
            try {
                dispatchChat(input.id, input)
                saved.complete(Unit)
                if (selectionGeneration == generation) activate(input.id)
                onOpenSession(input.id)
            } catch (cancelled: CancellationException) { saved.cancel(cancelled); throw cancelled }
            catch (failure: Exception) {
                saved.completeExceptionally(failure)
                AppLog.error("chat", "session.create.failed", mapOf("sessionId" to input.id) + ("causeType" to failure::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Не удалось сохранить чат. Введённый текст остаётся в черновике.") }
            } finally { pendingCreations.remove(input.id) }
        }
    }

    override fun selectSession(id: String) {
        activate(id)
        onOpenSession(id)
    }

    /** Durable edits belong to the application service, even when their screen disappears. */
    private suspend fun researchChange(operation: String, questionId: String? = state.value.current?.id, block: suspend () -> Unit): Result<Unit> = scope.async {
        val fields = mapOf("operationId" to Id.new(), "sessionId" to questionId.orEmpty())
        try {
            block()
            AppLog.info("chat", "research.$operation.completed", fields)
            Result.success(Unit)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            AppLog.error("chat", "research.$operation.failed", fields + ("causeType" to error::class.simpleName.orEmpty()))
            val message = "Не удалось сохранить изменение. Повторите попытку."
            _state.update { it.copy(notice = message) }
            Result.failure(IllegalStateException(message))
        }
    }.await()

    override suspend fun newQuestion(): Result<Unit> {
        val root = state.value.notebook ?: return Result.failure(IllegalStateException("Сначала создайте чат."))
        val source = state.value.current?.id ?: root.id
        val generation = ++selectionGeneration
        return researchChange("question.create") {
            pendingCreations[root.id]?.await()
            val id = Id.new()
            dispatchChat(root.id, ChatMachine.Intent.CreateQuestion(id, source, Id.now()))
            if (selectionGeneration == generation) activate(id)
        }
    }

    override suspend fun selectQuestion(id: String): Result<Unit> {
        val generation = ++selectionGeneration
        return researchChange("question.select", id) {
            dispatchChat(id, ChatMachine.Intent.SelectQuestion(id))
            if (selectionGeneration == generation) activate(id)
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
            AppLog.error("chat", "research.source.search.failed", mapOf("causeType" to failure::class.simpleName.orEmpty()))
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

    private suspend fun addResearchResources(questionId: String, scope: ResearchResourceScope, additions: List<ResearchResource>) {
        dispatchChat(questionId, ChatMachine.Intent.AddResources(questionId, scope == ResearchResourceScope.SHARED, additions))
    }

    override suspend fun setResourceEnabled(questionId: String, resourceKey: String, enabled: Boolean): Result<Unit> =
        setResourcesEnabled(questionId, setOf(resourceKey), enabled)

    override suspend fun setResourcesEnabled(questionId: String, resourceKeys: Set<String>, enabled: Boolean): Result<Unit> =
        researchChange("source.selection", questionId) {
            dispatchChat(questionId, ChatMachine.Intent.SelectResources(questionId, resourceKeys, enabled))
        }

    override suspend fun shareResource(questionId: String, resourceId: String): Result<Unit> = researchChange("source.share", questionId) {
        dispatchChat(questionId, ChatMachine.Intent.ShareResource(questionId, resourceId))
    }

    override suspend fun removeResource(questionId: String, resourceId: String, scope: ResearchResourceScope): Result<Unit> =
        researchChange("source.remove", questionId) {
            dispatchChat(questionId, ChatMachine.Intent.RemoveResource(questionId, scope == ResearchResourceScope.SHARED, resourceId))
        }

    private suspend fun rememberQuestionSources(ref: ChatMachine.RunRef, sources: List<SearchHit>, shared: Boolean = false) {
        if (sources.isEmpty()) return
        val additions = sources.mapNotNull { hit -> researchUrl(hit.url)?.let { url ->
            ResearchResource(Id.new(), hit.title.ifBlank { url }, url, discovered = true, snippet = hit.snippet)
        } }
        dispatchChat(ref.sessionId, ChatMachine.Fact.SourcesDiscovered(ref, additions, shared))
    }

    override fun deleteSession(id: String) {
        scope.launch {
            pendingCreations[id]?.await()
            dispatchChat(id, ChatMachine.Intent.Delete(id))
        }
    }

    private suspend fun deleteSessionResources(id: String, previous: ChatSession?) {
        chatJobs.value[id]?.cancelAndJoin()
        mediaGeneration?.deleteSession(id)
        previous?.let { runtime.deleteChatSession(it) }
        deletedDraftSessionIds += id
        requestPins?.remove(PinConversation(id))
        composerSessions.remove("chat:$id")?.revoke()
        questions.removeSession(id)
        draftRepository.remove("chat:$id")
        _state.update { current -> current.copy(drafts = current.drafts - id, sourceReadProblems = current.sourceReadProblems - id) }
    }

    private val chatJobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    private suspend fun <T> changeHistory(sessionId: String, operation: String, block: suspend () -> T): Result<T> = scope.async {
        try {
            pendingCreations[sessionId]?.await()
            val result = block()
            AppLog.info("chat", "history.$operation", mapOf("sessionId" to sessionId))
            Result.success(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "history.$operation.failed", mapOf("sessionId" to sessionId) + ("causeType" to failure::class.simpleName.orEmpty()))
            val message = "Не удалось изменить историю. Завершите текущий запрос и повторите попытку."
            _state.update { it.copy(notice = message) }
            Result.failure(IllegalStateException(message))
        }
    }.await()

    override suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit> = changeHistory(sessionId, "edit") {
        val message = chats.session(sessionId)?.messages?.firstOrNull { it.id == messageId } ?: error("Сообщение не найдено")
        val request = CodingRunCheckpoint(messageId, text.trim(), message.attachments, responseId = Id.new(), runId = Id.new(), responseTimelineId = Id.new())
        dispatchChat(sessionId, ChatMachine.Intent.EditMessage(sessionId, messageId, text, request, Id.now()))
        Unit
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit> = changeHistory(sessionId, "delete") {
        dispatchChat(sessionId, ChatMachine.Intent.DeleteMessage(sessionId, messageId, Id.now()))
        Unit
    }

    override suspend fun forkSession(sessionId: String, throughMessageId: String?): Result<String> = changeHistory(sessionId, "fork") {
        val source = checkNotNull(chats.session(sessionId)).withGeneratedMedia(mediaGeneration?.operations?.value.orEmpty())
        val root = checkNotNull(chats.session(source.researchChatId))
        val id = Id.new()
        dispatchChat(id, ChatMachine.Intent.ForkNotebook(source, root, id, Id.now(), source.messages.associate { it.id to Id.new() }, throughMessageId))
        onOpenSession(id)
        id
    }

    // Request-scoped captured ports contain no execution authority; only the machine's RunRequest may invoke them.
    private val preparedResponses = mutableMapOf<String, PreparedChatResponse>()
    private fun request(text: String, attachments: List<Attachment>) = CodingRunCheckpoint(
        Id.new(), text.trim(), attachments.chatVisible(), responseId = Id.new(), runId = Id.new(), responseTimelineId = Id.new())

    override fun send(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.chatVisible().isEmpty()) return
        val draft = composerDraft(state.value.current?.id)
        val version = draft.state.value.version
        if (state.value.current == null) newSession()
        val session = state.value.current ?: return
        val request = request(text, attachments)
        val prepared = responseExtensions.firstNotNullOfOrNull { it.prepare(session, request.prompt, request.messageId) }
        if (prepared != null) preparedResponses[request.runId] = prepared
        val input = ChatMachine.Intent.Submit(session.id, request, Id.now(), prepared?.bindingId)
        scope.launch {
            pendingCreations[session.id]?.await()
            acceptRequest(session.id, input, draft, version)
        }
    }

    override fun sendFollowUp(sessionId: String, messageId: String, question: String) {
        val session = state.value.current?.takeIf { it.id == sessionId } ?: return
        if (session.pendingRun != null || session.queuedPrompts.isNotEmpty()) return
        val answer = session.messages.lastOrNull()?.takeIf { it.id == messageId && it.role == ChatRole.AGENT } ?: return
        if (question !in answer.researchReply().followUps) return
        scope.launch { acceptRequest(sessionId, ChatMachine.Intent.FollowUp(sessionId, messageId, request(question, emptyList()), Id.now())) }
    }

    override fun pause() {
        val ref = state.value.current?.id?.let(::runRef) ?: return
        scope.launch { dispatchChat(ref.sessionId, ChatMachine.Intent.Pause(ref)) }
    }

    override fun discardPendingRequest() {
        val ref = state.value.current?.id?.let(::runRef) ?: return
        scope.launch {
            dispatchChat(ref.sessionId, ChatMachine.Intent.Discard(ref, Id.now()))
            _state.update { it.copy(drafts = it.drafts - ref.sessionId, notice = null) }
        }
    }

    override fun resume(text: String, attachments: List<Attachment>) {
        if (text.isNotBlank() || attachments.chatVisible().isNotEmpty()) { clarify(text, attachments); return }
        val ref = state.value.current?.id?.let(::runRef) ?: return
        scope.launch { dispatchChat(ref.sessionId, ChatMachine.Intent.Recover(ref)) }
    }

    override fun clarify(text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.chatVisible().isEmpty()) return
        val session = state.value.current ?: return
        val ref = runRef(session.id) ?: return
        val previous = session.pendingRun ?: return
        val revised = request(previous.prompt + if (text.isBlank()) "" else "\n\nУточнение пользователя: " + text.trim(),
            previous.attachments + attachments).copy(messageId = previous.messageId)
        val draft = composerDraft(session.id)
        val version = draft.state.value.version
        scope.launch { acceptRequest(session.id, ChatMachine.Intent.Clarify(ref, revised, Id.now()), draft, version) }
    }

    private fun recoverSavedResponse(ref: ChatMachine.RunRef) {
        val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            try {
                when (val recovered = runtime.inspectSavedResponse(ref)) {
                    is ChatSavedResponse.Completed -> {
                        check(recovered.request == ref) { "Ответ принадлежит другому запросу" }
                        val session = checkNotNull(chats.session(ref.sessionId))
                        val context = chats.stateFor(ref.sessionId)?.runs?.get(ref.sessionId)?.responseContext
                        val answer = assembleChatResponse(ref.responseId, recovered.text, context, session.pendingActivity,
                            session.pendingContent.filterIsInstance<TranscriptBlock.Media>() +
                                TranscriptBlock.Markdown("${ref.responseId}:recovered", recovered.text))
                        val message = ChatMessage(ref.responseId, ChatRole.AGENT, answer.text, Id.now(),
                            sources = answer.sources, attachments = answer.attachments, researchActivity = answer.activity,
                            followUps = answer.followUps, content = answer.content)
                        dispatchChat(ref.sessionId, ChatMachine.Fact.RecoveredReply(ref, recovered.proof, message))
                        _state.update { it.copy(drafts = it.drafts - ref.sessionId, notice = null) }
                    }
                    else -> {
                        dispatchChat(ref.sessionId, ChatMachine.Fact.RecoveryUnavailable(ref,
                            unknown = recovered == ChatSavedResponse.Unknown || recovered == ChatSavedResponse.Missing, missing = recovered == ChatSavedResponse.Missing))
                        _state.update { it.copy(notice = if (recovered == ChatSavedResponse.Unknown)
                            "Результат запроса пока неизвестен. Можно оставить его без продолжения и отправить новое сообщение."
                            else "Сохранённый ответ не найден. Можно оставить запрос без продолжения и отправить новое сообщение.") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("chat", "response.inspect.failed", mapOf("sessionId" to ref.sessionId, "requestId" to ref.runId) + ("causeType" to failure::class.simpleName.orEmpty()))
                withContext(NonCancellable) {
                    if (runRef(ref.sessionId) == ref) try {
                        dispatchChat(ref.sessionId, ChatMachine.Fact.RecoveryUnavailable(ref, unknown = true))
                    } catch (persistence: Exception) { AppLog.error("chat", "response.inspect.checkpoint.failed", mapOf("causeType" to persistence::class.simpleName.orEmpty())) }
                }
                _state.update { it.copy(notice = "Не удалось проверить сохранённый ответ. Повторите проверку позже.") }
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                chatJobs.update { jobs -> if (jobs[ref.sessionId] == ownJob) jobs - ref.sessionId else jobs }
                publishJournal()
            }
        }
        chatJobs.update { it + (ref.sessionId to job) }
        job.start()
    }

    /** Executes only the effect of an accepted durable transition. No request identities are invented here. */
    private fun runChatRequest(ref: ChatMachine.RunRef) {
        val session = chats.stateFor(ref.sessionId)?.sessions?.get(ref.sessionId) ?: return
        if (runRef(session.id) != ref) return
        val request = checkNotNull(session.pendingRun)
        val trimmed = request.prompt
        val visible = request.attachments
        val settings = state.value.settings
        val extensionResponse = preparedResponses.remove(request.runId)?.takeIf { it.bindingId == session.layoutProjectId }
            ?: responseExtensions.firstNotNullOfOrNull { it.prepare(session, trimmed, request.messageId) }
        val historyBefore = session.messages.filterNot { it.id == request.messageId }
        val requestProfile = ProfileResolver.resolve(session, settings, state.value.availableLlmProfiles)
        val previousActivity = session.pendingActivity
        val previousContent = session.pendingContent.interruptedMedia()
        val previousIds = previousActivity.map { it.id } + previousContent.map { it.id }
        val nextSequence = previousIds.mapNotNull { id -> id.removePrefix("${request.responseTimelineId}:").toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 0
        val recorder = CodingRunRecorder(CodingImageInvocation(session.id, request.runId,
            request.messageId, request.responseId, request.responseTimelineId), initialSequence = nextSequence)
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
        val operationFields = mapOf("sessionId" to session.id, "requestId" to request.messageId)
        publishDraft()
        AppLog.info("chat", "send.started", operationFields)
        val job = scope.launch(workerDispatcher + io.aequicor.magicpaper.domain.UsageOwner(io.aequicor.magicpaper.domain.UsageScope.chat(session.id)), start = CoroutineStart.LAZY) {
            val jobIdentity = currentCoroutineContext()[Job]
            var modelReplyFailed = false
            var completed = false
            var providerStarted = false
            var stoppedFailure: ChatMachine.Failure? = null
            try {
                extensionResponse?.bindingId?.let { binding -> dispatchChat(session.id, ChatMachine.Fact.ExtensionBound(ref, binding)) }
                val accepted = checkNotNull(chats.session(session.id))
                val answer = if (extensionResponse != null) {
                    providerStarted = true
                    extensionResponse.answer(historyBefore, requestProfile, visible)
                } else {
                    val researchRequest = researchRequest(trimmed)
                    val storedQuestion = checkNotNull(chats.session(session.id))
                    val storedRoot = checkNotNull(chats.session(session.researchChatId))
                    // URLs attached before this run: a search step must never present them as its own finds.
                    val knownSourceUrls = (storedRoot.resources + storedQuestion.questionResources)
                        .map { it.url }.filter { it.isNotBlank() }.toMutableSet()
                    val autoSearch = researchRequest.autoSearch(
                        hasHistory = historyBefore.isNotEmpty(),
                        hasSources = storedQuestion.availableResearchResources(storedRoot).isNotEmpty(),
                        hasAttachments = visible.isNotEmpty())
                    AppLog.debug("chat", "research.search.decision", operationFields + mapOf(
                        "automatic" to autoSearch.toString(), "sourceTask" to researchRequest.sourceTask.toString()))
                    if (autoSearch && researchSearch?.isConfigured(settings) == true) {
                        val searchId = "research-search:${request.messageId}"
                        recorder.apply(CodingEvent.ToolStarted("web.search", "", searchId,
                            title = if (sharedDiscovery) "Ищу общие источники" else "Ищу источники для вопроса"))
                        publishDraft()
                        val result = discoverResearchSources(ref, trimmed, settings, operationFields, sharedDiscovery)
                        recorder.apply(CodingEvent.ToolFinished("web.search", result.isFailure, searchId,
                            title = result.fold({ "Найдено источников: ${it.size}" }, { "Поиск недоступен" })))
                        val discovered = result.getOrDefault(emptyList()).distinctBy { it.url }
                        val freshDiscovered = discovered.filter { it.url !in knownSourceUrls }
                        knownSourceUrls += discovered.map { it.url }
                        recorder.noteOperation("web.search", searchId,
                            system = searchSystemLabel("web.search", freshDiscovered, readsPages = false),
                            sources = freshDiscovered)
                        publishDraft()
                    }
                    if (researchRequest.urls.isNotEmpty()) {
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
                        recorder.noteOperation("web.read", readId, system = readSystemLabel(candidates))
                        publishDraft()
                    }
                    val sourceChecks = sourceAccess.check(browserSources.apply(root.id, candidates)).associateBy { it.resource.key }.toMutableMap()
                    fun publishSourceProblems() {
                        val problems = sourceChecks.values.filter { it.problem != null }.associate { it.resource.key to it.problem!! }
                        _state.update { it.copy(sourceReadProblems = it.sourceReadProblems + (session.id to problems)) }
                    }
                    publishSourceProblems()
                    val availableResources = sourceChecks.values.toList().readableSources()
                    val unreadableCount = sourceChecks.values.count { it.problem != null }
                    AppLog.info("chat", "research.sources.checked", operationFields + mapOf(
                        "readableCount" to availableResources.size.toString(), "unavailableCount" to unreadableCount.toString()))
                    if (checksWebSources) {
                        recorder.apply(CodingEvent.ToolFinished("web.read", false, readId,
                            title = "Прочитано источников: ${availableResources.count { it.url.isNotBlank() }}" +
                                if (unreadableCount > 0) "; недоступно: $unreadableCount" else ""))
                        publishDraft()
                    }
                    knownSourceUrls += candidates.mapNotNull { it.url.takeIf { it.isNotBlank() } }
                    val initialSources = availableResources.mapNotNull { resource -> resource.url.takeIf { it.isNotEmpty() }?.let {
                        SearchHit(resource.title, it, resource.snippet)
                    } }
                    val researchAttachments = (visible + availableResources.mapNotNull { it.attachment }).distinctBy { it.id }
                    var finished = false
                    var sources = initialSources
                    var outputAttachments = emptyList<Attachment>()
                    suspend fun saveResponseContext() {
                        dispatchChat(session.id, ChatMachine.Fact.ResponseContextPrepared(ref, ChatMachine.ResponseContext(
                            researchRequest.sourceTask, sourceChecks.values.map { ChatMachine.CheckedSource(it.resource.url, it.resource.title, it.problem) },
                            sources, outputAttachments)))
                    }
                    saveResponseContext()
                    val excluded = sourceChecks.values.toList().unavailableSourceContext(allowSearch = !researchRequest.sourceTask)
                    val prompt = if (excluded.isEmpty()) trimmed else "$excluded\n\nВопрос пользователя:\n$trimmed"
                    providerStarted = true
                    runtime.runChat(accepted.copy(acquireComputerAccess = false, resources = availableResources,
                        mediaTools = root.mediaTools), prompt, requestProfile, researchAttachments).collect { event ->
                        if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                            dispatchChat(session.id, ChatMachine.Fact.NativeSessionBound(ref, event.sessionId))
                        }
                        // A model failure is separate from an unavailable page. Preserve a
                        // safe, specific cause in the activity instead of a raw provider body.
                        recorder.apply(if (event is CodingEvent.Failed) {
                            modelReplyFailed = true
                            CodingEvent.Failed(RESEARCH_MODEL_FAILURE)
                        } else event)
                        publishDraft(event !is CodingEvent.TextDelta && event !is CodingEvent.ThinkingDelta && event !is CodingEvent.ToolProgress)
                        if (event is CodingEvent.ToolStarted || event is CodingEvent.ToolFinished ||
                            event is CodingEvent.ToolProgress && event.media != null) {
                            val steps = recorder.draft(true).steps
                            dispatchChat(session.id, ChatMachine.Fact.Progress(ref, previousActivity + steps.researchActivity(),
                                mergeTranscriptContent(previousContent, steps.transcriptContent())))
                        }
                        // Search and reading may be billed separately: name the system of each operation
                        // and keep only the references this individual search newly returned.
                        val searchTool = (event as? CodingEvent.ToolStarted)?.tool ?: (event as? CodingEvent.ToolFinished)?.tool
                        if (searchTool != null && searchTool in researchSearchTools) {
                            val callId = (event as? CodingEvent.ToolStarted)?.callId ?: (event as? CodingEvent.ToolFinished)?.callId ?: ""
                            val hits = event.researchSources().filter { hit ->
                                !researchRequest.sourceTask || researchUrl(hit.url)?.let { "url:$it" in sourceChecks } == true
                            }.distinctBy { it.url }
                            val fresh = hits.filter { it.url !in knownSourceUrls }
                            knownSourceUrls += hits.map { it.url }
                            recorder.noteOperation(searchTool, callId,
                                system = searchSystemLabel(searchTool, fresh, readsPages = searchTool == "web.search"),
                                sources = if (event is CodingEvent.ToolFinished) fresh else null)
                        }
                        val candidatesFound = event.researchSources().filter { hit ->
                            !researchRequest.sourceTask || researchUrl(hit.url)?.let { "url:$it" in sourceChecks } == true
                        }
                        val unchecked = candidatesFound.mapNotNull { hit -> researchUrl(hit.url)?.let { url ->
                            ResearchResource(Id.new(), hit.title, url)
                        } }.filterNot { it.key in sourceChecks }
                        sourceAccess.check(browserSources.apply(root.id, unchecked)).forEach { sourceChecks[it.resource.key] = it }
                        if (unchecked.isNotEmpty()) publishSourceProblems()
                        val found = candidatesFound.filter { hit ->
                            val checked = researchUrl(hit.url)?.let { sourceChecks["url:$it"] }
                            checked != null && checked.problem == null
                        }.map { it.copy(snippet = "") }
                        if (found.isNotEmpty()) {
                            sources = (sources + found).distinctBy { it.url }
                            rememberQuestionSources(ref, found, sharedDiscovery && !researchRequest.sourceTask)
                        }
                        if (event is CodingEvent.FinalText) { outputAttachments = event.attachments }
                        if (found.isNotEmpty() || unchecked.isNotEmpty() || event is CodingEvent.FinalText) saveResponseContext()
                        if (event is CodingEvent.Finished) finished = true
                    }
                    val response = recorder.message(request.responseId, Id.now())
                    check(finished && !response.failed) { if (modelReplyFailed) "research_model_reply_failed" else "research_reply_incomplete" }
                    val context = checkNotNull(chats.stateFor(session.id)?.runs?.get(session.id)?.responseContext)
                    assembleChatResponse(request.responseId, response.text, context, previousActivity + response.steps.researchActivity(),
                        mergeTranscriptContent(previousContent, response.steps.transcriptContent()))
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
                    content = answer.content,
                )
                val transition = dispatchChat(session.id, ChatMachine.Fact.ReplyStored(ref, agentMessage))
                completed = transition.effects.any { it is ChatMachine.Effect.RequestCompleted }
                _state.update { it.copy(drafts = it.drafts - session.id) }
                AppLog.info("chat", "send.completed", operationFields)
            } catch (cancelled: CancellationException) {
                runtime.abort(session.id)
                AppLog.info("chat", "send.cancelled", operationFields)
                throw cancelled
            } catch (failure: Exception) {
                // Отказ транспорта — это сбой модели, а не исследования. Подтверждённый отказ
                // (ответ провайдера получен) останавливает запрос известным сбоем с понятной
                // причиной; потерянный ответ сохраняет карантин «неизвестного исхода».
                val rejection = failure.transportRejection()
                if (rejection != null) modelReplyFailed = true
                stoppedFailure = if (modelReplyFailed) ChatMachine.Failure.MODEL else ChatMachine.Failure.RESEARCH
                if (recorder.draft(false).failedMessage == null)
                    recorder.apply(CodingEvent.Failed(if (modelReplyFailed) RESEARCH_MODEL_FAILURE else "Не удалось завершить исследование"))
                AppLog.error("chat", "send.failed", operationFields + mapOf("phase" to if (modelReplyFailed) "model" else "research",
                    "causeType" to failure::class.simpleName.orEmpty()) +
                    (rejection?.let { mapOf("status" to it.statusCode.toString()) } ?: emptyMap()))
                _state.update { it.copy(notice = when {
                    rejection?.confirmedRejection == true -> "$RESEARCH_MODEL_FAILURE. Проверьте подключение к модели и повторите запрос."
                    rejection != null -> "$RESEARCH_MODEL_FAILURE. Исход обращения не подтверждён. Можно проверить сохранённый ответ или оставить запрос без продолжения."
                    modelReplyFailed -> "$RESEARCH_MODEL_FAILURE. Проверьте подключение к модели. Можно проверить сохранённый ответ или оставить запрос без продолжения."
                    else -> "Не удалось завершить запрос. Можно проверить сохранённый ответ или оставить запрос без продолжения."
                }) }
            } finally {
                withContext(NonCancellable) {
                    var deferredEffects = emptyList<ChatMachine.Effect>()
                    if (runRef(session.id) == ref) {
                        val stopped = recorder.message(request.responseId, Id.now())
                        val activity = previousActivity + stopped.steps.researchActivity()
                        _state.update { it.copy(drafts = it.drafts + (session.id to CodingDraft(
                            steps = previousActivity + stopped.steps, failedMessage = if (stopped.failed) "Не удалось завершить исследование" else null))) }
                        try {
                            dispatchChat(session.id, ChatMachine.Fact.Progress(ref, activity,
                                mergeTranscriptContent(previousContent, stopped.steps.transcriptContent()).interruptedMedia()))
                            val unknown = providerStarted && runtime.inspectSavedResponse(ref) !is ChatSavedResponse.Interrupted
                            deferredEffects = dispatchChat(session.id, ChatMachine.Fact.RunStopped(ref, unknown,
                                stoppedFailure, Id.now()), deliverEffects = false).effects
                        } catch (failure: Exception) {
                            AppLog.error("chat", "activity.save.failed", operationFields + ("causeType" to failure::class.simpleName.orEmpty()))
                            _state.update { it.copy(notice = "Не удалось подтвердить сохранение запроса. Восстановите чат перед новым действием.") }
                        }
                    }
                    val ownJob = jobIdentity
                    chatJobs.update { jobs -> if (jobs[session.id] == ownJob) jobs - session.id else jobs }
                    publishJournal()
                    withContext(Dispatchers.Main.immediate) {
                        deliverEffects(deferredEffects)
                        if (acceptingCommands && completed && runRef(session.id) == null && chats.session(session.id)?.pendingRun == null &&
                            chats.session(session.id)?.queuedPrompts?.isNotEmpty() == true)
                            dispatchChat(session.id, ChatMachine.Intent.AdvanceQueue(session.id, Id.now()))
                    }
                }
            }
        }
        chatJobs.update { it + (session.id to job) }
        job.start()
    }

    private suspend fun discoverResearchSources(ref: ChatMachine.RunRef, query: String, settings: AppSettings,
        fields: Map<String, String>, shared: Boolean): Result<List<SearchHit>> {
        val search = checkNotNull(researchSearch)
        try {
            val result = withTimeout(15_000) { search.searchWithDiagnostics(query, settings, limit = 5) }
            if (result.hits.isEmpty() && result.issues.isNotEmpty()) {
                AppLog.info("chat", "research.search.unavailable", fields + mapOf("issueCount" to result.issues.size.toString()))
            } else {
                rememberQuestionSources(ref, result.hits, shared)
                return Result.success(result.hits)
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.info("chat", "research.search.timeout", fields)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("chat", "research.search.failed", fields + ("causeType" to failure::class.simpleName.orEmpty()))
        }
        val message = "Поиск недоступен. Исследование продолжится по доступным источникам."
        _state.update { it.copy(notice = message) }
        return Result.failure(IllegalStateException(message))
    }

    /** Search and reading name the systems that actually performed each operation. */
    private fun searchSystemLabel(tool: String, hits: List<SearchHit>, readsPages: Boolean): String {
        if (tool != "web.search") {
            return "Поиск: $tool"
        }
        val providers = hits.map { it.provider }.filter { it.isNotBlank() }.distinct()
        val name = providers.ifEmpty { listOfNotNull(researchSearch?.displayName) }.joinToString(", ")
            .ifBlank { _state.value.settings.descriptionSearchLabel() }
        return "Поиск: $name" + if (readsPages) " · чтение найденных страниц: загрузчик MagicPaper" else ""
    }

    /** Selected pages are read by the application loader, unless a manual browser snapshot is reused. */
    private fun readSystemLabel(candidates: List<ResearchResource>): String {
        val web = candidates.filter { it.url.isNotBlank() }
        val browser = web.count { it.readableText != null }
        val parts = buildList {
            if (web.size > browser) add("загрузчик страниц MagicPaper (прямые HTTP-запросы)")
            if (browser > 0) add("снимки страниц из браузера приложения")
        }
        return "Чтение: " + parts.joinToString(" + ").ifBlank { "MagicPaper" }
    }

    // ---- Настройки ---------------------------------------------------------

    /** Завершение ознакомительного тура: сохранить черновик и впустить в приложение.
     * [onboardingProfile] — профиль, созданный на шаге «источник магии» (если настроен). */
    override fun selectChatModel(selection: ModelSelection) {
        if (_state.value.availableLlmProfiles.none { it.id == selection.profileId && selection.modelId in it.displayModels }) return
        if (_state.value.current == null) newSession()
        val session = _state.value.current ?: return
        scope.launch {
            pendingCreations[session.id]?.await()
            dispatchChat(session.id, ChatMachine.Intent.SetModel(session.id, selection))
        }
    }

    override suspend fun importNotebooks(sessions: List<ChatSession>) {
        for ((id, notebook) in sessions.groupBy { it.researchChatId }) dispatchChat(id, ChatMachine.Intent.ImportNotebook(notebook))
    }
    override suspend fun unlinkProfile(profileId: String) {
        chats.start()
        for ((id, notebook) in chats.states.value) if (!notebook.deleted) dispatchChat(id, ChatMachine.Intent.UnlinkProfile(profileId))
    }
    override suspend fun wipeHistory() { prepareForReset(); chats.wipe() }

    /** Drain this application's writers while retaining its reusable supervisor. */
    suspend fun prepareForReset() {
        acceptingCommands = false
        browserRecovery?.close()
        browserSources.clear()
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it != caller }?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.forEach { it.join() }
        resetDrafts()
        archiveObserver = null
        pinFailureObserver = null
        chatJobs.value = emptyMap()
        pendingCreations.clear()
        preparedResponses.clear()
        _state.value = ChatState(sourceBrowserSupported = browserRecovery != null)
        journalObserver = null
    }

    /** Called after the application has cancelled and joined the draft writers. */
    suspend fun resetDrafts() { composerSessions.values.forEach { it.revoke() }; composerSessions.clear(); deletedDraftSessionIds.clear(); questions.reset() }

    override suspend fun close() {
        acceptingCommands = false
        try { browserRecovery?.close(); browserSources.clear(); composerSessions.values.forEach { it.awaitSaved() }; questions.flush() }
        finally {
            val jobs = scope.coroutineContext[Job]?.children?.toList().orEmpty()
            scope.cancel()
            jobs.joinAll()
        }
    }
}
