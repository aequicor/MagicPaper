package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.data.storage.logPersistenceFailure
import kotlinx.coroutines.CoroutineExceptionHandler

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.DraftSession
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.Attachment
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
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmProfileRepository
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.ProjectDirPicker
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.SettingsRepository
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.domain.asMeta
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.domain.CodingRunCheckpoint
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.interruptedCodingRequest
import io.aequicor.magicpaper.domain.recordDrafts
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json


/** Owns coding runs and recovery independently of screen component lifetimes. */
class DefaultCodingService(
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val store: KeyValueStore,
    private val json: Json,
    private val codingRuntime: CodingRuntime? = null,
    private val codingProjects: CodingProjectRepository? = null,
    private val dirPicker: ProjectDirPicker? = null,
    private val gateway: LlmGateway? = null,
    val planningChat: PlanningChatService? = null,
    val requestPins: RequestPinService? = null,
    val usage: UsageLedger,
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onOpenSession: (String?, String?) -> Unit = { _, _ -> },
    private val onCreateSession: (String?) -> Unit = {},
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val draftBlobs: io.aequicor.magicpaper.data.storage.DraftBlobStore = io.aequicor.magicpaper.data.storage.InMemoryDraftBlobStore(),
    private val removePluginDrafts: suspend (projectId: String, planIds: Set<String>?) -> Unit = { _, _ -> },
) : CodingService {
    private val _state = MutableStateFlow(CodingState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("coding", "background.failed", error)
        _state.update { it.copy(notice = "Не удалось выполнить действие с сессией. Проверьте её состояние и повторите попытку.") }
    })
    override val state: StateFlow<CodingState> = _state.asStateFlow()
    val sessionTitles = if (codingProjects != null && gateway != null)
        SessionTitleService(codingProjects, profileRepo, settingsRepo, gateway, scope) else null
    val unreadTracker = UnreadTracker(store, json)
    init {
        planningChat?.organisms?.beforeDeleteSession = { projectId, sessionId ->
            withContext(Dispatchers.Main.immediate) {
                requestPins?.remove(PinConversation(sessionId, projectId))
                sessionTitles?.forget(sessionId)
                unreadTracker.forget(sessionId)
                removeSessionDrafts(setOf(sessionId))
            }
        }
    }
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) {
        _state.update { it.copy(settings = settings, llmProfiles = profiles, subscriptionAvailable = subscriptionAvailable, subscriptionSignedIn = subscriptionSignedIn) }
        // Sync global feature flags to the coding runtime for optimization decisions.
        codingRuntime?.globalFeatureFlags = settings.featureFlags
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
                val opened = current.coding.currentSession?.takeIf { shown }?.session?.let { PinConversation(it.id, it.projectId) }
                sources.keys.retainAll(current.coding.sessions.map { PinConversation(it.session.id, it.session.projectId) }.toSet())
                current.coding.sessions.forEach { session ->
                    val key = PinConversation(session.session.id, session.session.projectId)
                    if (key == opened || session.running || pins.isTracking(key)) {
                        val reopened = key == opened && key != previousOpen
                        if (reopened || sources[key] !== session.messages) {
                            pins.sync(key, session.messages.pinMessages(session.session.planningMode), profile, reopened)
                            sources[key] = session.messages
                        }
                    }
                }
                previousOpen = opened
            }
        }
    }
    override fun dismissNotice() { _state.update { it.copy(notice = null) } }
    private val interactionQueue = UserInteractionQueue()
    private val interactionDecisions = json.decodeFromString<Set<String>>(store.read("coding-interaction-decisions") ?: "[]").toMutableSet()
    private val interactionSubmitting = mutableSetOf<String>()
    private val interactionErrors = mutableMapOf<String, String>()
    private var decisionStorageRecovery: UserInteractionRequest? = null
    private val requestedInputRecovery = mutableSetOf<String>()
    private val _questionnaireDrafts = MutableStateFlow<Map<String, QuestionnaireDraft>>(emptyMap())
    val questionnaireDrafts = _questionnaireDrafts.asStateFlow()
    // Kept above the composer lifetime so switching sessions and temporarily replacing it cannot lose attachments.
    val composerDrafts = mutableMapOf<String, io.aequicor.magicpaper.ui.components.CodingComposerDraft>()
    private val deletedDraftSessionIds = mutableSetOf<String>()
    private val sessionCreationDrafts = mutableMapOf<String, DraftSession<CodingEngine>>()
    private val sessionCreationJobs = mutableMapOf<String, Job>()
    private val deletedDraftProjectIds = mutableSetOf<String>()
    private val _sessionCreationStatus = MutableStateFlow<Map<String, SessionCreationStatus>>(emptyMap())
    override val sessionCreationStatus = _sessionCreationStatus.asStateFlow()
    override fun sessionCreationDraft(projectId: String): DraftSession<CodingEngine>? {
        if (projectId in deletedDraftProjectIds || _state.value.coding.projects.none { it.id == projectId }) return null
        return sessionCreationDrafts.getOrPut(projectId) {
            DraftSession(draftRepository, "coding-session-create:$projectId", CodingEngine.serializer(),
                _state.value.settings.defaultCodingEngine, scope, json)
        }
    }
    private suspend fun removeSessionCreationDraft(projectId: String) {
        deletedDraftProjectIds += projectId
        sessionCreationDrafts.remove(projectId)?.revoke()
        _sessionCreationStatus.update { it - projectId }
        try { draftRepository.remove("coding-session-create:$projectId") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("coding", "session.creation.draft.remove.failed", failure, mapOf("projectId" to projectId))
            _state.update { it.copy(notice = "Проект удалён. Не удалось удалить черновик новой сессии.") }
        }
    }
    fun composerDraft(id: String): io.aequicor.magicpaper.ui.components.CodingComposerDraft = composerDrafts.getOrPut(id) {
        io.aequicor.magicpaper.ui.components.CodingComposerDraft(
            composerDraftSession(draftRepository, draftBlobs, "coding:$id", scope), scope)
    }.also { if (id in deletedDraftSessionIds) it.revoke() }

    private val questionnaireSessions = mutableMapOf<String, Pair<String, io.aequicor.magicpaper.data.storage.DraftSession<QuestionnaireDraft>>>()

    /** Removes unopened drafts as well as current editors; tombstone epochs reject older actors. */
    private suspend fun removeSessionDrafts(ids: Set<String>) {
        deletedDraftSessionIds += ids
        ids.forEach { composerDrafts.remove(it)?.revoke() }
        fun ownsQuestionnaire(identity: String): Boolean =
            json.decodeFromString<List<String>>(identity).getOrNull(2) in ids
        val owned = questionnaireSessions.filterValues { ownsQuestionnaire(it.first) }
        owned.values.forEach { it.second.revoke() }
        owned.keys.forEach { questionnaireSessions.remove(it) }
        _questionnaireDrafts.update { it - owned.keys }
        var failed = false
        suspend fun remove(key: String) {
            try { draftRepository.remove(key) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { AppLog.error("coding", "draft.remove.failed", error); failed = true }
        }
        ids.forEach { remove("coding:$it") }
        val stored = try { draftRepository.keys("questionnaire:").filter { ownsQuestionnaire(it.removePrefix("questionnaire:")) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { AppLog.error("coding", "draft.list.failed", error); failed = true; emptyList() }
        (stored + owned.values.map { "questionnaire:${it.first}" }).distinct().forEach { remove(it) }
        if (failed) _state.update { it.copy(notice = "Сессии удалены. Не удалось удалить все черновики.") }
        refreshInteractions()
    }

    private suspend fun removePlanningDrafts(projectId: String, planIds: Set<String>? = null) {
        try { removePluginDrafts(projectId, planIds) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { AppLog.error("coding", "planning.drafts.remove.failed", error, mapOf("projectId" to projectId)); _state.update { it.copy(notice = "Сессии удалены. Не удалось удалить все черновики.") } }
    }

    private fun questionnaireSession(request: UserInteractionRequest): io.aequicor.magicpaper.data.storage.DraftSession<QuestionnaireDraft> {
        // A changed request/generation has a distinct draft even when the runtime reuses its id.
        val identity = json.encodeToString(listOf(request.id, request.sourceId, request.ownerSessionId,
            request.revision?.toString().orEmpty(), request.runtimeGeneration.toString(), request.runId,
            json.encodeToString(request.questions)))
        questionnaireSessions[request.id]?.takeIf { it.first == identity }?.let { return it.second }
        questionnaireSessions[request.id]?.second?.revoke()
        val secretIds = request.questions.filter { it.secret }.map { it.id }.toSet()
        val session = io.aequicor.magicpaper.data.storage.DraftSession(
            repository = draftRepository, key = "questionnaire:$identity", serializer = QuestionnaireDraft.serializer(),
            initial = QuestionnaireDraft(request.initialAnswers), scope = scope, json = json,
            redact = { value -> value.copy(answers = value.answers.map { if (it.questionId in secretIds) PlanningAnswer(it.questionId) else it }) },
            extractSecrets = { value -> value.answers.filter { it.questionId in secretIds }
                .associate { it.questionId to json.encodeToString(PlanningAnswer.serializer(), it) } },
            hydrateSecrets = { value, fields -> value.copy(answers = value.answers.map { answer ->
                fields[answer.questionId]?.let { json.decodeFromString(PlanningAnswer.serializer(), it) } ?: answer
            }) },
        )
        questionnaireSessions[request.id] = identity to session
        scope.launch {
            session.state.collect { snapshot ->
                if (questionnaireSessions[request.id]?.first == identity) {
                    if (snapshot.loaded) _questionnaireDrafts.update { it + (request.id to snapshot.value) }
                    snapshot.error?.let { interactionErrors[request.id] = "Не удалось сохранить ответы"; refreshInteractions() }
                }
            }
        }
        return session
    }

    override fun updateQuestionnaireDraft(id: String, draft: QuestionnaireDraft) {
        if (id in interactionSubmitting) return
        val request = _state.value.coding.interactions.firstOrNull { it.id == id } ?: return
        questionnaireSession(request).update(draft)
        _questionnaireDrafts.update { it + (id to draft) }
    }

    private fun currentInteractionCandidates(): List<UserInteractionRequest> = interactionCandidates(
        _state.value.coding, planningChat?.store?.plans?.value.orEmpty(), planningChat?.states?.value.orEmpty(),
        planningChat?.persistenceErrors?.value.orEmpty(), codingRuntime?.questionnaires?.value.orEmpty(), requestedInputRecovery) + listOfNotNull(decisionStorageRecovery)

    private fun saveInteractionDecisions() = store.write("coding-interaction-decisions",
        json.encodeToString(interactionDecisions.filterNot { it.startsWith("runtime:") || it.startsWith("approval:") }.toSet()))

    private fun legacyInteractionAliases() = legacyRecoveryDecisionAliases(
        _state.value.coding, planningChat?.store?.plans?.value.orEmpty(), interactionDecisions)

    private fun refreshInteractions() {
        val queued = interactionQueue.reconcile(currentInteractionCandidates().filterNot {
            it.ownerSessionId in deletedDraftSessionIds || it.sessionId in deletedDraftSessionIds
        },
            interactionDecisions.withLegacyRecoveryDecisions(legacyInteractionAliases())).map { request ->
            request.copy(submitting = request.submitting || request.id in interactionSubmitting,
                error = interactionErrors[request.id] ?: request.error)
        }
        queued.forEach(::questionnaireSession)
        _state.update { state ->
            val sessions = state.coding.sessions.map { item ->
                val interactions = queued.filter { it.affects(item.session) }
                if (item.interactions == interactions) item else item.copy(interactions = interactions)
            }
            if (state.coding.interactions == queued && state.coding.sessions == sessions) state
            else state.copy(coding = state.coding.copy(interactions = queued, sessions = sessions))
        }
    }

    /** Reopening an acknowledged action joins the queue; it never jumps over the active request. */
    override fun openQuestionnaire(kind: InteractionKind, sourceId: String) {
        if (kind == InteractionKind.RECOVER_INPUT) requestedInputRecovery.add(sourceId)
        val targets = currentInteractionCandidates().filter { it.kind == kind &&
            (it.sourceId == sourceId || it.planId == sourceId || it.ownerSessionId == sourceId) }
        interactionDecisions.reopenInteractionDecisions(targets.map { it.id }, legacyInteractionAliases())
        if (targets.isNotEmpty()) {
            store.write("coding-interaction-decisions", json.encodeToString(interactionDecisions.toSet()))
            refreshInteractions()
        }
    }

    override fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        val request = _state.value.coding.interactions.firstOrNull { it.id == id } ?: return
        if (request.submitting || !interactionSubmitting.add(id)) return
        val persistedDraft = questionnaireSession(request)
        val draftVersion = persistedDraft.state.value.version
        interactionErrors.remove(id)
        refreshInteractions()
        scope.launch {
            try {
                val fresh = currentInteractionCandidates().firstOrNull { it.id == id } ?: error("Обращение уже закрыто")
                require(fresh.questions == request.questions && fresh.details == request.details && fresh.revision == request.revision && fresh.runtimeGeneration == request.runtimeGeneration && fresh.runId == request.runId) { "Обращение изменилось. Проверьте актуальные данные." }
                validateInteractionAnswers(fresh.questions, answers)
                when (request.kind) {
                    InteractionKind.RECOVER_DECISIONS -> {
                        if (!answers.single().skipped && "leave" !in answers.single().selected) saveInteractionDecisions()
                        decisionStorageRecovery = null
                    }
                    InteractionKind.RUNTIME -> {
                        codingRuntime!!.respondQuestionnaire(request.sourceId, answers)
                    }
                    InteractionKind.APPROVAL -> {
                        if (request.outcomeUnknown) {
                            val approval = _state.value.coding.approvals.firstOrNull { it.id == request.sourceId } ?: error("Разрешение уже закрыто")
                            codingRuntime?.abort(approval.sessionId)
                        } else codingRuntime!!.respondApproval(request.sourceId,
                            if ("yes" in answers.single().selected) CodingApprovalDecision.ALLOW_ONCE else CodingApprovalDecision.DENY)
                    }
                    InteractionKind.RECOVER_RUN -> {
                        val session = _state.value.coding.sessions.firstOrNull { it.session.id == request.sessionId } ?: error("Сессия удалена")
                        val answer = answers.single()
                        if (answer.skipped || "leave" in answer.selected) {
                            updateStoredCodingSession(session.session) { latest ->
                                val checkpoint = latest.pendingRun ?: session.messages.interruptedCodingRequest()?.let { CodingRunCheckpoint(it.id, it.text) }
                                latest.copy(pendingRun = checkpoint?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true))
                            }
                            appendCodingMessage(session.session, CodingMessage(request.id + "-left", CodingRole.USER,
                                "Оставить работу остановленной.", createdAt = Id.now()))
                        } else resumeCodingSession(request.sessionId, answer.text, fromQuestionnaire = true)
                    }
                    else -> planningChat!!.submitInteraction(request, answers)
                }
                interactionDecisions.add(id)
                if (request.kind !in setOf(InteractionKind.RUNTIME, InteractionKind.APPROVAL, InteractionKind.RECOVER_DECISIONS)) {
                    try { saveInteractionDecisions() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        AppLog.error("coding", "decision.save.failed", e, mapOf("requestId" to id))
                        // The action already happened. Retrying storage must never repeat the action.
                        decisionStorageRecovery = request.copy(id = "decision-storage:$id", sourceId = id,
                            kind = InteractionKind.RECOVER_DECISIONS, revision = null, planId = null,
                            questions = listOf(PlanningQuestion("decision", "Не удалось сохранить принятое решение. Как продолжить?", QuestionKind.SINGLE,
                                listOf(QuestionOption("retry", "Повторить сохранение"), QuestionOption("leave", "Оставить остановленной")), allowCustomInput = false)),
                            details = "Не удалось сохранить решение. Повторите сохранение, не отправляя ответ заново.", initialAnswers = emptyList(), submitting = false, error = null)
                    }
                }
                try { persistedDraft.clearIfUnchanged(draftVersion) }
                catch (error: CancellationException) { throw error }
                catch (error: io.aequicor.magicpaper.data.storage.StorageException) {
                    logPersistenceFailure("coding", "questionnaire.draft.clear.failed", error, mapOf("requestId" to id))
                    _state.update { it.copy(notice = if (error.committed) "Ответ принят. Не удалось удалить временные данные." else "Ответ принят. Не удалось очистить черновик.") }
                }
                if (questionnaireSessions[id]?.second === persistedDraft) _questionnaireDrafts.update { it - id }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "questionnaire.submit.failed", e, mapOf("requestId" to id)); interactionErrors[id] = "Не удалось передать ответ. Проверьте состояние запроса перед повторным подтверждением." }
            finally { interactionSubmitting.remove(id); refreshInteractions() }
        }
    }

    /** Активные прогоны по идентификаторам кодинг-сессий (параллельно в разных сессиях). */
    private val codingJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val codingSessionLocks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private val closingState = MutableStateFlow(false)
    private var closing: Boolean
        get() = closingState.value
        set(value) { closingState.value = value }
    private val idleCodingDraft = CodingDraft()
    private fun removeCodingJob(id: String): Job? = codingJobs.getAndUpdate { it - id }[id]


    private var started = false
    override suspend fun start() {
        if (started) return
        started = true
        observePins()
        val settings = settingsRepo.load()
        val profiles = profileRepo.load()
        val projects = codingProjects?.all().orEmpty()
        _state.update { it.copy(settings = settings, llmProfiles = profiles,
            coding = it.coding.copy(projects = projects, sessions = loadCodingSessions(projects), projectStatuses = codingStatusSnapshot(projects))) }
        // Sync global feature flags to the coding runtime.
        codingRuntime?.globalFeatureFlags = settings.featureFlags
        observeRuntime()
        refreshCodingEngines()
        restoreCodingRuns(projects)
        startPendingImmunityDiagnostics()
        _state.value.coding.sessions.forEach { startQueuedPrompt(it.session.id) }
    }
    private fun observeRuntime() {
        scope.launch { _state.collect { refreshInteractions() } }
        codingRuntime?.let { runtime -> scope.launch { runtime.questionnaires.collect { refreshInteractions() } } }
        planningChat?.organisms?.let { service -> scope.launch {
            service.store.organisms.collect { organisms ->
                _state.update { it.copy(coding = it.coding.copy(organisms = organisms)) }
            }
        } }
        planningChat?.let { service -> scope.launch {
            combine(service.states, service.store.plans, service.persistenceErrors, service.drafts) { _, _, _, _ -> Unit }.collect {
                _state.update { state -> state.copy(coding = state.coding.copy(sessions = state.coding.sessions.map(::withPlanningState))) }
                refreshInteractions()
            }
        } }
        codingRuntime?.computerUse?.let { computer -> scope.launch {
            computer.state.collect { value ->
                _state.update { it.copy(coding = it.coding.copy(computer = value, computerSupported = computer.supported)) }
            }
        } }
        codingRuntime?.let { runtime -> scope.launch {
            runtime.approvals.collect { requests ->
                _state.update { state -> state.copy(coding = state.coding.copy(approvals = requests)) }
            }
        } }
        planningChat?.let { service -> scope.launch {
            service.changes.collect {
                val repo = codingProjects ?: return@collect
                val projects = repo.all()
                val stored = projects.flatMap { repo.sessions(it.id) }
                val storedProjectIds = projects.map { it.id }.toSet()
                codingRuntime?.computerUse?.let { computer ->
                    val owner = stored.firstOrNull { it.id == computer.state.value.sessionId }
                    if (owner == null || owner.planningMode || owner.researchMode || owner.stageId != null) computer.disable()
                }
                val old = _state.value.coding.sessions.associateBy { it.session.id }
                // Only clean up sessions that truly disappeared (project deleted),
                // not sessions that were just created in-memory but haven't reached
                // the concurrent storage snapshot yet (addCodingSession race).
                old.values.filter { item ->
                    stored.none { it.id == item.session.id } && item.session.projectId !in storedProjectIds
                }.forEach {
                    requestPins?.remove(PinConversation(it.session.id, it.session.projectId))
                    sessionTitles?.forget(it.session.id)
                    unreadTracker.forget(it.session.id)
                }
                // Список подписывает начатые задачи плана; сервис сам пропускает названные.
                stored.forEach { session -> sessionTitles?.sync(session) }
                val histories = buildMap {
                    for (session in stored) {
                        if (session.id !in old || session.organismId != null || session.stageId != null || session.planningMode ||
                            service.store.plans.value.any { it.parentSessionId == session.id })
                            put(session.id, repo.messages(session.projectId, session.id))
                    }
                }
                val storedIds = stored.map { it.id }.toSet()
                _state.update { state ->
                    // Repository reads suspend. Merge into the latest draft rather than
                    // overwriting output received while the history was being loaded.
                    val current = state.coding.sessions.associateBy { it.session.id }
                    // Keep sessions that were just created in-memory but haven't been
                    // captured by the concurrent storage snapshot (addCodingSession race).
                    val preserved = current.values.filter { it.session.id !in storedIds && it.session.projectId in storedProjectIds }
                    val merged = stored.map { session ->
                        val previous = current[session.id] ?: CodingSessionUi(session)
                        withUnread(withPlanningState(previous.copy(session = session, messages = histories[session.id] ?: previous.messages)))
                    } + preserved
                    // Skip state mutation when no session reference changed —
                    // prevents unnecessary Compose recomposition.
                    if (merged.size == state.coding.sessions.size &&
                        merged.indices.all { i -> merged[i] === state.coding.sessions[i] }) state
                    else state.copy(coding = state.coding.copy(sessions = merged))
                }
                startPendingImmunityDiagnostics()
            }
        } }
        planningChat?.let { service -> scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(service.drafts, service.execution.live) { _, _ -> Unit }.sample(50).collect {
                // Live activity updates statuses using saved history already in memory.
                // Only service.changes reloads persisted messages.
                _state.update { state ->
                    val updated = state.coding.sessions.map { withUnread(withPlanningState(it)) }
                    // withPlanningState returns the same reference when nothing changed.
                    // Skip the copy entirely to avoid triggering Compose recomposition
                    // every 50 ms when no planning state actually changed.
                    if (updated.indices.all { i -> updated[i] === state.coding.sessions[i] }) state
                    else state.copy(coding = state.coding.copy(sessions = updated))
                }
            }
        } }

    }
    private fun withUnread(item: CodingSessionUi): CodingSessionUi {
        val unread = unreadTracker.hasUnread(item.session.id, item.messages, _state.value.coding.currentSessionId)
        return if (item.unread == unread) item else item.copy(unread = unread)
    }

    private fun withPlanningState(item: CodingSessionUi): CodingSessionUi {
        val service = planningChat ?: return item
        val session = item.session
        val activePlanId = service.states.value[session.id]?.activePlanId
        val plan = service.store.plans.value.firstOrNull { it.id == session.planId }
            ?: service.store.plans.value.firstOrNull { it.id == activePlanId && it.parentSessionId == session.id }
            ?: service.store.plans.value.filter { it.parentSessionId == session.id }
                .let { plans -> plans.firstOrNull { it.phase != io.aequicor.magicpaper.domain.ExecutionPhase.COMPLETE } ?: plans.lastOrNull() }
        val workerRunning = plan?.milestones?.firstOrNull { it.id == session.stageId }?.let {
            plan.isStageWorking(it)
        } == true
        val inputStatus = service.states.value[session.id]?.inputs?.lastOrNull()?.status
        val failedRequest = inputStatus == io.aequicor.magicpaper.domain.OrchestrationInputStatus.FAILED
        val interruptedRequest = inputStatus in
            listOf(io.aequicor.magicpaper.domain.OrchestrationInputStatus.CANCELLED, io.aequicor.magicpaper.domain.OrchestrationInputStatus.FAILED)
        val awaitingUser = service.states.value[session.parentSessionId ?: session.id]?.openQuestions(plan?.id).orEmpty().any {
            session.stageId == null || it.stageIds.isEmpty() || session.stageId in it.stageIds
        }
        val draft = service.drafts.value[session.id]
            ?: if (plan != null || session.planningMode) idleCodingDraft else item.draft
        val running = workerRunning || service.drafts.value[session.id]?.active == true || codingJobs.value[session.id]?.isActive == true
        if (item.plan === plan && item.draft === draft && item.interruptedRequest == interruptedRequest && item.failedRequest == failedRequest &&
            item.awaitingUser == awaitingUser && item.running == running) return item
        return item.copy(plan = plan, interruptedRequest = interruptedRequest, failedRequest = failedRequest,
            awaitingUser = awaitingUser, draft = draft, running = running)
    }

    private suspend fun loadCodingSessions(projects: List<CodingProject>): List<CodingSessionUi> {
        val repo = codingProjects ?: return emptyList()
        return projects.flatMap { project -> repo.sessions(project.id).map { session ->
            withUnread(withPlanningState(CodingSessionUi(session, repo.messages(project.id, session.id))))
        } }
    }

    private suspend fun codingStatusSnapshot(projects: List<CodingProject>): Map<String, CodingSessionStatus> {
        val repo = codingProjects ?: return emptyMap()
        return projects.associate { project ->
            val statuses = repo.sessions(project.id).map { session ->
                withPlanningState(CodingSessionUi(session, repo.messages(project.id, session.id))).status
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
            val active = codingJobs.value[session.id]
            if (active != null && active.isActive) {
                _state.value.coding.sessions.firstOrNull { it.session.id == session.id }?.status
                    ?: CodingSessionStatus.WORKING
            } else {
                withPlanningState(CodingSessionUi(session, repo.messages(projectId, session.id))).status
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
    override fun codingProfileOf(session: CodingSession, plan: Plan?): LlmProfile? {
        val s = _state.value
        if (session.planningMode) return session.modelSelection?.let { ProfileResolver.selection(it, s.availableLlmProfiles) } ?: ProfileResolver.resolve(null as ChatSession?, s.settings, s.availableLlmProfiles)
        val workerPlan = plan ?: planningChat?.store?.plans?.value?.firstOrNull { it.id == session.planId }
            ?: s.coding.sessions.firstOrNull { it.session.id == session.id }?.plan
        return ProfileResolver.coding(session, s.coding.projects.firstOrNull { it.id == session.projectId }, s.settings, s.availableLlmProfiles, workerPlan)
    }

    // ---- Навигация -------------------------------------------------------


    override fun requestCodingSession() { onCreateSession(_state.value.coding.current?.id) }
    override fun requestCodingSessionInProject(projectId: String) {
        scope.launch { openCodingProject(projectId); onOpenSession(projectId, null); onCreateSession(projectId) }
    }
    override fun cancelCodingSessionCreation() { _state.value.coding.current?.id?.let { discardCodingSessionDraft(it) {} } }
    override fun refreshCodingEngines() {
        val runtime = codingRuntime ?: return
        scope.launch {
            for (engine in CodingEngine.entries) {
                val status = try { runtime.status(engine) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { AppLog.error("coding", "engine.status.failed", e); RuntimeStatus(RuntimePhase.ERROR, "Не удалось проверить движок.") }
                _state.update { it.copy(coding = it.coding.copy(engines = it.coding.engines + (engine to status))) }
            }
        }
    }

    override fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val profile = ProfileResolver.selection(selection, _state.value.availableLlmProfiles) ?: return
        if ((!ui.session.planningMode || forProject) && !profile.supportsCoding) return
        val updated = ui.session.copy(modelSelection = selection, llmProfileId = selection.profileId)
        updateCodingSession(sessionId) { it.copy(session = updated) }
        scope.launch {
            if (updated.stageId != null && updated.planId != null) {
                val effort = EffortSelection.ofOrNull(ModelDefaults.capability(profile).resolveEffort(selection.effort).level)
                planningChat?.store?.update(checkNotNull(updated.planId)) { plan ->
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
            updateStoredCodingSession(ui.session) { it.copy(modelSelection = selection, llmProfileId = selection.profileId) }
        }
        if (forProject) {
            val project = _state.value.coding.projects.firstOrNull { it.id == updated.projectId } ?: return
            val next = project.copy(modelSelection = selection)
            _state.update { st -> st.copy(coding = st.coding.copy(projects = st.coding.projects.map { if (it.id == next.id) next else it }, current = st.coding.current?.let { if (it.id == next.id) next else it })) }
            scope.launch { codingProjects?.save(next) }
        }
    }

    override fun prepareCodingRuntime(engine: CodingEngine) {
        val runtime = codingRuntime ?: return
        if (engine in _state.value.coding.preparingEngines) return
        _state.update { it.copy(coding = it.coding.copy(preparingEngines = it.coding.preparingEngines + engine)) }
        scope.launch {
            try {
                runtime.ensureReady(engine).collect { status ->
                    _state.update { it.copy(coding = it.coding.copy(engines = it.coding.engines + (engine to status),
                        runtime = if (engine == CodingEngine.PI) status else it.coding.runtime)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "engine.action.failed", e); _state.update { it.copy(notice = "Не удалось выполнить действие с движком. Повторите попытку.") } }
            finally { _state.update { it.copy(coding = it.coding.copy(preparingEngines = it.coding.preparingEngines - engine)) } }
        }
    }

    override fun uninstallCodingRuntime(engine: CodingEngine) {
        val runtime = codingRuntime ?: return
        // Dependency removal is unavailable while any work is using the shared adapters.
        if (_state.value.coding.sessions.any { it.running } || planningChat?.execution?.live?.value?.isNotEmpty() == true) {
            _state.update { it.copy(notice = "Сначала остановите выполняющиеся сессии и планы.") }; return
        }
        scope.launch {
            try { runtime.uninstall(engine); refreshCodingEngines() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "engine.action.failed", e); _state.update { it.copy(notice = "Не удалось выполнить действие с движком. Повторите попытку.") } }
        }
    }

    /** Новый проект: выбор папки нативным диалогом. */
    override fun addCodingProject() {
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
            repo.sessions(project.id).forEach { repo.deleteSession(project.id, it.id) }
            _state.update { it.copy(coding = it.coding.copy(projects = repo.all())) }
            openCodingProject(project.id)
            requestCodingSession()
        }
    }

    /** Открыть проект: загрузить его кодинг-сессии с журналами. */
    override fun selectCodingProject(id: String) {
        scope.launch { openCodingProject(id); onOpenSession(id, _state.value.coding.currentSessionId) }
    }

    /** Открыть проект: список сессий с журналами; чужие активные прогоны сохраняются. */
    private suspend fun openCodingProject(projectId: String) {
        val repo = codingProjects ?: return
        val project = repo.all().firstOrNull { it.id == projectId } ?: return
        val sessions = repo.sessions(projectId)
        // Determine which session should have messages loaded: prefer current, else first.
        val currentId = _state.value.coding.currentSessionId
        val activeSessionId = sessions.firstOrNull { it.id == currentId }?.id ?: sessions.firstOrNull()?.id
        val loaded = sessions.map { session ->
            val messages = if (session.id == activeSessionId) repo.messages(projectId, session.id) else emptyList()
            withPlanningState(CodingSessionUi(
                session = session,
                messages = messages,
                running = codingJobs.value[session.id]?.isActive == true,
            ))
        }
        currentCoroutineContext().ensureActive()
        _state.update { st ->
            // The same queue supplies every project indicator, including inactive projects.
            val loadedIds = loaded.map { it.session.id }.toSet()
            val carried = st.coding.sessions.filter {
                it.session.projectId != projectId && it.session.id !in loadedIds
            }
            st.copy(
                coding = st.coding.copy(
                    current = project,
                    sessions = loaded.map { fresh -> st.coding.sessions.firstOrNull {
                        it.session.id == fresh.session.id && it.running
                    } ?: fresh } + carried,
                    currentSessionId = st.coding.currentSessionId
                        .takeIf { it != null && it in loadedIds }
                        ?: loaded.firstOrNull()?.session?.id,
                ),
            )
        }
    }

    private val deletingCodingProjects = MutableStateFlow<Set<String>>(emptySet())
    private val _immunityActions = MutableStateFlow<Set<String>>(emptySet())
    val immunityActions: StateFlow<Set<String>> = _immunityActions.asStateFlow()

    override fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction, deleteConfirmed: Boolean) {
        val service = planningChat?.organisms ?: return
        if (action == ImmunityAction.DELETE_HISTORY && !deleteConfirmed) return
        val key = "$organismId:$proposalId"
        while (true) {
            val pending = _immunityActions.value
            if (key in pending) return
            if (_immunityActions.compareAndSet(pending, pending + key)) break
        }
        scope.launch {
            try {
                service.approveImmunityIntervention(organismId, proposalId, action) { projectId, target ->
                    val repo = codingProjects ?: error("Хранилище сессий недоступно")
                    val ids = repo.sessions(projectId).sessionTreeIds(target).toMutableSet()
                    val planIds = planningChat.store.plans.value.filter { it.projectId == projectId && it.parentSessionId in ids }.map { it.id }.toSet()
                    val running = ids.mapNotNull { removeCodingJob(it) }
                    running.forEach { it.cancel() }
                    val stopFailures = ids.mapNotNull { runCatching { codingRuntime?.abort(it) }.exceptionOrNull() }
                    running.joinAll()
                    stopFailures.firstOrNull()?.let { throw it }
                    ids.addAll(planningChat.deleteSessionTree(projectId, target))
                    removeSessionDrafts(ids)
                    removePlanningDrafts(projectId, planIds)
                    ids.forEach { requestPins?.remove(PinConversation(it, projectId)); sessionTitles?.forget(it); unreadTracker.forget(it) }
                    _state.update { state ->
                        val rest = state.coding.sessions.filterNot { it.session.id in ids }
                        state.copy(coding = state.coding.copy(sessions = rest,
                            currentSessionId = state.coding.currentSessionId?.takeUnless { it in ids }
                                ?: rest.firstOrNull { it.session.projectId == projectId }?.session?.id))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "immunity.action.failed", e); _state.update { it.copy(notice = "Не удалось выполнить действие иммунитета. Проверьте состояние и повторите попытку.") } }
            finally { _immunityActions.update { it - key } }
        }
    }

    override fun dismissImmunityIntervention(organismId: String, proposalId: String) {
        val service = planningChat?.organisms ?: return
        scope.launch {
            try { service.dismissImmunityIntervention(organismId, proposalId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "immunity.dismiss.failed", e); _state.update { it.copy(notice = "Не удалось отклонить предложение. Проверьте состояние и повторите попытку.") } }
        }
    }

    override fun deleteCodingProject(id: String) {
        val repo = codingProjects ?: return
        while (true) {
            val pending = deletingCodingProjects.value
            if (id in pending) return
            if (deletingCodingProjects.compareAndSet(pending, pending + id)) break
        }
        scope.launch {
            try {
                sessionCreationJobs[id]?.join()
                val sessions = repo.sessions(id)
                val running = sessions.mapNotNull { removeCodingJob(it.id) }
                running.forEach { it.cancel() }
                val failures = sessions.mapNotNull { session -> runCatching { codingRuntime?.abort(session.id) }.exceptionOrNull() }
                running.joinAll()
                // The service joins plan scopes and durably tombstones their organism before
                // the project key disappears. An uncertain stop leaves the project intact.
                if (planningChat != null) planningChat.deleteProjectSessions(id)
                else {
                    failures.firstOrNull()?.let { throw it }
                    sessions.forEach { codingRuntime?.reconcile(it.id) }
                }
                sessions.forEach { requestPins?.remove(PinConversation(it.id, id)); sessionTitles?.forget(it.id) }
                repo.delete(id)
                removeSessionCreationDraft(id)
                removeSessionDrafts(sessions.map { it.id }.toSet())
                removePlanningDrafts(id)
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
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "project.delete.failed", e); _state.update { it.copy(notice = "Не удалось удалить проект. Проверьте состояние и повторите попытку.") } }
            finally { deletingCodingProjects.update { it - id } }
        }
    }

    // ---- Кодинг-сессии проекта ------------------------------------------------

    /** Explicit creation uses the same project-owned form and durable acceptance path. */
    override fun addCodingSession(engine: CodingEngine) {
        val projectId = _state.value.coding.current?.id ?: return
        val draft = sessionCreationDraft(projectId) ?: return
        draft.update(engine)
        scope.launch {
            try { draft.awaitSaved(); createCodingSession(projectId) { onOpenSession(projectId, it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.creation.draft.failed", failure, mapOf("projectId" to projectId))
                _state.update { it.copy(notice = "Не удалось сохранить выбор движка. Повторите попытку.") }
            }
        }
    }

    override fun createCodingSession(projectId: String, onCreated: (String) -> Unit) {
        val draft = sessionCreationDraft(projectId) ?: return
        val point = draft.state.value
        if (!point.loaded) return
        sessionCreationAction(projectId, "create", "Не удалось создать сессию. Повторите попытку.") {
            val repo = checkNotNull(codingProjects)
            draft.awaitSaved()
            val project = repo.all().firstOrNull { it.id == projectId }
            check(project != null && projectId !in deletingCodingProjects.value) { "Project unavailable" }
            val session = CodingSession(id = Id.new(), projectId = projectId, name = "Новая сессия",
                engine = point.value, createdAt = Id.now(),
                modelSelection = project.modelSelection ?: ProfileResolver.favoriteDefault(
                    _state.value.settings, _state.value.availableLlmProfiles, coding = true))
            repo.saveSession(session)
            // The entity now exists. A later cleanup/presentation failure must not invite another create.
            _state.update { it.copy(coding = it.coding.copy(
                sessions = listOf(CodingSessionUi(session = session)) + it.coding.sessions,
                currentSessionId = session.id)) }
            try { draft.clearIfUnchanged(point.version, _state.value.settings.defaultCodingEngine) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.creation.draft.clear.failed", failure, mapOf("projectId" to projectId, "sessionId" to session.id))
                _state.update { it.copy(notice = "Сессия создана. Не удалось очистить черновик выбора движка.") }
            }
            AppLog.info("coding", "session.created", mapOf("projectId" to projectId, "sessionId" to session.id, "backend" to point.value.name))
            try {
                if (projectId !in deletingCodingProjects.value) {
                    refreshProjectStatus(projectId)
                    onCreated(session.id)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.created.presentation.failed", failure, mapOf("projectId" to projectId, "sessionId" to session.id))
                _state.update { it.copy(notice = "Сессия создана. Откройте её в списке проекта.") }
            }
        }
    }

    override fun discardCodingSessionDraft(projectId: String, onDiscarded: () -> Unit) {
        val draft = sessionCreationDraft(projectId) ?: return
        val version = draft.state.value.version
        sessionCreationAction(projectId, "discard", "Не удалось удалить черновик. Повторите попытку.") {
            if (draft.clearIfUnchanged(version, _state.value.settings.defaultCodingEngine)) onDiscarded()
        }
    }

    private fun sessionCreationAction(projectId: String, operation: String, message: String, action: suspend () -> Unit) {
        if (closing || projectId in deletingCodingProjects.value || sessionCreationJobs[projectId]?.isActive == true) return
        _sessionCreationStatus.update { it + (projectId to SessionCreationStatus(busy = true)) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.creation.failed", failure, mapOf("projectId" to projectId, "operation" to operation))
                _sessionCreationStatus.update { it + (projectId to SessionCreationStatus(error = message)) }
            } finally {
                sessionCreationJobs.remove(projectId)
                _sessionCreationStatus.update { it + (projectId to (it[projectId] ?: SessionCreationStatus()).copy(busy = false)) }
            }
        }
        sessionCreationJobs[projectId] = job
        job.start()
    }

    override fun openPlanningChat() {
        scope.launch {
            val service = planningChat ?: return@launch
            val plan = service.store.plans().firstOrNull()

            if (plan != null) { openCodingProject(plan.projectId); selectCodingSession(plan.parentSessionId) }
        }
    }

    override fun selectCodingSession(id: String) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == id } ?: return
        // Load messages on-demand if not already loaded (optimization: openCodingProject only loads active session).
        if (selected.messages.isEmpty() && !selected.running) {
            scope.launch {
                val repo = codingProjects ?: return@launch
                val messages = repo.messages(selected.session.projectId, id)
                updateCodingSession(id) { it.copy(messages = messages) }
            }
        }
        markSessionRead(id)
        onOpenSession(selected.session.projectId, id)
    }

    override fun markSessionRead(sessionId: String) {
        val session = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val lastAgent = session.messages.lastOrNull { it.role == CodingRole.AGENT && !it.systemContext && !it.systemNotice }
            ?: return
        unreadTracker.markRead(sessionId, lastAgent.id)
    }

    override fun deleteAllCodingSessions(projectId: String) {
        val repo = codingProjects ?: return
        scope.launch {
            try {
                val sessions = repo.sessions(projectId)
                val running = sessions.mapNotNull { removeCodingJob(it.id) }
                running.forEach { it.cancel() }
                val failures = sessions.mapNotNull { runCatching { codingRuntime?.abort(it.id) }.exceptionOrNull() }
                running.joinAll()
                if (planningChat != null) planningChat.deleteProjectSessions(projectId)
                else {
                    failures.firstOrNull()?.let { throw it }
                    sessions.forEach { codingRuntime?.reconcile(it.id) }
                    sessions.forEach { repo.deleteSession(projectId, it.id) }
                }
                sessions.forEach { requestPins?.remove(PinConversation(it.id, projectId)); sessionTitles?.forget(it.id) }
                removeSessionDrafts(sessions.map { it.id }.toSet())
                removePlanningDrafts(projectId)
                _state.update { state ->
                    val remaining = state.coding.sessions.filterNot { it.session.projectId == projectId }
                    state.copy(coding = state.coding.copy(sessions = remaining,
                        currentSessionId = state.coding.currentSessionId?.takeIf { id -> remaining.any { it.session.id == id } },
                        projectStatuses = state.coding.projectStatuses - projectId))
                }
                refreshProjectStatus(projectId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "sessions.delete.failed", e); _state.update { it.copy(notice = "Не удалось удалить сессии. Проверьте состояние и повторите попытку.") } }
        }
    }

    override fun archiveCodingSession(id: String) {
        val repo = codingProjects ?: return
        val target = _state.value.coding.sessions.firstOrNull { it.session.id == id } ?: return
        scope.launch {
            if ((target.session.stageId != null || target.session.organismId != null) && planningChat != null) {
                planningChat.archiveSession(id)
                return@launch
            }
            val archived = repo.updateSession(target.session.projectId, id) { it.copy(archived = true) }
            _state.update { state ->
                state.copy(coding = state.coding.copy(
                    sessions = state.coding.sessions.map { if (it.session.id == id) it.copy(session = archived) else it },
                ))
            }
            refreshProjectStatus(target.session.projectId)
        }
    }

    override fun deleteCodingSession(id: String) {
        val repo = codingProjects ?: return
        val coding = _state.value.coding
        val target = coding.sessions.firstOrNull { it.session.id == id } ?: return
        scope.launch {
            try {
                val projectId = target.session.projectId
                val ids = repo.sessions(projectId).sessionTreeIds(id).toMutableSet()
                val planIds = planningChat?.store?.plans?.value.orEmpty().filter { it.projectId == projectId && it.parentSessionId in ids }.map { it.id }.toSet()
                val running = ids.mapNotNull { removeCodingJob(it) }
                running.forEach { it.cancel() }
                val failures = ids.mapNotNull { runCatching { codingRuntime?.abort(it) }.exceptionOrNull() }
                running.joinAll()
                if (planningChat != null) ids.addAll(planningChat.deleteSessionTree(projectId, id))
                else {
                    failures.firstOrNull()?.let { throw it }
                    ids.forEach { codingRuntime?.reconcile(it) }
                    ids.forEach { repo.deleteSession(projectId, it) }
                }
                ids.forEach { requestPins?.remove(PinConversation(it, projectId)); sessionTitles?.forget(it); unreadTracker.forget(it) }
                removeSessionDrafts(ids)
                removePlanningDrafts(projectId, planIds)
                _state.update { state ->
                    val rest = state.coding.sessions.filterNot { it.session.id in ids }
                    state.copy(coding = state.coding.copy(
                        sessions = rest,
                        currentSessionId = if (state.coding.currentSessionId in ids)
                            rest.firstOrNull { it.session.projectId == projectId }?.session?.id
                        else state.coding.currentSessionId,
                    ))
                }
                refreshProjectStatus(target.session.projectId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "session.delete.failed", e); _state.update { it.copy(notice = "Не удалось удалить сессию. Проверьте состояние и повторите попытку.") } }
        }
    }

    override fun changeCodingInteractionMode(sessionId: String, mode: CodingInteractionMode) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        scope.launch {
            try {
                val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }
                val busy = sessionId in codingJobs.value || ui?.running == true || ui?.interactions?.isNotEmpty() == true ||
                    ui?.awaitingUser == true || planningChat?.drafts?.value?.get(sessionId)?.active == true
                selected.session.changeInteractionMode(mode, busy)
                val updated = if (selected.session.organismId != null && planningChat != null)
                    planningChat.changeManagedInteractionMode(selected.session, mode)
                else updateStoredCodingSession(selected.session) { latest -> latest.changeInteractionMode(mode, busy) }
                if (updated.interactionMode != CodingInteractionMode.CODE) codingRuntime?.computerUse?.disable(sessionId)
                if (selected.session.interactionMode != updated.interactionMode) {
                    appendCodingMessage(updated, CodingMessage(Id.new(), CodingRole.AGENT,
                        "Режим сессии: ${updated.interactionMode.title}. " +
                            if (updated.researchMode) "Исходники и Git защищены от записи. Сборки и тесты доступны только через защищённый запуск проверок."
                            else "Новый режим применяется к последующим запросам.",
                        createdAt = Id.now(), systemNotice = true))
                    val project = _state.value.coding.projects.firstOrNull { it.id == updated.projectId }
                    if (project != null && codingRuntime != null) {
                        val context = codingRuntime.sessionContext(project, updated, codingProfileOf(updated))
                        appendCodingMessage(updated, CodingMessage(Id.new(), CodingRole.AGENT,
                            "Контекст после смены режима на «${updated.interactionMode.title}»\n\n$context",
                            createdAt = Id.now(), systemContext = true))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "mode.change.failed", e); _state.update { it.copy(notice = "Не удалось изменить режим. Проверьте состояние и повторите попытку.") } }
        }
    }

    override fun toggleSessionFeatureFlag(sessionId: String, flag: FeatureFlag) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        scope.launch {
            try {
                val updated = updateStoredCodingSession(selected.session) { latest ->
                    latest.copy(featureFlags = latest.featureFlags.with(flag, !latest.featureFlags.resolve(_state.value.settings.featureFlags).isEnabled(flag)))
                }
                // Sync to runtime if this is the active session
                codingRuntime?.globalFeatureFlags = _state.value.settings.featureFlags
                val flagEnabled = updated.featureFlags.resolve(_state.value.settings.featureFlags).isEnabled(flag)
                appendCodingMessage(updated, CodingMessage(Id.new(), CodingRole.AGENT,
                    if (flagEnabled) "⚡ ${flag.title}: включено для этой сессии. Оптимизации применяются к следующему запросу."
                    else "${flag.title}: выключено для этой сессии.",
                    createdAt = Id.now(), systemNotice = true))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.error("coding", "feature-flag.toggle.failed", e) }
        }
    }

    override fun sendCodingPrompt(text: String) {
        val coding = _state.value.coding
        val session = coding.currentSession ?: return
        sendCodingPromptTo(session.session.id, text)
    }

    /**
     * Запрос кодинг-агенту в произвольной сессии (можно ответить агенту
     * из фоновой сессии, не переключаясь на неё).
     * Прогоны разных сессий (в том числе разных проектов) идут параллельно.
     */
    override fun sendCodingPromptTo(sessionId: String, text: String, attachments: List<Attachment>) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (planningChat != null && (selected.planningMode || selected.stageId != null)) {
            val draft = composerDrafts[sessionId]
            val version = draft?.version
            planningChat.send(selected, text, onAccepted = {
                if (attachments.isEmpty() && version != null) clearAcceptedComposer(draft, version)
            }); return
        }
        if (text.isBlank() && attachments.isEmpty()) return
        val request = CodingRunCheckpoint(Id.new(), text.trim(), attachments, interactionMode = selected.interactionMode)
        if (!validateInput(selected, attachments, request.messageId)) return
        val composer = composerDrafts[sessionId]
        val version = composer?.version
        scope.launch {
            val saved = updateStoredCodingSession(selected) { latest ->
                require(!latest.archived && latest.interactionMode == selected.interactionMode) { "Сессия изменилась" }
                latest.copy(queuedPrompts = latest.queuedPrompts + request)
            }
            if (version != null) clearAcceptedComposer(composer, version)
            AppLog.info("coding", "input.queued", mapOf("sessionId" to sessionId, "requestId" to request.messageId))
            startQueuedPrompt(saved.id)
        }
    }

    private fun startQueuedPrompt(sessionId: String) {
        if (closing || sessionId in codingJobs.value) return
        val session = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (session.pendingRun != null || session.archived) return
        session.queuedPrompts.firstOrNull()?.let { launchCodingRun(session, it, recovering = false) }
    }

    private val clarifyingSessions = mutableSetOf<String>()

    override fun clarifyCodingSession(sessionId: String, text: String, attachments: List<Attachment>) {
        if (text.isBlank() && attachments.isEmpty()) return
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (!validateInput(ui.session, attachments, sessionId)) return
        if (ui.session.planningMode || ui.session.stageId != null) {
            sendCodingPromptTo(sessionId, text, attachments)
            return
        }
        val running = codingJobs.value[sessionId]
        if (running == null) { resumeCodingSession(sessionId, text, attachments); return }
        if (!clarifyingSessions.add(sessionId)) return
        val draft = composerDrafts[sessionId]
        val version = draft?.version
        scope.launch {
            try {
                // Acceptance uses the latest persisted checkpoint and precedes native cancellation.
                val accepted = updateStoredCodingSession(ui.session) { latest ->
                    check(codingJobs.value[sessionId] === running) { "Запуск изменился. Отправьте уточнение повторно." }
                    val request = checkNotNull(latest.pendingRun) { "Запуск ещё не готов принять уточнение." }
                    latest.copy(pendingRun = request.copy(
                        prompt = request.prompt + "\n\nУточнение пользователя: " + text.trim(),
                        attachments = (request.attachments + attachments).distinctBy { it.id }, intent = ExecutionIntent.STOP))
                }
                if (version != null) clearAcceptedComposer(draft, version)
                codingRuntime?.abort(sessionId)
                running.cancelAndJoin()
                codingRuntime?.reconcile(sessionId)
                val current = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return@launch
                launchCodingRun(current, checkNotNull(accepted.pendingRun).copy(intent = ExecutionIntent.RUN, stoppedByUser = false),
                    recovering = true, clearComposer = false,
                    additionalMessage = CodingMessage(Id.new(), CodingRole.USER, text.trim(), createdAt = Id.now(),
                        attachments = attachments.map { it.asMeta() }))
            } finally { clarifyingSessions.remove(sessionId) }
        }
    }

    override fun resumeCodingSession(sessionId: String, text: String, attachments: List<Attachment>, fromQuestionnaire: Boolean) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (!(if (fromQuestionnaire) ui.copy(interactions = emptyList()) else ui).canResume) return
        if (planningChat != null && (ui.plan != null || ui.session.planningMode || ui.session.stageId != null)) {
            val draft = composerDrafts[sessionId]
            val version = draft?.version
            planningChat.resume(ui.session, text, onAccepted = {
                if (attachments.isEmpty() && version != null) clearAcceptedComposer(draft, version)
            }); return
        }
        val request = ui.session.pendingRun ?: ui.messages.interruptedCodingRequest()?.let {
            CodingRunCheckpoint(it.id, it.text)
        } ?: return
        val instruction = text.trim()
        launchCodingRun(ui.session, request.copy(
            prompt = request.prompt + if (instruction.isNotEmpty()) "\n\nУточнение пользователя: $instruction" else "",
            attachments = (request.attachments + attachments).distinctBy { it.id }, intent = ExecutionIntent.RUN, stoppedByUser = false,
        ), recovering = true, additionalMessage = instruction.takeIf { it.isNotEmpty() || attachments.isNotEmpty() }?.let {
            CodingMessage(Id.new(), CodingRole.USER, it, createdAt = Id.now(), attachments = attachments.map { attachment -> attachment.asMeta() })
        })
    }

    private suspend fun clearAcceptedComposer(draft: io.aequicor.magicpaper.ui.components.CodingComposerDraft?, version: Long) {
        try { withContext(Dispatchers.Main.immediate) { draft?.clearIfUnchanged(version) } }
        catch (e: CancellationException) { throw e }
        catch (error: io.aequicor.magicpaper.data.storage.StorageException) { logPersistenceFailure("coding", "draft.clear.failed", error); _state.update { it.copy(notice = if (error.committed) "Сообщение сохранено. Не удалось удалить временные данные." else "Сообщение сохранено. Не удалось очистить черновик.") } }
    }

    private fun startPendingImmunityDiagnostics() {
        val service = planningChat ?: return
        for (item in _state.value.coding.sessions) {
            val organism = item.session.organismId?.let { service.organisms?.store?.organisms?.value?.get(it) } ?: continue
            val signal = organism.nextImmunityResearch(item.session, item.messages) ?: continue
            if (item.session.id in codingJobs.value || item.running) continue
            launchCodingRun(item.session, CodingRunCheckpoint("signal-${signal.id}", signal.diagnostic,
                responseId = "immunity-report-${signal.id}", interactionMode = CodingInteractionMode.RESEARCH),
                recovering = false, userInitiated = false)
        }
    }

    private suspend fun restoreCodingRuns(projects: List<CodingProject>) {
        if (codingRuntime?.supported != true) return
        val repo = codingProjects ?: return
        for (project in projects) for (session in repo.sessions(project.id)) {
            val request = session.pendingRun ?: continue
            if (request.intent != ExecutionIntent.RUN || session.archived || session.planningMode || session.stageId != null) continue
            // Child sessions (SESSION kind with organism) are recovered by their parent's
            // recoverUnknownChildren() once the parent's withScope registers its handle.
            if (session.organismId != null && session.sessionKind == SessionKind.SESSION) continue
            val response = repo.messages(project.id, session.id).firstOrNull { it.id == request.responseId }
            if (response != null) {
                // The reply may have reached disk just before the checkpoint was cleared.
                updateStoredCodingSession(session) { it.copy(pendingRun = if (response.failed) request.copy(intent = ExecutionIntent.STOP) else null) }
                continue
            }
            if (_state.value.coding.sessions.none { it.session.id == session.id }) {
                val ui = CodingSessionUi(session, repo.messages(project.id, session.id))
                _state.update { it.copy(coding = it.coding.copy(sessions = it.coding.sessions + ui)) }
            }
            launchCodingRun(session, request, recovering = true, userInitiated = false)
        }
    }

    private suspend fun updateStoredCodingSession(session: CodingSession, change: (CodingSession) -> CodingSession): CodingSession {
        codingSessionLocks.update { if (session.id in it) it else it + (session.id to Mutex()) }
        return codingSessionLocks.value.getValue(session.id).withLock {
            val repo = codingProjects ?: error("Хранилище сессий недоступно")
            repo.updateSession(session.projectId, session.id, change).also { saved ->
                updateCodingSession(saved.id) { it.copy(session = saved) }
            }
        }
    }

    private suspend fun appendCodingMessage(session: CodingSession, message: CodingMessage) {
        val repo = codingProjects ?: return
        if (planningChat != null) planningChat.append(session.projectId, session.id, message)
        else {
            val history = repo.messages(session.projectId, session.id)
            if (history.none { it.id == message.id }) repo.saveMessages(session.projectId, session.id, history + message)
        }
        if (session.sessionKind == SessionKind.IMMUNITY && message.id.startsWith("immunity-report-")) {
            val organism = session.organismId?.let { planningChat?.organisms?.store?.organisms?.value?.get(it) }
            val signal = organism?.signals?.firstOrNull { "immunity-report-${it.id}" == message.id }
            if (signal != null && signal.sender !in organism.historyDeletedIds) {
                val recipient = repo.sessions(session.projectId).firstOrNull { it.id == signal.sender }
                if (recipient != null) planningChat?.append(session.projectId, recipient.id, message.copy(
                    id = message.id + "-received", origin = MessageOrigin.SESSION,
                    route = MessageRoute(SessionAddress(session.id, session.name, "Иммунитет"),
                        SessionAddress(recipient.id, recipient.name, "Сессия"), kind = "Результат диагностики")))
            }
        }
        val history = repo.messages(session.projectId, session.id)
        updateCodingSession(session.id) {
            val savedDraft = message.timelineId != null && message.timelineId == it.draft.timelineId
            it.copy(messages = history, draft = if (savedDraft) CodingDraft() else it.draft)
        }
    }

    private fun validateInput(session: CodingSession, attachments: List<Attachment>, requestId: String): Boolean {
        if (attachments.any { it.kind == AttachmentKind.IMAGE }) {
            val profile = codingProfileOf(session)?.forCoding()
            if (profile != null && !ModelCapabilities.resolve(profile.provider, profile.modelId, profile.baseUrl).vision) {
                AppLog.info("coding", "run.input.rejected", mapOf(
                    "sessionId" to session.id, "requestId" to requestId,
                    "model" to profile.modelId, "reason" to "image-input-unsupported"))
                _state.update { it.copy(notice = "Выбранная модель не поддерживает изображения. Выберите модель с поддержкой изображений и повторите отправку.") }
                return false
            }
        }
        return true
    }

    private fun launchCodingRun(session: CodingSession, checkpoint: CodingRunCheckpoint, recovering: Boolean, additionalMessage: CodingMessage? = null, userInitiated: Boolean = true, clearComposer: Boolean = true) {
        val runtime = codingRuntime ?: return
        val project = _state.value.coding.projects.firstOrNull { it.id == session.projectId } ?: return
        // A cancelled job still owns the session while its runtime and saved output
        // are being cleaned up. Its finally block releases this entry.
        if (closing || session.projectId in deletingCodingProjects.value || session.id in codingJobs.value) return
        if (userInitiated && !validateInput(session, checkpoint.attachments, checkpoint.messageId)) return
        var request = checkpoint.copy(
            responseId = checkpoint.responseId.ifBlank { Id.new() },
            responseTimelineId = checkpoint.responseTimelineId.ifBlank { Id.new() },
        )
        val recorder = CodingRunRecorder(CodingImageInvocation(
            sessionId = session.id,
            invocationId = request.runId,
            inputMessageId = request.messageId,
            responseMessageId = request.responseId,
            responseTimelineId = request.responseTimelineId,
        ))
        val composer = composerDrafts[session.id]
        val composerVersion = composer?.version.takeIf { clearComposer && session.queuedPrompts.none { it.messageId == checkpoint.messageId } }
        val operationFields = mapOf("projectId" to project.id, "sessionId" to session.id, "requestId" to request.messageId, "operationId" to request.runId)
        AppLog.info("coding", "run.started", operationFields)
        AppLog.debug("coding", "run.strategy", operationFields + ("reason" to if (recovering) "restore-checkpoint" else "accepted-input"))
        val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            try {
                if (userInitiated) planningChat?.prepareManagedUserTurn(session, checkpoint.messageId)
                var current = updateStoredCodingSession(session) { latest ->
                    require(latest.interactionMode == session.interactionMode) { "Режим сессии изменился. Отправьте запрос повторно." }
                    request = request.copy(interactionMode = request.interactionMode ?: latest.interactionMode)
                    latest.namedFromPrompt(request.prompt).copy(pendingRun = request,
                        queuedPrompts = latest.queuedPrompts.filterNot { it.messageId == request.messageId }).forPendingRun()
                }
                if (codingProjects!!.messages(project.id, session.id).none { it.id == request.messageId }) {
                    appendCodingMessage(session, CodingMessage(request.messageId, CodingRole.USER,
                        request.prompt, createdAt = Id.now(), attachments = request.attachments.map { it.asMeta() },
                        images = request.attachments.mapNotNull { it.asCodingInputImage(checkNotNull(recorder.imageInvocation)) }))
                }
                additionalMessage?.let { appendCodingMessage(session, it) }
                if (userInitiated && composerVersion != null) clearAcceptedComposer(composer, composerVersion)
                updateCodingSession(session.id) { it.copy(running = true, draft = recorder.draft(active = true)) }
                if (recovering) runtime.reconcile(session.id)
                var prompt = if (recovering) "Продолжи незавершённую работу в этой сессии. Сначала проверь сохранённый контекст, " +
                    "результаты команд и состояние файлов; учитывай уже сделанное и не повторяй завершённые действия.\n\n" + request.prompt else request.prompt
                if (current.sessionKind == SessionKind.IMMUNITY) {
                    prompt = immunityResearchPrompt(prompt, planningChat?.organisms?.store?.organisms?.value?.get(current.organismId),
                        planningChat?.store?.plans?.value.orEmpty())
                }
                if (current.needsHistorySeed || current.sessionKind == SessionKind.IMMUNITY) {
                    prompt = researchContextSeed(codingProjects!!.messages(project.id, session.id),
                        codingProfileOf(current)?.advanced?.contextMessages ?: 20, request.messageId) + prompt
                }
                var ended = false
                recorder.recordDrafts(runtime.run(project, current, prompt, codingProfileOf(current), request.attachments), onEvent = { event ->
                    if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                        // Save the native conversation before the first command, not at the end of the turn.
                        current = updateStoredCodingSession(session) { it.copy(piSessionId = event.sessionId, needsHistorySeed = false) }
                    }
                    if (event is CodingEvent.Finished) ended = true
                }).collect { draft ->
                    updateCodingSession(session.id) { it.copy(draft = draft) }
                }
                if (!ended) recorder.apply(CodingEvent.Failed("Выполнение прервано. Нажмите «Продолжить»."))
                val response = recorder.message(request.responseId, Id.now())
                appendCodingMessage(session, response)
                updateStoredCodingSession(session) { latest -> latest.copy(pendingRun =
                    if (response.failed || latest.pendingRun?.intent == ExecutionIntent.STOP) latest.pendingRun?.copy(intent = ExecutionIntent.STOP) else null) }
                AppLog.info("coding", "run.finished", operationFields + ("outcome" to if (response.failed) "failed" else "completed"))
            } catch (e: CancellationException) {
                AppLog.info("coding", "run.cancelled", operationFields + ("reason" to if (closing) "application-close" else "user-stop"))
                try { withContext(NonCancellable) { runtime.abort(session.id) } }
                catch (cancelled: CancellationException) {
                    // Preserve the primary cancellation after the remaining cleanup.
                    AppLog.info("coding", "run.abort.cancelled", operationFields)
                }
                catch (cleanup: Exception) {
                    AppLog.error("coding", "run.abort.failed", cleanup, operationFields)
                    _state.update { it.copy(notice = "Не удалось подтвердить остановку процесса. Проверьте состояние сессии.") }
                }
                // Shutdown retains RUN; an explicit stop has already persisted STOP.
                if (recorder.timeline().isNotEmpty()) withContext(NonCancellable) {
                    recorder.apply(CodingEvent.Failed(if (closing) "Работа продолжится после запуска приложения." else "Работа остановлена. Нажмите «Продолжить»."))
                    // Partial output is not a completed response checkpoint.
                    try { appendCodingMessage(session, recorder.message(Id.new(), Id.now())) }
                    catch (cancelled: CancellationException) {
                        AppLog.info("coding", "run.partial-save.cancelled", operationFields)
                    }
                    catch (failure: Exception) {
                        AppLog.error("coding", "run.partial-save.failed", failure, operationFields)
                        _state.update { it.copy(notice = "Не удалось сохранить промежуточный результат. Проверьте сессию после запуска.") }
                    }
                }
                throw e
            } catch (e: Exception) {
                AppLog.error("coding", "run.failed", e, operationFields)
                recorder.apply(CodingEvent.Failed("Не удалось продолжить работу. Проверьте подключение и состояние сессии."))
                try {
                    appendCodingMessage(session, recorder.message(request.responseId, Id.now()))
                    updateStoredCodingSession(session) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP)) }
                } catch (storageError: Exception) {
                    if (storageError is CancellationException) throw storageError
                    AppLog.error("coding", "run.failure-save.failed", storageError, operationFields)
                    _state.update { it.copy(notice = "Не удалось сохранить состояние сессии. Последнее сохранённое состояние доступно после запуска.") }
                }
            } finally {
                fun clearRun() {
                    codingJobs.update { it - session.id }
                    updateCodingSession(session.id) { it.copy(running = false, draft = idleCodingDraft) }
                }
                // Keep cleanup atomic with respect to UI starts. A shutdown hook can
                // run while AWT is exiting, so shutdown must not wait for the UI thread.
                if (closing) clearRun()
                else withContext(NonCancellable + Dispatchers.Main.immediate) { clearRun(); startPendingImmunityDiagnostics(); startQueuedPrompt(session.id) }
                withContext(NonCancellable) {
                    runCatching { refreshProjectStatus(project.id) }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        AppLog.error("coding", "run.refresh.failed", failure, operationFields)
                        _state.update { it.copy(notice = "Не удалось прочитать состояние сессии. Повторите открытие проекта.") }
                    }
                }
            }
        }
        codingJobs.update { it + (session.id to job) }
        job.start()
    }

    /** Прервать прогон текущей сессии (процесс её агента). */
    override fun abortCodingRun() {
        val id = _state.value.coding.currentSession?.session?.id ?: return
        abortCodingSession(id)
    }

    override fun abortCodingSession(sessionId: String) {
        codingRuntime?.computerUse?.disable(sessionId)
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (ui.session.organismId != null && planningChat != null) {
            planningChat.stopManagedSession(sessionId)
            return
        }
        if (planningChat != null && (ui.plan != null || ui.session.planningMode || ui.session.stageId != null)) {
            planningChat.cancelRequest(ui.session.parentSessionId ?: sessionId)
            ui.plan?.let { planningChat.control(it.id, "stop") }
            return
        }
        // Mark as stopped in the UI state immediately (before the async persistence)
        // so that refreshInteractions() cannot create a RECOVER_RUN interaction in the
        // window between the run job clearing `running` and the stored session update.
        updateCodingSession(sessionId) {
            it.copy(session = it.session.copy(pendingRun = it.session.pendingRun?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true)))
        }
        scope.launch {
            updateStoredCodingSession(ui.session) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true)) }
            codingRuntime?.abort(sessionId)
            codingJobs.value[sessionId]?.cancel()
        }
    }

    /** Graceful application exit must not turn resumable work into a user stop. */
    override suspend fun shutdownCoding() {
        closing = true
        val jobs = codingJobs.value.values.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        scope.cancel()
    }

    override fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) {
        scope.launch { codingRuntime?.respondApproval(id, decision) }
    }

    override fun enableComputerUse(sessionId: String, access: io.aequicor.magicpaper.domain.ComputerAccess) {
        val session = state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (session.running || session.session.stageId != null || session.session.planningMode || session.session.researchMode) return
        scope.launch {
            codingRuntime?.computerUse?.let { computer ->
                computer.enable(sessionId, access)
                computer.preview(sessionId)
            }
        }
    }

    override fun disableComputerUse(sessionId: String) { codingRuntime?.computerUse?.disable(sessionId) }
    override fun previewComputerUse(sessionId: String) { scope.launch { codingRuntime?.computerUse?.preview(sessionId) } }
    override fun openComputerSystemSettings() { codingRuntime?.computerUse?.openSystemSettings() }

    /** Точечное обновление сессии в состоянии (по id, где бы она ни лежала). */
    private fun updateCodingSession(sessionId: String, transform: (CodingSessionUi) -> CodingSessionUi) {
        _state.update { st ->
            st.copy(
                coding = st.coding.copy(
                    sessions = st.coding.sessions.map {
                        if (it.session.id == sessionId) withUnread(transform(it)) else it
                    },
                ),
            )
        }
    }


    override suspend fun reload() {
        if (!started) { start(); return }
        val projects = codingProjects?.all().orEmpty()
        val sessions = loadCodingSessions(projects)
        _state.update { old -> old.copy(coding = old.coding.copy(projects = projects, sessions = sessions,
            current = old.coding.current?.takeIf { selected -> projects.any { it.id == selected.id } },
            currentSessionId = old.coding.currentSessionId?.takeIf { selected -> sessions.any { it.session.id == selected } })) }
    }

    override suspend fun activate(projectId: String?, sessionId: String?) {
        visible.value = true
        if (projectId == null || _state.value.coding.projects.none { it.id == projectId }) {
            _state.update { it.copy(coding = it.coding.copy(current = null, currentSessionId = null)) }
            return
        }
        openCodingProject(projectId)
        // The route may target a session other than the project's most recent one.
        // Load its history after openCodingProject has rebuilt the projections.
        if (sessionId != null) {
            val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId && it.session.projectId == projectId }
            if (selected != null && selected.messages.isEmpty() && !selected.running) {
                val messages = codingProjects?.messages(projectId, sessionId).orEmpty()
                updateCodingSession(sessionId) { it.copy(messages = messages) }
            }
        }
        currentCoroutineContext().ensureActive()
        _state.update { state -> state.copy(coding = state.coding.copy(currentSessionId = sessionId?.takeIf { id ->
            state.coding.sessions.any { it.session.id == id && it.session.projectId == projectId }
        })) }
    }
    override suspend fun applySettings(settings: AppSettings): Result<Unit> = planningChat?.organisms?.saveSettingsAndApplyLimits(settings)
        ?: run { settingsRepo.save(settings); Result.success(Unit) }
    override suspend fun clearProfileOverrides(id: String) {
        for (item in _state.value.coding.sessions.filter { it.session.llmProfileId == id }) {
            codingProjects?.updateSession(item.session.projectId, item.session.id) { it.copy(llmProfileId = null) }
                ?.let { saved -> updateCodingSession(saved.id) { it.copy(session = saved) } }
        }
    }
    /** Drain this application's writers while retaining its reusable supervisor. */
    suspend fun prepareForReset() {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        closing = true
        val children = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it != caller }?.toList().orEmpty()
        children.forEach { it.cancel() }
        children.forEach { it.join() }
        resetDrafts()
        pinObserver = null
        visible.value = false
        codingJobs.value = emptyMap()
        sessionTitles?.clear()
        interactionDecisions.clear()
        interactionSubmitting.clear()
        interactionErrors.clear()
        requestedInputRecovery.clear()
        decisionStorageRecovery = null
        _state.value = CodingState()
        started = false
        closing = false
    }

    /** Called after the application has cancelled and joined the draft writers. */
    suspend fun resetDrafts() { composerDrafts.values.forEach { it.revoke() }
        questionnaireSessions.values.forEach { it.second.revoke() }
        sessionCreationDrafts.values.forEach { it.revoke() }
        sessionCreationDrafts.clear()
        sessionCreationJobs.clear()
        deletedDraftProjectIds.clear()
        _sessionCreationStatus.value = emptyMap()
        composerDrafts.clear()
        questionnaireSessions.clear()
        deletedDraftSessionIds.clear()
        _questionnaireDrafts.value = emptyMap() }

    override suspend fun close() {
        try {
            sessionCreationJobs.values.toList().joinAll()
            sessionCreationDrafts.values.forEach { it.awaitSaved() }
            composerDrafts.values.forEach { it.awaitSaved() }
            questionnaireSessions.values.forEach { it.second.awaitSaved() }
        } finally { shutdownCoding() }
    }
}
