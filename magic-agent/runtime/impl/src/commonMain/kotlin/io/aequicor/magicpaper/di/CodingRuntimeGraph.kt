package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryDraftRepository
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import kotlinx.serialization.json.Json

/** Coding's mutually connected runtime collaborators have one application lifetime. */
class CodingRuntimeGraph(
    store: KeyValueStore,
    private val json: Json,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val codingProjects: CodingProjectOwner?,
    private val codingRuntime: CodingRuntime,
    private val planningWorkspace: PlanningWorkspace,
    private val integrationChecks: SessionIntegrationCheckRunner?,
    private val usageLedger: UsageLedger,
    private val gateway: LlmGateway,
    private val search: SearchEngine,
    draftRepository: DraftRepository = InMemoryDraftRepository(),
    events: EventJournal = InMemoryEventJournal(),
    taskWorkspace: TaskWorkspace = UnavailableTaskWorkspace,
    private val sourceAccess: ResearchSourceAccess = ResearchSourceAccess(),
    mediaGeneration: MediaGenerationService? = null,
    mediaAllowed: suspend (ToolExecutionContext, MediaKind) -> Boolean = { _, _ -> true },
    orchestrationFactory: ((OrchestrationActions) -> CustomOrchestration)? = null,
    val toolQuestions: RuntimeQuestionnaireService,
    val toolReceipts: ToolReceiptStore,
    toolSessionFactory: ToolSession.Factory,
    mediaToolFactory: MediaToolCommands.Factory,
    mediaToolReceipts: MediaToolReceiptOwner,
    questionnaireToolFactory: QuestionnaireToolCommands.Factory,
    organismStoreFactory: SessionOrganismStoreFactory,
    planningStoreFactory: PlanningStoreFactory,
    private val taskWorktreeOwner: TaskWorktreeOwner,
    private val modelDossiers: ModelDossierRepository,
    private val settingsCommands: SettingsCommands,
    private val nativeModels: NativeModelSnapshots = NativeModelSnapshots.None,
) {
    val organisms: SessionOrganismService? = codingProjects?.let { io.aequicor.magicpaper.domain.SessionOrganismService(
        organismStoreFactory.create(::knownToolSecrets), it, settingsRepo,
        sourceSnapshot = { project -> planningWorkspace.verificationSnapshot(project.path) },
        execution = object : SessionOrganismExecution {
            override suspend fun start(session: CodingSession, task: SessionTask, scopeOwner: String?) = tree().executions.start(session, task, scopeOwner)
            override suspend fun stop(sessionIds: Set<String>) = tree().executions.stop(sessionIds)
            override suspend fun await(sessionIds: Set<String>) = tree().executions.await(sessionIds)
        }, sources = object : SessionOrganismSourceAccess {
            override suspend fun canCreateCodeChild(session: CodingSession) = tree().sources.canCreateCodeChild(session)
            override suspend fun project(session: CodingSession, project: CodingProject) = tree().sources.project(session, project)
            override suspend fun lease(session: CodingSession, project: CodingProject) = tree().sources.lease(session, project)
            override suspend fun inspect(result: SessionResult) = tree().sources.inspect(result)
        }, integrationWorkspaces = integrationChecks?.let { checks -> SessionIntegrationWorkspaces(planningWorkspace, checks, ::knownToolSecrets) },
        reconcilePlanRetry = { request -> PlanRetryNativeRecovery(runtime, toolReceipts, ::knownToolSecrets).reconcile(request) },
        reconcileUnknownOutcomes = { request -> SessionQuarantineRecovery(runtime, toolReceipts, ::knownToolSecrets).reconcile(request) },
        canRecreateAfterQuarantine = { ids, snapshot -> ids.all { owner ->
            toolReceipts.forOwner(snapshot.projectId, owner)?.none { receipt -> receipt.mutating && receipt.phase in
                setOf(ToolPhase.STARTED, ToolPhase.UNKNOWN, ToolPhase.WAITING, ToolPhase.PROGRESS) } == true
        } }) }
    val sessionTree: SessionTreeRuntime? = organisms?.let { io.aequicor.magicpaper.domain.SessionTreeRuntime(it, codingProjects!!, profileRepo, settingsRepo, planningWorkspace = planningWorkspace,
        runtimeProvider = { runtime }, cancelQuestions = { sessionId ->
            try { toolQuestions.revoke(sessionId) }
            finally { organisms?.reconcileIntegrationsForSession(sessionId) }
        }, externalPlanStop = { rootId, caller -> planningChat?.stopOwnedPlanRuns(rootId, caller) ?: true }) }
    val taskWorktrees = codingProjects?.let { projects ->
        val tree = checkNotNull(sessionTree)
        TaskWorktreeService(CodingTaskWorktreeSessionAccess(projects), taskWorkspace, planningWorkspace,
            taskWorktreeOwner, object : TaskWorktreeRuntimeAccess {
                override suspend fun requireQuiescent(sessionId: String) = tree.requireTaskQuiescent(sessionId)
                override suspend fun releaseUnownedLeases() = tree.releaseUnownedRootLeases()
            })
    }
    private val toolAuthority = OrganismToolAuthority(organisms, codingProjects, codingRuntime)
    val mediaTools = mediaToolFactory.create(toolReceipts, mediaGeneration, mediaAllowed,
        checkScope = { planningTools().checkScope(it) }, authorizeTool = toolAuthority::authorizeTool, receiptOwner = mediaToolReceipts)
    val questionnaireTools = questionnaireToolFactory.create(toolQuestions)
    private val toolActions = OrchestrationActions { context, operation, tool, arguments ->
        planningTools().execute(context, operation, tool, arguments)
    }
    private val toolCommands = ApplicationToolCommands(questionnaireTools, mediaTools, toolActions,
        orchestrationFactory?.invoke(toolActions),
        taskHandoff = { context, result ->
            checkNotNull(taskWorktrees).handoff(context, result.outcome == TaskHandoffOutcome.RESULT, result.checks)
        }, search = ::searchTools)
    val toolSessions = ToolSessionFactory(toolReceipts, questionnaireTools, mediaTools, ::planningTools, toolAuthority, toolCommands, toolSessionFactory)
    val runtime: CodingRuntime = MeteredCodingRuntime(ToolEnabledCodingRuntime(codingRuntime, toolSessions,
        toolQuestions, mediaTools, { session -> planningTools().prepareWorker(session) }, sessionTree), usageLedger)

    // These closures resolve only when a run starts. Constructing the graph never calls back
    // through runtime -> composer -> orchestration, so every collaborator has one lifetime.
    private fun tree(): SessionTreeRuntime = checkNotNull(sessionTree) { "Дочерний runtime недоступен" }
    private fun planningTools(): PlanningToolAccess = checkNotNull(planningChat) { "Планировщик недоступен" }
    private fun knownToolSecrets(): Set<String> = planningChat?.knownSecrets().orEmpty()
    val planningStore = planningStoreFactory.create(::knownToolSecrets)
    val acceptanceChecks = io.aequicor.magicpaper.domain.AcceptanceChecks()
    /** Recommendations follow the rollout flag; admission and recovery judge saved plans by the catalog whatever the flag says. */
    private val nativeRecommendations = NativeModelSnapshots { engine ->
        if (settingsRepo.load().featureFlags.isEnabled(FeatureFlag.NATIVE_CODING_MODELS)) nativeModels.snapshot(engine) else null
    }
    val planComposer = PlanComposer(gateway, json, search,
        io.aequicor.magicpaper.domain.RuntimePlanningGateway(runtime),
        projectLookup = { id -> codingProjects?.all()?.firstOrNull { it.id == id } }, acceptanceChecks = acceptanceChecks, toolSessions = toolSessions,
        retryLimit = { settingsRepo.load().agentLimits.retries }, nativeRecommendations = nativeRecommendations)
    val planningJournalRecovery = PlanningJournalRecovery(planningStore, runtime, codingProjects, organisms)
    val planStrategyClassifier = PlanStrategyClassifier(planningStore, gateway, profileRepo, settingsRepo)
    val planningExecution = io.aequicor.magicpaper.domain.PlanningExecutionService(
        planningStore, runtime, codingProjects, profileRepo, settingsRepo,
        LlmMilestoneVerifier(gateway, json, retryLimit = { settingsRepo.load().agentLimits.retries }), planningWorkspace, acceptanceChecks = acceptanceChecks, taskWorktrees = taskWorktrees, journalRecovery = planningJournalRecovery,
        strategyClassifier = planStrategyClassifier, nativeModels = nativeModels,
        attemptAuthority = object : PlanningAttemptAuthority {
            private fun owner() = checkNotNull(organisms) { "Владелец сессий планирования недоступен" }
            override suspend fun requireRuntimePolicyReady() {
                check(settingsCommands.runtimePolicy() is SettingsRuntimePolicy.Confirmed) {
                    "Применение настроек не завершено. Повторите сохранение настроек перед запуском."
                }
            }
            override suspend fun prepareAttempt(plan: Plan, stageId: String, attempt: StageAttempt) = owner().preparePlanAttempt(plan, stageId, attempt)
            override suspend fun authorizeRetry(plan: Plan, stageId: String, attempt: StageAttempt) = owner().authorizePlanRetry(plan, stageId, attempt)
            override suspend fun attemptCheckpoint(plan: Plan, stageId: String, attempt: StageAttempt) = owner().planAttemptCheckpoint(plan, stageId, attempt)
            override suspend fun stoppedCheckpoint(plan: Plan) = owner().planStopped(plan)
        },
        chatHooksProvider = { planningChat },
    )
    val planningChat: OrchestrationService? by lazy { codingProjects?.let { OrchestrationService(planningStore, planningExecution, it, profileRepo, settingsRepo, planComposer, gateway,
        toolSessions = toolSessions, organisms = organisms, sessionTree = sessionTree, draftRepository = draftRepository, modelDossiers = modelDossiers, nativeModels = nativeModels) } }

    private suspend fun searchTools(context: ToolExecutionContext, query: String): kotlinx.serialization.json.JsonElement {
        val saved = settingsRepo.load()
        val plan = context.planId?.let { planningStore.planFor(it) }
        val effectiveSettings = if (plan == null) saved else saved.copy(searchProvider = plan.searchProvider)
        val result = search.searchWithDiagnostics(query, effectiveSettings, 5)
        if (result.hits.isEmpty()) {
            val diagnosis = result.issues.ifEmpty { listOf("Поиск не нашёл результатов по запросу «$query».") }
            error(diagnosis.joinToString("; "))
        }
        val checked = if (context.mode == CodingInteractionMode.RESEARCH) sourceAccess.check(result.hits.map {
            ResearchResource(io.aequicor.magicpaper.util.Id.new(), it.title, it.url)
        }).readableSources() else null
        if (checked != null && checked.isEmpty()) error("Найденные страницы недоступны для чтения. Сниппеты исключены; попробуй другой запрос или источник.")
        return kotlinx.serialization.json.buildJsonArray { result.hits.filter { hit -> checked == null || checked.any { it.url == hit.url } }.forEach { hit -> add(kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(hit.title))
            if (checked == null) put("snippet", kotlinx.serialization.json.JsonPrimitive(hit.snippet))
            else put("text", kotlinx.serialization.json.JsonPrimitive(checked.first { it.url == hit.url }.readableText.orEmpty()))
            put("url", kotlinx.serialization.json.JsonPrimitive(hit.url))
            if (hit.provider.isNotBlank()) put("provider", kotlinx.serialization.json.JsonPrimitive(hit.provider))
        }) } }
    }

    private var planningExecutionResetAttempted = false
    private var planningChatResetAttempted = false
    private var workspaceResetAttempted = false

    suspend fun pauseForReset() {
        planningExecutionResetAttempted = true
        planningExecution.pauseForReset()
        planningChatResetAttempted = planningChat != null
        planningChat?.pauseForReset()
        // Legacy active worktrees may not have an input stream yet. Import their uncertainty before reset admission.
        codingProjects?.let { projects ->
            for (project in projects.all()) for (session in projects.sessions(project.id)) {
                val legacy = session.taskWorktree ?: continue
                taskWorktreeOwner.projection(TaskWorktreeOwnerId(project.id, session.id), legacy, session.runtimeGeneration)
            }
        }
        workspaceResetAttempted = true
        taskWorktreeOwner.prepareForReset()
        taskWorktrees?.releaseRetainedLeases()
        toolQuestions.clearForReset()
    }

    suspend fun clearForReset() {
        organisms?.store?.clearForReset()
    }

    suspend fun resumeAfterReset() {
        var failure: Exception? = null
        suspend fun resumeOwner(action: suspend () -> Unit) {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { action() } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.CancellationException) {
                    failure?.let(error::addSuppressed); failure = error
                } else if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        if (workspaceResetAttempted) resumeOwner {
            taskWorktreeOwner.resumeAfterReset()
            workspaceResetAttempted = false
        }
        if (planningChatResetAttempted) resumeOwner {
            planningChat?.resumeAfterReset()
            planningChatResetAttempted = false
        }
        if (planningExecutionResetAttempted) resumeOwner {
            planningExecution.resumeAfterReset()
            planningExecutionResetAttempted = false
        }
        failure?.let { throw it }
    }

    suspend fun start() {
        toolQuestions.start()
        planningChat?.bootstrap()
        planningExecution.bootstrap()
        // Coding-run restoration inspects the recovered organism projection. Do not let
        // DefaultCodingService admit a parent while interrupted children still look RUNNING.
        // Recovery records unknown outcomes; it never relaunches those children.
        planningChat?.awaitReady()
    }
    suspend fun close() {
        var failure: Exception? = null
        suspend fun closeOwner(action: suspend () -> Unit) {
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { action() } } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.CancellationException) {
                    failure?.let(error::addSuppressed); failure = error
                } else if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        closeOwner { planningChat?.shutdown() }
        closeOwner { planningExecution.shutdown() }
        closeOwner { taskWorktreeOwner.close() }
        closeOwner { taskWorktrees?.releaseRetainedLeases() }
        failure?.let { throw it }
    }
}

fun codingProjectRepository(
    store: KeyValueStore,
    json: Json,
    settingsRepo: SettingsRepository,
    profileRepo: LlmProfileRepository,
    events: EventJournal,
): CodingJournalStore = CodingJournalStore(
    BackgroundCodingProjectRepository(JsonCodingProjectRepository(store, json,
        migrateEngine = { session, project ->
            legacyCodingEngine(ProfileResolver.coding(session, project, settingsRepo.load(), profileRepo.load()))
        })), events, StoredCodingPayloads(store, json), json)
