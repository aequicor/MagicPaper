package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.data.storage.InMemoryDraftRepository
import kotlinx.serialization.json.Json

/** Coding's mutually connected runtime collaborators have one application lifetime. */
class CodingRuntimeGraph(
    store: KeyValueStore,
    private val json: Json,
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val codingProjects: CodingProjectRepository?,
    private val codingRuntime: CodingRuntime?,
    private val planningWorkspace: PlanningWorkspace,
    private val integrationChecks: SessionIntegrationCheckRunner?,
    private val usageLedger: UsageLedger,
    private val gateway: LlmGateway,
    private val search: SearchEngine,
    draftRepository: DraftRepository = InMemoryDraftRepository(),
    taskWorkspace: TaskWorkspace = UnavailableTaskWorkspace,
    private val sourceAccess: ResearchSourceAccess = ResearchSourceAccess(),
) {
    val taskWorktrees = codingProjects?.let { TaskWorktreeService(it, taskWorkspace, planningWorkspace) }
    val toolHost = ToolHost(StoredToolReceipts(store), io.aequicor.magicpaper.domain.RuntimeQuestionnaires(
        io.aequicor.magicpaper.data.coding.JsonRuntimeQuestionnaireStore(store, "tool-questionnaires")))
    val organisms = codingProjects?.let { io.aequicor.magicpaper.domain.SessionOrganismService(
        io.aequicor.magicpaper.data.coding.SessionOrganismStore(store), it, settingsRepo,
        sourceSnapshot = { project -> planningWorkspace.verificationSnapshot(project.path) }) }
    val sessionTree = organisms?.let { io.aequicor.magicpaper.domain.SessionTreeRuntime(it, codingProjects!!, profileRepo, settingsRepo, planningWorkspace = planningWorkspace) }
    val runtime = codingRuntime?.let { io.aequicor.magicpaper.data.coding.MeteredCodingRuntime(ToolEnabledCodingRuntime(it, toolHost, sessionTree), usageLedger) }
    init {
    taskWorktrees?.requireQuiescent = { sessionTree?.requireTaskQuiescent(it) }
    toolHost.taskHandoff = { context, result ->
        checkNotNull(taskWorktrees).handoff(context, result.outcome == TaskHandoffOutcome.RESULT, result.checks)
    }
    organisms?.integrationWorkspaces = integrationChecks?.let { io.aequicor.magicpaper.domain.SessionIntegrationWorkspaces(planningWorkspace, it) { toolHost.knownSecrets() } }
    sessionTree?.runtime = runtime
    if (runtime != null && organisms != null) {
        val recovery = io.aequicor.magicpaper.domain.PlanRetryNativeRecovery(runtime, toolHost.receipts) { toolHost.knownSecrets() }
        organisms.reconcilePlanRetry = recovery::reconcile
        val quarantineRecovery = io.aequicor.magicpaper.domain.SessionQuarantineRecovery(runtime, toolHost.receipts) { toolHost.knownSecrets() }
        organisms.reconcileUnknownOutcomes = quarantineRecovery::reconcile
    }
    sessionTree?.cancelQuestions = { sessionId ->
        try { toolHost.questions.revoke(sessionId) }
        finally { organisms?.reconcileIntegrationsForSession(sessionId) }
    }
    toolHost.contextDefaults = { context ->
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        val node = organism?.sessions?.get(context.ownerSessionId)
        if (node == null) context else context.copy(organismId = organism.id, runtimeGeneration = node.generation,
            planningRulesSnapshot = node.rules)
    }
    toolHost.authorizeReceipt = { context, definition ->
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        if (organism != null) {
            val node = organisms!!.store.get(organism.id).sessions.getValue(context.ownerSessionId)
            require(node.generation == context.runtimeGeneration && definition.allowsAuthorityMode(context, node.mode)) { "Полномочия запуска отозваны" }
        }
    }
    toolHost.authorizeTool = { context, definition ->
        toolHost.authorizeReceipt(context, definition)
        val organism = organisms?.store?.organisms?.value?.values?.firstOrNull {
            it.projectId == context.projectId && context.ownerSessionId in it.sessions
        }
        // Per-command CAS belongs to dispatch; a long-lived tool session keeps only its generation fence.
        if (organism != null && definition.mutating && definition.id != "immunity.signal")
            organisms!!.store.check(organisms.authority(context.copy(stateVersion = null), organism))
    }
    toolHost.unknownOutcome = { context, receipt ->
        try {
            val session = codingProjects?.sessions(context.projectId)?.firstOrNull { it.id == context.ownerSessionId }
            if (session != null && organisms != null) {
                val organism = organisms.ensure(session)
                organisms.project(organisms.store.quarantine(organism.id, session.id, context.runtimeGeneration,
                    receipt.operationId.ifBlank { receipt.id }, "Неизвестный исход ${receipt.toolId}: ${receipt.error.ifBlank { receipt.result.toString() }}"))
            }
        } finally {
            // A native provider can execute file/shell tools without re-entering ToolExecutor.
            // Revoke that running connection as well; its owner joins/reconciles asynchronously.
            codingRuntime?.abort(context.sessionId)
        }
    }
    }
    val planningStore = PlanningStore(JsonPlanningRepository(store, json))
    val acceptanceChecks = io.aequicor.magicpaper.domain.AcceptanceChecks()
    val planComposer = PlanComposer(gateway, json, search,
        io.aequicor.magicpaper.domain.RuntimePlanningGateway(runtime ?: NoopCodingRuntime),
        projectLookup = { id -> codingProjects?.all()?.firstOrNull { it.id == id } }, acceptanceChecks = acceptanceChecks, toolHost = toolHost,
        retryLimit = { settingsRepo.load().agentLimits.retries })
    val planningExecution = io.aequicor.magicpaper.domain.PlanningExecutionService(
        planningStore, runtime ?: NoopCodingRuntime, codingProjects, profileRepo, settingsRepo,
        LlmMilestoneVerifier(gateway, json, retryLimit = { settingsRepo.load().agentLimits.retries }), planningWorkspace, acceptanceChecks = acceptanceChecks, taskWorktrees = taskWorktrees,
    )
    val planningChat = codingProjects?.let { OrchestrationService(planningStore, planningExecution, it, profileRepo, settingsRepo, planComposer, gateway,
        toolHost = toolHost, organisms = organisms, sessionTree = sessionTree, draftRepository = draftRepository) }
    init {
    toolHost.search = { context, query ->
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
        kotlinx.serialization.json.buildJsonArray { result.hits.filter { hit -> checked == null || checked.any { it.url == hit.url } }.forEach { hit -> add(kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(hit.title))
            if (checked == null) put("snippet", kotlinx.serialization.json.JsonPrimitive(hit.snippet))
            else put("text", kotlinx.serialization.json.JsonPrimitive(checked.first { it.url == hit.url }.readableText.orEmpty()))
            put("url", kotlinx.serialization.json.JsonPrimitive(hit.url))
            if (hit.provider.isNotBlank()) put("provider", kotlinx.serialization.json.JsonPrimitive(hit.provider))
        }) } }
    }
    }

    suspend fun pauseForReset() {
        planningExecution.pauseForReset()
        planningChat?.pauseForReset()
        toolHost.questions.clearForReset()
    }

    suspend fun clearForReset() {
        organisms?.store?.clearForReset()
    }

    suspend fun resumeAfterReset() {
        planningChat?.resumeAfterReset()
        planningExecution.resumeAfterReset()
    }

    suspend fun start() {
        planningChat?.bootstrap()
        planningExecution.bootstrap()
        // Coding-run restoration inspects the recovered organism projection. Do not let
        // DefaultCodingService restart a parent while its interrupted children still look RUNNING:
        // recoverUnknownChildren intentionally admits only UNKNOWN nodes after a process crash.
        planningChat?.awaitReady()
    }
    suspend fun close() {
        planningChat?.shutdown()
        planningExecution.shutdown()
    }
}

fun codingProjectRepository(
    store: KeyValueStore,
    json: Json,
    settingsRepo: SettingsRepository,
    profileRepo: LlmProfileRepository,
    runtime: CodingRuntime? = null,
): CodingProjectRepository = BackgroundCodingProjectRepository(JsonCodingProjectRepository(store, json,
    initialContext = { session, project ->
        val profiles = profileRepo.load()
        val settings = settingsRepo.load()
        val profile = if (session.planningMode) session.modelSelection?.let { ProfileResolver.selection(it, profiles) }
            ?: ProfileResolver.resolve(null as ChatSession?, settings, profiles)
        else ProfileResolver.coding(session, project, settings, profiles)
        runtime?.sessionContext(project, session, profile)
    },
    migrateEngine = { session, project ->
        legacyCodingEngine(ProfileResolver.coding(session, project, settingsRepo.load(), profileRepo.load()))
    },
))
