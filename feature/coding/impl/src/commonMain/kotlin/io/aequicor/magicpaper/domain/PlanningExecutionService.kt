package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.withJournaledIntent
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Application-owned durable scheduler. Composables only issue commands and observe state. */
class PlanningExecutionService(
    private val store: PlanningStore,
    private val runtime: CodingRuntime,
    private val projects: CodingProjectRepository?,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val verifier: MilestoneVerifier,
    private val workspaces: PlanningWorkspace = LocalPlanningWorkspace(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val outputClock: () -> Long = Id::now,
    private val acceptanceChecks: AcceptanceChecks = AcceptanceChecks(),
    private val taskWorktrees: TaskWorktreeService? = null,
    private val journalRecovery: PlanningJournalRecovery = PlanningJournalRecovery(store, runtime, projects),
    private val strategyClassifier: PlanStrategyClassifier? = null,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val supported: Boolean get() = runtime.supported
    var chatHooks: PlanningExecutionHooks? = null
    /** App-owned admission, after durable intent and before native startup. */
    var prepareAttempt: suspend (Plan, String, StageAttempt) -> StageAttempt = { _, _, attempt -> attempt }
    /** Capture one explicit retry before changing the durable plan; never called by the scheduler. */
    var authorizeRetry: suspend (Plan, String, StageAttempt) -> PlanAttemptRetryAuthorization? = { _, _, _ -> null }
    /** A separate aggregate projection after the Plan checkpoint has committed. */
    var attemptCheckpoint: suspend (Plan, String, StageAttempt) -> Unit = { _, _, _ -> }
    var stoppedCheckpoint: suspend (Plan) -> Unit = {}
    internal fun verificationGuidance(): String = acceptanceChecks.executionGuidance()
    private val jobs = mutableMapOf<String, Job>()
    private val jobsLock = Mutex()
    private val stageJobs = MutableStateFlow<Map<Pair<String, String>, Job>>(emptyMap())

    /** The caller may itself be handing a question to the coordinator: it will return WAIT. */
    suspend fun interruptStages(planId: String, stageIds: Set<String>) {
        val caller = currentCoroutineContext()[Job]
        val affected = stageJobs.value.filter { (key, job) -> key.first == planId && key.second in stageIds && job != caller }.values
        affected.forEach { it.cancel() }
        affected.toList().joinAll()
    }

    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState
    private val liveState = MutableStateFlow<Map<String, StageAttempt>>(emptyMap())
    private val secrets = MutableStateFlow<Set<String>>(emptySet())
    val live: StateFlow<Map<String, StageAttempt>> = liveState
    private var watcher: Job? = null
    private var closing = false
    private val storageGuard = scope.launch {
        store.failure.filterNotNull().collect { message ->
            errorState.value = message
            jobsLock.withLock { jobs.values.toList() }.forEach { it.cancel() }
        }
    }

    fun bootstrap() {
        if (watcher != null || !runtime.supported) return
        watcher = scope.launch {
            chatHooks?.awaitReady()
            store.plans().filter { it.stopping }.forEach { confirmStop(it.id) }
            while (isActive && !closing) {
                try {
                    if (store.failure.value != null) {
                        jobsLock.withLock { jobs.values.toList() }.joinAll()
                        store.recover()
                    }
                    store.plans().filter { it.pendingSessionProjections.isNotEmpty() }.forEach { synchronizeSessionProjections(it.id) }
                    store.plans().filter { it.intent == ExecutionIntent.RUN && it.phase != ExecutionPhase.COMPLETE }
                        .forEach { saved ->
                            val plan = if (saved.restoreSkippedVerification() != saved) store.update(saved.id) { it.restoreSkippedVerification() } else saved
                            if ((plan.issue?.requiresUser != true || plan.issue?.kind == IssueKind.CONFIGURATION) &&
                                (plan.issue?.retryAt ?: 0) <= Id.now()) launchProject(plan.id)
                        }
                    if (store.failure.value == null) errorState.value = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errorState.value = "Хранилище планирования: ${e.message}" }
                delay(5_000)
            }
        }
    }

    suspend fun start(projectId: String) {
        require(runtime.supported) { "Выполнение доступно в Desktop" }
        val plan = store.planFor(projectId) ?: error("План не найден")
        check(!plan.stopping) { "Дождитесь подтверждения остановки" }
        if (rejectUnsettledJournal(plan)) return
        if (plan.phase == ExecutionPhase.COMPLETE) return
        if (plan.intent == ExecutionIntent.RUN) { launchProject(plan.id); return }
        val graph = DecisionCompiler.compile(plan)
        require(graph.valid && graph.stageIds.isNotEmpty()) { graph.errors.joinToString("\n").ifBlank { "Нет этапов" } }
        val rules = plan.planningRulesSnapshot ?: if (plan.runId.isBlank()) settings.load().planningRules.snapshot()
            else PlanningRulesSettings().snapshot().copy(source = PlanningRulesSource.LEGACY)
        store.update(projectId) { it.copy(intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, wizardStep = PlanningStep.STATUS,
            runId = it.runId.ifBlank { Id.new() }, planningRulesSnapshot = it.planningRulesSnapshot ?: rules,
            issue = null, status = PlanStatus.RUNNING) }
        chatHooks?.prepareSessions(store.planFor(plan.id)!!)
        launchProject(plan.id)
    }
    suspend fun pause(projectId: String) {
        store.update(projectId) { it.copy(intent = ExecutionIntent.PAUSE) }
    }
    suspend fun stop(projectId: String) {
        val plan = store.journal(projectId, PlanJournalOperation.STOP_INTENT) {
            it.copy(intent = ExecutionIntent.STOP, stopping = true)
        }
        // A broken transport must not prevent coroutine cancellation of the rest of the subtree.
        val signalErrors = runtimeSessionIds(plan).mapNotNull { sessionId ->
            try { runtime.abort(sessionId); null } catch (e: Exception) { safeText(e.message.orEmpty()) }
        }
        val job = jobsLock.withLock { jobs[plan.id]?.also { it.cancel() } }
        if (signalErrors.isNotEmpty()) store.update(plan.id) { it.copy(journal = it.journal +
            PlanJournalEntry(Id.new(), Id.now(), operation = PlanJournalOperation.STOP_SIGNAL_ERROR, detail = signalErrors.joinToString("\n"))) }
        if (job == null || job.isCompleted) confirmStop(plan.id)
    }
    suspend fun stopAndJoin(planId: String) {
        stop(planId)
        val job = jobsLock.withLock { jobs[planId] }
        job?.join()
        check(store.planFor(planId)?.stopping != true) { "Остановка не подтверждена; требуется восстановление" }
    }
    /** A coordinator callback can run inside its own controller; it must never join that ancestor. */
    suspend fun stopController(planId: String, caller: Job): Boolean {
        val controller = jobsLock.withLock { jobs[planId] }
        val plan = store.planFor(planId) ?: return true
        if (controller?.isCompleted != false && !plan.stopping &&
            (plan.phase == ExecutionPhase.COMPLETE || plan.status == PlanStatus.STOPPED)) return true
        fun owns(job: Job): Boolean = job === caller || job.children.any(::owns)
        val callerInsideController = controller?.let(::owns) == true
        stop(planId)
        if (callerInsideController) return false
        controller?.join()
        return store.planFor(planId)?.stopping != true
    }
    suspend fun edit(projectId: String, revision: Long, change: (Plan) -> Plan) = store.update(projectId, revision) { old ->
        validatePlanRevision(old, change(old))
    }
    /** Rebase an LLM proposal over telemetry, never over intervening user edits or started work. */
    suspend fun applyProposal(base: Plan, proposal: Plan) = store.update(base.id) { latest ->
        reduce(latest, PlanRevisionEvent.ProposalApplied(base, proposal))
    }
    /** Explicit retry does not erase counters; the caller fixes configuration or acknowledges uncertainty. */
    suspend fun retry(projectId: String, expectedCheckpoint: Plan? = null, expectedBlockerIds: Set<String>? = null) {
        val before = store.planFor(projectId) ?: return
        expectedCheckpoint?.let { before.requireRetryCheckpoint(it) }
        expectedBlockerIds?.let { expected ->
            require(expected.isNotEmpty() && before.blockingIssues(emptyList()).map { it.messageId }.toSet() == expected) {
                "Причина остановки изменилась; ответьте на актуальный запрос восстановления"
            }
        }
        check(!before.stopping) { "Дождитесь подтверждения остановки" }
        if (rejectUnsettledJournal(before)) return
        if (before.phase == ExecutionPhase.COMPLETE) return
        check(before.blockingIssues(emptyList()).none { it.issue.retryBlocked }) { "Повтор запуска не устраняет причину. Обсудите изменение плана с оркестратором." }
        val authorizations = before.selectedMilestones.mapNotNull { stage ->
            stage.attempts.lastOrNull()?.takeIf { it.phase != AttemptPhase.COMPLETE &&
                (it.error != null || it.interrupted || it.phase == AttemptPhase.FAILED ||
                    expectedCheckpoint?.milestones?.firstOrNull { original -> original.id == stage.id }?.attempts?.lastOrNull()?.let { original ->
                        original.id == it.id && (original.error != null || original.interrupted || original.phase == AttemptPhase.FAILED)
                    } == true) }?.let { attempt ->
                check(store.planFor(projectId) == before) { "Состояние плана изменилось; повторите действие" }
                expectedCheckpoint?.let { before.requireRetryCheckpoint(it) }
                authorizeRetry(before, stage.id, attempt)?.let { attempt.id to it }
            }
        }.toMap()
        store.update(projectId) { p ->
            check(!p.stopping) { "Дождитесь подтверждения остановки" }
            check(p == before) { "Состояние плана изменилось; повторите действие" }
            expectedCheckpoint?.let { p.requireRetryCheckpoint(it) }
            p.copy(issue = null, intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, status = PlanStatus.RUNNING,
            finalAttempt = p.finalAttempt?.retryAfterUserAction()?.let { if (p.issue?.kind == IssueKind.UNCERTAIN) it.copy(pendingToolExternal = false, pendingTool = "") else it },
            milestones = p.milestones.map { m -> m.copy(attempts = m.attempts.map { a ->
                val acknowledged = a.retryAfterUserAction().let { resumed ->
                    authorizations[a.id]?.let { resumed.copy(retryAuthorization = it) } ?: resumed
                }
                if (p.issue?.kind == IssueKind.UNCERTAIN) acknowledged.copy(pendingToolExternal = false, pendingTool = "") else acknowledged
            }) }) }
        launchProject(store.planFor(projectId)!!.id)
    }

    /** Host-only user decision; model commands never call this method. */
    internal suspend fun continueWithoutVerification(planId: String, expectedBlockerIds: Set<String>) {
        store.update(planId) { plan ->
            val blockers = plan.blockingIssues(emptyList())
            require(blockers.isNotEmpty() && blockers.map { it.messageId }.toSet() == expectedBlockerIds &&
                blockers.all { it.canSkipVerification }) { "Причина остановки изменилась; пропуск проверки недоступен." }
            val waivers = blockers.flatMap { blocker ->
                val record = blocker.attempt!!.acceptanceRecord!!
                val criteria = blocker.stage?.criteria() ?: plan.acceptanceCriteria()
                require(record.runId == plan.runId && record.criteria == criteria) { "План изменился; проверьте актуальные условия." }
                record.criteria.map { AcceptanceWaiver(plan.runId, it, record.attemptId, record.snapshotId, Id.now()) }
            }
            val attempts = blockers.map { it.attempt!!.id }.toSet()
            fun resume(attempt: StageAttempt) = if (attempt.id in attempts) attempt.copy(error = null, verificationSnapshot = null) else attempt
            plan.copy(acceptanceWaivers = (plan.acceptanceWaivers + waivers).distinctBy { it.runId to it.criterion },
                issue = null, intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, status = PlanStatus.RUNNING,
                milestones = plan.milestones.map { it.copy(attempts = it.attempts.map(::resume)) },
                finalAttempt = plan.finalAttempt?.let(::resume),
                journal = plan.journal + PlanJournalEntry(Id.new(), Id.now(), operation = PlanJournalOperation.USER_SKIP_VERIFICATION))
        }
        launchProject(planId)
    }
    /** Pause application-owned work without cancelling the reusable supervisor. */
    suspend fun pauseForReset() {
        closing = true
        watcher?.cancelAndJoin()
        watcher = null
        val active = jobsLock.withLock { jobs.values.toList() }
        active.forEach { it.cancel() }
        active.joinAll()
        jobsLock.withLock { jobs.clear() }
        stageJobs.value = emptyMap()
        liveState.value = emptyMap()
        secrets.value = emptySet()
        errorState.value = null
    }

    fun resumeAfterReset() {
        closing = false
        bootstrap()
    }

    suspend fun shutdown() {
        closing = true
        (chatHooks as? PlanningChatService)?.shutdown()
        watcher?.cancelAndJoin()
        val active = jobsLock.withLock { jobs.values.toList() }
        active.forEach { it.cancel() }; active.joinAll()
        storageGuard.cancelAndJoin()
        scope.cancel()
    }

    private suspend fun launchProject(id: String) = jobsLock.withLock {
        // A cancelled job can still own tools/workspaces while its NonCancellable cleanup runs.
        if (closing || jobs[id]?.isCompleted == false) return@withLock
        var plan = store.planFor(id)
        check(plan?.stopping != true) { "Дождитесь подтверждения остановки" }
        if (plan != null) {
            val pending = store.unsettled(plan.id)
            if (pending.isNotEmpty()) {
                quarantineJournal(plan, pending)
                return@withLock
            }
        }
        if (plan != null && plan.planningRulesSnapshot == null) {
            val snapshot = if (plan.runId.isBlank()) settings.load().planningRules.snapshot()
                else PlanningRulesSettings().snapshot().copy(source = PlanningRulesSource.LEGACY)
            plan = store.update(id) { it.copy(planningRulesSnapshot = it.planningRulesSnapshot ?: snapshot) }
        }
        jobs[id] = scope.launch(UsageOwner(UsageScope(plan?.parentSessionId?.let { "coding:$it" }, projectId = plan?.projectId, planId = id), updatesContext = false)) { executeProject(id) }
    }
    private suspend fun rejectUnsettledJournal(plan: Plan): Boolean = jobsLock.withLock {
        // A running effect is expected to have an open intent until its scope exits.
        if (jobs[plan.id]?.isCompleted == false) return@withLock false
        val pending = store.unsettled(plan.id)
        if (pending.isEmpty()) false else {
            quarantineJournal(plan, pending)
            true
        }
    }

    /** Discover even completed checkpoints before session restoration can admit new work. */
    suspend fun recoverJournalQuarantines() = jobsLock.withLock {
        store.plans().forEach { plan ->
            if (jobs[plan.id]?.isCompleted != false) {
                val pending = store.unsettled(plan.id)
                if (pending.isNotEmpty()) quarantineJournal(plan, pending)
            }
        }
    }

    private suspend fun quarantineJournal(plan: Plan, pending: List<io.aequicor.magicpaper.data.storage.JournalRecord>) {
        journalRecovery.quarantine(plan, pending)
        // A journal warning must not erase an independent acceptance/configuration blocker.
        if (plan.issue?.requiresUser == true) return
        val issue = PlanningJournalRecovery.uncertainty
        if (plan.issue != issue) store.update(plan.id) {
            it.copy(issue = issue, phase = if (it.phase == ExecutionPhase.COMPLETE) it.phase else ExecutionPhase.WAITING,
                status = PlanStatus.FAILED)
        }
    }

    /** Host-only recovery; inspecting and confirming outcomes never launches execution. */
    suspend fun reconcileJournalQuarantine(session: CodingSession, confirmed: Boolean): QuarantineRecoveryOutcome = jobsLock.withLock {
        val affected = store.plans().filter { it.projectId == session.projectId &&
            (it.parentSessionId == session.id || it.id == session.planId || it.milestones.any { stage -> stage.attempts.any { attempt -> attempt.sessionId == session.id } }) }
        check(affected.none { jobs[it.id]?.isCompleted == false }) { "Дождитесь остановки выполнения перед сверкой" }
        journalRecovery.reconcile(session, confirmed)
    }

    private suspend fun executeProject(id: String) {
        if (store.failure.value != null) return
        try {
            if (strategyClassifier?.beforeRun(id) == false) return
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            errorState.value = "Не удалось проверить возможность повтора. Повторите действие после проверки доступа к данным."
            if (store.failure.value == null) io.aequicor.magicpaper.logging.AppLog.error("planning.strategy", "admission.failed",
                fields = mapOf("planId" to id, "causeType" to (failure::class.simpleName ?: "Exception")))
            return
        }
        val initialTaskPlan = store.planFor(id) ?: return
        if (initialTaskPlan.intent != ExecutionIntent.RUN || initialTaskPlan.stopping) return
        val originalProject = projects?.all()?.firstOrNull { it.id == initialTaskPlan.projectId }
        if (initialTaskPlan.worktreeEnabled == true && originalProject != null && initialTaskPlan.workspace == null) {
            val parent = projects?.sessions(originalProject.id)?.firstOrNull { it.id == initialTaskPlan.parentSessionId }
            if (parent?.taskWorktree?.phase != TaskWorktreePhase.COMPLETE && parent?.taskWorktree != null) Unit
            else if (taskWorktrees?.availability(originalProject)?.available != true)
                store.update(id) { it.copy(worktreeEnabled = false, sharedWorkspace = true) }
        }
        if (initialTaskPlan.worktreeEnabled != null) store.update(id) { it.copy(sharedWorkspace = it.worktreeEnabled == false) }
        val workspaces = workspaceFor(id)
        val acquiredProjects = mutableListOf<CodingProject>()
        suspend fun acquire(owner: CodingProject): Boolean = withContext(NonCancellable) {
            workspaces.acquire(owner).also { if (it) acquiredProjects += owner }
        }
        suspend fun release(owner: CodingProject) = withContext(NonCancellable) {
            workspaces.release(owner)
            acquiredProjects.remove(owner)
        }
        try {
            val initial = store.planFor(id) ?: return
            if (initial.blockingIssues(emptyList()).any { it.issue.retryBlocked }) return
            var project = projects?.all()?.firstOrNull { it.id == initial.projectId }
            if (project == null) { block(id, PlanningIssue(IssueKind.CONFIGURATION, "Папка проекта не найдена")); return }
            check(store.plans().none { it.id != id && it.projectId == project.id && it.stopping }) {
                "Остановка другой работы в этом проекте не подтверждена"
            }
            if (initial.worktreeEnabled == true) {
                if (initial.runId.isBlank()) store.update(id) { it.copy(runId = Id.new()) }
                val task = checkNotNull(taskWorktrees).begin(project, initial.parentSessionId, store.planFor(id)!!.runId)
                project = project.copy(path = task.path)
            }
            val existingWorkspace = initial.workspace
            if (existingWorkspace != null) workspaces.validateExecutionPath(project, existingWorkspace.integrationPath)
            val executionOwner = existingWorkspace?.takeIf { !initial.sharedWorkspace && it.integrationPath != project.path }
                ?.let { project.copy(id = "plan-$id", path = it.integrationPath) } ?: project
            if (!acquire(executionOwner)) {
                errorState.value = "Проект выполняется другим экземпляром приложения"
                return
            }
            chatHooks?.awaitReady()
            var plan = store.planFor(id) ?: return
            if (plan.restoreSkippedVerification() != plan) plan = store.update(id) { it.restoreSkippedVerification() }
            plan = chatHooks?.recoverAssignments(plan) ?: plan
            if (plan.intent != ExecutionIntent.RUN) return
            val graph = DecisionCompiler.compile(plan)
            require(graph.valid) { graph.errors.joinToString("\n") }
            val roster = profiles.load()
            secrets.update { it + roster.map { p -> p.apiKey }.filter { it.isNotBlank() } }
            val savedSessions = projects?.sessions(project.id).orEmpty()
            plan.selectedMilestones.filter { !it.completed }.map { stage ->
                require(stage.acceptance.isNotBlank() || stage.description.isNotBlank()) { "Задайте критерии этапа «${stage.title}»" }
                val attempt = stage.attempts.lastOrNull()
                val profile = (attempt?.assignment ?: assignment(stage, roster)).executionProfile(roster)
                val sessionId = attempt?.sessionId ?: "plan-${plan.id}-stage-${stage.id}"
                val engine = attempt?.engine ?: savedSessions.firstOrNull { it.id == sessionId }?.engine ?: plan.engine ?: legacyCodingEngine(profile)
                engine to profile
            }.distinctBy { (engine, profile) -> Triple(engine, profile.id, profile.modelId) }
                .forEach { (engine, profile) -> runtime.preflight(engine, profile) }
            val judge = plan.plannerSelection?.let { ProfileResolver.selection(it, roster) } ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
            require(judge?.configured == true) { "Подключите модель для проверки результата" }
            if (plan.issue != null) plan = store.update(id) { it.copy(issue = null, phase = ExecutionPhase.RECOVERING) }
            if (plan.runId.isBlank()) plan = store.update(id) { it.copy(runId = Id.new()) }
            if (plan.workspace == null) {
                store.withJournaledIntent(id, PlanJournalOperation.PREPARE_INTENT) {
                    val workspace = workspaces.prepare(project, plan.runId)
                    plan = store.update(id) { it.copy(workspace = workspace, issue = null,
                        journal = if (workspace.git) it.journal else it.journal + PlanJournalEntry(Id.new(), Id.now(),
                            operation = PlanJournalOperation.GIT_UNAVAILABLE, detail = "Git недоступен: изменения выполняются без отдельных веток и коммитов. Репозиторий не создан.")) }
                    complete()
                }
            }
            chatHooks?.prepareSessions(plan)
            val workspace = plan.workspace!!
            workspaces.validateExecutionPath(project, workspace.integrationPath)
            if (!plan.sharedWorkspace && workspace.integrationPath != project.path && project in acquiredProjects) {
                // The snapshot is complete. Protect the actual execution directory while
                // allowing ordinary sessions to use the user's source checkout.
                check(acquire(project.copy(id = "plan-$id", path = workspace.integrationPath))) {
                    "Рабочая копия плана уже используется другой сессией"
                }
                release(project)
            }
            val integration = Mutex()
            coroutineScope {
                val active = mutableMapOf<String, Job>()
                while (isActive && !closing) {
                    plan = store.planFor(id) ?: break
                    if (plan.deliveries.any { d -> d.state == DeliveryState.QUEUED && plan.milestones.any { it.id == d.targetStageId && it.completed } }) {
                        chatHooks?.prepareSessions(plan)
                        plan = store.planFor(id) ?: break
                    }
                    active.entries.removeAll { it.value.isCompleted }
                    val compiled = DecisionCompiler.compile(plan)
                    if (!compiled.valid) { block(id, PlanningIssue(IssueKind.CONFIGURATION, compiled.errors.joinToString("\n"), requiresUser = true)); break }
                    val waitCycle = plan.waitCycleProblem()
                    if (waitCycle != null) { block(id, PlanningIssue(IssueKind.CONFIGURATION, waitCycle, requiresUser = true)); break }
                    val waitingStages = chatHooks?.blockedStages(plan).orEmpty()
                    interruptStages(id, waitingStages)
                    active.entries.removeAll { it.value.isCompleted }
                    if (plan.intent != ExecutionIntent.RUN || plan.issue != null || store.failure.value != null) {
                        if (active.isEmpty()) break
                        delay(100)
                        continue
                    }
                    val candidates = plan.milestones.filter { m ->
                        m.id in compiled.stageIds && !m.completed && m.id !in active && m.id !in waitingStages &&
                            m.attempts.lastOrNull()?.waitingForUser == null &&
                            m.attempts.lastOrNull()?.waitingForEvent == null &&
                            m.attempts.lastOrNull()?.error?.requiresUser != true &&
                            (m.attempts.lastOrNull()?.error?.retryAt ?: 0) <= Id.now() &&
                            compiled.dependencies[m.id].orEmpty().all { dep -> plan.milestones.first { it.id == dep }.completed }
                    }
                    // Whole-project acceptance snapshots require exclusive writers in a shared folder.
                    val slots = (if (workspace.git && !plan.sharedWorkspace) minOf(plan.parallelism.coerceAtLeast(1), settings.load().agentLimits.activeSessions ?: Int.MAX_VALUE) else 1) - active.size
                    candidates.take(slots.coerceAtLeast(0)).forEach { stage ->
                        val key = id to stage.id
                        val job = launch(start = CoroutineStart.LAZY) {
                            try { executeStage(id, stage.id, project, workspace, integration, judge!!) }
                            finally { stageJobs.update { it - key } }
                        }
                        stageJobs.update { it + (key to job) }
                        active[stage.id] = job
                        job.start()
                    }
                    if (active.isEmpty()) {
                        if (waitingStages.isNotEmpty() || plan.selectedMilestones.any { it.attempts.lastOrNull()?.waitingForEvent != null }) store.update(id) { it.copy(phase = ExecutionPhase.WAITING) }
                        plan.selectedMilestones.filterNot { it.completed }.mapNotNull { it.attempts.lastOrNull()?.error }
                            .sortedWith(compareByDescending<PlanningIssue> { it.requiresUser }.thenBy { it.retryAt })
                            .firstOrNull()?.let { block(id, it) }
                        break
                    }
                    delay(100)
                }
            }
            plan = store.planFor(id) ?: return
            if (plan.intent != ExecutionIntent.RUN || plan.issue != null || closing) return
            if (plan.selectedMilestones.all { it.completed } && chatHooks?.blockedStages(plan).orEmpty().isEmpty()) {
                if (!verifyIntegration(id, project, workspace, judge!!)) return
                if (!acceptanceStillValid(id, workspace.integrationPath)) return
                if (store.planFor(id)!!.selectedMilestones.any { !it.completed }) {
                    store.update(id) { it.copy(finalAttempt = null, finalAttemptHistory = it.finalAttemptHistory + listOfNotNull(it.finalAttempt), phase = ExecutionPhase.EXECUTING) }
                    return
                }
                if (project !in acquiredProjects && !acquire(project)) {
                    block(id, PlanningIssue(IssueKind.TRANSIENT, "Ожидание освобождения папки проекта для применения результата",
                        retryAt = Id.now() + 1_000, requiresUser = false))
                    return
                }
                store.update(id) { it.copy(phase = ExecutionPhase.APPLYING) }
                store.withJournaledIntent(id, PlanJournalOperation.APPLY_INTENT) {
                    val task = taskWorktrees?.session(project.id, plan.parentSessionId)?.taskWorktree?.takeIf { initial.worktreeEnabled == true && it.taskId == plan.runId }
                    val delivering = task?.phase in setOf(TaskWorktreePhase.CAPTURING, TaskWorktreePhase.MERGING, TaskWorktreePhase.CONFLICT, TaskWorktreePhase.DELIVERING, TaskWorktreePhase.COMPLETE)
                    val applied = if (delivering) workspace.copy(applied = true) else applyResult(id, project, workspace, judge) ?: return
                    if (task != null) {
                        taskWorktrees!!.complete(checkNotNull(originalProject), plan.parentSessionId, plan.runId,
                            planAccepted = true, executionLeaseHeld = true,
                            verifyMerged = { merged -> verifyTaskDelivery(id, merged, judge) },
                            repair = { conflict -> repairTaskDelivery(id, project, conflict, judge) })
                    }
                    if (!acceptanceStillValid(id, workspace.integrationPath)) return
                    store.update(id) {
                        require(it.intent == ExecutionIntent.RUN && it.issue == null &&
                            it.finalAttempt?.acceptanceRecord?.permitsProgress == true &&
                            it.finalAttempt?.acceptanceRecord?.criteria == it.acceptanceCriteria()) { "Приёмка или намерение запуска изменились" }
                        it.copy(workspace = applied, phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE, issue = null)
                    }
                    journal(id, PlanJournalOperation.APPLY_COMPLETE)
                    complete()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            try {
                val issue = if (e is UnsafePlanningWorkspace) PlanningIssue(IssueKind.CONFIGURATION, e.message.orEmpty(), requiresUser = true, retryBlocked = true)
                    else classify(e.message ?: "Ошибка исполнения")
                val saved = store.planFor(id)
                val failed = saved?.finalAttempt?.let { withRetry(it, issue) }
                if (failed != null) {
                    store.update(id) { it.copy(finalAttempt = safeAttempt(failed)) }
                    block(id, failed.error!!)
                } else if (issue.kind == IssueKind.TRANSIENT && saved != null) {
                    val decision = retryDecision(issue, saved.transportRetries)
                    val waiting = decision.applyTo(issue)
                    store.update(id) { it.copy(transportRetries = waiting.retries,
                        issue = waiting.copy(message = safeText(waiting.message)), phase = ExecutionPhase.WAITING,
                        status = if (waiting.requiresUser) PlanStatus.FAILED else PlanStatus.RUNNING) }
                } else block(id, issue)
            }
            catch (storage: Exception) { errorState.value = "Не удалось сохранить состояние: ${storage.message}" }
        } finally {
            val plan = store.plans.value.firstOrNull { it.id == id }
            val ids = plan?.milestones.orEmpty().flatMap { it.attempts }.map { it.id }.toSet() + listOfNotNull(plan?.finalAttempt?.id)
            liveState.update { it - ids }
            withContext(NonCancellable) {
                if (plan?.stopping == true) confirmStop(id)
                var releaseFailure: Throwable? = null
                acquiredProjects.toList().asReversed().forEach { owner ->
                    try { release(owner) } catch (failure: Throwable) { releaseFailure = releaseFailure ?: failure }
                }
                releaseFailure?.let { throw it }
            }
        }
    }

    private fun runtimeSessionIds(plan: Plan): List<String> =
        (plan.milestones.flatMap { it.attempts } + listOfNotNull(plan.finalAttempt))
            .flatMap { listOf(it.sessionId, "${it.sessionId}-merge", "${it.sessionId}-delivery") }.distinct()

    /** Runtime termination and external-effect success are separate: this only confirms termination. */
    private suspend fun confirmStop(id: String) {
        val plan = store.planFor(id)?.takeIf { it.intent == ExecutionIntent.STOP && it.stopping } ?: return
        try {
            runtimeSessionIds(plan).forEach { runtime.reconcile(it) }
            val stopped = store.journal(id, PlanJournalOperation.STOP_CONFIRMED) { latest ->
                latest.copy(stopping = false, status = PlanStatus.STOPPED)
            }
            if (stopped.intent == ExecutionIntent.STOP && !stopped.stopping) stoppedCheckpoint(stopped)
        } catch (e: Exception) {
            store.update(id) { it.copy(stopping = true, phase = ExecutionPhase.WAITING,
                issue = PlanningIssue(IssueKind.UNCERTAIN, "Остановка не подтверждена: ${safeText(e.message.orEmpty())}", requiresUser = true)) }
        }
    }

    private suspend fun verifyTaskDelivery(id: String, task: TaskWorktree, judge: LlmProfile) {
        val plan = store.planFor(id) ?: error("План удалён")
        check(canRun(id)) { "План остановлен" }
        val snapshot = workspaces.verificationSnapshot(task.path) ?: error("Проверка результата недоступна")
        val attempt = checkNotNull(plan.finalAttempt).copy(verificationSnapshot = snapshot)
        val (record, verdict) = reviewAcceptance(plan, Milestone("task-delivery", "Проверка слияния", description = plan.goal),
            attempt, task.path, plan.acceptanceCriteria(), attempt.report, judge)
        store.update(id) { it.copy(finalAttempt = it.finalAttempt?.copy(mergeAcceptanceRecord = record)) }
        check(verdict.passed && verdict.issue == null && workspaces.verificationSnapshot(task.path) == snapshot) { "Проверка слияния не пройдена. Продолжите работу над планом" }
    }

    private suspend fun repairTaskDelivery(id: String, project: CodingProject, task: TaskWorktree, judge: LlmProfile) {
        val plan = store.planFor(id) ?: error("План удалён")
        var attempt = checkNotNull(plan.finalAttempt)
        attempt = attempt.copy(mergePath = task.path).merging(MergeProgress.Running)
        store.update(id) { it.copy(finalAttempt = attempt, phase = ExecutionPhase.INTEGRATING) }
        val sessionId = "${attempt.sessionId}-delivery"
        var result = StageRunResult()
        try {
            monitoredRun(project.copy(path = task.path), CodingSession(sessionId, project.id, "Конфликт слияния", Id.now(),
                piSessionId = attempt.mergeEngineSessionId, engine = attempt.engine ?: plan.engine,
                planId = id, parentSessionId = plan.parentSessionId, planningRulesSnapshot = plan.planningRulesSnapshot,
                pendingRun = CodingRunCheckpoint("${attempt.id}-delivery", "")),
                "Разреши конфликт переноса задачи на ветку назначения в этой рабочей папке: сохрани обе стороны и добавь спорные файлы в индекс (git add). " +
                    "Приложение само продолжит перенос и проверки; не выполняй git rebase --continue, --skip или --abort. " +
                    "При неоднозначности задай вопрос через magicpaper_questionnaire. Не изменяй исходную папку. Цель: ${plan.goal}", judge).collect { event ->
                attempt = attempt.after(event, StageRunTrack.MERGE)
                result = result.after(event)
                if (event is CodingEvent.SessionStarted) store.update(id) { it.copy(finalAttempt = attempt) }
            }
        } finally { withContext(NonCancellable) { runtime.reconcile(sessionId) } }
        check(result.ended && result.failure == null) { "Конфликт требует продолжения" }
    }

    private suspend fun applyResult(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile): PlanWorkspace? {
        val workspaces = workspaceFor(id)
        while (canRun(id)) {
            try {
                val pending = store.planFor(id)!!.finalAttempt
                if (pending?.mergeProgress?.unresolved == true)
                    throw WorkspaceConflict(pending.mergePath, "Продолжение проверки переноса")
                return workspaces.apply(project, workspace)
            }
            catch (conflict: WorkspaceConflict) {
                val plan = store.planFor(id)!!
                var attempt = plan.finalAttempt ?: error("Нет итоговой проверки")
                suspend fun persist() { store.update(id) { it.copy(finalAttempt = safeAttempt(attempt.copy(updatedAt = Id.now())), phase = ExecutionPhase.INTEGRATING) } }
                if (attempt.mergeProgress.needsResolver) {
                    if (!canRetry(attempt.mergeRetries)) {
                        block(id, PlanningIssue(IssueKind.CONFLICT, "Не удалось разрешить конфликт переноса: ${conflict.workingPath}", requiresUser = true)); return null
                    }
                    if (attempt.mergeRetries > 0) delay(PlanningRetryPolicy.delayMillis(attempt.mergeRetries))
                    currentCoroutineContext().ensureActive()
                    attempt = attempt.copy(mergeRetries = PlanningRetryPolicy.nextRetry(attempt.mergeRetries), activity = "Разрешение конфликта переноса",
                        mergeAssignment = attempt.mergeAssignment ?: attempt.assignment, mergePath = conflict.workingPath)
                        .merging(MergeProgress.Admitted)
                    persist()
                }
                val sessionId = "${attempt.sessionId}-delivery"
                runtime.reconcile(sessionId)
                (attempt.resumption as? StageResumption.UnknownOutcome)?.let { unknown ->
                    block(id, PlanningIssue(IssueKind.UNCERTAIN, "Неизвестен результат команды переноса: ${unknown.tool}", requiresUser = true)); return null
                }
                if (attempt.mergeProgress.needsTurn) {
                    attempt = attempt.merging(MergeProgress.Running); persist()
                    store.withJournaledIntent(id, PlanJournalOperation.DELIVERY_CONFLICT_INTENT, attemptId = attempt.id) {
                        var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                        try {
                            val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                            val activityRecorder = CodingRunRecorder()
                            monitoredRun(project.copy(path = conflict.workingPath), CodingSession(sessionId, project.id, "Конфликт переноса", attempt.startedAt, attempt.mergeEngineSessionId, engine = attempt.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load())),
                                planId = plan.id, parentSessionId = plan.parentSessionId, planningRulesSnapshot = plan.planningRulesSnapshot,
                                pendingRun = CodingRunCheckpoint("${attempt.id}-delivery", "")),
                                "Разреши Git merge-конфликт, сохрани пользовательские изменения и результат плана. Цель: ${plan.goal}. Если изменения уже объединены, продолжи проверки. Добавь разрешённые файлы в индекс, выполни подходящие тесты и сообщи фактические результаты. Не изменяй исходную папку вне этой рабочей копии. Предыдущий отчёт: ${attempt.mergeReport}",
                                (attempt.mergeAssignment ?: attempt.assignment).executionProfile(profiles.load())).collect { event ->
                                activityRecorder.apply(event)
                                attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                                attempt = attempt.after(event, StageRunTrack.MERGE)
                                result = result.after(event)
                                val signal = event.signal
                                if (signal.showsProgress || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                                if (signal.showsProgress || Id.now() - lastSave >= 1000) { persist(); lastSave = Id.now() }
                            }
                        } catch (e: CancellationException) { runtime.abort(sessionId); throw e }
                        if (result.incomplete(attempt.report(StageRunTrack.MERGE))) {
                            if (result.ended) reject()
                            attempt = withRetry(attempt, classify(result.failure ?: "Перенос прерван", uncertain = !result.ended))
                            persist(); block(id, attempt.error!!); return null
                        }
                        attempt = attempt.copy(mergeVerificationSnapshot = workspaces.verificationSnapshot(conflict.workingPath))
                            .merging(MergeProgress.AwaitingVerdict); persist()
                        complete()
                    }
                }
                val (check, verdict) = reviewAcceptance(plan, Milestone("delivery", "Проверка переноса", description = plan.goal),
                    attempt.copy(verificationSnapshot = attempt.mergeVerificationSnapshot), conflict.workingPath, plan.acceptanceCriteria(), attempt.mergeReport, judge)
                attempt = attempt.copy(mergeAcceptanceRecord = check); persist()
                if (verdict.issue != null) {
                    attempt = withRetry(attempt, verdict.issue!!); persist(); block(id, attempt.error!!); return null
                }
                val valid = verdict.passed && workspaces.finishDeliveryConflict(conflict.workingPath)
                attempt = attempt.merging(if (valid) MergeProgress.Settled else MergeProgress.Rejected)
                persist()
            }
        }
        return null
    }

    private suspend fun verifyIntegration(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile): Boolean {
        val workspaces = workspaceFor(id)
        var plan = store.planFor(id)!!
        val finalId = "${plan.runId}-final" + if (plan.finalAttemptHistory.isEmpty()) "" else "-${plan.finalAttemptHistory.size + 1}"
        var attempt = plan.finalAttempt ?: StageAttempt(finalId, "$finalId-session",
            assignment(plan.selectedMilestones.first(), profiles.load()), path = workspace.integrationPath, startedAt = Id.now())
        if (attempt.engine == null) attempt = attempt.copy(engine = plan.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load())))
        if (attempt.phase == AttemptPhase.COMPLETE && attempt.acceptanceRecord != null) return acceptanceStillValid(id, workspace.integrationPath)
        if (attempt.phase == AttemptPhase.COMPLETE) attempt = attempt.copy(phase = AttemptPhase.PREPARED)
        suspend fun persist() { store.update(id) { it.copy(finalAttempt = safeAttempt(attempt.copy(updatedAt = Id.now())), phase = ExecutionPhase.VERIFYING) } }
        persist()
        val pendingCriteria = plan.acceptanceCriteria().filter { criterion ->
            plan.acceptanceWaivers.none { it.runId == plan.runId && it.criterion == criterion }
        }
        if (pendingCriteria.isEmpty() && attempt.phase == AttemptPhase.PREPARED && attempt.pendingTool.isBlank() && !attempt.pendingToolExternal) {
            attempt = attempt.copy(phase = AttemptPhase.VERIFYING, report = "Проверки пропущены по решению пользователя")
            persist()
        }
        if (attempt.phase != AttemptPhase.VERIFYING) {
            attempt = attempt.copy(verificationSnapshot = workspaces.verificationSnapshot(workspace.integrationPath))
            if (attempt.verificationSnapshot.isNullOrBlank()) {
                block(id, PlanningIssue(IssueKind.CONFIGURATION, "Снимок файлов недоступен; итоговая приёмка не проводилась", requiresUser = true))
                return false
            }
            runtime.reconcile(attempt.sessionId)
            (attempt.resumption as? StageResumption.UnknownOutcome)?.let { unknown ->
                block(id, PlanningIssue(IssueKind.UNCERTAIN,
                    "Нет подтверждения результата команды итоговой проверки: ${unknown.tool}", requiresUser = true))
                return false
            }
            attempt = attempt.copy(phase = AttemptPhase.EXECUTING); persist()
            store.withJournaledIntent(id, PlanJournalOperation.FINAL_VERIFICATION_INTENT, attemptId = attempt.id) {
                var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                val criteria = pendingCriteria.joinToString("\n") { it.description }
                try {
                    val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                    val activityRecorder = CodingRunRecorder()
                    monitoredRun(project.copy(path = workspace.integrationPath),
                        CodingSession(attempt.sessionId, project.id, "Итоговая проверка", attempt.startedAt, attempt.engineSessionId, engine = attempt.engine,
                            planId = plan.id, parentSessionId = plan.parentSessionId, planningRulesSnapshot = plan.planningRulesSnapshot,
                            pendingRun = CodingRunCheckpoint("${attempt.id}-verification", "")),
                        "Проверь объединённый результат проекта. Цель: ${plan.goal}. Критерии:\n$criteria\nЗапусти подходящие тесты и проверки. ${verificationGuidance()} Не изменяй исходный код. Отчитайся о командах и их фактических результатах. При продолжении сначала проверь предыдущие результаты: ${attempt.report}",
                        attempt.assignment.executionProfile(profiles.load())).collect { event ->
                        activityRecorder.apply(event)
                        attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                        attempt = attempt.after(event, StageRunTrack.WORK)
                        result = result.after(event)
                        val signal = event.signal
                        if (signal.showsProgress || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                        if (signal.showsProgress || Id.now() - lastSave >= 1000) { persist(); lastSave = Id.now() }
                    }
                } catch (e: CancellationException) { runtime.abort(attempt.sessionId); throw e }
                if (result.incomplete(attempt.report(StageRunTrack.WORK))) {
                    if (result.ended) reject()
                    attempt = withRetry(attempt, classify(result.failure ?: "Итоговая проверка прервана", uncertain = !result.ended))
                    persist(); block(id, attempt.error!!); return false
                }
                attempt = attempt.copy(phase = AttemptPhase.VERIFYING); persist()
                complete()
            }
        }
        workspaces.validateIntegration(workspace)
        plan = store.planFor(id)!!
        val (acceptance, verdict) = reviewAcceptance(plan, Milestone("final", "Итоговая проверка", description = plan.goal),
            attempt, workspace.integrationPath, plan.acceptanceCriteria(), attempt.verificationToolEvidence() + attempt.report, judge)
        attempt = attempt.copy(acceptanceRecord = acceptance)
        persist()
        chatHooks?.verified(plan, Milestone("final", "Итоговая проверка"), attempt, acceptance, judge.modelId)
        if (!verdict.passed) {
            attempt = withRetry(attempt, verdict.issue ?: PlanningIssue(IssueKind.VERIFICATION, verdict.note, requiresUser = true))
            persist(); block(id, attempt.error!!); return false
        }
        attempt = attempt.copy(phase = AttemptPhase.COMPLETE, error = null); persist()
        return true
    }

    private suspend fun reviewAcceptance(plan: Plan, stage: Milestone, attempt: StageAttempt, path: String,
        criteria: List<AcceptanceCriterion>, report: String, judge: LlmProfile): Pair<AcceptanceRecord, Verdict> {
        val waivers = plan.acceptanceWaivers.filter { it.runId == plan.runId && it.criterion in criteria }
        val pending = criteria.filter { criterion -> waivers.none { it.criterion == criterion } }
        val snapshot = if (pending.isEmpty()) attempt.verificationSnapshot ?: waivers.firstOrNull()?.snapshotId
            else attempt.verificationSnapshot ?: workspaces.verificationSnapshot(path)
        val evidence = if (snapshot.isNullOrBlank()) emptyList() else acceptanceChecks.collect(pending, path, snapshot)
        // Missing host collectors are a capability limitation, never a worker defect for the model to repair.
        val reviewable = pending.filter(acceptanceChecks::supports)
        val unavailable = pending.filterNot(acceptanceChecks::supports).map { criterion ->
            AcceptanceFinding(criterion.id, CheckStatus.NOT_RUN, criterion.description,
                "В приложении не подключена проверка ${criterion.checkId.ifBlank { criterion.environment.label() }}. Исполнитель не может зарегистрировать её в рантайме.")
        }
        val reviewed = if (reviewable.isEmpty()) AcceptanceReview(emptyList()) else verifier.review(stage, reviewable, plan.goal, report + "\nПроверки приложения:\n" +
            evidence.filter { proof -> reviewable.any { it.id == proof.criterionId } }
                .joinToString("\n") { "${it.criterionId}: ${it.environment}: ${it.status}: ${it.detail}" }, judge)
        val skipped = if (stage.id == "final") plan.selectedMilestones.filter { it.status == MilestoneStatus.SKIPPED }.flatMap { it.criteria() }.map { it.id }.toSet() else emptySet()
        val findings = (reviewed.findings + unavailable).sortedBy { finding -> criteria.indexOfFirst { it.id == finding.criterionId } }.map { if (it.criterionId in skipped) it.copy(status = CheckStatus.SKIPPED, observed = "Этап пропущен; проверка не выполнялась") else it } +
            waivers.map { AcceptanceFinding(it.criterion.id, CheckStatus.SKIPPED, it.criterion.description, "Проверка пропущена по решению пользователя") }
        val record = AcceptanceGate.evaluate(AcceptanceRecord(plan.runId, attempt.id, snapshot.orEmpty(), criteria, findings, evidence, waivers = waivers, reviewId = Id.new(), reviewer = judge.modelId, reviewedAt = Id.now()),
            criteria, if (pending.isEmpty()) snapshot else workspaces.verificationSnapshot(path))
        val repairProblem = if (!record.permitsProgress) record.automaticRepairProblem(attempt.acceptanceRecord) else null
        val issue = reviewed.issue ?: if (!record.permitsProgress && (!record.canRetryWithWorker || repairProblem != null))
            PlanningIssue(IssueKind.VERIFICATION, listOfNotNull(repairProblem, record.summary()).joinToString("\n\n"), requiresUser = true,
                retryBlocked = record.findings.any { it.recovery == AcceptanceRecovery.OWNER && it.status != CheckStatus.PASS && record.criteria.any { c -> c.id == it.criterionId && c.required } }) else null
        return record to Verdict(record.permitsProgress && issue == null,
            if (record.status == AcceptanceStatus.ACCEPTED_WITH_SKIPS) record.userSummary() else record.findings.singleOrNull()?.observed ?: record.summary(), issue)
    }

    private suspend fun acceptanceStillValid(id: String, path: String): Boolean {
        val plan = store.planFor(id) ?: return false
        if (plan.intent != ExecutionIntent.RUN || plan.issue != null) return false
        val attempt = plan.finalAttempt ?: return false
        val record = attempt.acceptanceRecord ?: return false
        val snapshot = if (record.allChecksSkippedByUser) record.snapshotId else workspaces.verificationSnapshot(path)
        val saved = store.update(id) { current ->
            val check = AcceptanceGate.evaluate(record, current.acceptanceCriteria(), snapshot)
            if (current.runId != record.runId || current.finalAttempt?.id != record.attemptId ||
                current.finalAttempt!!.acceptanceRecord != record || current.intent != ExecutionIntent.RUN) current
            else current.copy(finalAttempt = current.finalAttempt!!.copy(acceptanceRecord = check),
                issue = if (check.permitsProgress) current.issue else PlanningIssue(IssueKind.VERIFICATION, check.summary(), requiresUser = true),
                status = if (check.permitsProgress) current.status else PlanStatus.FAILED)
        }
        return saved.runId == record.runId && saved.finalAttempt?.id == record.attemptId && saved.intent == ExecutionIntent.RUN &&
            saved.issue == null && saved.finalAttempt?.acceptanceRecord?.permitsProgress == true
    }

    private fun assignment(m: Milestone, roster: List<LlmProfile>): StageAssignment {
        m.assignment?.let {
            val request = it.executionProfile(roster)
            val resolved = ModelDefaults.capability(request).resolveEffort(it.effort)
            return it.copy(effectiveEffort = EffortSelection.ofOrNull(resolved.level), options = it.options ?: request.advanced)
        }
        val p = roster.firstOrNull { it.id == m.agentProfileId && it.configured } ?: error("Источник этапа «${m.title}» недоступен")
        val model = m.agentModelId.ifBlank { p.codingModelId.ifBlank { p.modelId } }
        val effort = p.effortSelectionFor(model)
        val effective = ModelDefaults.capability(p.copy(modelId = model)).resolveEffort(effort).level
        return StageAssignment(p.id, model, effort, EffortSelection.ofOrNull(effective), displayName = p.modelName(model), options = p.forModel(model, effort).advanced)
    }

    private suspend fun executeStage(id: String, stageId: String, project: CodingProject, workspace: PlanWorkspace, integration: Mutex, judge: LlmProfile) {
        val workspaces = workspaceFor(id)
        var currentAttempt: StageAttempt? = null
        try {
            if (!canRunStage(id, stageId)) return
            var stage = store.planFor(id)!!.milestones.first { it.id == stageId }
            var attempt = stage.attempts.lastOrNull() ?: StageAttempt(Id.new(), if (store.planFor(id)!!.parentSessionId.isNotBlank()) "plan-$id-stage-$stageId" else Id.new(), assignment(stage, profiles.load()), startedAt = Id.now())
            val preparation = reduce(attempt.toState(), StageEvent.InspectPreparation)
            if (preparation.has(StageEffect.ResolveEngine)) {
                val saved = projects?.sessions(project.id)?.firstOrNull { it.id == attempt.sessionId }
                val engine = saved?.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load()))
                attempt = reduce(attempt.toState(), StageEvent.EngineResolved(engine)).state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
            }
            currentAttempt = attempt
            if (stage.attempts.isEmpty()) saveAttempt(id, stageId, attempt)
            if (preparation.has(StageEffect.PrepareWorkspace)) {
                store.withJournaledIntent(id, PlanJournalOperation.STAGE_WORKSPACE_INTENT, stageId, attempt.id) {
                    val prepared = integration.withLock { workspaces.stage(project, workspace, attempt) }
                    attempt = reduce(attempt.toState(), StageEvent.WorkspacePrepared(prepared)).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    complete()
                }
            }
            currentAttempt = attempt
            workspaces.validateExecutionPath(project, attempt.path)
            workspaces.reconcile(attempt)
            runtime.reconcile(attempt.sessionId)
            runtime.reconcile("${attempt.sessionId}-merge")
            val pending = store.unsettled(id).any { record ->
                PlanJournalSubject.decode(record.detail).let { it.stage == stageId && it.attempt == attempt.id }
            }
            val resumption = reduce(attempt.toState(), StageEvent.Reconciled(pending))
            attempt = resumption.state.applyTo(attempt)
            if (resumption.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
            currentAttempt = attempt
            resumption.issue?.let { block(id, it) }
            if (resumption.has(StageEffect.Yield)) return
            while (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunWorker)) {
                if (!canRunStage(id, stageId)) return
                val plan = store.planFor(id)!!
                val recorded = plan.coordination.any { it.id == "${attempt.id}-turn-${attempt.turnIndex}" }
                val turn = reduce(attempt.toState(), StageEvent.TurnRequested(chatHooks != null, recorded))
                attempt = turn.state.applyTo(attempt)
                if (turn.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                turn.issue?.let { block(id, it) }
                if (turn.has(StageEffect.Yield)) return
                if (turn.has(StageEffect.Coordinate)) {
                    val decision = chatHooks!!.finished(plan, stage, attempt)
                    val resumed = reduce(attempt.toState(), StageEvent.PlannerDecided(decision, restored = true))
                    attempt = resumed.state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    if (resumed.has(StageEffect.AskUser) || resumed.has(StageEffect.WaitForEvent)) {
                        if (!hasQueuedReply(id, stageId)) return
                        attempt = reduce(attempt.toState(), StageEvent.UserAnswered).state.applyTo(attempt)
                        saveAttempt(id, stageId, attempt)
                    }
                    if (resumed.has(StageEffect.RunVerifier)) break
                    continue
                }
                val frozen = attempt.assignment.executionProfile(profiles.load())
                val extraInstructions = chatHooks?.instructions(plan, stage, attempt).orEmpty()
                // Inbox migration can restore a completed turn's VERIFY checkpoint while this
                // coroutine is suspended. Never overwrite that checkpoint with a fresh native intent.
                if (!canRunStage(id, stageId)) return
                val drift = CheckpointDrift(plan, attempt, store.planFor(id), stageId)
                val checkpoint = reduce(attempt.toState(), StageEvent.CheckpointObserved(drift))
                if (drift is CheckpointDrift.Advanced) stage = drift.stage
                attempt = checkpoint.state.applyTo(attempt)
                currentAttempt = attempt
                if (checkpoint.has(StageEffect.RunVerifier)) break
                if (checkpoint.has(StageEffect.Yield)) return
                val prompt = stageWorkerPrompt(plan, stage, attempt, verificationGuidance(), extraInstructions)
                attempt = reduce(attempt.toState(), StageEvent.WorkerStarting(prompt, Id.now())).state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
                store.withJournaledIntent(id, PlanJournalOperation.AGENT_INTENT, stageId, attempt.id) {
                    val admitted = prepareAttempt(store.planFor(id)!!, stageId, attempt)
                    attempt = reduce(attempt.toState(), StageEvent.WorkerAdmitted(admitted)).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    currentAttempt = attempt
                    var result = StageRunResult()
                    var lastSave = 0L
                    var lastDisplay = 0L
                    var outputPendingSave = false
                    var outputPendingDisplay = false
                    val session = CodingSession(attempt.sessionId, project.id, "План: ${stage.title}", attempt.startedAt, attempt.engineSessionId, engine = attempt.engine,
                        planId = plan.id, stageId = stage.id, parentSessionId = plan.parentSessionId.takeIf { it.isNotBlank() },
                        role = CodingSessionRole.WORKER,
                        runtimeGeneration = attempt.sessionGeneration,
                        planningRulesSnapshot = plan.planningRulesSnapshot,
                        pendingRun = CodingRunCheckpoint("${attempt.id}-turn-${attempt.turnIndex}", ""))
                    if (plan.parentSessionId.isBlank()) projects?.saveSession(session)
                    val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                    val activityRecorder = CodingRunRecorder()
                    var deliveryAcknowledged = false
                    stageWorkspaceNotice(workspace, attempt)?.let { notice ->
                        activityRecorder.apply(CodingEvent.Notice(notice))
                        attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                        saveAttempt(id, stageId, attempt)
                    }
                    if (!canRunStage(id, stageId)) return
                    monitoredRun(project.copy(path = attempt.path), session, prompt, frozen).collect { event ->
                        val now = outputClock()
                        val signal = event.signal
                        if (signal == StageEngineSignal.Silence) {
                            // Silence is a flush opportunity, not new output. Rewriting a large
                            // plan every second also makes the orchestrator reload all histories.
                            if (outputPendingDisplay && now - lastDisplay >= 100) {
                                val preview = safeAttempt(attempt.copy(updatedAt = Id.now()))
                                liveState.update { it + (attempt.id to preview) }
                                lastDisplay = now
                                outputPendingDisplay = false
                            }
                            if (outputPendingSave && now - lastSave >= 1000) {
                                saveAttempt(id, stageId, attempt)
                                lastSave = now
                                outputPendingSave = false
                            }
                            return@collect
                        }
                        outputPendingSave = true
                        outputPendingDisplay = true
                        if (!deliveryAcknowledged && event.showsEngineAnswering) {
                            chatHooks?.started(store.planFor(id)!!, stage, attempt)
                            deliveryAcknowledged = true
                        }
                        activityRecorder.apply(event)
                        attempt = reduce(attempt.toState(), StageEvent.EngineOutput(event, StageRunTrack.WORK,
                            activityHistory + activityRecorder.timeline())).state.applyTo(attempt)
                        result = result.after(event)
                        currentAttempt = attempt
                        if (signal.showsProgress || now - lastDisplay >= 100) {
                            val preview = safeAttempt(attempt.copy(updatedAt = Id.now()))
                            liveState.update { it + (attempt.id to preview) }; lastDisplay = now
                            outputPendingDisplay = false
                        }
                        // Narrower than the display: only a record recovery will read is worth
                        // rewriting the whole plan for, out of turn.
                        if (signal.mustReachDisk || now - lastSave >= 1000) {
                            saveAttempt(id, stageId, attempt)
                            lastSave = now
                            outputPendingSave = false
                        }
                    }
                    val submitted = store.planFor(id)?.coordination?.firstOrNull { it.id == "${attempt.id}-turn-${attempt.turnIndex}" && it.toolCallId != null }
                    attempt = reduce(attempt.toState(), StageEvent.WorkerTurnEnded(Id.now(), submitted?.reply?.text)).state.applyTo(attempt)
                    currentAttempt = attempt
                    if (result.incomplete(attempt.report(StageRunTrack.WORK))) {
                        if (result.ended) reject()
                        // The turn is over either way: the attempt drops back to FAILED, from where
                        // it may run again, and the issue alone says whether anyone will start it.
                        val issue = classify(result.failure ?: "Поток завершился без подтверждённого результата", uncertain = !result.ended)
                        attempt = reduce(attempt.toState(), StageEvent.TransportFailed(issue, retryInputs(), workerFailed = true)).state.applyTo(attempt)
                        saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return
                    }
                    val accepted = reduce(attempt.toState(), StageEvent.WorkerAccepted(workspaces.verificationSnapshot(attempt.path), chatHooks != null))
                    attempt = accepted.state.applyTo(attempt)
                    if (accepted.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                    complete()
                }
                val decision = reduce(attempt.toState(), StageEvent.PlannerDecided(chatHooks?.finished(store.planFor(id)!!, stage, attempt)))
                attempt = decision.state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
                if (decision.has(StageEffect.AskUser) || decision.has(StageEffect.WaitForEvent)) {
                    if (!hasQueuedReply(id, stageId)) return
                    attempt = reduce(attempt.toState(), StageEvent.UserAnswered).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                }
            }
            if (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunVerifier)) {
                val verificationPlan = store.planFor(id)!!
                val (acceptance, verdict) = reviewAcceptance(verificationPlan, stage, attempt, attempt.path,
                    stage.criteria(), verificationPlan.stageVerificationReport(stageId, attempt), judge)
                attempt = reduce(attempt.toState(), StageEvent.AcceptanceRecorded(acceptance)).state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
                chatHooks?.verified(verificationPlan, stage, attempt, acceptance, judge.modelId)
                val verification = reduce(attempt.toState(), StageEvent.VerificationDecided(verdict, retryInputs()))
                if (verification.has(StageEffect.RecordVerification)) {
                    store.update(id) { p -> p.copy(
                        milestones = p.milestones.map { if (it.id == stageId) it.copy(checkNote = safeText(verdict.note)) else it },
                        coordination = p.coordination.map { record ->
                            if (record.id == "${attempt.id}-turn-${attempt.turnIndex - 1}")
                                record.copy(verification = StageVerification(verdict.passed, safeText(verdict.note))) else record
                        }) }
                }
                attempt = verification.state.applyTo(attempt)
                if (verification.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                verification.issue?.let { block(id, it) }
                if (verification.has(StageEffect.Yield)) return
                store.withJournaledIntent(id, PlanJournalOperation.CAPTURE_INTENT, stageId, attempt.id) {
                    val commit = workspaces.capture(attempt.copy(report = stage.title + "\n" + attempt.report))
                    attempt = reduce(attempt.toState(), StageEvent.Captured(commit)).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    complete()
                }
            }
            if (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunMerge)) integration.withLock {
                val latest = store.planFor(id) ?: return@withLock
                if (latest.issue != null || latest.intent == ExecutionIntent.STOP || closing || store.failure.value != null) return@withLock
                store.withJournaledIntent(id, PlanJournalOperation.MERGE_INTENT, stageId, attempt.id) {
                    val integrationResult = workspaces.integrate(workspace, attempt)
                    var merged = reduce(attempt.toState(), StageEvent.MergeStarted(integrationResult)).has(StageEffect.Finish)
                    while (!merged && canRun(id)) {
                        val conflict = reduce(attempt.toState(), StageEvent.ConflictRequested(workspace.integrationPath, settings.load().agentLimits.retries))
                        if (conflict.has(StageEffect.Yield)) break
                        conflict.effects.filterIsInstance<StageEffect.Delay>().forEach { delay(it.millis) }
                        currentCoroutineContext().ensureActive()
                        attempt = conflict.state.applyTo(attempt)
                        if (conflict.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                        if (conflict.has(StageEffect.RunConflictAgent)) {
                            attempt = reduce(attempt.toState(), StageEvent.ConflictStarted).state.applyTo(attempt)
                            saveAttempt(id, stageId, attempt)
                            store.withJournaledIntent(id, PlanJournalOperation.CONFLICT_AGENT_INTENT, stageId, attempt.id) {
                                val mergeSession = CodingSession("${attempt.sessionId}-merge", project.id, "Объединение: ${stage.title}", attempt.startedAt, attempt.mergeEngineSessionId, engine = attempt.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load())),
                                    planId = id, stageId = stageId, parentSessionId = latest.parentSessionId,
                                    planningRulesSnapshot = latest.planningRulesSnapshot,
                                    pendingRun = CodingRunCheckpoint("${attempt.id}-merge", ""))
                                var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                                val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                                val activityRecorder = CodingRunRecorder()
                                monitoredRun(project.copy(path = workspace.integrationPath), mergeSession,
                                    stageMergePrompt(stage, attempt),
                                    (attempt.mergeAssignment ?: attempt.assignment).executionProfile(profiles.load())).collect { event ->
                                    activityRecorder.apply(event)
                                    attempt = reduce(attempt.toState(), StageEvent.EngineOutput(event, StageRunTrack.MERGE,
                                        activityHistory + activityRecorder.timeline())).state.applyTo(attempt)
                                    result = result.after(event)
                                    val signal = event.signal
                                    if (signal.showsProgress || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                                    if (signal.showsProgress || Id.now() - lastSave >= 1000) { saveAttempt(id, stageId, attempt); lastSave = Id.now() }
                                }
                                if (result.incomplete(attempt.report(StageRunTrack.MERGE))) {
                                    if (result.ended) reject()
                                    attempt = withRetry(attempt, classify(result.failure ?: "Объединение прервано", uncertain = !result.ended))
                                    saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                                }
                                attempt = reduce(attempt.toState(), StageEvent.ConflictTurnEnded).state.applyTo(attempt); saveAttempt(id, stageId, attempt)
                                complete()
                            }
                        }
                        val verdict = verifier.verify(stage, store.planFor(id)!!.goal, attempt.mergeReport, judge)
                        if (verdict.issue != null) {
                            attempt = withRetry(attempt, verdict.issue!!)
                            saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                        }
                        merged = verdict.passed && workspaces.finishConflict(workspace, attempt)
                        attempt = reduce(attempt.toState(), StageEvent.MergeFinished(merged)).state.applyTo(attempt)
                        saveAttempt(id, stageId, attempt)
                    }
                    if (!merged) {
                        reject()
                        val issue = PlanningIssue(IssueKind.CONFLICT, "Не удалось объединить ${stage.title}; рабочие копии сохранены", requiresUser = true)
                        saveAttempt(id, stageId, attempt.copy(error = issue)); block(id, issue); return@withLock
                    }
                    attempt = reduce(attempt.toState(), StageEvent.Completed).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    journal(id, PlanJournalOperation.STAGE_COMPLETE, stageId, attempt.id)
                    complete()
                }
            }
        } catch (e: CancellationException) {
            currentAttempt?.let { snapshot ->
                withContext(NonCancellable) {
                    runtime.abort(snapshot.sessionId); runtime.abort("${snapshot.sessionId}-merge")
                    // Keep streamed context and engine identity, without charging a retry.
                    val plan = store.planFor(id)
                    val saved = plan?.milestones?.firstOrNull { it.id == stageId }?.attempts?.lastOrNull()
                    if (saved != null) {
                        val waiting = stageId in chatHooks?.blockedStages(plan).orEmpty()
                        val interruption = reduce(saved.toState(), StageEvent.Interrupted(snapshot, waiting, Id.now()))
                        if (interruption.has(StageEffect.Persist)) saveAttempt(id, stageId, interruption.state.applyTo(saved))
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            val issue = if (e is UnsafePlanningWorkspace) PlanningIssue(IssueKind.CONFIGURATION, e.message.orEmpty(), requiresUser = true, retryBlocked = true)
                else classify(e.message ?: "Ошибка этапа")
            try {
                // Read the durable attempt: local snapshots can precede a phase transition.
                val saved = store.planFor(id)?.milestones?.firstOrNull { it.id == stageId }?.attempts?.lastOrNull()
                val failed = saved?.let { withRetry(it, issue) }
                if (failed != null) saveAttempt(id, stageId, failed)
                block(id, failed?.error ?: issue.copy(requiresUser = true))
            } catch (storage: Exception) { errorState.value = storage.message; throw storage }
        } finally {
            currentAttempt?.let { a -> liveState.update { it - a.id } }
        }
    }
    private val shared = object : PlanningWorkspace by LocalPlanningWorkspace() {
        override suspend fun acquire(project: CodingProject) = workspaces.acquire(project)
        override suspend fun validateExecutionPath(project: CodingProject, path: String) = workspaces.validateExecutionPath(project, path)
        override suspend fun release(project: CodingProject) = workspaces.release(project)
        override suspend fun verificationSnapshot(path: String) = workspaces.verificationSnapshot(path)
    }
    private val direct = object : PlanningWorkspace by shared {
        override suspend fun validateExecutionPath(project: CodingProject, path: String) = Unit
    }
    private suspend fun workspaceFor(id: String): PlanningWorkspace {
        val plan = store.planFor(id)
        return when (plan?.worktreeEnabled) {
            true -> workspaces
            false -> direct
            null -> if (plan?.sharedWorkspace == true) shared else workspaces
        }
    }
    private suspend fun canRunStage(id: String, stageId: String): Boolean = canRun(id) &&
        store.planFor(id)?.let { stageId !in chatHooks?.blockedStages(it).orEmpty() } == true
    private suspend fun canRun(id: String) = !closing && store.failure.value == null && store.planFor(id)?.intent == ExecutionIntent.RUN
    private suspend fun canRetry(count: Int): Boolean = PlanningRetryPolicy.canRetry(count, settings.load().agentLimits.retries)

    /** The clock and the randomness enter the retry policy here and nowhere else. */
    private suspend fun retryDecision(issue: PlanningIssue, completedRetries: Int): RetryDecision =
        PlanningRetryPolicy.decide(issue, completedRetries, settings.load().agentLimits.retries, Id.now(), Random.nextLong(500))

    /** Spends one transport attempt on [issue] and records the outcome on the attempt. */
    private suspend fun withRetry(attempt: StageAttempt, issue: PlanningIssue): StageAttempt =
        reduce(attempt.toState(), StageEvent.TransportFailed(issue, retryInputs())).state.applyTo(attempt)

    private suspend fun retryInputs() = StageRetryInputs(settings.load().agentLimits.retries, Id.now(), Random.nextLong(500))

    /** Flush partial output while waiting. A coding turn may legitimately be silent during reasoning;
     * the chat-request timeout is not an inactivity deadline for the coding runtime.
     * Runtime failures and explicit cancellation remain authoritative. */
    private fun monitoredRun(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> = channelFlow {
        val events = runtime.run(project, session, prompt, profile).produceIn(this)
        var silentTicks = 0
        try {
            while (isActive) {
                val received = withTimeoutOrNull(100) { events.receiveCatching() }
                if (received == null) {
                    silentTicks++
                    if (silentTicks == 300) send(CodingEvent.Notice("Ожидание новых событий агента. Запрос остаётся активным; при необходимости его можно остановить."))
                    // Flush without adding a visible or persisted timeline entry on every tick.
                    send(CodingEvent.Notice(""))
                    continue
                }
                val event = received.getOrNull()
                if (event == null) {
                    // A producer can cancel itself (budget/deadline) while the scheduler
                    // remains active. Keep its reason; a clean EOF must not hide that stop.
                    // A real owner/user cancellation still propagates as cancellation.
                    currentCoroutineContext().ensureActive()
                    received.exceptionOrNull()?.let { cause ->
                        send(CodingEvent.Failed(cause.message?.takeIf { it.isNotBlank() }
                            ?: "Выполнение агента прервано"))
                    }
                    break
                }
                silentTicks = 0
                send(event)
                // Drain normal completion so runtime cleanup and independent experience checks finish.
                // Cancelling the producer at Finished would misclassify completed runs as cancellation.
            }
        } finally { events.cancel() }
    }
    private suspend fun saveAttempt(id: String, stageId: String, attempt: StageAttempt): Plan {
        val projection = attempt.takeIf { it.phase == AttemptPhase.COMPLETE && it.sessionGeneration > 0 }?.let(::projectionId)
        val saved = store.update(id) { p ->
            p.copy(phase = ExecutionPhase.EXECUTING,
                pendingSessionProjections = if (projection == null) p.pendingSessionProjections else p.pendingSessionProjections + projection,
                milestones = p.milestones.map { m ->
                if (m.id != stageId) m else m.copy(
                    status = when { attempt.phase == AttemptPhase.COMPLETE -> MilestoneStatus.DONE; attempt.error?.requiresUser == true -> MilestoneStatus.FAILED; else -> MilestoneStatus.ACTIVE },
                    report = safeText(attempt.report),
                    attempts = m.attempts.filterNot { it.id == attempt.id } + safeAttempt(attempt.copy(updatedAt = Id.now())),
                )
            })
        }
        val checkpoint = saved.milestones.first { it.id == stageId }.attempts.first { it.id == attempt.id }
        if (projection == null) attemptCheckpoint(saved, stageId, checkpoint)
        else projectAcceptedCheckpoint(saved, stageId, checkpoint)
        return saved
    }
    private fun projectionId(attempt: StageAttempt) = "${attempt.id}:${attempt.sessionGeneration}"

    /** Retry only the saved projection; a committed accepted stage never re-runs its native/Git effects. */
    suspend fun synchronizeSessionProjections(id: String) {
        val saved = store.planFor(id) ?: return
        saved.milestones.forEach { stage ->
            stage.attempts.filter { it.phase == AttemptPhase.COMPLETE && projectionId(it) in saved.pendingSessionProjections }
                .forEach { projectAcceptedCheckpoint(saved, stage.id, it) }
        }
    }
    private suspend fun projectAcceptedCheckpoint(plan: Plan, stageId: String, attempt: StageAttempt) {
        val marker = projectionId(attempt)
        try {
            attemptCheckpoint(plan, stageId, attempt)
            store.update(plan.id) { it.copy(pendingSessionProjections = it.pendingSessionProjections - marker) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Plan's acceptance was already durable. Do not downgrade it because a separate
            // aggregate/message projection failed after that commit.
            errorState.value = "Результат сохранён; синхронизация сессии ожидает повторной попытки"
            runCatching {
                store.update(plan.id) { latest ->
                    val event = "$marker-session-projection"
                    if (latest.journal.any { it.id == event }) latest else latest.copy(journal = latest.journal +
                        PlanJournalEntry(event, Id.now(), stageId, attempt.id, PlanJournalOperation.SESSION_PROJECTION_PENDING.wire, safeText(error.message.orEmpty())))
                }
            }
        }
    }
    /** A wizard answer can arrive between publishing the question and saving WAIT. */
    private suspend fun hasQueuedReply(id: String, stageId: String): Boolean = store.planFor(id)?.deliveries?.any {
        it.targetStageId == stageId && it.state == DeliveryState.QUEUED && it.replyTo != null
    } == true

    private suspend fun journal(id: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = "") =
        store.journal(id, operation, stageId, attemptId)
    private suspend fun block(id: String, issue: PlanningIssue) = store.update(id) {
        it.copy(issue = issue.copy(message = safeText(issue.message)), phase = ExecutionPhase.WAITING, status = if (issue.requiresUser) PlanStatus.FAILED else PlanStatus.RUNNING)
    }
    private fun safeText(text: String) = PlanningDiagnostics.redact(text, secrets.value)
    private fun publish(attempt: StageAttempt) {
        val safe = safeAttempt(attempt)
        liveState.update { state ->
            val old = state[attempt.id]
            if (old?.report == safe.report && old.activity == safe.activity && old.mergeReport == safe.mergeReport && old.steps == safe.steps) state
            else state + (attempt.id to safe.copy(updatedAt = Id.now()))
        }
    }
    private fun safeAttempt(a: StageAttempt) = a.copy(prompt = safeText(a.prompt), report = safeText(a.report), activity = safeText(a.activity),
        acceptanceRecord = a.acceptanceRecord?.let { record -> record.copy(
            findings = record.findings.map { it.copy(observed = safeText(it.observed), artifacts = it.artifacts.map(::safeText)) },
            evidence = record.evidence.map { it.copy(detail = safeText(it.detail), artifacts = it.artifacts.map(::safeText)) }) },
        steps = a.steps.filter { it.isVisibleActivity }.map { it.copy(title = safeText(it.title), result = safeText(it.result), tool = safeText(it.tool), callId = safeText(it.callId)) },
        mergeReport = safeText(a.mergeReport), pendingTool = safeText(a.pendingTool), error = a.error?.let { it.copy(message = safeText(it.message)) })
    private fun classify(message: String, uncertain: Boolean = false): PlanningIssue {
        val lower = message.lowercase()
        val kind = when {
            uncertain -> IssueKind.UNCERTAIN
            listOf("ошибка сохранения", "поврежден", "повреждён", "disk", "no space", "диске").any { it in lower } -> IssueKind.STORAGE
            listOf("401", "403", "авториз", "источник", "модель", "папка", "подключите").any { it in lower } -> IssueKind.CONFIGURATION
            Regex("\\b5\\d{2}\\b").containsMatchIn(lower) ||
                listOf("429", "network", "connection", "timeout", "сеть").any { it in lower } -> IssueKind.TRANSIENT
            "конфликт" in lower || "conflict" in lower -> IssueKind.CONFLICT
            else -> IssueKind.UNCERTAIN
        }
        return PlanningIssue(kind, message.take(2000), requiresUser = kind != IssueKind.TRANSIENT)
    }
}
