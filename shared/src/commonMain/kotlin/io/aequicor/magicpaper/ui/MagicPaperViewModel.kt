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
import io.aequicor.magicpaper.domain.CodingRunCheckpoint
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.domain.interruptedCodingRequest
import io.aequicor.magicpaper.domain.recordDrafts
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
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
    private val searchConnectionChecker: SearchConnectionChecker? = null,
    requestPinRepository: RequestPinRepository? = null,
    val usage: io.aequicor.magicpaper.domain.UsageLedger = io.aequicor.magicpaper.domain.UsageLedger(io.aequicor.magicpaper.data.storage.JsonUsageRepository(store, json)),
    private val workerDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val projectSkills get() = codingRuntime?.projectSkills

    suspend fun checkSearchConnection(connection: SearchConnection, draft: AppSettings): SearchConnectionResult =
        searchConnectionChecker?.check(connection, draft)
            ?: SearchConnectionResult(false, "Проверка подключения недоступна.")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val requestPins = requestPinRepository?.let { RequestPinService(it, gateway, scope, json) { conversation ->
        if (conversation.projectId == null) io.aequicor.magicpaper.domain.UsageScope.chat(conversation.sessionId)
        else _state.value.coding.sessions.firstOrNull { it.session.id == conversation.sessionId }?.session
            ?.let(io.aequicor.magicpaper.domain.UsageScope::coding)
            ?: io.aequicor.magicpaper.domain.UsageScope("coding:${conversation.sessionId}", projectId = conversation.projectId)
    } }
    /** Начатые сессии плана получают короткое название; вызов модели вне прогона. */
    val sessionTitles = if (codingProjects != null && gateway != null)
        SessionTitleService(codingProjects, profileRepo, settingsRepo, gateway, scope) else null

    private val interactionQueue = UserInteractionQueue()
    private val interactionDecisions = runCatching { json.decodeFromString<Set<String>>(store.read("coding-interaction-decisions") ?: "[]") }.getOrDefault(emptySet()).toMutableSet()
    private val interactionSubmitting = mutableSetOf<String>()
    private val interactionErrors = mutableMapOf<String, String>()
    private var decisionStorageRecovery: UserInteractionRequest? = null
    private val requestedInputRecovery = mutableSetOf<String>()
    private val _questionnaireDrafts = MutableStateFlow<Map<String, QuestionnaireDraft>>(emptyMap())
    val questionnaireDrafts = _questionnaireDrafts.asStateFlow()
    // Kept above the composer lifetime so switching sessions and temporarily replacing it cannot lose attachments.
    val composerDrafts = mutableMapOf<String, io.aequicor.magicpaper.ui.components.CodingComposerDraft>()

    fun updateQuestionnaireDraft(id: String, draft: QuestionnaireDraft) {
        if (id !in interactionSubmitting) _questionnaireDrafts.update { it + (id to draft) }
    }

    private fun currentInteractionCandidates(): List<UserInteractionRequest> = interactionCandidates(
        _state.value.coding, planningChat?.store?.plans?.value.orEmpty(), planningChat?.states?.value.orEmpty(),
        planningChat?.persistenceErrors?.value.orEmpty(), codingRuntime?.questionnaires?.value.orEmpty(), requestedInputRecovery) + listOfNotNull(decisionStorageRecovery)

    private fun saveInteractionDecisions() = store.write("coding-interaction-decisions",
        json.encodeToString(interactionDecisions.filterNot { it.startsWith("runtime:") || it.startsWith("approval:") }.toSet()))

    private fun legacyInteractionAliases() = legacyRecoveryDecisionAliases(
        _state.value.coding, planningChat?.store?.plans?.value.orEmpty(), interactionDecisions)

    private fun refreshInteractions() {
        val queued = interactionQueue.reconcile(currentInteractionCandidates(),
            interactionDecisions.withLegacyRecoveryDecisions(legacyInteractionAliases())).map { request ->
            request.copy(submitting = request.submitting || request.id in interactionSubmitting,
                error = interactionErrors[request.id] ?: request.error)
        }
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
    fun openQuestionnaire(kind: InteractionKind, sourceId: String) {
        if (kind == InteractionKind.RECOVER_INPUT) requestedInputRecovery.add(sourceId)
        val targets = currentInteractionCandidates().filter { it.kind == kind &&
            (it.sourceId == sourceId || it.planId == sourceId || it.ownerSessionId == sourceId) }
        interactionDecisions.reopenInteractionDecisions(targets.map { it.id }, legacyInteractionAliases())
        if (targets.isNotEmpty()) {
            store.write("coding-interaction-decisions", json.encodeToString(interactionDecisions.toSet()))
            refreshInteractions()
        }
    }

    fun submitQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        val request = _state.value.coding.interactions.firstOrNull { it.id == id } ?: return
        if (request.submitting || !interactionSubmitting.add(id)) return
        interactionErrors.remove(id)
        refreshInteractions()
        scope.launch {
            try {
                val fresh = currentInteractionCandidates().firstOrNull { it.id == id } ?: error("Обращение уже закрыто")
                require(fresh.questions == request.questions && fresh.details == request.details && fresh.revision == request.revision) { "Обращение изменилось. Проверьте актуальные данные." }
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
                    try { saveInteractionDecisions() } catch (e: Exception) {
                        // The action already happened. Retrying storage must never repeat the action.
                        decisionStorageRecovery = request.copy(id = "decision-storage:$id", sourceId = id,
                            kind = InteractionKind.RECOVER_DECISIONS, revision = null, planId = null,
                            questions = listOf(PlanningQuestion("decision", "Не удалось сохранить принятое решение. Как продолжить?", QuestionKind.SINGLE,
                                listOf(QuestionOption("retry", "Повторить сохранение"), QuestionOption("leave", "Оставить остановленной")), allowCustomInput = false)),
                            details = e.message ?: "Хранилище недоступно", initialAnswers = emptyList(), submitting = false, error = null)
                    }
                }
                _questionnaireDrafts.update { it - id }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { interactionErrors[id] = e.message ?: "Не удалось передать ответ. Повторите подтверждение." }
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

    init {
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
        scope.launch { bootstrap() }
        requestPins?.let { pins -> scope.launch {
            var previousOpen: PinConversation? = null
            // Draft/status updates keep the same saved message list. Do not rebuild
            // every pin and rescan every conversation on each streamed token.
            val pinSources = mutableMapOf<PinConversation, Any>()
            var previousProfile: LlmProfile? = null
            _state.collect { state ->
                val opened = if (state.viewingCoding) {
                    state.coding.currentSession?.session?.let { PinConversation(it.id, it.projectId) }
                } else {
                    state.current?.let { PinConversation(it.id) }
                }
                // Same operational model used by generateModelDescriptions, independent of chat overrides.
                val profile = ProfileResolver.resolve(null as ChatSession?, state.settings, state.availableLlmProfiles)
                    ?.takeIf { it.provider != ProviderType.OPENAI_SUBSCRIPTION || state.openAiSubscription.account?.signedIn == true }
                if (profile != previousProfile) pinSources.clear()
                previousProfile = profile
                val existingPins = state.sessions.map { PinConversation(it.id) } +
                    state.coding.sessions.map { PinConversation(it.session.id, it.session.projectId) }
                pinSources.keys.retainAll(existingPins.toSet())
                state.sessions.forEach { session ->
                    val key = PinConversation(session.id)
                    if (key == opened || pins.isTracking(key)) {
                        val current = state.current?.takeIf { it.id == session.id } ?: session
                        val reopened = key == opened && key != previousOpen
                        if (reopened || pinSources[key] !== current.messages) {
                            pins.sync(key, current.pinMessages(), profile, reopened = reopened)
                            pinSources[key] = current.messages
                        }
                    }
                }
                state.coding.sessions.forEach { session ->
                    val key = PinConversation(session.session.id, session.session.projectId)
                    if (key == opened || session.running || pins.isTracking(key)) {
                        val reopened = key == opened && key != previousOpen
                        if (reopened || pinSources[key] !== session.messages) {
                            pins.sync(key, session.messages.pinMessages(session.session.effectiveRole == CodingSessionRole.ORCHESTRATOR), profile, reopened = reopened)
                            pinSources[key] = session.messages
                        }
                    }
                }
                previousOpen = opened
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
                        withPlanningState(previous.copy(session = session, messages = histories[session.id] ?: previous.messages))
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
                    val updated = state.coding.sessions.map(::withPlanningState)
                    // withPlanningState returns the same reference when nothing changed.
                    // Skip the copy entirely to avoid triggering Compose recomposition
                    // every 50 ms when no planning state actually changed.
                    if (updated.indices.all { i -> updated[i] === state.coding.sessions[i] }) state
                    else state.copy(coding = state.coding.copy(sessions = updated))
                }
            }
        } }

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
                    sessions = loadCodingSessions(projects),
                    projectStatuses = codingStatusSnapshot(projects),
                ),
            )
        }
        projects.firstOrNull()?.let { project -> openCodingProject(project.id) }
        refreshCodingEngines()
        restoreCodingRuns(projects)
        startPendingImmunityDiagnostics()
        if (openAiSubscription != null && profiles.any { it.provider == ProviderType.OPENAI_SUBSCRIPTION }) {
            refreshOpenAiSubscription()
        }
    }

    /** Сводные статусы всех проектов (по первой загрузке, без активных прогонов). */
    private suspend fun loadCodingSessions(projects: List<CodingProject>): List<CodingSessionUi> {
        val repo = codingProjects ?: return emptyList()
        return projects.flatMap { project -> repo.sessions(project.id).map { session ->
            withPlanningState(CodingSessionUi(session, repo.messages(project.id, session.id)))
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
    fun codingProfileOf(session: CodingSession, plan: Plan? = null): LlmProfile? {
        val s = _state.value
        if (session.planningMode) return session.modelSelection?.let { ProfileResolver.selection(it, s.availableLlmProfiles) } ?: ProfileResolver.resolve(null as ChatSession?, s.settings, s.availableLlmProfiles)
        val workerPlan = plan ?: planningChat?.store?.plans?.value?.firstOrNull { it.id == session.planId }
            ?: s.coding.sessions.firstOrNull { it.session.id == session.id }?.plan
        return ProfileResolver.coding(session, s.coding.projects.firstOrNull { it.id == session.projectId }, s.settings, s.availableLlmProfiles, workerPlan)
    }

    // ---- Навигация -------------------------------------------------------

    fun openEnginesSettings() { _state.update { it.copy(screen = Screen.SETTINGS, enginesSettingsOpen = true) }; refreshCodingEngines() }
    fun closeEnginesSettings() = _state.update { it.copy(enginesSettingsOpen = false) }
    fun requestCodingSession() = _state.update { it.copy(coding = it.coding.copy(creatingSession = true)) }

    /** Выбрать проект и открыть диалог создания сессии (для единой боковой панели). */
    fun requestCodingSessionInProject(projectId: String) {
        scope.launch {
            openCodingProject(projectId)
            _state.update { it.copy(viewingCoding = true, coding = it.coding.copy(creatingSession = true)) }
        }
    }
    fun cancelCodingSessionCreation() = _state.update { it.copy(coding = it.coding.copy(creatingSession = false)) }
    fun refreshCodingEngines() {
        val runtime = codingRuntime ?: return
        scope.launch {
            for (engine in CodingEngine.entries) {
                val status = try { runtime.status(engine) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { RuntimeStatus(RuntimePhase.ERROR, e.message.orEmpty()) }
                _state.update { it.copy(coding = it.coding.copy(engines = it.coding.engines + (engine to status))) }
            }
        }
    }

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

    /** true поглощает Paste только если буфер содержит вложения. */
    fun pasteAttachments(alreadyAttached: Int, onResult: (List<Attachment>) -> Unit): Boolean {
        val read = filePicker?.clipboardFiles() ?: return false
        val room = (MAX_ATTACHMENTS_PER_MESSAGE - alreadyAttached).coerceAtLeast(0)
        if (room == 0) {
            _state.update { it.copy(notice = "Не больше $MAX_ATTACHMENTS_PER_MESSAGE вложений на сообщение.") }
            return true
        }
        scope.launch {
            try {
                val picked = read()
                onResult(picked.take(room).map { Attachment.fromBytes(it.name, it.mimeType, it.bytes) })
                if (picked.size > room) {
                    _state.update { it.copy(notice = "Не больше $MAX_ATTACHMENTS_PER_MESSAGE вложений на сообщение.") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message ?: "Не удалось вставить вложение из буфера обмена.") }
            }
        }
        return true
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
                    viewingCoding = false,
                )
        }
        scope.launch { chats.save(session) }
    }

    fun selectSession(id: String) {
        scope.launch {
            val session = chats.session(id) ?: return@launch
            _state.update { it.copy(current = session, viewingCoding = false) }
        }
    }

    fun deleteSession(id: String) {
        scope.launch {
            requestPins?.remove(PinConversation(id))
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

    // Per-session jobs for regular chat (not coding sessions, which use codingJobs)
    private val chatJobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    
    private fun removeChatJob(sessionId: String): Job? = chatJobs.getAndUpdate { it - sessionId }[sessionId]

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        val visible = attachments.chatVisible()
        if (trimmed.isEmpty() && visible.isEmpty()) return
        val settings = _state.value.settings
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
        val historyBefore = session.messages
        val updated = session.copy(
            messages = session.messages + userMessage,
            title = if (session.messages.isEmpty()) trimmed.take(40) else session.title,
            updatedAt = Id.now(),
        )
        val requestProfile = ProfileResolver.resolve(updated, settings, _state.value.availableLlmProfiles)
        val operationalProfile = ProfileResolver.resolve(null as ChatSession?, settings, _state.value.availableLlmProfiles)
        _state.update { st ->
            st.copy(
                current = updated,
                sessions = st.sessions.map { if (it.id == updated.id) updated else it },
            )
        }
        val job = scope.launch(workerDispatcher + io.aequicor.magicpaper.domain.UsageOwner(io.aequicor.magicpaper.domain.UsageScope.chat(session.id))) {
            try {
                chats.save(updated)
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
                    )
                }
            } finally {
                chatJobs.update { it - session.id }
            }
        }
        chatJobs.update { it + (session.id to job) }
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
            val applied = try {
                planningChat?.organisms?.saveSettingsAndApplyLimits(settings) ?: run {
                    settingsRepo.save(settings)
                    Result.success(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = "Не удалось сохранить настройки: ${error.message}") }
                return@launch
            }
            val notice = applied.exceptionOrNull()?.let { "Настройки сохранены. Не удалось применить ограничения: ${it.message}" }
                ?: "Настройки сохранены."
            _state.update { it.copy(settings = settings, notice = notice) }
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
                codingRepo?.updateSession(item.session.projectId, item.session.id) { it.copy(llmProfileId = null) }?.let { cleared ->
                    updateCodingSession(item.session.id) { it.copy(session = cleared) }
                }
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
            updateStoredCodingSession(ui.session) { it.copy(modelSelection = selection, llmProfileId = selection.profileId) }
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

    /** Обновить параметры вариантов из каталога провайдера (если они не переопределены вручную). */
    fun refreshProfileParameters(profileId: String) {
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
                usage = usage.state.value,
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
            requestPins?.clear()
            usage.replace(bundle.usage)
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
            requestPins?.clear()
            usage.clear()
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

    fun prepareCodingRuntime(engine: CodingEngine = CodingEngine.PI) {
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
            catch (e: Exception) { _state.update { it.copy(notice = e.message) } }
            finally { _state.update { it.copy(coding = it.coding.copy(preparingEngines = it.coding.preparingEngines - engine)) } }
        }
    }

    fun uninstallCodingRuntime(engine: CodingEngine = CodingEngine.PI) {
        val runtime = codingRuntime ?: return
        // Dependency removal is unavailable while any work is using the shared adapters.
        if (_state.value.coding.sessions.any { it.running } || planningChat?.execution?.live?.value?.isNotEmpty() == true) {
            _state.update { it.copy(notice = "Сначала остановите выполняющиеся сессии и планы.") }; return
        }
        scope.launch {
            try { runtime.uninstall(engine); refreshCodingEngines() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = e.message) } }
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
            repo.sessions(project.id).forEach { repo.deleteSession(project.id, it.id) }
            _state.update { it.copy(coding = it.coding.copy(projects = repo.all())) }
            openCodingProject(project.id)
            requestCodingSession()
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
            withPlanningState(CodingSessionUi(
                session = session,
                messages = repo.messages(projectId, session.id),
                running = codingJobs.value[session.id]?.isActive == true,
            ))
        }
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

    fun approveImmunityIntervention(organismId: String, proposalId: String, action: ImmunityAction, deleteConfirmed: Boolean = false) {
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
                    val running = ids.mapNotNull { removeCodingJob(it) }
                    running.forEach { it.cancel() }
                    ids.forEach { runCatching { codingRuntime?.abort(it) } }
                    running.joinAll()
                    ids.addAll(planningChat.deleteSessionTree(projectId, target))
                    ids.forEach { requestPins?.remove(PinConversation(it, projectId)); sessionTitles?.forget(it) }
                    _state.update { state ->
                        val rest = state.coding.sessions.filterNot { it.session.id in ids }
                        state.copy(coding = state.coding.copy(sessions = rest,
                            currentSessionId = state.coding.currentSessionId?.takeUnless { it in ids }
                                ?: rest.firstOrNull { it.session.projectId == projectId }?.session?.id))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось выполнить действие иммунитета") } }
            finally { _immunityActions.update { it - key } }
        }
    }

    fun dismissImmunityIntervention(organismId: String, proposalId: String) {
        val service = planningChat?.organisms ?: return
        scope.launch {
            try { service.dismissImmunityIntervention(organismId, proposalId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось отклонить предложение") } }
        }
    }

    fun deleteCodingProject(id: String) {
        val repo = codingProjects ?: return
        while (true) {
            val pending = deletingCodingProjects.value
            if (id in pending) return
            if (deletingCodingProjects.compareAndSet(pending, pending + id)) break
        }
        scope.launch {
            try {
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
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось удалить проект") } }
            finally { deletingCodingProjects.update { it - id } }
        }
    }

    // ---- Кодинг-сессии проекта ------------------------------------------------

    /** Новая кодинг-сессия в текущем проекте: отдельный контекст и журнал. */
    fun addCodingSession(engine: CodingEngine = _state.value.settings.defaultCodingEngine) {
        val repo = codingProjects ?: return
        val project = _state.value.coding.current ?: return
        if (project.id in deletingCodingProjects.value) return
        scope.launch {
            if (project.id in deletingCodingProjects.value || repo.all().none { it.id == project.id }) return@launch
            val session = CodingSession(
                id = Id.new(),
                projectId = project.id,
                name = "Новая сессия",
                engine = engine,
                createdAt = Id.now(),
                modelSelection = project.modelSelection ?: ProfileResolver.favoriteDefault(_state.value.settings, _state.value.availableLlmProfiles, coding = true),
            )
            repo.saveSession(session)
            val initialMessages = repo.messages(project.id, session.id)
            _state.update {
                it.copy(
                    coding = it.coding.copy(
                        sessions = listOf(CodingSessionUi(session = session, messages = initialMessages)) + it.coding.sessions,
                        currentSessionId = session.id,
                        creatingSession = false,
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
            _state.update { it.copy(screen = Screen.CHAT, viewingCoding = true) }
            if (plan != null) { openCodingProject(plan.projectId); selectCodingSession(plan.parentSessionId) }
        }
    }

    fun selectCodingSession(id: String) {
        val state = _state.value
        val sessionUi = state.coding.sessions.firstOrNull { it.session.id == id }
        val project = if (sessionUi != null && state.coding.current?.id != sessionUi.session.projectId)
            state.coding.projects.firstOrNull { it.id == sessionUi.session.projectId }
        else null
        _state.update { st ->
            st.copy(
                coding = st.coding.copy(
                    current = project ?: st.coding.current,
                    currentSessionId = id,
                ),
                viewingCoding = true,
            )
        }
    }

    /** Переключатель режима просмотра: чат или кодинг. */
    fun setViewingCoding(viewingCoding: Boolean) {
        _state.update { it.copy(viewingCoding = viewingCoding) }
    }

    /** Выбор сессии из единой боковой панели. */
    fun selectUnifiedSession(id: String, isCoding: Boolean) {
        if (isCoding) {
            val state = _state.value
            val sessionUi = state.coding.sessions.firstOrNull { it.session.id == id }
            if (sessionUi != null) {
                // Если сессия принадлежит другому проекту — переключаем
                // текущий проект, иначе CodingScreen не найдёт выбранную
                // сессию в списке текущего проекта и покажет пустой хинт.
                val project = if (state.coding.current?.id != sessionUi.session.projectId)
                    state.coding.projects.firstOrNull { it.id == sessionUi.session.projectId }
                else null
                _state.update { st ->
                    st.copy(
                        coding = st.coding.copy(
                            current = project ?: st.coding.current,
                            currentSessionId = id,
                        ),
                        viewingCoding = true,
                    )
                }
            }
        } else {
            selectSession(id)
        }
    }

    /** Удалить сессию с её журналом (активный прогон прерывается). */
    fun deleteAllCodingSessions(projectId: String) {
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
                _state.update { state ->
                    val remaining = state.coding.sessions.filterNot { it.session.projectId == projectId }
                    state.copy(coding = state.coding.copy(sessions = remaining,
                        currentSessionId = state.coding.currentSessionId?.takeIf { id -> remaining.any { it.session.id == id } },
                        projectStatuses = state.coding.projectStatuses - projectId))
                }
                refreshProjectStatus(projectId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось удалить сессии") } }
        }
    }

    fun archiveCodingSession(id: String) {
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

    fun deleteCodingSession(id: String) {
        val repo = codingProjects ?: return
        val coding = _state.value.coding
        val target = coding.sessions.firstOrNull { it.session.id == id } ?: return
        scope.launch {
            try {
                if (target.session.stageId != null && planningChat != null && planningChat.hasLiveOrchestrator(target.session)) {
                    planningChat.archiveSession(id)
                    return@launch
                }
                val projectId = target.session.projectId
                val ids = repo.sessions(projectId).sessionTreeIds(id).toMutableSet()
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
                ids.forEach { requestPins?.remove(PinConversation(it, projectId)); sessionTitles?.forget(it) }
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
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось удалить сессию") } }
        }
    }

    fun changeCodingInteractionMode(sessionId: String, mode: CodingInteractionMode) {
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
            catch (e: Exception) { _state.update { it.copy(notice = e.message ?: "Не удалось изменить режим") } }
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
        val selected = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId }?.session ?: return
        if (planningChat != null && (selected.planningMode || selected.stageId != null)) {
            planningChat.send(selected, text); return
        }
        if (text.isBlank() && attachments.isEmpty()) return
        launchCodingRun(selected, CodingRunCheckpoint(Id.new(), text.trim(), attachments), recovering = false)
    }

    fun resumeCodingSession(sessionId: String, text: String = "", attachments: List<Attachment> = emptyList(), fromQuestionnaire: Boolean = false) {
        val ui = _state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (!(if (fromQuestionnaire) ui.copy(interactions = emptyList()) else ui).canResume) return
        if (planningChat != null && (ui.plan != null || ui.session.planningMode || ui.session.stageId != null)) {
            planningChat.resume(ui.session, text); return
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

    private fun launchCodingRun(session: CodingSession, checkpoint: CodingRunCheckpoint, recovering: Boolean, additionalMessage: CodingMessage? = null, userInitiated: Boolean = true) {
        val runtime = codingRuntime ?: return
        val project = _state.value.coding.projects.firstOrNull { it.id == session.projectId } ?: return
        // A cancelled job still owns the session while its runtime and saved output
        // are being cleaned up. Its finally block releases this entry.
        if (closing || session.projectId in deletingCodingProjects.value || session.id in codingJobs.value) return
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
        val job = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) {
            try {
                if (userInitiated) planningChat?.prepareManagedUserTurn(session, checkpoint.messageId)
                var current = updateStoredCodingSession(session) { latest ->
                    require(latest.interactionMode == session.interactionMode) { "Режим сессии изменился. Отправьте запрос повторно." }
                    request = request.copy(interactionMode = request.interactionMode ?: latest.interactionMode)
                    latest.namedFromPrompt(request.prompt).copy(pendingRun = request).forPendingRun()
                }
                if (codingProjects!!.messages(project.id, session.id).none { it.id == request.messageId }) {
                    appendCodingMessage(session, CodingMessage(request.messageId, CodingRole.USER,
                        request.prompt, createdAt = Id.now(), attachments = request.attachments.map { it.asMeta() },
                        images = request.attachments.mapNotNull { it.asCodingInputImage(checkNotNull(recorder.imageInvocation)) }))
                }
                additionalMessage?.let { appendCodingMessage(session, it) }
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
            } catch (e: CancellationException) {
                runtime.abort(session.id)
                // Shutdown retains RUN; an explicit stop has already persisted STOP.
                if (recorder.timeline().isNotEmpty()) withContext(NonCancellable) {
                    recorder.apply(CodingEvent.Failed(if (closing) "Работа продолжится после запуска приложения." else "Работа остановлена. Нажмите «Продолжить»."))
                    // Partial output is not a completed response checkpoint.
                    runCatching { appendCodingMessage(session, recorder.message(Id.new(), Id.now())) }
                }
                throw e
            } catch (e: Exception) {
                recorder.apply(CodingEvent.Failed(e.message ?: "Не удалось продолжить работу"))
                try {
                    appendCodingMessage(session, recorder.message(request.responseId, Id.now()))
                    updateStoredCodingSession(session) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP)) }
                } catch (storageError: Exception) {
                    if (storageError is CancellationException) throw storageError
                    _state.update { it.copy(notice = "Не удалось сохранить состояние сессии: ${storageError.message}") }
                }
            } finally {
                fun clearRun() {
                    codingJobs.update { it - session.id }
                    updateCodingSession(session.id) { it.copy(running = false, draft = idleCodingDraft) }
                }
                // Keep cleanup atomic with respect to UI starts. A shutdown hook can
                // run while AWT is exiting, so shutdown must not wait for the UI thread.
                if (closing) clearRun()
                else withContext(NonCancellable + Dispatchers.Main.immediate) { clearRun(); startPendingImmunityDiagnostics() }
                withContext(NonCancellable) {
                    runCatching { refreshProjectStatus(project.id) }.onFailure { failure ->
                        _state.update { it.copy(notice = "Не удалось прочитать состояние сессии: ${failure.message}") }
                    }
                }
            }
        }
        codingJobs.update { it + (session.id to job) }
        job.start()
    }

    /** Прервать прогон текущей сессии (процесс её агента). */
    fun abortCodingRun() {
        val id = _state.value.coding.currentSession?.session?.id ?: return
        abortCodingSession(id)
    }

    fun abortCodingSession(sessionId: String) {
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
        scope.launch {
            updateStoredCodingSession(ui.session) { it.copy(pendingRun = it.pendingRun?.copy(intent = ExecutionIntent.STOP, stoppedByUser = true)) }
            codingRuntime?.abort(sessionId)
            codingJobs.value[sessionId]?.cancel()
        }
    }

    /** Graceful application exit must not turn resumable work into a user stop. */
    suspend fun shutdownCoding() {
        closing = true
        val jobs = codingJobs.value.values.toList()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        scope.cancel()
    }

    fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) {
        scope.launch { codingRuntime?.respondApproval(id, decision) }
    }

    fun enableComputerUse(sessionId: String, access: io.aequicor.magicpaper.domain.ComputerAccess) {
        val session = state.value.coding.sessions.firstOrNull { it.session.id == sessionId } ?: return
        if (session.running || session.session.stageId != null || session.session.planningMode || session.session.researchMode) return
        scope.launch {
            codingRuntime?.computerUse?.let { computer ->
                computer.enable(sessionId, access)
                computer.preview(sessionId)
            }
        }
    }

    fun disableComputerUse(sessionId: String) { codingRuntime?.computerUse?.disable(sessionId) }
    fun previewComputerUse(sessionId: String) { scope.launch { codingRuntime?.computerUse?.preview(sessionId) } }
    fun openComputerSystemSettings() { codingRuntime?.computerUse?.openSystemSettings() }

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
        closing = true
        scope.cancel()
        codingRuntime?.computerUse?.disable()
        openAiSubscription?.close()
        super.onCleared()
    }
}
