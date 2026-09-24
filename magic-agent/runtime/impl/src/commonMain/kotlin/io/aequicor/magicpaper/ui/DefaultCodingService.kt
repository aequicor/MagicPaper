package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.planning.command
import io.aequicor.magicpaper.data.coding.CodingCommandRejected

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.phase
import kotlin.time.TimeSource
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
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
    private val codingProjects: CodingProjectOwner? = null,
    private val dirPicker: ProjectDirPicker? = null,
    private val gateway: LlmGateway? = null,
    val planningChat: PlanningChatService? = null,
    val requestPins: RequestPinService? = null,
    val usage: UsageLedger,
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onOpenSession: (String?, String?) -> Unit = { _, _ -> },
    private val draftRepository: io.aequicor.magicpaper.data.storage.DraftRepository = io.aequicor.magicpaper.data.storage.InMemoryDraftRepository(),
    private val draftBlobs: io.aequicor.magicpaper.data.storage.DraftBlobStore = io.aequicor.magicpaper.data.storage.InMemoryDraftBlobStore(),
    private val taskWorktrees: TaskWorktreeService? = null,
    private val removePluginDrafts: suspend (projectId: String, planIds: Set<String>?) -> Unit = { _, _ -> },
    private val archiveClock: () -> Long = Id::now,
    private val archiveTicks: Flow<Unit> = sessionArchiveTicks(),
    override val mediaGeneration: MediaGenerationService? = null,
    private val settingsCommands: SettingsCommands,
    private val models: CodingModelCatalog? = null,
    val plans: PlanUsageMonitor? = null,
) : CodingService {
    private val _state = MutableStateFlow(CodingState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
        AppLog.error("coding", "background.failed", error)
        _state.update { it.copy(notice = "Не удалось выполнить действие с сессией. Проверьте её состояние и повторите попытку.") }
    })
    override val state: StateFlow<CodingState> = _state.asStateFlow()
    override fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        scope.launch {
            try {
                rememberLastUsedSession(acceptCodingSession(selected.session, CodingMachine.Intent.SetMediaTool(CodingMachine.ref(selected.session), kind, enabled)))
                AppLog.info("coding", "media.policy.changed", mapOf("sessionId" to sessionId,
                    "kind" to kind.name, "enabled" to enabled.toString()))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "media.policy.failed", failure, mapOf("sessionId" to sessionId))
                _state.update { it.copy(notice = "Не удалось сохранить параметры медиа. Повторите попытку.") }
            }
        }
    }
    val sessionTitles = if (codingProjects != null && gateway != null)
        SessionTitleService(codingProjects, profileRepo, settingsRepo, gateway, scope) else null
    val unreadTracker = UnreadTracker(store, json, workerDispatcher) { error ->
        AppLog.error("coding", "read-marker.load.failed", error)
        _state.update { it.copy(notice = "Не удалось загрузить отметки прочтения. Перезапустите приложение, чтобы повторить загрузку.") }
    }
    init {
        // Готовое название задачи появляется в списке сразу, а не после следующей перезагрузки журналов.
        sessionTitles?.let { titles -> scope.launch {
            titles.titles.collect { ready ->
                if (ready.isEmpty()) return@collect
                _state.update { state ->
                    val current = state.coding.sessions
                    val updated = current.map { item ->
                        val title = ready[item.session.id] ?: return@map item
                        if (item.session.shortTitle == title) item else item.copy(session = item.session.copy(shortTitle = title))
                    }
                    // Список меняет ссылку всегда, поэтому сравнивают элементы: иначе каждая публикация
                    // названия вызвала бы лишнюю реконпозицию всего журнала.
                    if (updated.indices.all { updated[it] === current[it] }) state
                    else state.copy(coding = state.coding.copy(sessions = updated))
                }
            }
        } }

    }
    override fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean) {
        _state.update { it.copy(settings = settings, llmProfiles = profiles, subscriptionAvailable = subscriptionAvailable, subscriptionSignedIn = subscriptionSignedIn) }
        // Automation policy is owned by start/applySettings, not asynchronous UI configuration echoes.
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
    private val recoveryJobs = mutableMapOf<CodingRecovery, Job>()
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
    /**
     * The conversation a person last worked with in each project, kept across restarts: a new session there starts
     * with its parameters. Configuring a session, sending it a task and creating it update the mark; opening one
     * does not, and neither does a background run.
     */
    private fun lastUsedSessionId(projectId: String): String? = store.read(lastUsedSessionKey(projectId))
    private fun rememberLastUsedSession(session: CodingSession) {
        if (!session.isConversation) return
        try { store.write(lastUsedSessionKey(session.projectId), session.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("coding", "session.last-used.save.failed", failure, mapOf("projectId" to session.projectId, "sessionId" to session.id))
            _state.update { it.copy(notice = "Не удалось запомнить параметры сессии для новых сессий.") }
        }
    }
    private fun lastUsedSessionKey(projectId: String) = "coding-session-last-used:$projectId"

    private suspend fun removeSessionCreationDraft(projectId: String) {
        deletedDraftProjectIds += projectId
        sessionCreationDrafts.remove(projectId)?.revoke()
        _sessionCreationStatus.update { it - projectId }
        try { store.delete(lastUsedSessionKey(projectId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLog.error("coding", "session.last-used.remove.failed", failure, mapOf("projectId" to projectId)) }
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
                            val source = session.session.pendingRun?.messageId
                                ?: requireNotNull(session.messages.interruptedCodingRequest()).id
                            // Leaving it stopped is itself the user's explicit review of the uncertain outcome;
                            // without the recorded decisions, its working folder stays busy for every other session.
                            val decided = acknowledgeNativeRecoveryQuietly(session.session, session.session.pendingRun?.runId)
                            acceptCodingSession(session.session,
                                CodingMachine.Intent.DeferRecovery(CodingMachine.ref(session.session), source,
                                    decidedAttempt = decided.attempt, decisionId = decided.decisionId,
                                    decidedNoDispatch = decided.proof))
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
    /** The last Git answer per project, shown until a fresh probe replaces it. */
    private val worktreeAvailabilities = MutableStateFlow<Map<String, WorktreeAvailability>>(emptyMap())
    private val worktreeProbes = MutableStateFlow<Set<String>>(emptySet())
    private val closingState = MutableStateFlow(false)
    private var closing: Boolean
        get() = closingState.value
        set(value) { closingState.value = value }
    private val idleCodingDraft = CodingDraft()
    private val statusRecencySaveFailure = "Не удалось сохранить порядок сессий. Повторите изменение статуса."
    private fun removeCodingJob(id: String): Job? = codingJobs.getAndUpdate { it - id }[id]


    private var started = false
    override suspend fun start() {
        if (started) return
        started = true
        closing = false
        observePins()
        val settings = settingsRepo.load()
        val profiles = profileRepo.load()
        val projects = AppLog.phase("coding", "start.projects") { codingProjects?.all().orEmpty() }
        val sessions = AppLog.phase("coding", "start.sessions", mapOf("count" to projects.size.toString())) { loadCodingSessions(projects) }
        _state.update { it.copy(settings = settings, llmProfiles = profiles,
            coding = it.coding.copy(projects = projects, sessions = sessions, projectStatuses = codingStatusSnapshot(projects, sessions),
                nativeModelEngines = codingRuntime?.modelSources?.keys.orEmpty())) }
        AppLog.info("coding", "sessions.listed", mapOf("count" to projects.size.toString(), "sessionsCount" to sessions.size.toString()))
        projects.forEach(::refreshWorktreeAvailability)
        when (val policy = settingsCommands.runtimePolicy()) {
            is SettingsRuntimePolicy.Confirmed -> codingRuntime?.computerUse?.configure(policy.settings.computerAccess, policy.settings.applicationAccess)
            SettingsRuntimePolicy.Unconfirmed -> {
                codingRuntime?.computerUse?.invalidatePolicy()
                _state.update { it.copy(notice = SettingsRuntimeUnconfirmed().message) }
            }
        }
        codingRuntime?.globalFeatureFlags = settings.featureFlags
        knownImmunitySignals = planningChat?.organisms?.store?.organisms?.value?.values.orEmpty().flatMap { it.signals }.map { it.id }.toSet()
        observeRuntime()
        refreshCodingEngines()
        AppLog.phase("coding", "start.restore") { restoreCodingRuns(projects) }
        observeCodingJournal()
        observeAutoArchive()
    }

    private var journalObserver: Job? = null
    private var journalFailureObserver: Job? = null
    private fun observeCodingJournal() {
        val owner = codingProjects ?: return
        if (journalObserver != null) return
        val removed = owner.states.value.mapValues { it.value.removedSessions }.toMutableMap()
        journalObserver = scope.launch {
            owner.states.collect { all ->
                val projecting = TimeSource.Monotonic.markNow()
                val savedProjects = all.values.filterNot { it.deleted }.mapNotNull { it.project }.sortedByDescending { it.createdAt }
                val sessions = all.values.flatMap { it.sessions.values }.sortedByDescending { it.createdAt }
                _state.update { state ->
                    val old = state.coding.sessions.associateBy { it.session.id }
                    val projected = sessions.map { saved ->
                        val previous = old[saved.id] ?: withWorktreeAvailability(CodingSessionUi(saved))
                        withUnread(withPlanningState(previous.copy(session = saved,
                            messages = all[saved.projectId]?.histories?.get(saved.id).orEmpty())))
                    }
                    state.copy(coding = state.coding.copy(projects = savedProjects, sessions = projected,
                        current = state.coding.current?.let { selected -> savedProjects.firstOrNull { it.id == selected.id } },
                        currentSessionId = state.coding.currentSessionId?.takeIf { id -> sessions.any { it.id == id } }))
                }
                val projected = projecting.elapsedNow().inWholeMilliseconds
                val projectedFields = mapOf("count" to sessions.size.toString(), "elapsedMs" to projected.toString())
                // Runs after every saved change: only a slow rebuild of the list reaches INFO.
                if (projected >= SLOW_PROJECTION_MILLIS) AppLog.info("coding", "sessions.projected", projectedFields)
                else AppLog.debug("coding", "sessions.projected", projectedFields)
                for ((projectId, aggregate) in all) {
                    val fresh = aggregate.removedSessions - removed[projectId].orEmpty()
                    removed[projectId] = aggregate.removedSessions
                    for (id in fresh) {
                        try {
                            removeSessionDrafts(setOf(id)); requestPins?.remove(PinConversation(id, projectId))
                            sessionTitles?.forget(id); unreadTracker.forget(id); mediaGeneration?.deleteSession(id)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            AppLog.error("coding", "deleted-session.cleanup.failed", mapOf("projectId" to projectId, "sessionId" to id,
                                "causeType" to failure::class.simpleName.orEmpty()))
                            _state.update { it.copy(notice = "Сессия удалена. Не удалось очистить временные данные.") }
                        }
                    }
                }
            }
        }
        journalFailureObserver = scope.launch { owner.failures.collect { failures ->
            failures.values.lastOrNull()?.let { notice -> _state.update { it.copy(notice = notice) } }
        } }
    }

    private fun observeAutoArchive() {
        val repo = codingProjects ?: return
        scope.launch {
            combine(combine(state, codingJobs) { current, jobs -> current.coding.sessions.map {
                it.session to (current.coding.readyForArchive(it) && it.session.id !in jobs)
            } }
                .distinctUntilChanged(), archiveTicks.onStart { emit(Unit) }) { sessions, _ -> sessions }
                .collect { sessions ->
                    for ((snapshot, _) in sessions) {
                        if (closing) return@collect
                        try {
                            val ui = state.value.coding.sessions.firstOrNull { it.session.id == snapshot.id } ?: continue
                            val ready = readyForAutoArchive(ui.session.id)
                            val now = archiveClock()
                            val since = ui.session.archiveReadySince
                            if ((!ready && since == null) || ui.session.archived) continue
                            val due = ready && since != null && now - since >= CODING_ARCHIVE_DELAY
                            if (ready && since != null && !due) continue
                            val saved = repo.acceptSession(ui.session, CodingMachine.Fact.ArchiveReadinessObserved(
                                CodingMachine.ref(ui.session), ui.session, ready, now, CODING_ARCHIVE_DELAY))
                            if (due && saved == ui.session && saved.organismId != null &&
                                readyForAutoArchive(saved.id)) {
                                val organisms = planningChat?.organisms ?: continue
                                organisms.setArchiveVisibility(saved, true) { readyForAutoArchive(saved.id) }
                            }
                            val committed = if (saved.organismId != null && due)
                                repo.sessions(saved.projectId).firstOrNull { it.id == saved.id } ?: continue else saved
                            _state.update { current -> current.copy(coding = current.coding.copy(sessions = current.coding.sessions.map {
                                if (it.session == ui.session) it.copy(session = committed) else it
                            })) }
                            if (committed.archived && !ui.session.archived) {
                                AppLog.info("coding", "session.auto-archived", mapOf("sessionId" to saved.id))
                                refreshProjectStatus(saved.projectId)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            AppLog.error("coding", "session.auto-archive.failed", failure, mapOf("sessionId" to snapshot.id))
                            _state.update { it.copy(notice = "Не удалось архивировать сессию. Повторная попытка будет выполнена автоматически.") }
                        }
                    }
                }
        }
    }
    private fun readyForAutoArchive(id: String): Boolean {
        val coding = state.value.coding
        val item = coding.sessions.firstOrNull { it.session.id == id } ?: return false
        return id !in codingJobs.value && id !in changingHistory && coding.readyForArchive(item)
    }
    private fun observeRuntime() {
        // Screens consume one display state; these owners keep observing while no screen exists.
        scope.launch { usage.state.collect { archive ->
            _state.update { it.copy(coding = it.coding.copy(usageContexts = archive.contexts)) }
        } }
        models?.let { catalog -> scope.launch { catalog.snapshots.collect { snapshots ->
            _state.update { it.copy(coding = it.coding.copy(modelCatalogs = snapshots)) }
        } } }
        requestPins?.let { pins -> scope.launch { pins.groups.collect { groups ->
            _state.update { it.copy(coding = it.coding.copy(requestPins = groups)) }
        } } }
        requestPins?.let { pins -> scope.launch {
            kotlinx.coroutines.flow.combine(pins.failures, state) { failures, state ->
                val session = state.coding.currentSession?.session
                val key = session?.let { PinConversation(it.id, it.projectId) }
                key to (failures[key] ?: failures[PinConversation("")])
            }.distinctUntilChanged().collect { (_, message) ->
                if (message != null) _state.update { it.copy(notice = message) }
            }
        } }
        mediaGeneration?.let { media -> scope.launch { media.state.collect { connections ->
            _state.update { it.copy(coding = it.coding.copy(mediaConnections = connections)) }
        } } }
        scope.launch { _immunityActions.collect { actions ->
            _state.update { it.copy(coding = it.coding.copy(immunityActions = actions)) }
        } }
        scope.launch { _quarantineRecovery.collect { recovery ->
            _state.update { it.copy(coding = it.coding.copy(quarantineRecovery = recovery)) }
        } }
        scope.launch { _questionnaireDrafts.collect { drafts ->
            _state.update { it.copy(coding = it.coding.copy(questionnaireDrafts = drafts)) }
        } }
        // Status recency belongs to the durable session, not to the Compose lifetime.
        // Initial observation seeds legacy sessions without making them look newly active.
        codingProjects?.let { scope.launch {
            _state.collect { current ->
                current.coding.sessions.forEach { item ->
                    val observed = item.status
                    if (item.session.lastStatus == observed) return@forEach
                    try {
                        acceptCodingSession(item.session, CodingMachine.Fact.StatusObserved(CodingMachine.ref(item.session), observed, Id.now()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        AppLog.error(
                            "coding",
                            "session.status-recency.save.failed",
                            failure,
                            mapOf("sessionId" to item.session.id),
                        )
                        _state.update {
                            it.copy(notice = statusRecencySaveFailure)
                        }
                    }
                }
            }
        } }
        taskWorktrees?.let { worktrees -> scope.launch {
            worktrees.changes.collect {
                val saved = _state.value.coding.projects.flatMap { codingProjects?.sessions(it.id).orEmpty() }.associateBy { it.id }
                _state.update { state -> state.copy(coding = state.coding.copy(sessions = state.coding.sessions.map { ui ->
                    saved[ui.session.id]?.let { ui.copy(session = it) } ?: ui
                })) }
            }
        } }

        scope.launch { _state.collect { refreshInteractions() } }
        codingRuntime?.let { runtime -> scope.launch { runtime.questionnaires.collect { refreshInteractions() } } }
        planningChat?.organisms?.let { service -> scope.launch {
            service.store.organisms.collect { organisms ->
                _state.update { it.copy(coding = it.coding.copy(organisms = organisms)) }
            }
        } }
        planningChat?.let { service -> scope.launch {
            launch {
                service.failureEvents.filterNotNull().collect {
                    _state.update { state -> state.copy(notice = "Не удалось выполнить действие планирования. Проверьте состояние плана.") }
                }
            }
            val planning = combine(service.states, service.store.plans, service.drafts, service.execution.live, service.store.runUi()) { states, plans, drafts, live, runs ->
                CodingPlanningState(states, plans, drafts, live, runs = runs)
            }
            combine(planning, service.sessions, service.persistenceErrors, service.unsavedInputs, codingJobs) { display, sessions, errors, unsaved, jobs ->
                PlanningSessionSnapshot(display.copy(sessions = sessions, persistenceErrors = errors, unsavedInputs = unsaved),
                    jobs.filterValues { it.isActive }.keys)
            }.collect { snapshot ->
                _state.update { state ->
                    val coding = projectCodingPlanning(state.coding, snapshot)
                    if (coding === state.coding) state else state.copy(coding = coding)
                }
                refreshInteractions()
            }
        } }
        codingRuntime?.computerUse?.let { computer -> scope.launch {
            computer.state.collect { value ->
                _state.update { it.copy(coding = it.coding.copy(computer = value, computerSupported = computer.supported, applicationSupported = computer.applicationSupported)) }
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
                // A restore projects saved titles and never starts a model request.
                val histories = buildMap {
                    for (session in stored) {
                        if (session.id !in old || session.organismId != null || session.stageId != null || session.planningMode ||
                            service.store.plans.value.any { it.parentSessionId == session.id })
                            put(session.id, repo.messages(session.projectId, session.id))
                    }
                }
                val snapshot = planningSessionSnapshot()
                _state.update { state ->
                    // Reads suspend; project onto the latest draft after they finish.
                    val merged = mergeStoredCodingSessions(state.coding.sessions, stored, storedProjectIds, histories)
                        .map { withUnread(snapshot?.let { inputs -> projectPlanningSession(it, inputs) } ?: it) }
                    // Skip state mutation when no session reference changed —
                    // prevents unnecessary Compose recomposition.
                    if (merged.size == state.coding.sessions.size &&
                        merged.indices.all { i -> merged[i] === state.coding.sessions[i] }) state
                    else state.copy(coding = state.coding.copy(sessions = merged))
                }
                val now = service.organisms?.store?.organisms?.value?.values.orEmpty().flatMap { it.signals }.map { it.id }.toSet()
                liveImmunitySignals += now - knownImmunitySignals
                knownImmunitySignals = now
                startPendingImmunityDiagnostics()
            }
        } }
        planningChat?.let { service -> scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(service.drafts, service.execution.live) { _, _ -> Unit }.sample(50).collect {
                // Sampling delays delivery. Use current control state so an older streamed
                // observation cannot restore a question, plan or draft after it was cleared.
                val snapshot = planningSessionSnapshot() ?: return@collect
                // Live activity updates statuses using saved history already in memory.
                // Only service.changes reloads persisted messages.
                _state.update { state ->
                    val updated = state.coding.sessions.map { withUnread(projectPlanningSession(it, snapshot)) }
                    // The projection preserves references when nothing changed.
                    // Skip the copy entirely to avoid triggering Compose recomposition
                    // every 50 ms when no planning state actually changed.
                    if (updated.indices.all { i -> updated[i] === state.coding.sessions[i] }) state
                    else state.copy(coding = state.coding.copy(sessions = updated))
                }
            }
        } }

    }
    private fun withUnread(item: CodingSessionUi): CodingSessionUi {
        val unread = unreadTracker.hasUnread(item.session.id, item.messages)
        return if (item.unread == unread) item else item.copy(unread = unread)
    }

    private fun planningSessionSnapshot(): PlanningSessionSnapshot? = planningChat?.let { service ->
        PlanningSessionSnapshot(CodingPlanningState(service.states.value, service.store.plans.value, service.drafts.value,
            service.execution.live.value, service.sessions.value, service.persistenceErrors.value, service.unsavedInputs.value, service.store.runUiSnapshot()),
            codingJobs.value.filterValues { it.isActive }.keys)
    }

    private fun withPlanningState(item: CodingSessionUi): CodingSessionUi {
        val projection = planningSessionSnapshot()?.let { projectPlanningSession(item, it) } ?: item
        return projection.copy(runPhase = codingProjects?.states?.value?.get(item.session.projectId)?.runs?.get(item.session.id)?.phase)
    }

    /** Rows come from the journal already held in memory; the Git answer arrives behind them, see [refreshWorktreeAvailability]. */
    private suspend fun loadCodingSessions(projects: List<CodingProject>): List<CodingSessionUi> {
        val repo = codingProjects ?: return emptyList()
        return projects.flatMap { project ->
            AppLog.phase("coding", "project.sessions", mapOf("projectId" to project.id)) {
                repo.sessions(project.id).map { session ->
                    withUnread(withPlanningState(CodingSessionUi(session, repo.messages(project.id, session.id))))
                }
            }
        }
    }

    private fun withWorktreeAvailability(item: CodingSessionUi): CodingSessionUi {
        val known = worktreeAvailabilities.value[item.session.projectId] ?: return item
        return if (known == item.worktreeAvailability) item else item.copy(worktreeAvailability = known)
    }

    /**
     * Asks Git whether the project can take a task worktree, behind the list instead of in front of it: the probe
     * is several journaled Git reads, about half a second each, and every session switch used to wait for all of
     * them. One probe per project at a time; a switch while it runs joins it. It belongs to the service scope, so
     * leaving a session no longer cancels a journaled read midway. Starting a run and the worktree switch still ask
     * Git themselves before they act; this answer only drives what the row shows.
     */
    private fun refreshWorktreeAvailability(project: CodingProject) {
        var claimed = false
        worktreeProbes.update { running -> claimed = project.id !in running; running + project.id }
        if (!claimed) return
        scope.launch {
            try {
                val capability = try {
                    AppLog.phase("coding", "worktree.availability", mapOf("projectId" to project.id)) {
                        taskWorktrees?.availability(project) ?: WorktreeAvailability(false, "Worktree недоступен на этой платформе")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLog.error("coding", "worktree.availability.failed", failure, mapOf("projectId" to project.id))
                    WorktreeAvailability(false, "Проверка Git временно недоступна")
                }
                publishWorktreeAvailability(project.id, capability)
            } finally { worktreeProbes.update { it - project.id } }
        }
    }

    /** The answer belongs to the project, so every one of its rows shows it, and the next switch starts from it. */
    private fun publishWorktreeAvailability(projectId: String, capability: WorktreeAvailability) {
        worktreeAvailabilities.update { it + (projectId to capability) }
        _state.update { state -> state.copy(coding = state.coding.copy(sessions = state.coding.sessions.map { row ->
            if (row.session.projectId == projectId) withWorktreeAvailability(row) else row
        })) }
    }

    /** Statuses are read off an already-built session list, not recomputed from the journal a second time. */
    private fun codingStatusSnapshot(projects: List<CodingProject>, sessions: List<CodingSessionUi>): Map<String, CodingSessionStatus> {
        val byProject = sessions.groupBy { it.session.projectId }
        return projects.associate { it.id to aggregateCodingStatus(byProject[it.id].orEmpty().map { session -> session.status }) }
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
                withUnread(withPlanningState(CodingSessionUi(session, repo.messages(projectId, session.id)))).status
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


    override fun requestCodingSession() {
        val projectId = _state.value.coding.current?.id ?: return
        createCodingSessionWithRememberedEngine(projectId)
    }
    override fun requestCodingSessionInProject(projectId: String) {
        scope.launch {
            openCodingProject(projectId)
            onOpenSession(projectId, null)
            createCodingSessionWithRememberedEngine(projectId)
        }
    }
    override fun cancelCodingSessionCreation() { _state.value.coding.current?.id?.let { discardCodingSessionDraft(it) {} } }
    override fun refreshCodingEngines() {
        val runtime = codingRuntime ?: return
        scope.launch {
            for (engine in io.aequicor.magicpaper.data.coding.backendCatalog.descriptors.map { it.engine }) {
                val known = _state.value.coding.engines[engine]
                val status = try { runtime.status(engine) }
                catch (e: CancellationException) { throw e }
                // A failed probe does not unlearn the capabilities an earlier successful one reported.
                catch (e: Exception) { AppLog.error("coding", "engine.status.failed", e); RuntimeStatus(RuntimePhase.ERROR, "Не удалось проверить движок.",
                    dependenciesRemovable = known?.dependenciesRemovable, verifiesExternalInstall = known?.verifiesExternalInstall) }
                _state.update { it.copy(coding = it.coding.copy(engines = it.coding.engines + (engine to status))) }
            }
        }
    }

    override fun selectDefaultCodingEngine(engine: CodingEngine) {
        scope.launch {
            try {
                val saved = settingsCommands.selectDefaultCodingEngine(engine)
                _state.update { it.copy(settings = saved) }
                AppLog.info("coding", "default.engine.selected", mapOf("backend" to engine.name))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "default.engine.select.failed", failure, mapOf("backend" to engine.name))
                _state.update { it.copy(notice = "Не удалось сохранить выбор движка. Повторите попытку.") }
            }
        }
    }

    private fun createCodingSessionWithRememberedEngine(projectId: String) {
        val draft = sessionCreationDraft(projectId) ?: return
        draft.update(_state.value.settings.defaultCodingEngine)
        scope.launch {
            try {
                draft.awaitSaved()
                createCodingSession(projectId) { onOpenSession(projectId, it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.creation.preference.failed", failure, mapOf("projectId" to projectId))
                _state.update { it.copy(notice = "Не удалось создать сессию. Повторите попытку.") }
            }
        }
    }

    override fun selectCodingSearchProvider(sessionId: String, provider: SearchProvider) {
        val planning = planningChat ?: return
        val session = state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (!session.planningMode || session.stageId != null || session.archived) return
        scope.launch {
            try { planning.configure(session, search = provider) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "search-provider.save.failed", failure, mapOf("sessionId" to sessionId))
                _state.update { it.copy(notice = "Не удалось сохранить источник поиска. Повторите выбор.") }
            }
        }
    }

    override fun selectCodingModel(sessionId: String, selection: ModelSelection, forProject: Boolean) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val profile = ProfileResolver.selection(selection, _state.value.availableLlmProfiles) ?: return
        if ((!ui.session.planningMode || forProject) && !profile.supportsCoding) return
        val updated = ui.session.copy(modelSelection = selection, llmProfileId = selection.profileId)
        updateCodingSession(sessionId) { it.copy(session = updated) }
        rememberLastUsedSession(updated)
        scope.launch {
            if (updated.stageId != null && updated.planId != null) {
                val effort = EffortSelection.ofOrNull(ModelDefaults.capability(profile).resolveEffort(selection.effort).level)
                planningChat?.store?.command(checkNotNull(updated.planId),
                    PlanningMachine.Intent.AssignStage(checkNotNull(updated.stageId),
                        StageAssignment(selection.profileId, selection.modelId, effort, effort,
                            manual = true, displayName = profile.modelName(selection.modelId)),
                        PlanningMachine.Stamp(Id.new(), Id.now())))
            }
            acceptCodingSession(ui.session, CodingMachine.Intent.SetSessionModel(CodingMachine.ref(ui.session), selection))
        }
        if (forProject) {
            val project = _state.value.coding.projects.firstOrNull { it.id == updated.projectId } ?: return
            val next = project.copy(modelSelection = selection)
            _state.update { st -> st.copy(coding = st.coding.copy(projects = st.coding.projects.map { if (it.id == next.id) next else it }, current = st.coding.current?.let { if (it.id == next.id) next else it })) }
            scope.launch { codingProjects?.dispatch(next.id, CodingMachine.Intent.SetProjectModel(selection)) }
        }
    }

    override fun selectNativeCodingModel(sessionId: String, selection: CodingModelSelection, forProject: Boolean) {
        val coding = _state.value.coding
        val ui = coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val flags = ui.session.featureFlags.resolve(_state.value.settings.featureFlags)
        if (!coding.usesNativeModels(ui.session, flags) || ui.session.engine != selection.engine) return
        when (val resolved = coding.modelCatalogs[selection.engine]?.resolve(selection)) {
            is CodingModelResolution.Available -> Unit
            is CodingModelResolution.LevelUnsupported -> {
                AppLog.info("coding", "model.native.rejected", mapOf("sessionId" to sessionId, "reason" to "level-unsupported"))
                _state.update { it.copy(notice = "Модель ${resolved.model.name} не поддерживает уровень ${resolved.level}. Выберите другой уровень.") }
                return
            }
            else -> {
                AppLog.info("coding", "model.native.rejected", mapOf("sessionId" to sessionId, "reason" to "not-in-catalog"))
                _state.update { it.copy(notice = "Этой модели нет в каталоге движка. Обновите список моделей и выберите снова.") }
                return
            }
        }
        val updated = ui.session.copy(codingModel = selection)
        updateCodingSession(sessionId) { it.copy(session = updated) }
        rememberLastUsedSession(updated)
        AppLog.info("coding", "model.native.selected", mapOf("sessionId" to sessionId, "engine" to selection.engine.name, "forProject" to forProject.toString()))
        scope.launch { acceptCodingSession(ui.session, CodingMachine.Intent.SetSessionCodingModel(CodingMachine.ref(ui.session), selection)) }
        if (forProject) {
            val project = _state.value.coding.projects.firstOrNull { it.id == updated.projectId } ?: return
            val next = project.copy(codingModel = selection)
            _state.update { st -> st.copy(coding = st.coding.copy(projects = st.coding.projects.map { if (it.id == next.id) next else it }, current = st.coding.current?.let { if (it.id == next.id) next else it })) }
            scope.launch { codingProjects?.dispatch(next.id, CodingMachine.Intent.SetProjectCodingModel(selection)) }
        }
    }

    override fun refreshCodingModels(engine: CodingEngine) {
        val catalog = models ?: return
        if (engine in _state.value.coding.refreshingModels) return
        _state.update { it.copy(coding = it.coding.copy(refreshingModels = it.coding.refreshingModels + engine)) }
        scope.launch {
            try {
                val outcome = catalog.refresh(engine)
                if (outcome is CodingModelRefresh.Failed) _state.update { it.copy(notice = when (outcome.reason) {
                    CodingModelRefreshFailure.UNAVAILABLE -> "Каталог моделей недоступен на этой платформе."
                    CodingModelRefreshFailure.EMPTY -> "Движок не вернул ни одной модели. Проверьте вход в аккаунт."
                    CodingModelRefreshFailure.FAILED -> "Не удалось получить список моделей. Повторите попытку."
                } + if (outcome.retained != null) " Показан прежний список." else "") }
            } finally {
                _state.update { it.copy(coding = it.coding.copy(refreshingModels = it.coding.refreshingModels - engine)) }
            }
        }
    }

    override fun prepareCodingRuntime(engine: CodingEngine) {
        val runtime = codingRuntime ?: return
        if (engine in _state.value.coding.preparingEngines) return
        _state.update { it.copy(coding = it.coding.copy(preparingEngines = it.coding.preparingEngines + engine)) }
        scope.launch {
            try {
                runtime.ensureReady(engine).collect { status ->
                    _state.update { it.copy(coding = it.coding.copy(engines = it.coding.engines + (engine to status))) }
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

    override fun recover(recovery: CodingRecovery) {
        val runtime = codingRuntime ?: return
        if (recovery in recoveryJobs) return
        _state.update { it.copy(coding = it.coding.copy(pendingRecoveries = it.coding.pendingRecoveries + recovery)) }
        // Lazy start registers the job before an action that ends without suspending can clear it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (recovery) {
                    is CodingRecovery.SignIn -> signIn(runtime, recovery.engine)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.error("coding", "recovery.failed", e, mapOf("kind" to recovery::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Не удалось выполнить действие. Повторите попытку.") }
            } finally {
                recoveryJobs.remove(recovery)
                _state.update { it.copy(coding = it.coding.copy(pendingRecoveries = it.coding.pendingRecoveries - recovery)) }
            }
        }
        recoveryJobs[recovery] = job
        job.start()
    }

    override fun cancelRecovery(recovery: CodingRecovery) { recoveryJobs[recovery]?.cancel() }

    private suspend fun signIn(runtime: CodingRuntime, engine: CodingEngine) {
        when (val result = runtime.signIn(engine)) {
            EngineSignInResult.SignedIn -> {
                AppLog.info("coding", "engine.signed_in", mapOf("backend" to engine.name))
                _state.update { it.copy(notice = "Вход в ${engine.title} выполнен. Продолжите сессию.") }
                refreshCodingEngines()
            }
            is EngineSignInResult.Failed -> {
                AppLog.info("coding", "engine.sign_in.unfinished", mapOf("backend" to engine.name))
                _state.update { it.copy(notice = result.reason) }
            }
        }
    }

    /** Новый проект: выбор папки нативным диалогом. */
    override fun addCodingProject() {
        if (closing) return
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
            repo.dispatch(project.id, CodingMachine.Intent.CreateProject(project))
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
        // Every sidebar row needs its saved result, including sessions that are not selected.
        val loaded: List<CodingSessionUi> = withContext(workerDispatcher) {
            AppLog.phase("coding", "project.sessions", mapOf("projectId" to projectId, "count" to sessions.size.toString())) {
                sessions.map { session ->
                    val messages = repo.messages(projectId, session.id)
                    withUnread(withPlanningState(CodingSessionUi(
                        session = session,
                        messages = messages,
                        running = codingJobs.value[session.id]?.isActive == true,
                    )))
                }
            }
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
                    // The Git answer is read here, not when the rows were built: a probe that landed in between wins.
                    sessions = loaded.map { fresh -> st.coding.sessions.firstOrNull {
                        it.session.id == fresh.session.id && it.running
                    } ?: withWorktreeAvailability(fresh) } + carried,
                    currentSessionId = st.coding.currentSessionId
                        .takeIf { it != null && it in loadedIds }
                        ?: loaded.firstOrNull()?.session?.id,
                ),
            )
        }
        refreshWorktreeAvailability(project)
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

    private val _quarantineRecovery = MutableStateFlow(QuarantineRecoveryState())
    val quarantineRecovery: StateFlow<QuarantineRecoveryState> = _quarantineRecovery.asStateFlow()

    /** Явное восстановление: сначала сверка по журналу движка, затем — подтверждение человека. */
    override fun reconcileCodingQuarantine(sessionId: String, confirmed: Boolean) {
        val service = planningChat?.organisms ?: return
        while (true) {
            val current = _quarantineRecovery.value
            if (sessionId in current.busy) return
            if (_quarantineRecovery.compareAndSet(current, current.copy(busy = current.busy + sessionId))) break
        }
        scope.launch {
            try {
                val repo = codingProjects ?: error("Хранилище сессий недоступно")
                var session: CodingSession? = null
                for (project in repo.all()) {
                    session = repo.sessions(project.id).firstOrNull { it.id == sessionId }
                    if (session != null) break
                }
                val target = checkNotNull(session) { "Сессия недоступна" }
                val journalOutcome = planningChat.execution.reconcileJournalQuarantine(target, confirmed)
                val outcome = if (journalOutcome != QuarantineRecoveryOutcome.NO_QUARANTINE) journalOutcome
                    else service.reconcileQuarantine(target, confirmed)
                when (outcome) {
                    QuarantineRecoveryOutcome.NEEDS_CONFIRMATION -> {
                        AppLog.info("coding", "quarantine.unproven", mapOf("sessionId" to sessionId))
                        revealQuarantineRecovery(sessionId)
                    }
                    else -> {
                        AppLog.info("coding", "quarantine.reconciled", mapOf("sessionId" to sessionId,
                            "outcome" to outcome.name, "confirmed" to confirmed.toString()))
                        _quarantineRecovery.update { it.copy(awaitingConfirmation = it.awaitingConfirmation - sessionId) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "quarantine.recovery.failed", failure, mapOf("sessionId" to sessionId))
                _state.update { it.copy(notice = "Не удалось снять блокировку сессии. Проверьте фактический результат прерванных операций и повторите сверку.") }
            } finally { _quarantineRecovery.update { it.copy(busy = it.busy - sessionId) } }
        }
    }

    /** Блокировка карантином — действуемое состояние: диалог восстановления открывается сам. */
    private fun revealQuarantineRecovery(sessionId: String) = _quarantineRecovery.update { state ->
        state.copy(awaitingConfirmation = state.awaitingConfirmation + sessionId,
            reveal = state.reveal + (sessionId to (state.reveal[sessionId] ?: 0L) + 1))
    }

    override fun deleteCodingProject(id: String) {
        if (_state.value.coding.sessions.any { it.session.projectId == id && it.session.id in changingHistory }) return
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
                val sessionIds = sessions.map { it.id }.toSet()
                queuedComputerRequests.update { requests -> requests.filterValues { it !in sessionIds } }
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
                repo.dispatch(id, CodingMachine.Intent.DeleteProject)
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
        if (closing) return
        val draft = sessionCreationDraft(projectId) ?: return
        val point = draft.state.value
        if (!point.loaded) return
        sessionCreationAction(projectId, "create", "Не удалось создать сессию. Повторите попытку.") {
            val repo = checkNotNull(codingProjects)
            draft.awaitSaved()
            val project = repo.all().firstOrNull { it.id == projectId }
            check(project != null && projectId !in deletingCodingProjects.value) { "Project unavailable" }
            val lastUsed = lastUsedSessionId(projectId)
            val template = repo.sessions(projectId).lastConversation(lastUsed)
            val session = CodingSession(id = Id.new(), projectId = projectId, name = "Новая сессия",
                engine = point.value, createdAt = Id.now(),
                codingModel = project.codingModel?.takeIf { it.engine == point.value },
                modelSelection = project.modelSelection ?: ProfileResolver.favoriteDefault(
                    _state.value.settings, _state.value.availableLlmProfiles, coding = true))
                .withParametersOf(template) { ProfileResolver.selection(it, _state.value.availableLlmProfiles)?.supportsCoding == true }
            AppLog.debug("coding", "session.parameters.inherited", mapOf("projectId" to projectId,
                "templateSessionId" to (template?.id ?: "none"), "reason" to when {
                    template == null -> "no-conversation"
                    template.id == lastUsed -> "last-used"
                    else -> "newest"
                }))
            repo.dispatch(projectId, CodingMachine.Intent.CreateSession(session))
            rememberLastUsedSession(session)
            // The entity now exists. A later cleanup/presentation failure must not invite another create.
            _state.update { it.copy(coding = it.coding.copy(
                sessions = it.coding.sessions.withSessionFirst(CodingSessionUi(session = session)),
                currentSessionId = session.id)) }
            try {
                val saved = settingsCommands.selectDefaultCodingEngine(point.value)
                _state.update { it.copy(settings = saved) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.engine.preference.save.failed", failure,
                    mapOf("projectId" to projectId, "sessionId" to session.id, "backend" to point.value.name))
                _state.update { it.copy(notice = "Сессия создана. Не удалось запомнить выбранный движок.") }
            }
            try { draft.clearIfUnchanged(point.version, point.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.creation.draft.clear.failed", failure, mapOf("projectId" to projectId, "sessionId" to session.id))
                _state.update { it.copy(notice = "Сессия создана. Не удалось очистить черновик выбора движка.") }
            }
            try {
                codingRuntime?.sessionContext(project, session, codingProfileOf(session))?.let { context ->
                    repo.dispatch(projectId, CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(
                        CodingMessage("${session.id}-system-context", CodingRole.AGENT, context,
                            createdAt = session.createdAt, systemContext = true))))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.context.failed", mapOf("sessionId" to session.id, "causeType" to failure::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Сессия создана. Не удалось сохранить сведения о её контексте.") }
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
        // A newly published session can precede its first history snapshot.
        if (selected.messages.isEmpty() && !selected.running) {
            scope.launch {
                val repo = codingProjects ?: return@launch
                val messages = repo.messages(selected.session.projectId, id)
                updateCodingSession(id) { it.copy(messages = messages) }
            }
        }
        onOpenSession(selected.session.projectId, id)
    }

    override fun markSessionRead(sessionId: String, messageId: String) {
        val session = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        val lastAgent = session.messages.lastOrNull { it.role == CodingRole.AGENT && !it.systemContext && !it.systemNotice }
            ?: return
        if (lastAgent.id != messageId || unreadTracker.lastRead(sessionId) == messageId) return
        val failureNotice = "Не удалось сохранить отметку прочтения. Откройте сессию повторно, чтобы повторить попытку."
        scope.launch {
            try {
                unreadTracker.markRead(sessionId, messageId)
                updateCodingSession(sessionId) { it }
                _state.update { if (it.notice == failureNotice) it.copy(notice = null) else it }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                AppLog.error("coding", "read-marker.save.failed", error, mapOf("sessionId" to sessionId))
                _state.update { it.copy(notice = failureNotice) }
            }
        }
    }

    override fun setSessionManuallyVerified(sessionId: String, responseId: String, verified: Boolean) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (selected.completedResponseId != responseId) return
        val failureNotice = "Не удалось сохранить отметку проверки. Повторите попытку."
        scope.launch {
            try {
                acceptCodingSession(selected.session, CodingMachine.Intent.VerifyResponse(CodingMachine.ref(selected.session), responseId, verified))
                AppLog.info("coding", "manual-verification.saved", mapOf("sessionId" to sessionId, "verified" to verified.toString()))
                _state.update { if (it.notice == failureNotice) it.copy(notice = null) else it }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                AppLog.error("coding", "manual-verification.save.failed", error, mapOf("sessionId" to sessionId))
                _state.update { it.copy(notice = failureNotice) }
            }
        }
    }

    override fun deleteAllCodingSessions(projectId: String) {
        if (_state.value.coding.sessions.any { it.session.projectId == projectId && it.session.id in changingHistory }) return
        val repo = codingProjects ?: return
        scope.launch {
            try {
                val sessions = repo.sessions(projectId)
                val sessionIds = sessions.map { it.id }.toSet()
                queuedComputerRequests.update { requests -> requests.filterValues { it !in sessionIds } }
                val running = sessions.mapNotNull { removeCodingJob(it.id) }
                running.forEach { it.cancel() }
                val failures = sessions.mapNotNull { runCatching { codingRuntime?.abort(it.id) }.exceptionOrNull() }
                running.joinAll()
                if (planningChat != null) planningChat.deleteProjectSessions(projectId)
                else {
                    failures.firstOrNull()?.let { throw it }
                    sessions.forEach { codingRuntime?.reconcile(it.id) }
                    sessions.forEach { mediaGeneration?.deleteSession(it.id); repo.dispatch(projectId, CodingMachine.Intent.DeleteSession(CodingMachine.ref(it))) }
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
            val archived = repo.acceptSession(target.session, CodingMachine.Intent.ArchiveSession(CodingMachine.ref(target.session), true))
            _state.update { state ->
                state.copy(coding = state.coding.copy(
                    sessions = state.coding.sessions.map { if (it.session.id == id) it.copy(session = archived) else it },
                ))
            }
            refreshProjectStatus(target.session.projectId)
        }
    }

    override fun restoreCodingSession(id: String) {
        val repo = codingProjects ?: return
        val target = state.value.coding.sessions.firstOrNull { it.session.id == id }?.session ?: return
        scope.launch {
            try {
                var saved = repo.acceptSession(target, CodingMachine.Intent.ArchiveSession(CodingMachine.ref(target), false, archiveClock()))
                if (saved.organismId != null) {
                    requireNotNull(planningChat?.organisms).setArchiveVisibility(saved, false)
                    saved = repo.sessions(saved.projectId).firstOrNull { it.id == id } ?: return@launch
                }
                _state.update { current -> current.copy(coding = current.coding.copy(sessions = current.coding.sessions.map {
                    if (it.session.id == id) it.copy(session = saved) else it
                })) }
                refreshProjectStatus(saved.projectId)
                AppLog.info("coding", "session.unarchived", mapOf("sessionId" to id))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "session.unarchive.failed", failure, mapOf("sessionId" to id))
                _state.update { it.copy(notice = "Не удалось разархивировать сессию. Повторите попытку.") }
            }
        }
    }

    override fun deleteCodingSession(id: String) {
        if (id in changingHistory) return
        val repo = codingProjects ?: return
        val coding = _state.value.coding
        val target = coding.sessions.firstOrNull { it.session.id == id } ?: return
        scope.launch {
            try {
                val projectId = target.session.projectId
                val ids = repo.sessions(projectId).sessionTreeIds(id).toMutableSet()
                queuedComputerRequests.update { requests -> requests.filterValues { it !in ids } }
                val planIds = planningChat?.store?.plans?.value.orEmpty().filter { it.projectId == projectId && it.parentSessionId in ids }.map { it.id }.toSet()
                val running = ids.mapNotNull { removeCodingJob(it) }
                running.forEach { it.cancel() }
                val failures = ids.mapNotNull { runCatching { codingRuntime?.abort(it) }.exceptionOrNull() }
                running.joinAll()
                if (planningChat != null) ids.addAll(planningChat.deleteSessionTree(projectId, id))
                else {
                    failures.firstOrNull()?.let { throw it }
                    ids.forEach { codingRuntime?.reconcile(it) }
                    ids.forEach { mediaGeneration?.deleteSession(it); repo.sessions(projectId).firstOrNull { session -> session.id == it }?.let { session -> repo.dispatch(projectId, CodingMachine.Intent.DeleteSession(CodingMachine.ref(session))) } }
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
        if (sessionId in changingHistory) return
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        scope.launch {
            try {
                val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }
                val busy = sessionId in codingJobs.value || ui?.running == true || ui?.interactions?.isNotEmpty() == true ||
                    ui?.awaitingUser == true || planningChat?.drafts?.value?.get(sessionId)?.active == true
                selected.session.changeInteractionMode(mode, busy)
                val updated = if (selected.session.organismId != null && planningChat != null)
                    planningChat.changeManagedInteractionMode(selected.session, mode)
                else acceptCodingSession(selected.session, CodingMachine.Intent.ChangeMode(CodingMachine.ref(selected.session), mode))
                rememberLastUsedSession(updated)
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

    override fun toggleWorktree(sessionId: String) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (ui.worktreeLocked) return
        val project = _state.value.coding.projects.firstOrNull { it.id == ui.session.projectId } ?: return
        scope.launch {
            val capability = taskWorktrees?.availability(project) ?: WorktreeAvailability(false, "Worktree недоступен на этой платформе")
            publishWorktreeAvailability(project.id, capability)
            if (!capability.available) return@launch
            rememberLastUsedSession(acceptCodingSession(ui.session, CodingMachine.Intent.SetWorktreeEnabled(CodingMachine.ref(ui.session), !ui.session.worktreeEnabled)))
        }
    }

    override fun toggleSessionFeatureFlag(sessionId: String, flag: FeatureFlag) {
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        scope.launch {
            try {
                val updated = acceptCodingSession(selected.session, CodingMachine.Intent.SetFeatureFlag(CodingMachine.ref(selected.session),
                    flag, !selected.session.featureFlags.resolve(_state.value.settings.featureFlags).isEnabled(flag)))
                rememberLastUsedSession(updated)
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
        if (closing) return
        if (sessionId in changingHistory) return
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (planningChat != null && (selected.planningMode || selected.stageId != null)) {
            val draft = composerDrafts[sessionId]
            val version = draft?.version
            planningChat.send(selected, text, onAccepted = {
                if (attachments.isEmpty() && version != null) clearAcceptedComposer(draft, version)
            }); return
        }
        if (text.isBlank() && attachments.isEmpty()) return
        val request = CodingRunCheckpoint(Id.new(), text.trim(), attachments, interactionMode = selected.interactionMode, worktreeEnabled = selected.worktreeEnabled)
        if (!validateInput(selected, attachments, request.messageId)) return
        rememberLastUsedSession(selected)
        val composer = composerDrafts[sessionId]
        val version = composer?.version
        scope.launch {
            val saved = acceptCodingSession(selected, CodingMachine.Intent.Enqueue(CodingMachine.ref(selected), request))
            if (version != null) clearAcceptedComposer(composer, version)
            AppLog.info("coding", "input.queued", mapOf("sessionId" to sessionId, "requestId" to request.messageId))
            queuedComputerRequests.update { it + (request.messageId to sessionId) }
            liveQueuedRequests += request.runId
            startQueuedPrompt(saved.id)
        }
    }

    // requestId -> sessionId; restored/imported queues never acquire settings-based automation.
    private val queuedComputerRequests = MutableStateFlow<Map<String, String>>(emptyMap())
    private val liveQueuedRequests = mutableSetOf<String>()

    private fun startQueuedPrompt(sessionId: String) {
        if (closing || sessionId in codingJobs.value || sessionId in changingHistory) return
        val session = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (session.pendingRun != null || session.archived) return
        session.queuedPrompts.firstOrNull()?.takeIf { it.runId in liveQueuedRequests }?.let { request ->
            launchCodingRun(session, request, recovering = false, acquireComputerAccess = queuedComputerRequests.value[request.messageId] == sessionId)
        }
    }

    private val clarifyingSessions = mutableSetOf<String>()
    /** Повторное «Продолжить», пока предыдущее ещё принимается, отбрасывается: заметка не дублируется. */
    private val resumingSessions = mutableSetOf<String>()

    override fun clarifyCodingSession(sessionId: String, text: String, attachments: List<Attachment>) {
        if (closing) return
        if (sessionId in changingHistory) return
        if (text.isBlank() && attachments.isEmpty()) return
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (!validateInput(ui.session, attachments, sessionId)) return
        if (ui.session.planningMode || ui.session.stageId != null) {
            sendCodingPromptTo(sessionId, text, attachments)
            return
        }
        if (ui.session.taskWorktree?.phase in setOf(TaskWorktreePhase.CAPTURING, TaskWorktreePhase.MERGING, TaskWorktreePhase.DELIVERING)) {
            sendCodingPromptTo(sessionId, text, attachments); return
        }
        val running = codingJobs.value[sessionId]
        if (running == null && codingProjects?.states?.value?.get(ui.session.projectId)?.runs?.get(sessionId) == null) {
            resumeCodingSession(sessionId, text, attachments); return
        }
        if (!clarifyingSessions.add(sessionId)) return
        val draft = composerDrafts[sessionId]
        val version = draft?.version
        scope.launch {
            try {
                // Acceptance uses the latest persisted checkpoint and precedes native cancellation.
                val previous = checkNotNull(codingProjects?.states?.value?.get(ui.session.projectId)?.sessions?.get(sessionId)?.pendingRun)
                val request = CodingRunCheckpoint(Id.new(), previous.prompt + "\n\nУточнение пользователя: " + text.trim(),
                    (previous.attachments + attachments).distinctBy { it.id }, responseId = Id.new(), responseTimelineId = Id.new(),
                    interactionMode = ui.session.interactionMode, worktreeEnabled = previous.worktreeEnabled,
                    workspaceTaskId = previous.workspaceTaskId)
                val note = CodingMessage(request.messageId, CodingRole.USER, text.trim(), createdAt = Id.now(),
                    attachments = attachments.map { it.asMeta() }, inputAttachments = attachments)
                acceptCodingSession(ui.session, CodingMachine.Intent.Clarify(runRef(ui.session), request, note))
                liveQueuedRequests += request.runId
                if (version != null) clearAcceptedComposer(draft, version)
                updateCodingSession(sessionId) { it.copy(messages = codingProjects!!.states.value[ui.session.projectId]?.histories?.get(sessionId).orEmpty()) }
                if (running != null) {
                    codingRuntime?.abort(sessionId)
                    running.cancelAndJoin()
                    // The run's own cleanup already settled the checkpoint via RunStopped; a native
                    // attempt still tearing down makes this best-effort call fail too, and its real
                    // outcome (INTERRUPTED vs UNKNOWN) is re-read from the saved run below regardless.
                    try { codingRuntime?.reconcile(sessionId) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { AppLog.debug("coding", "run.clarify.reconcile_uncertain",
                        mapOf("sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty())) }
                }
                val owner = checkNotNull(codingProjects)
                val current = owner.states.value[ui.session.projectId]?.sessions?.get(sessionId) ?: return@launch
                val stopped = owner.states.value[ui.session.projectId]?.runs?.get(sessionId) ?: return@launch
                if (stopped.phase !in setOf(CodingMachine.Phase.INTERRUPTED, CodingMachine.Phase.UNKNOWN)) {
                    _state.update { it.copy(notice = "Уточнение сохранено. Исход предыдущего запроса неизвестен; проверьте состояние перед продолжением.") }
                    return@launch
                }
                // Same settlement as the explicit "Продолжить" path: INTERRUPTED discards outright,
                // UNKNOWN runs the abandon/acknowledge handshake against the native recovery snapshot.
                finishPreviousForExplicitRun(current)
                startQueuedPrompt(sessionId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "run.clarify.failed", failure, mapOf("sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Не удалось подтвердить продолжение. Проверьте сохранённый запрос и состояние сессии.") }
            } finally { clarifyingSessions.remove(sessionId) }
        }
    }

    override fun resumeCodingSession(sessionId: String, text: String, attachments: List<Attachment>, fromQuestionnaire: Boolean) {
        if (closing) return
        if (sessionId in changingHistory || sessionId in resumingSessions) return
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (!(if (fromQuestionnaire) ui.copy(interactions = emptyList()) else ui).canResume) return
        if (planningChat != null && (ui.plan != null || ui.session.planningMode || ui.session.stageId != null)) {
            val draft = composerDrafts[sessionId]
            val version = draft?.version
            planningChat.resume(ui.session, text, onAccepted = {
                if (attachments.isEmpty() && version != null) clearAcceptedComposer(draft, version)
            }); return
        }
        val request = ui.session.pendingRun ?: ui.session.queuedPrompts.firstOrNull() ?: ui.messages.interruptedCodingRequest()?.let {
            CodingRunCheckpoint(it.id, it.text)
        } ?: return
        val instruction = text.trim()
        val withNote = instruction.isNotEmpty() || attachments.isNotEmpty()
        if (!validateInput(ui.session, (request.attachments + attachments).distinctBy { it.id }, request.messageId)) return
        if (!resumingSessions.add(sessionId)) return
        scope.launch {
            try {
                var pendingSession = ui.session
                val waiting = pendingSession.queuedPrompts.firstOrNull()
                if (waiting != null && withNote) pendingSession = acceptCodingSession(pendingSession,
                    CodingMachine.Intent.QueuedClarified(CodingMachine.ref(pendingSession), waiting.runId,
                        CodingMessage(Id.new(), CodingRole.USER, instruction, createdAt = Id.now(),
                            attachments = attachments.map { it.asMeta() }, inputAttachments = attachments), attachments))
                else if (waiting == null && withNote) {
                    val previous = codingProjects?.states?.value?.get(pendingSession.projectId)?.runs?.get(sessionId)
                    if (previous != null) {
                        val clarification = request.copy(messageId = Id.new(), runId = Id.new(), responseId = Id.new(), responseTimelineId = Id.new(),
                            prompt = request.prompt + if (instruction.isNotEmpty()) "\n\nУточнение пользователя: $instruction" else "",
                            attachments = (request.attachments + attachments).distinctBy { it.id }, intent = ExecutionIntent.RUN, stoppedByUser = false)
                        pendingSession = acceptCodingSession(pendingSession, CodingMachine.Intent.Clarify(previous.ref, clarification,
                            CodingMessage(clarification.messageId, CodingRole.USER, instruction, createdAt = Id.now(),
                                attachments = attachments.map { it.asMeta() }, inputAttachments = attachments)))
                    }
                }
                taskWorktrees?.inspectTaskOutcome(pendingSession.projectId, sessionId)
                taskWorktrees?.retryFailedVerification(pendingSession.projectId, sessionId)
                val latest = finishPreviousForExplicitRun(pendingSession)
                val queued = latest.queuedPrompts.firstOrNull()
                val fresh = queued ?: request.copy(messageId = Id.new(), runId = Id.new(), responseId = Id.new(), responseTimelineId = Id.new(),
                    prompt = request.prompt + if (instruction.isNotEmpty()) "\n\nУточнение пользователя: $instruction" else "",
                    attachments = (request.attachments + attachments).distinctBy { it.id }, intent = ExecutionIntent.RUN, stoppedByUser = false)
                if (queued == null) acceptCodingSession(latest, CodingMachine.Intent.Enqueue(CodingMachine.ref(latest), fresh))
                launchCodingRun(latest, fresh, recovering = true, resumeInstruction = withNote)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "run.resume.failed", failure, mapOf("sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Не удалось подтвердить исход предыдущей работы. Сессия остаётся остановленной; сохранённый запрос доступен для восстановления.") }
            } finally { resumingSessions.remove(sessionId) }
        }
    }

    /** Only an explicit user continuation may acknowledge a stopped unknown attempt. Replay never calls this. */
    private suspend fun finishPreviousForExplicitRun(session: CodingSession): CodingSession {
        val owner = checkNotNull(codingProjects)
        val run = owner.states.value[session.projectId]?.runs?.get(session.id) ?: return session
        if (run.phase == CodingMachine.Phase.INTERRUPTED)
            return acceptCodingSession(session, CodingMachine.Intent.DiscardInterrupted(run.ref))
        check(run.phase == CodingMachine.Phase.UNKNOWN) { "Сначала остановите текущую работу" }
        val recovery = codingRuntime?.recovery ?: error("Проверка предыдущего запуска недоступна")
        val snapshot = recovery.inspect(session.id)
        check(!snapshot.persistenceUnknown) { "Сохранение предыдущего запуска не подтверждено" }
        val attempts = snapshot.items.filter { it.ref.sessionId == session.id && it.ref.requestId == run.ref.requestId }
        val noDispatch = snapshot.noDispatch.filter { it.proof.sessionId == session.id && it.proof.requestId == run.ref.requestId }
        check(attempts.isEmpty() || noDispatch.isEmpty()) { "Подтверждения предыдущего запуска противоречат друг другу" }
        if (attempts.isEmpty()) {
            val item = noDispatch.singleOrNull()
            if (item == null) {
                // The journal is consistent and alive for this session, yet it holds no attempt and no proof for this
                // request: it never reached any engine, so there is no external outcome to fence and the run settles
                // as undispatched. A journal without resolved records of another request of the session — an empty
                // or unreadable one, or one holding only noise about this very request — cannot prove that, and refuses.
                check(snapshot.items.any { it.ref.requestId != run.ref.requestId } ||
                    snapshot.noDispatch.any { it.proof.requestId != run.ref.requestId && it.acknowledgement != null }) { "Подтверждение предыдущего запуска не найдено" }
                return acceptCodingSession(session, CodingMachine.Intent.DiscardUndispatched(run.ref))
            }
            check(item.proof.engine == session.engine) { "Подтверждение относится к другому движку" }
            val saved = item.acknowledgement
            val decision = if (saved != null) {
                check(run.abandonNoDispatchProof == item.proof && run.abandonDecisionId == saved.parentDecisionId && saved.proof == item.proof)
                saved.parentDecisionId
            } else Id.new()
            acceptCodingSession(session, CodingMachine.Intent.AbandonNotDispatched(run.ref, decision, item.proof))
            try {
                val acknowledged = saved ?: recovery.acknowledgeNoDispatch(item.proof, decision)
                return acceptCodingSession(session, CodingMachine.Fact.NoDispatchAcknowledged(run.ref, acknowledged))
            } catch (failure: Exception) {
                keepRecoveryUnknown(owner, session, run.ref, failure)
                throw failure
            }
        }
        val previous = attempts.maxBy { it.ref.attempt }
        val saved = previous.acknowledgement
        val decision = if (saved != null) {
            check(run.abandonAttempt == previous.ref && run.abandonDecisionId == saved.parentDecisionId && saved.predecessor == previous.ref)
            saved.parentDecisionId
        } else Id.new()
        acceptCodingSession(session, CodingMachine.Intent.Abandon(run.ref, decision, previous.ref))
        try {
            val stopped = recovery.stop(previous.ref)
            check(!stopped.persistenceUnknown && stopped.items.any { it.ref == previous.ref && it.termination == NativeRunTermination.STOPPED }) { "Остановка процесса не подтверждена" }
            val acknowledgement = saved ?: recovery.acknowledge(previous.ref, decision)
            return acceptCodingSession(session, CodingMachine.Fact.AbandonAcknowledged(run.ref, acknowledgement))
        } catch (failure: Exception) {
            keepRecoveryUnknown(owner, session, run.ref, failure)
            throw failure
        }
    }

    private suspend fun keepRecoveryUnknown(owner: CodingProjectOwner, session: CodingSession, ref: CodingMachine.RunRef, failure: Exception) {
        withContext(NonCancellable) {
            try { owner.dispatch(session.projectId, CodingMachine.Fact.RunStopped(ref, unknown = true)) }
            catch (storageFailure: Exception) { failure.addSuppressed(storageFailure) }
        }
    }

    /** The native decision a deferral recorded; continuation must match it exactly instead of inventing a fresh one. */
    private class RecordedDecision(val attempt: NativeRunRecoveryRef? = null, val proof: NativeRunNoDispatchProof? = null,
        val decisionId: String? = null)

    /** Best-effort: an unconfirmed native outcome must not block the "leave stopped" decision itself,
     * only every other session's use of the same working folder until the native side catches up.
     * Every recorded acknowledgement is a pending decision the session's next native admission must carry
     * exactly, and only the application's own continuation can carry one: a known outcome needs no
     * decision at all, so acknowledging it would permanently fence the session's runs. Only what
     * [NativeRunRecoverySnapshot.decided] still lacks — an outcome nobody can know, or a request proven
     * never dispatched — is acknowledged here, with one decision the deferral records durably. */
    private suspend fun acknowledgeNativeRecoveryQuietly(session: CodingSession, requestId: String?): RecordedDecision {
        val recovery = codingRuntime?.recovery ?: return RecordedDecision()
        var recorded = RecordedDecision()
        try {
            val snapshot = recovery.inspect(session.id)
            if (snapshot.persistenceUnknown) return recorded
            val decision = Id.new()
            var decided = 0
            snapshot.items.filter { it.acknowledgement == null && it.outcome == NativeRunOutcome.UNKNOWN }.forEach { item ->
                val stopped = if (item.termination == NativeRunTermination.STOPPED) snapshot else recovery.stop(item.ref)
                if (stopped.items.any { it.ref == item.ref && it.termination == NativeRunTermination.STOPPED }) {
                    recovery.acknowledge(item.ref, decision); decided++
                    if (requestId == null || item.ref.requestId == requestId)
                        recorded = RecordedDecision(attempt = item.ref, decisionId = decision)
                }
            }
            snapshot.noDispatch.filter { it.acknowledgement == null }.forEach { item ->
                recovery.acknowledgeNoDispatch(item.proof, decision); decided++
                if (recorded.decisionId == null && (requestId == null || item.proof.requestId == requestId))
                    recorded = RecordedDecision(proof = item.proof, decisionId = decision)
            }
            if (decided > 0) AppLog.debug("coding", "recovery.defer.decided", mapOf("sessionId" to session.id, "count" to decided.toString()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("coding", "recovery.defer.acknowledge.failed", mapOf("sessionId" to session.id, "causeType" to failure::class.simpleName.orEmpty()))
        }
        return recorded
    }

    private suspend fun clearAcceptedComposer(draft: io.aequicor.magicpaper.ui.components.CodingComposerDraft?, version: Long) {
        try { withContext(Dispatchers.Main.immediate) { draft?.clearIfUnchanged(version) } }
        catch (e: CancellationException) { throw e }
        catch (error: io.aequicor.magicpaper.data.storage.StorageException) { logPersistenceFailure("coding", "draft.clear.failed", error); _state.update { it.copy(notice = if (error.committed) "Сообщение сохранено. Не удалось удалить временные данные." else "Сообщение сохранено. Не удалось очистить черновик.") } }
    }

    private var knownImmunitySignals = emptySet<String>()
    private var liveImmunitySignals = emptySet<String>()
    private fun startPendingImmunityDiagnostics() {
        val service = planningChat ?: return
        for (item in _state.value.coding.sessions) {
            val organism = item.session.organismId?.let { service.organisms?.store?.organisms?.value?.get(it) } ?: continue
            val signal = organism.nextImmunityResearch(item.session, item.messages) ?: continue
            if (signal.id !in liveImmunitySignals || item.session.id in codingJobs.value || item.running) continue
            liveImmunitySignals -= signal.id
            launchCodingRun(item.session, CodingRunCheckpoint("signal-${signal.id}", signal.diagnostic,
                responseId = "immunity-report-${signal.id}", interactionMode = CodingInteractionMode.RESEARCH),
                recovering = false, userInitiated = false)
        }
    }

    private suspend fun restoreCodingRuns(projects: List<CodingProject>) {
        // Journal restoration marks admitted work UNKNOWN. Reopening a project never emits run/cancel/ack effects.
        val owner = codingProjects ?: return
        for (project in projects) for (session in owner.sessions(project.id)) {
            if (session.pendingRun == null) continue
            if (_state.value.coding.sessions.none { it.session.id == session.id }) {
                val ui = CodingSessionUi(session, owner.messages(project.id, session.id))
                _state.update { it.copy(coding = it.coding.copy(sessions = it.coding.sessions + ui)) }
            }
        }
    }

    private suspend fun acceptCodingSession(session: CodingSession, input: CodingMachine.Input): CodingSession {
        val owner = codingProjects ?: error("Хранилище сессий недоступно")
        return owner.acceptSession(session, input).also { saved ->
            updateCodingSession(saved.id) { it.copy(session = saved) }
            _state.update { if (it.notice == statusRecencySaveFailure) it.copy(notice = null) else it }
        }
    }

    private fun runRef(session: CodingSession): CodingMachine.RunRef =
        checkNotNull(codingProjects?.states?.value?.get(session.projectId)?.runs?.get(session.id)?.ref) { "Запрос ещё не принят" }

    private val changingHistory = mutableSetOf<String>()

    private suspend fun <T> changeHistory(sessionId: String, operation: String,
        action: suspend (CodingSessionUi, CodingProjectOwner) -> T): Result<T> = scope.async {
        if (!changingHistory.add(sessionId)) return@async Result.failure(IllegalStateException("Дождитесь сохранения истории."))
        try {
            val ui = _state.value.coding.sessions.first { it.session.id == sessionId }
            check(ui.session.projectId !in deletingCodingProjects.value)
            if (operation != "fork") {
                check(ui.canChangeHistory && sessionId !in codingJobs.value)
                check(planningChat?.drafts?.value?.get(sessionId)?.active != true)
                codingRuntime?.reconcileDecided(sessionId)
            }
            val result = action(ui, checkNotNull(codingProjects))
            AppLog.info("coding", "history.$operation", mapOf("sessionId" to sessionId))
            Result.success(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("coding", "history.$operation.failed", failure, mapOf("sessionId" to sessionId))
            val message = (failure as? HistoryActionRejected)?.message
                ?: "Не удалось изменить историю. Завершите текущую задачу и повторите попытку."
            _state.update { it.copy(notice = message) }
            Result.failure(IllegalStateException(message))
        } finally {
            changingHistory.remove(sessionId)
            startQueuedPrompt(sessionId)
        }
    }.await()

    private suspend fun replaceHistory(ui: CodingSessionUi, repo: CodingProjectOwner, messages: List<CodingMessage>): CodingSession {
        val saved = acceptCodingSession(ui.session, CodingMachine.Intent.ReplaceHistory(CodingMachine.ref(ui.session), ui.messages, messages))
        updateCodingSession(saved.id) { it.copy(messages = messages, draft = CodingDraft(), failedRequest = false, interruptedRequest = false) }
        return saved
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit> = changeHistory(sessionId, "delete") { ui, repo ->
        require(ui.messages.any { it.id == messageId })
        replaceHistory(ui, repo, ui.messages.filterNot { it.id == messageId })
        Unit
    }

    override suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit> = changeHistory(sessionId, "edit") { ui, repo ->
        if (ui.session.archived) throw HistoryActionRejected("Архивную сессию можно продолжить в форке.")
        val message = ui.messages.first { it.id == messageId }
        require(message.role == CodingRole.USER && message.origin == MessageOrigin.USER)
        require(text.isNotBlank() || message.inputAttachments.isNotEmpty())
        // Old metadata-only logs cannot safely recreate missing attachment bytes.
        if (message.attachments.size != message.inputAttachments.size)
            throw HistoryActionRejected("В старом сообщении сохранились только названия файлов. Прикрепите их заново в новом сообщении.")
        val request = CodingRunCheckpoint(Id.new(), text.trim(), message.inputAttachments,
            responseId = Id.new(), responseTimelineId = Id.new(),
            interactionMode = ui.session.interactionMode, worktreeEnabled = ui.session.worktreeEnabled)
        check(validateInput(ui.session, request.attachments, request.messageId))
        val invocation = CodingImageInvocation(sessionId, request.runId, request.messageId, request.responseId, request.responseTimelineId)
        val edited = message.copy(id = request.messageId, text = request.prompt, inputStatus = null,
            images = request.attachments.mapNotNull { it.asCodingInputImage(invocation) })
        val messages = ui.messages.through(messageId) { it.id }.dropLast(1) + edited
        val planning = planningChat != null && (ui.session.planningMode || ui.session.stageId != null)
        val saved = if (planning) replaceHistory(ui, repo, messages) else acceptCodingSession(ui.session,
            CodingMachine.Intent.EditRequest(CodingMachine.ref(ui.session), ui.messages, messages, request)).also { saved ->
                updateCodingSession(saved.id) { it.copy(messages = messages, draft = CodingDraft(), failedRequest = false, interruptedRequest = false) }
            }
        changingHistory.remove(sessionId)
        if (planning) {
            checkNotNull(planningChat!!.send(saved, request.prompt, inputId = request.messageId)).join()
        } else launchCodingRun(saved, request, recovering = false, clearComposer = false)
    }

    override suspend fun forkSession(sessionId: String, throughMessageId: String?): Result<String> = changeHistory(sessionId, "fork") { ui, repo ->
        val fork = ui.session.fork()
        val messages = ui.messages.through(throughMessageId) { it.id }
            .filterNot { it.systemContext || it.systemNotice }
            .map { it.withGeneratedMedia(mediaGeneration?.operations?.value.orEmpty()).forFork(fork.id) }
        // Publish the new session only after its complete independent log exists.
        repo.dispatch(fork.projectId, CodingMachine.Intent.CreateSession(fork, messages))
        _state.update { it.copy(coding = it.coding.copy(sessions = it.coding.sessions.withSessionFirst(CodingSessionUi(fork, messages)))) }
        onOpenSession(fork.projectId, fork.id)
        fork.id
    }

    private suspend fun appendCodingMessage(session: CodingSession, message: CodingMessage) {
        val repo = codingProjects ?: return
        if (planningChat != null) planningChat.append(session.projectId, session.id, message)
        else {
            val history = repo.messages(session.projectId, session.id)
            if (history.none { it.id == message.id }) repo.dispatch(session.projectId, CodingMachine.Fact.HistoryPublished(CodingMachine.ref(session), listOf(message)))
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
            val declared = session.codingModel?.let { choice -> _state.value.coding.modelCatalogs[choice.engine]?.find(choice.provider, choice.modelId) }
            // A model the engine itself declared is judged by its declaration, not by the name heuristic.
            val (modelName, vision) = if (declared != null) declared.name to declared.acceptsImages else {
                val profile = codingProfileOf(session)?.forCoding()
                if (profile == null) return true
                profile.modelId to ModelCapabilities.resolve(profile.provider, profile.modelId, profile.baseUrl).vision
            }
            if (!vision) {
                AppLog.info("coding", "run.input.rejected", mapOf(
                    "sessionId" to session.id, "requestId" to requestId,
                    "model" to modelName, "reason" to "image-input-unsupported"))
                _state.update { it.copy(notice = "Модель $modelName не поддерживает изображения. Выберите модель с поддержкой изображений и повторите отправку.") }
                return false
            }
        }
        return true
    }

    private suspend fun inspectNativeRecovery(runtime: CodingRuntime, session: CodingSession, ref: CodingMachine.RunRef): NativeRunRecoverySnapshot? {
        val recovery = runtime.recovery ?: return null
        val snapshot = recovery.inspect(session.id)
        val owner = codingProjects
        if (!snapshot.persistenceUnknown && owner != null) {
            val state = owner.states.value[session.projectId]
            val expected = state?.acknowledgements?.get(session.id)?.id ?: state?.noDispatchAcknowledgements?.get(session.id)?.id
            snapshot.consumptions.singleOrNull { it.acknowledgementId == expected && it.engine == session.engine &&
                it.sessionId == session.id && it.requestId == ref.requestId }?.let { proof ->
                owner.dispatch(session.projectId, CodingMachine.Fact.RecoveryAcknowledgementConsumed(ref, proof))
            }
        }
        return snapshot
    }

    private suspend fun nativeOutcomeUnknown(runtime: CodingRuntime, session: CodingSession, ref: CodingMachine.RunRef): Boolean {
        val sessionId = session.id
        val requestId = ref.requestId
        return try {
            val snapshot = inspectNativeRecovery(runtime, session, ref) ?: return true
            val attempt = snapshot.items.filter { it.ref.sessionId == sessionId && it.ref.requestId == requestId && it.ref.engine == session.engine }.maxByOrNull { it.ref.attempt }
            snapshot.persistenceUnknown || attempt == null || attempt.outcome == NativeRunOutcome.UNKNOWN ||
                snapshot.noDispatch.any { it.proof.engine == session.engine && it.proof.sessionId == sessionId && it.proof.requestId == requestId } ||
                attempt.termination !in setOf(NativeRunTermination.STOPPED, NativeRunTermination.NOT_STARTED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AppLog.error("coding", "run.inspect.failed", mapOf("sessionId" to sessionId, "requestId" to requestId, "causeType" to failure::class.simpleName.orEmpty()))
            _state.update { it.copy(notice = "Не удалось проверить исход запуска. Работа остаётся остановленной.") }
            true
        }
    }

    /**
     * The failure itself, or a cleanup failure that the session tree retained on it as suppressed; that includes the same-type
     * original behind a copy made by coroutine stack trace recovery.
     */
    private fun Throwable.unconfirmedNativeOutcome(): NativeRunRecoveryRequired? {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            (current as? NativeRunRecoveryRequired ?: current.suppressedExceptions.firstNotNullOfOrNull { it as? NativeRunRecoveryRequired })
                ?.let { return it }
            current = current.cause?.takeIf { it::class == this::class }
        }
        return null
    }

    private fun launchCodingRun(session: CodingSession, checkpoint: CodingRunCheckpoint, recovering: Boolean, resumeInstruction: Boolean = false, userInitiated: Boolean = true, clearComposer: Boolean = true, acquireComputerAccess: Boolean = userInitiated) {
        val runtime = codingRuntime ?: return
        val project = _state.value.coding.projects.firstOrNull { it.id == session.projectId } ?: return
        // A cancelled job still owns the session while its runtime and saved output
        // are being cleaned up. Its finally block releases this entry.
        if (closing || session.projectId in deletingCodingProjects.value || session.id in codingJobs.value || session.id in changingHistory) return
        if (userInitiated && !validateInput(session, checkpoint.attachments, checkpoint.messageId)) return
        queuedComputerRequests.update { it - checkpoint.messageId }
        liveQueuedRequests -= checkpoint.runId
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
            var admittedRef: CodingMachine.RunRef? = null
            var previousAcknowledgement: NativeRunRecoveryAcknowledgement? = null
            var previousNoDispatchAcknowledgement: NativeRunNoDispatchAcknowledgement? = null
            var nativeDispatched = false
            try {
                requireConfirmedSettings()
                if (userInitiated) planningChat?.prepareManagedUserTurn(session, checkpoint.messageId)
                val owner = checkNotNull(codingProjects)
                val latest = checkNotNull(owner.states.value[session.projectId]?.sessions?.get(session.id))
                val unfinished = latest.taskWorktree?.takeUnless { it.phase == TaskWorktreePhase.COMPLETE }
                request = request.copy(interactionMode = request.interactionMode ?: latest.interactionMode,
                    workspaceTaskId = unfinished?.taskId ?: request.workspaceTaskId ?: request.runId.takeIf { request.worktreeEnabled == true })
                val accepted = owner.dispatch(project.id, CodingMachine.Intent.BeginRun(CodingMachine.ref(latest), request, Id.now(), sessionTitles == null))
                val effect = accepted.effects.filterIsInstance<CodingMachine.Effect.RunRequest>().single()
                admittedRef = effect.ref
                previousAcknowledgement = effect.previousAcknowledgement
                previousNoDispatchAcknowledgement = effect.previousNoDispatchAcknowledgement
                request = effect.request
                var current = accepted.state.sessions.getValue(session.id)
                updateCodingSession(session.id) { it.copy(session = current) }
                sessionTitles?.sync(current)
                if (codingProjects!!.messages(project.id, session.id).none { it.id == request.messageId }) {
                    appendCodingMessage(session, CodingMessage(request.messageId, CodingRole.USER,
                        request.prompt, createdAt = Id.now(), attachments = request.attachments.map { it.asMeta() },
                        inputAttachments = request.attachments,
                        images = request.attachments.mapNotNull { it.asCodingInputImage(checkNotNull(recorder.imageInvocation)) }))
                }
                if (userInitiated && composerVersion != null) clearAcceptedComposer(composer, composerVersion)
                updateCodingSession(session.id) { it.copy(running = true, draft = recorder.draft(active = true)) }
                currentCoroutineContext().ensureActive()
                if (recovering) runtime.reconcileDecided(session.id)
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
                var executionProject = project
                var workspaceRecord = current.taskWorktree?.takeIf { it.taskId == request.workspaceTaskId }
                // Найденная незавершённая задача продолжает работать в своей копии, даже если чекпоинт
                // не просил worktree (устаревший/восстановленный запрос): брошенная копия с неслитой работой
                // не должна отправлять агента в исходную папку.
                if ((request.worktreeEnabled == true || workspaceRecord != null) && current.interactionMode == CodingInteractionMode.CODE && current.stageId == null && current.sessionKind != SessionKind.SESSION) {
                    val capability = taskWorktrees?.availability(project) ?: WorktreeAvailability(false, "Worktree недоступен на этой платформе")
                    publishWorktreeAvailability(project.id, capability)
                    if (workspaceRecord != null || capability.available) {
                        recorder.apply(CodingEvent.Notice("Подготовка worktree"))
                        workspaceRecord = checkNotNull(taskWorktrees).begin(project, session.id, checkNotNull(request.workspaceTaskId))
                        current = taskWorktrees.session(project.id, session.id)
                        val branchNotice = if (workspaceRecord.reuseBranch.isBlank()) {
                            "Создана worktree-ветка ${workspaceRecord.branch}"
                        } else {
                            "Worktree переключён с ${workspaceRecord.reuseBranch} на ${workspaceRecord.branch}"
                        }
                        appendCodingMessage(session, CodingMessage("${request.responseId}-worktree-branch", CodingRole.AGENT,
                            branchNotice, createdAt = Id.now(), systemNotice = true))
                        executionProject = project.copy(path = workspaceRecord.path)
                        prompt += "\n\nРабочая папка этой задачи: ${workspaceRecord.path}. Работай только в ней. " +
                            "Не изменяй исходную папку ${workspaceRecord.sourcePath} и не выполняй слияние в неё. " +
                            "Уточнения задавай через magicpaper_questionnaire. После полного выполнения и проверок вызови magicpaper_task_handoff с RESULT " +
                            "и командами проверок (массивы аргументов). Затем заверши ответ. При блокировке передай BLOCKED."
                    } else {
                        current = acceptCodingSession(current, CodingMachine.Fact.WorktreeUnavailable(checkNotNull(admittedRef)))
                    }
                }

                var ended = false
                var repaired = false
                val deliveryOnly = workspaceRecord?.phase in setOf(TaskWorktreePhase.CAPTURING, TaskWorktreePhase.MERGING, TaskWorktreePhase.CONFLICT, TaskWorktreePhase.DELIVERING, TaskWorktreePhase.COMPLETE)
                if (deliveryOnly) ended = true
                else {
                    if (workspaceRecord != null) {
                        taskWorktrees!!.revokeHandoff(project.id, session.id, workspaceRecord.taskId)
                        current = taskWorktrees.session(project.id, session.id)
                    }
                    nativeDispatched = true
                    val recoveryBinding = when {
                        previousAcknowledgement != null -> NativeRunRecoveryBinding(acknowledgement = previousAcknowledgement)
                        previousNoDispatchAcknowledgement != null -> NativeRunRecoveryBinding(noDispatchAcknowledgement = previousNoDispatchAcknowledgement)
                        else -> kotlin.coroutines.EmptyCoroutineContext
                    }
                    withContext(recoveryBinding) {
                        recorder.recordDrafts(runtime.run(executionProject, current.copy(acquireComputerAccess = acquireComputerAccess),
                            prompt, codingProfileOf(current), request.attachments), onEvent = { event ->
                            if (event is CodingEvent.SessionStarted && event.sessionId.isNotBlank()) {
                                // Save the native conversation before the first command, not at the end of the turn.
                                current = acceptCodingSession(current, CodingMachine.Fact.NativeSessionBound(checkNotNull(admittedRef), event.sessionId))
                            }
                            if (event is CodingEvent.Finished) ended = true
                        }).collect { draft -> updateCodingSession(session.id) { it.copy(draft = draft) } }
                    }
                    if (previousAcknowledgement != null || previousNoDispatchAcknowledgement != null)
                        inspectNativeRecovery(runtime, session, checkNotNull(admittedRef))
                }
                if (!ended) recorder.apply(CodingEvent.Failed("Выполнение прервано. Нажмите «Продолжить»."))
                if (workspaceRecord != null && ended && recorder.draft(active = false).failedMessage == null) {
                    runtime.reconcileDecided(session.id)
                    check(runtime.questionnaires.value.none { it.sessionId == session.id }) { "Ожидается ответ на уточнение" }
                    check(taskWorktrees!!.session(project.id, session.id).pendingRun?.intent == ExecutionIntent.RUN) { "Задача остановлена" }
                    if (!deliveryOnly) {
                        taskWorktrees.recordResponse(project.id, session.id, checkNotNull(request.workspaceTaskId), recorder.message(request.responseId, Id.now()))
                        current = taskWorktrees.session(project.id, session.id)
                    }
                    val taskId = checkNotNull(request.workspaceTaskId)
                    // A repair is a fresh request of the same run: the agent continues in the task copy on what refused
                    // its result. It reports whether the agent's turn ended cleanly; the handoff is judged by the caller.
                    suspend fun repairTurn(copy: TaskWorktree, notice: String, instruction: String, returnTask: suspend () -> Unit): Boolean {
                        recorder.apply(CodingEvent.Notice(notice))
                        repaired = true
                        val beforeRepair = checkNotNull(owner.states.value[project.id])
                        val repair = owner.dispatch(project.id, CodingMachine.Intent.BeginRepair(checkNotNull(admittedRef), Id.new(),
                            checkNotNull(beforeRepair.childRevisions["workspace:${session.id}"])))
                        val repairEffect = repair.effects.filterIsInstance<CodingMachine.Effect.RunRequest>().single()
                        admittedRef = repairEffect.ref
                        // The repair is a fresh request; prior native work proves nothing
                        // about whether this request reached the runtime.
                        nativeDispatched = false
                        returnTask()
                        val repairSession = repair.state.sessions.getValue(session.id)
                        request = checkNotNull(repairSession.pendingRun)
                        var repairEnded = false
                        val repairRecovery = when {
                            repairEffect.previousAcknowledgement != null -> NativeRunRecoveryBinding(acknowledgement = repairEffect.previousAcknowledgement)
                            repairEffect.previousNoDispatchAcknowledgement != null -> NativeRunRecoveryBinding(noDispatchAcknowledgement = repairEffect.previousNoDispatchAcknowledgement)
                            else -> kotlin.coroutines.EmptyCoroutineContext
                        }
                        nativeDispatched = true
                        withContext(repairRecovery) {
                            recorder.recordDrafts(runtime.run(project.copy(path = copy.path), repairSession,
                                "$instruction\n\nАктуальная задача и уточнения:\n${request.prompt}",
                                codingProfileOf(repairSession)), onEvent = { if (it is CodingEvent.Finished) repairEnded = true }).collect { draft ->
                                updateCodingSession(session.id) { it.copy(draft = draft) }
                            }
                        }
                        if (repairEffect.previousAcknowledgement != null || repairEffect.previousNoDispatchAcknowledgement != null)
                            inspectNativeRecovery(runtime, session, checkNotNull(admittedRef))
                        runtime.reconcileDecided(session.id)
                        return repairEnded && recorder.draft(active = false).failedMessage == null
                    }
                    val finished = taskWorktrees.complete(project, session.id, taskId, repair = { conflict ->
                        val clean = repairTurn(conflict, "Разрешение конфликта",
                            "Разреши конфликт переноса задачи на ветку назначения в этой рабочей папке: отредактируй спорные файлы, " +
                                "сохрани обе стороны и добавь их в индекс (git add). Приложение само продолжит перенос и запустит проверки: " +
                                "не выполняй git rebase --continue, --skip или --abort и не меняй исходную папку. " +
                                "При неоднозначности задай вопрос через magicpaper_questionnaire. Перед завершением передай RESULT через magicpaper_task_handoff с командами проверок.") {
                            taskWorktrees.bindRun(project.id, session.id, taskId)
                        }
                        check(clean && taskWorktrees.session(project.id, session.id).taskWorktree?.handoffGeneration != null) { "Конфликт требует продолжения" }
                    }, repairChecks = { refused, report ->
                        // The checks are the agent's own: their failure is its to fix, like a conflict, not the user's.
                        val clean = repairTurn(refused, "Исправление после проверки",
                            "Приложение не приняло переданный результат задачи: проверки из magicpaper_task_handoff не прошли на объединённом результате.\n\n" +
                                "Отчёт проверки:\n$report\n\n" +
                                "Устрани причину в этой рабочей папке: если не запустилась или неверна сама команда проверки, исправь команду, " +
                                "иначе исправь изменения. Не убирай и не ослабляй проверки ради прохождения. Не меняй исходную папку и не выполняй слияние: " +
                                "приложение само зафиксирует изменения, повторит перенос и проверки. При неоднозначности задай вопрос через magicpaper_questionnaire. " +
                                "Затем снова передай RESULT через magicpaper_task_handoff с командами проверок (массивы аргументов). " +
                                "Если причина вне рабочей папки и устранить её нельзя, передай BLOCKED и объясни причину.") {
                            taskWorktrees.returnForRepair(project.id, session.id, taskId)
                        }
                        // A clean turn without a new handoff is refused by the capture that follows, with the reason the task keeps.
                        check(clean) { "Исправление после проверки прервано. Нажмите «Продолжить»." }
                    }, spawnRefused = { command ->
                        // Only the user can widen the containment or waive a check; the agent cannot fix either.
                        taskWorktrees.askSpawnRefusal(project.id, session.id, command)?.also { decision ->
                            recorder.apply(CodingEvent.Notice(when (decision) {
                                SpawnRefusalDecision.ALLOW -> "Проверка повторяется с разрешённым запуском программ"
                                SpawnRefusalDecision.SKIP -> "Проверка пропущена по вашему решению"
                            }))
                        }
                    })
                    recorder.apply(CodingEvent.Notice("Результат влит в ${finished.targetBranch}"))
                    current = taskWorktrees.session(project.id, session.id)
                    updateCodingSession(session.id) { it.copy(session = current) }
                }
                val recorded = recorder.message(request.responseId, Id.now())
                // A delivery that only finished the Git work republishes the saved answer; a repair's agent answered anew.
                val response = (if (deliveryOnly && !repaired) current.taskWorktree?.executionResponse ?: recorded else recorded).copy(id = request.responseId)
                current = acceptCodingSession(current, CodingMachine.Fact.RunFinished(checkNotNull(admittedRef), response,
                    outcomeKnown = ended && (!nativeDispatched || !nativeOutcomeUnknown(runtime, session, checkNotNull(admittedRef)))))
                updateCodingSession(session.id) { it.copy(messages = owner.states.value[project.id]?.histories?.get(session.id).orEmpty()) }
                if (workspaceRecord != null && current.taskWorktree?.phase == TaskWorktreePhase.COMPLETE) appendCodingMessage(current,
                    CodingMessage("${request.responseId}-merged", CodingRole.AGENT, "Результат влит в ${current.taskWorktree?.targetBranch}", createdAt = Id.now(), systemNotice = true))
                AppLog.info("coding", "run.finished", operationFields + ("outcome" to if (response.failed) "failed" else "completed"))
            } catch (unconfirmed: SettingsRuntimeUnconfirmed) {
                // Admission was refused before BeginRun. Keep the durable queued input and
                // do not manufacture a run outcome or mark an unrelated worktree as failed.
                AppLog.info("coding", "run.settings_unconfirmed", operationFields)
                _state.update { it.copy(notice = unconfirmed.message) }
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
                    recorder.apply(CodingEvent.Failed("Работа остановлена. Проверьте сохранённый результат перед продолжением."))
                    // Partial output is not a completed response checkpoint.
                    try { admittedRef?.let { ref -> codingProjects?.dispatch(project.id, CodingMachine.Fact.RunOutputPublished(ref, recorder.message(Id.new(), Id.now()))) } }
                    catch (cancelled: CancellationException) {
                        AppLog.info("coding", "run.partial-save.cancelled", operationFields)
                    }
                    catch (refused: CodingCommandRejected) {
                        // The stop that ended this launch already settled its run (the session tree records a stop that lands
                        // before the engine starts), so the run takes no output. Nothing failed to save.
                        AppLog.info("coding", "run.partial-save.refused", operationFields + ("reason" to refused.message.orEmpty()))
                    }
                    catch (failure: Exception) {
                        AppLog.error("coding", "run.partial-save.failed", failure, operationFields)
                        _state.update { it.copy(notice = "Не удалось сохранить промежуточный результат. Проверьте сессию после запуска.") }
                    }
                }
                throw e
            } catch (e: Exception) {
                AppLog.error("coding", "run.failed", e, operationFields + ("causeType" to e::class.simpleName.orEmpty()))
                val task = taskWorktrees?.session(project.id, session.id)?.taskWorktree?.takeIf { it.taskId == request.workspaceTaskId }
                val blocked = e as? SessionQuarantineBlocked
                if (blocked != null) revealQuarantineRecovery(session.id)
                val unconfirmed = e.unconfirmedNativeOutcome()
                val safeError = when {
                    blocked != null -> "Сессия заблокирована: исход прерванной операции не подтверждён. " +
                        "Откройте «Восстановление после прерванной операции» над диалогом."
                    // The run's own failure may only follow from it; this outcome is what the next request has to settle.
                    unconfirmed != null -> unconfirmed.message ?: "Не удалось продолжить работу. Проверьте подключение и состояние сессии."
                    // Занятая папка — действенная ошибка для любого режима: ожидание уже сделано.
                    e is TaskWorkspaceBusy -> e.message ?: "Не удалось завершить работу с Git. Повторите продолжение."
                    request.worktreeEnabled == true && (e is IllegalStateException || e is IllegalArgumentException) ->
                        e.message ?: "Не удалось завершить работу с Git. Повторите продолжение."
                    else -> "Не удалось продолжить работу. Проверьте подключение и состояние сессии."
                }
                if (task != null) try { taskWorktrees!!.failure(project.id, session.id, checkNotNull(request.workspaceTaskId), safeError) }
                catch (noteError: Exception) {
                    if (noteError is CancellationException) throw noteError
                    // The note only annotates the task: losing it must not also lose what the agent did in this run.
                    AppLog.error("coding", "run.task-note.failed", noteError, operationFields)
                    _state.update { it.copy(notice = "Причина сбоя не сохранена в задаче. Ответ агента сохранён в истории.") }
                }
                recorder.apply(CodingEvent.Failed(safeError))
                try {
                    admittedRef?.let { ref -> codingProjects?.dispatch(project.id,
                        CodingMachine.Fact.RunOutputPublished(ref, recorder.message(request.responseId, Id.now()))) }
                } catch (storageError: Exception) {
                    if (storageError is CancellationException) throw storageError
                    if (storageError is CodingCommandRejected) {
                        // Another owner already settled the run (a stop), so it takes no output. Nothing failed to save,
                        // and the reason would vanish with the draft this run clears.
                        AppLog.info("coding", "run.failure-save.refused", operationFields + ("reason" to storageError.message.orEmpty()))
                        _state.update { it.copy(notice = safeError) }
                    } else {
                        AppLog.error("coding", "run.failure-save.failed", storageError, operationFields)
                        _state.update { it.copy(notice = "Не удалось сохранить состояние сессии. Последнее сохранённое состояние доступно после запуска.") }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    val ref = admittedRef
                    val owner = codingProjects
                    val active = ref?.let { owner?.states?.value?.get(project.id)?.run(it) }
                    if (ref != null && owner != null && active?.phase in setOf(CodingMachine.Phase.RUNNING, CodingMachine.Phase.STOPPING)) {
                        val unknown = nativeDispatched && nativeOutcomeUnknown(runtime, session, ref)
                        try { acceptCodingSession(session, CodingMachine.Fact.RunStopped(ref, unknown)) }
                        catch (failure: Exception) {
                            AppLog.error("coding", "run.stop-save.failed", operationFields + ("causeType" to failure::class.simpleName.orEmpty()))
                            _state.update { it.copy(notice = "Не удалось подтвердить сохранение остановки. Проверьте состояние сессии.") }
                        }
                    }
                }
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
        if (ui.session.organismId != null && planningChat != null &&
            !(ui.session.taskWorktree != null && ui.session.interactionMode == CodingInteractionMode.CODE && ui.session.stageId == null)) {
            planningChat.stopManagedSession(sessionId)
            if (sessionId !in codingJobs.value) return
        }
        if (planningChat != null && (ui.plan != null || ui.session.planningMode || ui.session.stageId != null)) {
            planningChat.cancelRequest(ui.session.parentSessionId ?: sessionId)
            ui.plan?.let { planningChat.control(it.id, "stop") }
            return
        }
        scope.launch {
            val run = codingProjects?.states?.value?.get(ui.session.projectId)?.runs?.get(sessionId) ?: return@launch
            acceptCodingSession(ui.session, CodingMachine.Intent.Pause(run.ref))
            codingRuntime?.abort(sessionId)
            codingJobs.value[sessionId]?.cancel()
            // Освободить удержания рабочей директории после остановки сессии
            taskWorktrees?.releaseRetainedLeases(sessionId)
        }
    }

    /** Graceful application exit must not turn resumable work into a user stop. */
    override suspend fun shutdownCoding() {
        closing = true
        val jobs = codingJobs.value.values.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        // Joined, not just cancelled: the archive ticker's producer runs on Dispatchers.Default and would
        // otherwise finish after shutdown returned, resuming its collector on Main from that thread.
        scope.coroutineContext[Job]?.cancelAndJoin()
    }

    override fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) {
        scope.launch { codingRuntime?.respondApproval(id, decision) }
    }

    override fun enableComputerUse(sessionId: String, access: io.aequicor.magicpaper.domain.ComputerAccess) {
        val session = state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (session.running || session.session.stageId != null || session.session.planningMode || session.session.researchMode) return
        scope.launch {
            try {
                val computer = codingRuntime?.computerUse ?: return@launch
                val policy = computer.capturePolicy() ?: throw SettingsRuntimeUnconfirmed()
                requireConfirmedSettings()
                if (computer.enable(sessionId, access, policy)) computer.preview(sessionId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.error("coding", "computer.enable.failed", fields = mapOf("causeType" to failure::class.simpleName.orEmpty()))
                _state.update { it.copy(notice = "Не удалось включить управление. Проверьте состояние настроек и повторите действие.") }
            }
        }
    }

    override fun disableComputerUse(sessionId: String) { codingRuntime?.computerUse?.disable(sessionId) }
    override fun previewComputerUse(sessionId: String) { scope.launch { codingRuntime?.computerUse?.preview(sessionId) } }
    override fun openComputerSystemSettings() { codingRuntime?.computerUse?.openSystemSettings() }
    override val computerPermissions: ComputerPermissions? get() = codingRuntime?.computerUse?.permissions

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
        _state.update { old -> old.copy(coding = old.coding.copy(projects = projects, sessions = sessions.map(::withWorktreeAvailability),
            current = old.coding.current?.takeIf { selected -> projects.any { it.id == selected.id } },
            currentSessionId = old.coding.currentSessionId?.takeIf { selected -> sessions.any { it.session.id == selected } })) }
        projects.forEach(::refreshWorktreeAvailability)
    }

    override suspend fun activate(projectId: String?, sessionId: String?) {
        visible.value = true
        if (projectId == null || _state.value.coding.projects.none { it.id == projectId }) {
            _state.update { it.copy(coding = it.coding.copy(current = null, currentSessionId = null)) }
            return
        }
        AppLog.phase("coding", "open.project", mapOf("projectId" to projectId)) { openCodingProject(projectId) }
        // The route may target a session other than the project's most recent one.
        // Load its history after openCodingProject has rebuilt the projections.
        if (sessionId != null) {
            val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId && it.session.projectId == projectId }
            if (selected != null && selected.messages.isEmpty() && !selected.running) {
                val messages = AppLog.phase("coding", "open.history", mapOf("sessionId" to sessionId)) {
                    codingProjects?.messages(projectId, sessionId).orEmpty()
                }
                updateCodingSession(sessionId) { it.copy(messages = messages) }
            }
        }
        currentCoroutineContext().ensureActive()
        _state.update { state -> state.copy(coding = state.coding.copy(currentSessionId = sessionId?.takeIf { id ->
            state.coding.sessions.any { it.session.id == id && it.session.projectId == projectId }
        })) }
    }
    private val settingsApplyLock = Mutex()

    override suspend fun prepareSettings(previous: AppSettings, next: AppSettings) = settingsApplyLock.withLock {
        // Every pending configuration is unconfirmed. Invalidation is synchronous and does
        // not wait for an OS permission request or its journal acknowledgement.
        codingRuntime?.computerUse?.invalidatePolicy()
        Unit
    }

    override suspend fun applySettings(settings: AppSettings): Result<Unit> = settingsApplyLock.withLock {
        val result = planningChat?.organisms?.applySettingsLimits(settings) ?: Result.success(Unit)
        if (result.isSuccess) codingRuntime?.computerUse?.configure(settings.computerAccess, settings.applicationAccess)
        result
    }

    private class SettingsRuntimeUnconfirmed : IllegalStateException(
        "Изменение настроек не завершено. Проверьте настройки и повторите действие.",
    )

    private suspend fun requireConfirmedSettings() {
        if (settingsCommands.runtimePolicy() !is SettingsRuntimePolicy.Confirmed) throw SettingsRuntimeUnconfirmed()
    }

    override suspend fun clearProfileOverrides(id: String) {
        val owner = codingProjects ?: return
        for (project in owner.all()) owner.dispatch(project.id, CodingMachine.Intent.UnlinkProfile(id))
        reload()
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
        journalObserver = null; journalFailureObserver = null
        liveQueuedRequests.clear(); liveImmunitySignals = emptySet(); knownImmunitySignals = emptySet()
        visible.value = false
        codingJobs.value = emptyMap()
        queuedComputerRequests.value = emptyMap()
        _immunityActions.value = emptySet()
        _quarantineRecovery.value = QuarantineRecoveryState()
        sessionTitles?.clear()
        interactionDecisions.clear()
        interactionSubmitting.clear()
        interactionErrors.clear()
        requestedInputRecovery.clear()
        decisionStorageRecovery = null
        _state.value = CodingState()
        started = false
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

/** A rebuild of the session list slower than this is reported at INFO; it runs after every saved change. */
private const val SLOW_PROJECTION_MILLIS = 100L
