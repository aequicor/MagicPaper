package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.planning.command
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
import kotlinx.coroutines.flow.flowOn
import io.aequicor.magicpaper.data.storage.JournalRecord
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Application-owned durable scheduler. Composables only issue commands and observe state. */
class PlanningExecutionService(
    private val store: PlanningStore,
    private val runtime: CodingRuntime,
    private val projects: CodingProjectOwner?,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val verifier: MilestoneVerifier,
    private val workspaces: PlanningWorkspace,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val outputClock: () -> Long = Id::now,
    private val retryClock: () -> Long = Id::now,
    private val acceptanceChecks: AcceptanceChecks = AcceptanceChecks(),
    private val taskWorktrees: TaskWorktreeService? = null,
    private val journalRecovery: PlanningJournalRecovery = PlanningJournalRecovery(store, runtime, projects),
    private val strategyClassifier: PlanStrategyClassifier? = null,
    private val attemptAuthority: PlanningAttemptAuthority,
    private val chatHooksProvider: () -> PlanningExecutionHooks?,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val supported: Boolean get() = runtime.supported
    // The graph resolves this scoped neighbour only when used, after both owners are constructed.
    private val chatHooks: PlanningExecutionHooks? get() = chatHooksProvider()
    internal fun verificationGuidance(): String = acceptanceChecks.executionGuidance()
    private val nativeRecovery = PlanningNativeRecoveryInterpreter(store, runtime)
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
            store.plans() // Restore projections only; a saved RUN or STOP never admits external work.
            while (isActive && !closing) {
                try {
                    if (store.failure.value != null) {
                        jobsLock.withLock { jobs.values.toList() }.joinAll()
                        store.recover()
                    }
                    store.plans().filter { it.pendingSessionProjections.isNotEmpty() }.forEach { synchronizeSessionProjections(it.id) }
                    store.plans().filter { store.currentAdmission(it.id) != null && it.intent == ExecutionIntent.RUN && it.phase != ExecutionPhase.COMPLETE }
                        .forEach { saved ->
                            val plan = saved
                            if ((plan.issue?.requiresUser != true || plan.issue?.kind == IssueKind.CONFIGURATION) &&
                                (plan.issue?.retryAt ?: 0) <= retryClock()) launchProject(plan.id)
                        }
                    if (store.failure.value == null) errorState.value = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errorState.value = "Не удалось восстановить планирование. Проверьте доступ к данным." }
                delay(5_000)
            }
        }
    }

    suspend fun start(projectId: String) {
        require(runtime.supported) { "Выполнение доступно в Desktop" }
        attemptAuthority.requireRuntimePolicyReady()
        val plan = store.planFor(projectId) ?: error("План не найден")
        check(!plan.stopping) { "Дождитесь подтверждения остановки" }
        if (rejectUnsettledJournal(plan)) return
        if (plan.phase == ExecutionPhase.COMPLETE) return
        if (store.currentAdmission(plan.id) != null) { launchProject(plan.id); return }
        val pausedRef = store.machineStates.value[plan.id]?.run?.takeIf { it.phase == PlanningMachine.RunPhase.PAUSED }?.ref
        if(pausedRef != null && jobsLock.withLock { jobs[plan.id]?.isActive == true }) {
            store.command(plan.id, PlanningMachine.Intent.Resume(pausedRef, stamp()))
            return
        }
        val graph = DecisionCompiler.compile(plan)
        require(graph.valid && graph.stageIds.isNotEmpty()) { graph.errors.joinToString("\n").ifBlank { "Нет этапов" } }
        val rules = plan.planningRulesSnapshot ?: if (plan.runId.isBlank()) settings.load().planningRules.snapshot()
            else PlanningRulesSettings().snapshot().copy(source = PlanningRulesSource.LEGACY)
        store.command(projectId, PlanningMachine.Intent.Start(Id.new(), rules, stamp()))
        chatHooks?.prepareSessions(store.planFor(plan.id)!!)
        launchProject(plan.id)
    }
    suspend fun pause(projectId: String) {
        store.command(projectId, PlanningMachine.Intent.Pause(stamp()))
    }
    suspend fun stop(projectId: String) {
        val requested = store.dispatch(projectId, PlanningMachine.Intent.Stop(stamp()))
        val refusal = requested.rejection
        // A finished plan has nothing to stop, and archiving or deleting its session asks for it all the same.
        // Only that refusal is absorbed: a fenced or deleted owner still refuses for its own reason.
        if (refusal != null && requested.state.let { !it.persistenceUnknown && !it.deleted && it.plan?.phase == ExecutionPhase.COMPLETE }) return
        if (refusal != null) throw IllegalArgumentException(refusal.reason)
        val plan = checkNotNull(requested.state.plan)
        // A broken transport must not prevent coroutine cancellation of the rest of the subtree.
        val signalErrors = runtimeSessionIds(plan).mapNotNull { sessionId ->
            try { runtime.abort(sessionId); null } catch (e: Exception) { safeText(e.message.orEmpty()) }
        }
        val job = jobsLock.withLock { jobs[plan.id]?.also { it.cancel() } }
        if (signalErrors.isNotEmpty()) store.command(plan.id, PlanningMachine.Fact.JournalObserved(
            PlanJournalOperation.STOP_SIGNAL_ERROR, detail = "Не удалось передать сигнал остановки", stamp = stamp()))
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
    suspend fun edit(projectId: String, revision: Long, change: (Plan) -> Plan): Plan {
        val old = store.planFor(projectId) ?: error("План не найден")
        return store.command(projectId, PlanningMachine.Intent.Edit(revision, change(old), stamp()))
    }
    /** Rebase a proposal over telemetry, never over intervening edits or started work. */
    /** Explicit retry does not erase counters; the caller fixes configuration or acknowledges uncertainty. */
    suspend fun retry(projectId: String, expectedCheckpoint: Plan? = null, expectedBlockerIds: Set<String>? = null) {
        attemptAuthority.requireRuntimePolicyReady()
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
                attemptAuthority.authorizeRetry(before, stage.id, attempt)?.let { attempt.id to it }
            }
        }.toMap()
        val rules = before.planningRulesSnapshot ?: settings.load().planningRules.snapshot()
        store.command(projectId, PlanningMachine.Intent.Retry(before, authorizations, Id.new(), rules, stamp()))
        launchProject(store.planFor(projectId)!!.id)
    }

    /** Host-only user decision; model commands never call this method. */
    internal suspend fun continueWithoutVerification(planId: String, expectedBlockerIds: Set<String>,
        expectedProofs: Set<PlanningSkipProof>) {
        attemptAuthority.requireRuntimePolicyReady()
        val plan = store.planFor(planId) ?: error("План не найден")
        store.command(planId, PlanningMachine.Intent.SkipVerification(expectedBlockerIds, Id.new(),
            plan.planningRulesSnapshot ?: settings.load().planningRules.snapshot(), stamp(),
            expectedProofs))
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
        store.revokeAdmissions()
        jobsLock.withLock { journalRecovery.clearInspections() }
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
        attemptAuthority.requireRuntimePolicyReady()
        val admission = store.currentAdmission(id) ?: return@withLock
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
            plan = store.command(id, PlanningMachine.Fact.RulesBound(snapshot, stamp()))
        }
        jobs[id] = scope.launch(PlanningRunContext(admission) + UsageOwner(UsageScope(plan?.parentSessionId?.let { "coding:$it" }, projectId = plan?.projectId, planId = id), updatesContext = false)) { executeProject(id) }
    }
    private suspend fun rejectUnsettledJournal(plan: Plan): Boolean = jobsLock.withLock {
        // A running effect is expected to have an open intent until its scope exits.
        if (jobs[plan.id]?.isCompleted == false && store.machineStates.value[plan.id]?.pendingOperations.orEmpty().isEmpty()) return@withLock false
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
        if (plan.issue != issue) store.command(plan.id, PlanningMachine.Fact.IssueObserved(null, issue, stamp = stamp()))
    }

    /** Host-only recovery; inspecting and confirming outcomes never launches execution. */
    suspend fun reconcileJournalQuarantine(session: CodingSession, confirmed: Boolean): QuarantineRecoveryOutcome = jobsLock.withLock {
        val affected = store.plans().filter { it.projectId == session.projectId &&
            (it.parentSessionId == session.id || it.id == session.planId || it.milestones.any { stage -> stage.attempts.any { attempt -> attempt.sessionId == session.id } }) }
        check(affected.none { jobs[it.id]?.isCompleted == false }) { "Дождитесь остановки выполнения перед сверкой" }
        journalRecovery.reconcile(session, confirmed)
    }

    suspend fun inspectPlanRecovery(planId: String): PlanningRecoveryInspection = jobsLock.withLock {
        check(!closing && jobs[planId]?.isCompleted != false) { "Дождитесь остановки выполнения перед сверкой" }
        journalRecovery.inspectPlan(planId)
    }

    suspend fun confirmPlanRecovery(inspection: PlanningRecoveryInspection) = jobsLock.withLock {
        check(!closing && jobs[inspection.planId]?.isCompleted != false) { "Дождитесь остановки выполнения перед сверкой" }
        journalRecovery.confirmPlan(inspection)
    }

    private suspend fun executeProject(id: String) {
        if (store.failure.value != null || store.currentAdmission(id) != runRef(id)) return
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
        var worktreeEnabled = initialTaskPlan.worktreeEnabled
        if (worktreeEnabled == true && originalProject != null && initialTaskPlan.workspace == null) {
            val parent = projects?.sessions(originalProject.id)?.firstOrNull { it.id == initialTaskPlan.parentSessionId }
            val existingTask = parent?.taskWorktree?.takeUnless { it.phase == TaskWorktreePhase.COMPLETE }
            if (existingTask == null && taskWorktrees?.availability(originalProject)?.available != true)
                worktreeEnabled = false
        }
        // Commit the resolved capability once; the pre-check snapshot must not restore a declined mode.
        worktreeEnabled?.let { store.command(id, PlanningMachine.Fact.WorkspaceSelected(runRef(id), it, stamp())) }
        val workspaces = workspaceFor(id)
        val acquiredProjects = mutableMapOf<CodingProject, WorkspaceLease>()
        suspend fun acquire(owner: CodingProject): Boolean = withContext(NonCancellable) {
            workspaces.acquire(owner, Id.new())?.also { acquiredProjects[owner] = it } != null
        }
        suspend fun release(owner: CodingProject) = withContext(NonCancellable) {
            val lease = acquiredProjects[owner] ?: return@withContext
            workspaces.release(lease)
            acquiredProjects.remove(owner)
        }
        var executionFailure: Throwable? = null
        try {
            val initial = store.planFor(id) ?: return
            if (initial.blockingIssues(emptyList()).any { it.issue.retryBlocked }) return
            var project = projects?.all()?.firstOrNull { it.id == initial.projectId }
            if (project == null) { block(id, PlanningIssue(IssueKind.CONFIGURATION, "Папка проекта не найдена")); return }
            check(store.plans().none { it.id != id && it.projectId == project.id && it.stopping }) {
                "Остановка другой работы в этом проекте не подтверждена"
            }
            if (initial.worktreeEnabled == true) {
                check(initial.runId.isNotBlank()) { "Запуск не был разрешён" }
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
            if (plan.restoreSkippedVerification() != plan) plan = store.command(id, PlanningMachine.Fact.SkippedVerificationRestored(runRef(id), stamp()))
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
            if (plan.issue != null) plan = store.command(id, PlanningMachine.Fact.IssueObserved(runRef(id), null, stamp = stamp()))
            check(plan.runId.isNotBlank()) { "Запуск не был разрешён" }
            if (plan.workspace == null) {
                attemptAuthority.requireRuntimePolicyReady()
                store.withJournaledIntent(id, PlanJournalOperation.PREPARE_INTENT) {
                    val workspace = workspaces.prepare(project, plan.runId, WorkspaceOperation(acquiredProjects.getValue(project), "prepare:${record.stream}:${record.seq}"))
                    plan = store.command(id, PlanningMachine.Fact.WorkspacePrepared(runRef(id), workspace, stamp()))
                    if(!workspace.git) store.command(id, PlanningMachine.Fact.JournalObserved(PlanJournalOperation.GIT_UNAVAILABLE,
                        detail = "Git недоступен: изменения выполняются без отдельных веток и коммитов. Репозиторий не создан.", stamp = stamp()))
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
            val executionLease = acquiredProjects.values.single()
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
                            (m.attempts.lastOrNull()?.error?.retryAt ?: 0) <= retryClock() &&
                            compiled.dependencies[m.id].orEmpty().all { dep -> plan.milestones.first { it.id == dep }.completed }
                    }
                    // Whole-project acceptance snapshots require exclusive writers in a shared folder.
                    val slots = (if (workspace.git && !plan.sharedWorkspace) minOf(plan.parallelism.coerceAtLeast(1), settings.load().agentLimits.activeSessions ?: Int.MAX_VALUE) else 1) - active.size
                    candidates.take(slots.coerceAtLeast(0)).forEach { stage ->
                        val key = id to stage.id
                        val job = launch(start = CoroutineStart.LAZY) {
                            try { executeStage(id, stage.id, project, workspace, integration, judge!!, executionLease) }
                            finally { stageJobs.update { it - key } }
                        }
                        stageJobs.update { it + (key to job) }
                        active[stage.id] = job
                        job.start()
                    }
                    if (active.isEmpty()) {
                        if (waitingStages.isNotEmpty() || plan.selectedMilestones.any { it.attempts.lastOrNull()?.waitingForEvent != null }) store.command(id, PlanningMachine.Fact.PhaseObserved(runRef(id), ExecutionPhase.WAITING, stamp()))
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
                if (!verifyIntegration(id, project, workspace, judge!!, executionLease)) return
                if (!acceptanceStillValid(id, workspace.integrationPath)) return
                if (store.planFor(id)!!.selectedMilestones.any { !it.completed }) {
                    store.planFor(id)?.finalAttempt?.let { final ->
                        store.command(id, PlanningMachine.Fact.FinalAttemptCleared(runRef(id), final, stamp()))
                    }
                    return
                }
                if (project !in acquiredProjects && !acquire(project)) {
                    block(id, PlanningIssue(IssueKind.TRANSIENT, "Ожидание освобождения папки проекта для применения результата",
                        retryAt = Id.now() + 1_000, requiresUser = false))
                    return
                }
                store.command(id, PlanningMachine.Fact.PhaseObserved(runRef(id), ExecutionPhase.APPLYING, stamp()))
                attemptAuthority.requireRuntimePolicyReady()
                store.withJournaledIntent(id, PlanJournalOperation.APPLY_INTENT) {
                    val task = taskWorktrees?.session(project.id, plan.parentSessionId)?.taskWorktree?.takeIf { initial.worktreeEnabled == true && it.taskId == plan.runId }
                    val delivering = task?.phase in setOf(TaskWorktreePhase.CAPTURING, TaskWorktreePhase.MERGING, TaskWorktreePhase.CONFLICT, TaskWorktreePhase.DELIVERING, TaskWorktreePhase.COMPLETE)
                    val applied = if (delivering) workspace.copy(applied = true) else applyResult(id, project, workspace, judge, acquiredProjects.getValue(project), "apply:${record.stream}:${record.seq}") ?: return
                    if (task != null) {
                        taskWorktrees!!.complete(checkNotNull(originalProject), plan.parentSessionId, plan.runId,
                            planAccepted = true, executionLease = acquiredProjects.getValue(project),
                            verifyMerged = { merged -> verifyTaskDelivery(id, merged, judge) },
                            repair = { conflict -> repairTaskDelivery(id, project, conflict, judge) })
                    }
                    if (!acceptanceStillValid(id, workspace.integrationPath)) return
                    store.command(id, PlanningMachine.Fact.Applied(runRef(id), applied, stamp()))
                    complete()
                }
            }
        } catch (e: CancellationException) { executionFailure = e; throw e }
        catch (e: Exception) {
            executionFailure = e
            try {
                val issue = if (e is NativeRunRecoveryRequired) PlanningRecoveryIssues.nativeUncertainty
                    else if (e is UnsafePlanningWorkspace) PlanningIssue(IssueKind.CONFIGURATION, e.message.orEmpty(), requiresUser = true, retryBlocked = true)
                    else classify(e.message ?: "Ошибка исполнения")
                val saved = store.planFor(id)
                val finalAttempt = saved?.finalAttempt
                if (finalAttempt != null) {
                    val failed = transitionFinal(id, finalAttempt, FinalAttemptMutation.Failed(issue, retryInputs(),
                        delivery = finalAttempt.phase == AttemptPhase.COMPLETE))
                    block(id, failed.error!!)
                } else if (issue.kind == IssueKind.TRANSIENT && saved != null) {
                    val decision = retryDecision(issue, saved.transportRetries)
                    val waiting = decision.applyTo(issue)
                    store.command(id, PlanningMachine.Fact.IssueObserved(runRef(id), waiting.copy(message = safeText(waiting.message)), waiting.retries, stamp()))
                } else block(id, issue)
            }
            catch (storage: Exception) { errorState.value = "Не удалось сохранить состояние: ${storage.message}" }
        } finally {
            val plan = store.plans.value.firstOrNull { it.id == id }
            val ids = plan?.milestones.orEmpty().flatMap { it.attempts }.map { it.id }.toSet() + listOfNotNull(plan?.finalAttempt?.id)
            liveState.update { it - ids }
            withContext(NonCancellable) {
                var releaseFailure: Throwable? = null
                fun retain(failure: Throwable) {
                    val primary = releaseFailure
                    if (primary == null) releaseFailure = failure else if (failure !== primary) primary.addSuppressed(failure)
                }
                if (plan?.stopping == true) try { confirmStop(id) } catch (failure: Throwable) { retain(failure) }
                acquiredProjects.keys.toList().asReversed().forEach { owner ->
                    try { release(owner) } catch (failure: Throwable) { retain(failure) }
                }
                releaseFailure?.let { cleanup ->
                    io.aequicor.magicpaper.logging.AppLog.error("planning.workspace", "lease.release_failed",
                        mapOf("planId" to id, "causeType" to cleanup::class.simpleName.orEmpty(), "result" to "lease_retained"))
                    errorState.value = "Освобождение рабочей папки не подтверждено. Требуется сверка состояния."
                    val primary = executionFailure
                    if (primary == null) throw cleanup
                    if (primary !== cleanup) primary.addSuppressed(cleanup)
                }
            }
        }
    }

    private fun runtimeSessionIds(plan: Plan): List<String> =
        (plan.milestones.flatMap { it.attempts } + listOfNotNull(plan.finalAttempt))
            .flatMap { listOf(it.sessionId, "${it.sessionId}-merge", "${it.sessionId}-delivery") }.distinct()

    /** Runtime termination and external-effect success are separate: this only confirms termination. */
    private suspend fun confirmStop(id: String) {
        val state = store.machineStates.value[id] ?: return
        val plan = state.plan?.takeIf { it.intent == ExecutionIntent.STOP && it.stopping } ?: return
        val stopId = state.stopId ?: return // Restored legacy STOP requires a fresh explicit request.
        try {
            runtimeSessionIds(plan).forEach { nativeRecovery.stopResources(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            io.aequicor.magicpaper.logging.AppLog.error("planning.execution", "stop.reconcile_failed", fields =
                mapOf("planId" to id, "stopId" to stopId, "causeType" to (failure::class.simpleName ?: "Exception")))
            store.dispatch(id, PlanningMachine.Fact.StopUnknown(stamp(), stopId))
            return
        }
        val result = store.dispatch(id, PlanningMachine.Fact.StopConfirmed(stamp(), stopId))
        if(result.rejection != null) return // A newer stop owns the next confirmation.
        val stopped = checkNotNull(result.state.plan)
        try { attemptAuthority.stoppedCheckpoint(stopped) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            errorState.value = "Остановка подтверждена; не удалось обновить состояние сессии. Повторите синхронизацию."
            io.aequicor.magicpaper.logging.AppLog.error("planning.execution", "stop.projection_failed", fields =
                mapOf("planId" to id, "stopId" to stopId, "causeType" to (failure::class.simpleName ?: "Exception")))
        }
    }

    private suspend fun verifyTaskDelivery(id: String, task: TaskWorktree, judge: LlmProfile) {
        val plan = store.planFor(id) ?: error("План удалён")
        check(canRun(id)) { "План остановлен" }
        val snapshot = workspaces.verificationSnapshot(task.path) ?: error("Проверка результата недоступна")
        val attempt = checkNotNull(plan.finalAttempt).copy(verificationSnapshot = snapshot)
        val (record, verdict) = reviewAcceptance(plan, Milestone("task-delivery", "Проверка слияния", description = plan.goal),
            attempt, task.path, plan.acceptanceCriteria(), attempt.report, judge)
        val saved = checkNotNull(store.command(id, PlanningMachine.Fact.MergeAcceptanceRecorded(runRef(id), PlanningMachine.AttemptRef.from(attempt), record, stamp())).finalAttempt)
        val valid = verdict.passed && verdict.issue == null && workspaces.verificationSnapshot(task.path) == snapshot
        if (saved.mergeProgress == MergeProgress.AwaitingVerdict)
            transitionFinal(id, saved, FinalAttemptMutation.DeliveryFinished(valid))
        check(valid) { "Проверка слияния не пройдена. Продолжите работу над планом" }
    }

    private suspend fun transitionFinal(id: String, expected: StageAttempt, mutation: FinalAttemptMutation): StageAttempt =
        checkNotNull(store.command(id, PlanningMachine.Fact.FinalTransitioned(runRef(id),
            PlanningMachine.AttemptRef.from(expected), mutation, stamp())).finalAttempt)

    private suspend fun repairTaskDelivery(id: String, project: CodingProject, task: TaskWorktree, judge: LlmProfile) {
        val plan = store.planFor(id) ?: error("План удалён")
        var attempt = checkNotNull(plan.finalAttempt)
        attempt = transitionFinal(id, attempt, FinalAttemptMutation.DeliveryStarted(task.path))
        val sessionId = "${attempt.sessionId}-delivery"
        var result = StageRunResult()
        attemptAuthority.requireRuntimePolicyReady()
        store.withJournaledIntent(id, PlanJournalOperation.DELIVERY_CONFLICT_INTENT, attemptId = attempt.id) {
        try {
            monitoredRun(record, PlanningMachine.AttemptRef.from(attempt), project.copy(path = task.path), CodingSession(sessionId, project.id, "Конфликт слияния", Id.now(),
                piSessionId = attempt.mergeEngineSessionId, engine = attempt.engine ?: plan.engine,
                planId = id, parentSessionId = plan.parentSessionId, planningRulesSnapshot = plan.planningRulesSnapshot,
                pendingRun = CodingRunCheckpoint("${attempt.id}-delivery", "")),
                "Разреши конфликт переноса задачи на ветку назначения в этой рабочей папке: сохрани обе стороны и добавь спорные файлы в индекс (git add). " +
                    "Приложение само продолжит перенос и проверки; не выполняй git rebase --continue, --skip или --abort. " +
                    "При неоднозначности задай вопрос через magicpaper_questionnaire. Не изменяй исходную папку. Цель: ${plan.goal}", judge).collect { event ->
                attempt = attempt.after(event, StageRunTrack.MERGE)
                result = result.after(event)
                if (event is CodingEvent.SessionStarted) attempt = transitionFinal(id, attempt,
                    FinalAttemptMutation.ProgressObserved(StageProgress.from(safeAttempt(attempt)), delivery = true))
            }
        } finally { withContext(NonCancellable) { nativeRecovery.prepare(id, sessionId) } }
        attempt = transitionFinal(id, attempt, FinalAttemptMutation.ProgressObserved(StageProgress.from(safeAttempt(attempt)), delivery = true))
        check(result.ended && result.failure == null) { "Конфликт требует продолжения" }
        transitionFinal(id, attempt, FinalAttemptMutation.DeliveryTurnEnded(null))
        complete()
        }
    }

    private suspend fun applyResult(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile, lease: WorkspaceLease, operationId: String): PlanWorkspace? {
        val workspaces = workspaceFor(id)
        while (canRun(id)) {
            try {
                val pending = store.planFor(id)!!.finalAttempt
                if (pending?.mergeProgress?.unresolved == true)
                    throw WorkspaceConflict(pending.mergePath, "Продолжение проверки переноса")
                return workspaces.apply(project, workspace, WorkspaceOperation(lease, "$operationId:apply:${pending?.mergeRetries ?: 0}"))
            }
            catch (conflict: WorkspaceConflict) {
                val plan = store.planFor(id)!!
                var attempt = plan.finalAttempt ?: error("Нет итоговой проверки")
                suspend fun advance(mutation: FinalAttemptMutation) { attempt = transitionFinal(id, attempt, mutation) }
                suspend fun persist() = advance(FinalAttemptMutation.ProgressObserved(StageProgress.from(safeAttempt(attempt)), delivery = true))
                if (attempt.mergeProgress.needsResolver) {
                    if (!canRetry(attempt.mergeRetries)) {
                        block(id, PlanningIssue(IssueKind.CONFLICT, "Не удалось разрешить конфликт переноса: ${conflict.workingPath}", requiresUser = true)); return null
                    }
                    if (attempt.mergeRetries > 0) delay(PlanningRetryPolicy.delayMillis(attempt.mergeRetries))
                    currentCoroutineContext().ensureActive()
                    advance(FinalAttemptMutation.DeliveryRequested(conflict.workingPath, settings.load().agentLimits.retries))
                }
                val sessionId = "${attempt.sessionId}-delivery"
                nativeRecovery.prepare(id, sessionId)
                (attempt.resumption as? StageResumption.UnknownOutcome)?.let { unknown ->
                    block(id, PlanningIssue(IssueKind.UNCERTAIN, "Неизвестен результат команды переноса: ${unknown.tool}", requiresUser = true)); return null
                }
                if (attempt.mergeProgress.needsTurn) {
                    advance(FinalAttemptMutation.DeliveryStarted(conflict.workingPath))
                    attemptAuthority.requireRuntimePolicyReady()
                    store.withJournaledIntent(id, PlanJournalOperation.DELIVERY_CONFLICT_INTENT, attemptId = attempt.id) {
                        var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                        try {
                            val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                            val activityRecorder = CodingRunRecorder()
                            monitoredRun(record, PlanningMachine.AttemptRef.from(attempt), project.copy(path = conflict.workingPath), CodingSession(sessionId, project.id, "Конфликт переноса", attempt.startedAt, attempt.mergeEngineSessionId, engine = attempt.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load())),
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
                            persist()
                            advance(FinalAttemptMutation.Failed(classify(result.failure ?: "Перенос прерван", uncertain = !result.ended), retryInputs(), delivery = true))
                            block(id, attempt.error!!); return null
                        }
                        persist()
                        advance(FinalAttemptMutation.DeliveryTurnEnded(workspaces.verificationSnapshot(conflict.workingPath)))
                        complete()
                    }
                }
                val (check, verdict) = reviewAcceptance(plan, Milestone("delivery", "Проверка переноса", description = plan.goal),
                    attempt.copy(verificationSnapshot = attempt.mergeVerificationSnapshot), conflict.workingPath, plan.acceptanceCriteria(), attempt.mergeReport, judge)
                advance(FinalAttemptMutation.DeliveryReviewed(check))
                if (verdict.issue != null) {
                    advance(FinalAttemptMutation.Failed(verdict.issue!!, retryInputs(), delivery = true))
                    block(id, attempt.error!!); return null
                }
                val valid = verdict.passed && workspaces.finishDeliveryConflict(conflict.workingPath, WorkspaceOperation(lease, "$operationId:conflict:${attempt.mergeRetries}"))
                advance(FinalAttemptMutation.DeliveryFinished(valid))
            }
        }
        return null
    }

    private suspend fun verifyIntegration(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile, lease: WorkspaceLease): Boolean {
        val workspaces = workspaceFor(id)
        var plan = store.planFor(id)!!
        val finalId = "${plan.runId}-final" + if (plan.finalAttemptHistory.isEmpty()) "" else "-${plan.finalAttemptHistory.size + 1}"
        var attempt = plan.finalAttempt ?: run {
            val selected = assignment(plan.selectedMilestones.first(), profiles.load())
            checkNotNull(store.command(id, PlanningMachine.Fact.FinalAttemptCreated(runRef(id), finalId, "$finalId-session",
                selected, workspace.integrationPath, plan.engine ?: legacyCodingEngine(selected.executionProfile(profiles.load())),
                Id.now(), stamp())).finalAttempt)
        }
        suspend fun advance(mutation: FinalAttemptMutation) { attempt = transitionFinal(id, attempt, mutation) }
        suspend fun persist() = advance(FinalAttemptMutation.ProgressObserved(StageProgress.from(safeAttempt(attempt))))
        if (attempt.engine == null) advance(FinalAttemptMutation.EngineResolved(plan.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load()))))
        if (attempt.phase == AttemptPhase.COMPLETE && attempt.acceptanceRecord != null) return acceptanceStillValid(id, workspace.integrationPath)
        if (attempt.phase == AttemptPhase.COMPLETE) advance(FinalAttemptMutation.LegacyReviewReopened)
        val pendingCriteria = plan.acceptanceCriteria().filter { criterion ->
            plan.acceptanceWaivers.none { it.runId == plan.runId && it.criterion == criterion }
        }
        if (pendingCriteria.isEmpty() && attempt.phase == AttemptPhase.PREPARED && attempt.pendingTool.isBlank() && !attempt.pendingToolExternal) {
            advance(FinalAttemptMutation.WaiversApplied)
        }
        if (attempt.phase != AttemptPhase.VERIFYING) {
            val snapshot = workspaces.verificationSnapshot(workspace.integrationPath)
            if (snapshot.isNullOrBlank()) {
                block(id, PlanningIssue(IssueKind.CONFIGURATION, "Снимок файлов недоступен; итоговая приёмка не проводилась", requiresUser = true))
                return false
            }
            advance(FinalAttemptMutation.VerificationPrepared(snapshot))
            nativeRecovery.prepare(id, attempt.sessionId)
            (attempt.resumption as? StageResumption.UnknownOutcome)?.let { unknown ->
                block(id, PlanningIssue(IssueKind.UNCERTAIN,
                    "Нет подтверждения результата команды итоговой проверки: ${unknown.tool}", requiresUser = true))
                return false
            }
            advance(FinalAttemptMutation.VerificationStarted)
            attemptAuthority.requireRuntimePolicyReady()
            store.withJournaledIntent(id, PlanJournalOperation.FINAL_VERIFICATION_INTENT, attemptId = attempt.id) {
                var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                val criteria = pendingCriteria.joinToString("\n") { it.description }
                try {
                    val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                    val activityRecorder = CodingRunRecorder()
                    monitoredRun(record, PlanningMachine.AttemptRef.from(attempt), project.copy(path = workspace.integrationPath),
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
                    persist()
                    advance(FinalAttemptMutation.Failed(classify(result.failure ?: "Итоговая проверка прервана", uncertain = !result.ended), retryInputs()))
                    block(id, attempt.error!!); return false
                }
                persist()
                advance(FinalAttemptMutation.VerificationTurnEnded)
                complete()
            }
        }
        workspaces.validateIntegration(workspace, WorkspaceOperation(lease, "validate:$id:${attempt.id}:${attempt.turnIndex}"))
        plan = store.planFor(id)!!
        val (acceptance, verdict) = reviewAcceptance(plan, Milestone("final", "Итоговая проверка", description = plan.goal),
            attempt, workspace.integrationPath, plan.acceptanceCriteria(), attempt.verificationToolEvidence() + attempt.report, judge)
        advance(FinalAttemptMutation.AcceptanceRecorded(acceptance))
        chatHooks?.verified(plan, Milestone("final", "Итоговая проверка"), attempt, acceptance, judge.modelId)
        if (!verdict.passed) {
            advance(FinalAttemptMutation.Failed(verdict.issue ?: PlanningIssue(IssueKind.VERIFICATION, verdict.note, requiresUser = true), retryInputs()))
            block(id, attempt.error!!); return false
        }
        advance(FinalAttemptMutation.Accepted)
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
        val saved = store.command(id, PlanningMachine.Fact.AcceptanceRechecked(runRef(id), record, snapshot, stamp()))
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

    private suspend fun executeStage(id: String, stageId: String, project: CodingProject, workspace: PlanWorkspace, integration: Mutex, judge: LlmProfile, lease: WorkspaceLease) {
        val workspaces = workspaceFor(id)
        var currentAttempt: StageAttempt? = null
        try {
            if (!canRunStage(id, stageId)) return
            var stage = store.planFor(id)!!.milestones.first { it.id == stageId }
            var attempt = stage.attempts.lastOrNull() ?: StageAttempt(Id.new(), if (store.planFor(id)!!.parentSessionId.isNotBlank()) "plan-$id-stage-$stageId" else Id.new(), assignment(stage, profiles.load()), startedAt = Id.now())
            if(stage.attempts.isEmpty()) {
                val created = store.command(id, PlanningMachine.Fact.StageCreated(runRef(id), stageId, attempt.id,
                    attempt.sessionId, attempt.assignment, attempt.startedAt, stamp()))
                attempt = created.milestones.single { it.id == stageId }.attempts.last()
            }
            val preparation = reduce(attempt.toState(), StageEvent.InspectPreparation)
            if (preparation.has(StageEffect.ResolveEngine)) {
                val saved = projects?.sessions(project.id)?.firstOrNull { it.id == attempt.sessionId }
                val engine = saved?.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load()))
                attempt = stageTransition(id, stageId, attempt, StageEvent.EngineResolved(engine)).state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
            }
            currentAttempt = attempt
            if (stage.attempts.isEmpty()) saveAttempt(id, stageId, attempt)
            if (preparation.has(StageEffect.PrepareWorkspace)) {
                attemptAuthority.requireRuntimePolicyReady()
                store.withJournaledIntent(id, PlanJournalOperation.STAGE_WORKSPACE_INTENT, stageId, attempt.id) {
                    val prepared = integration.withLock { workspaces.stage(project, workspace, attempt, WorkspaceOperation(lease, "stage:${record.stream}:${record.seq}")) }
                    attempt = stageTransition(id, stageId, attempt, StageEvent.WorkspacePrepared(prepared)).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    complete()
                }
            }
            currentAttempt = attempt
            workspaces.validateExecutionPath(project, attempt.path)
            workspaces.reconcile(attempt, WorkspaceOperation(lease, "reconcile:$id:${attempt.id}:${attempt.sessionGeneration}"))
            nativeRecovery.prepare(id, attempt.sessionId)
            nativeRecovery.prepare(id, "${attempt.sessionId}-merge")
            val pending = store.unsettled(id).any { record ->
                PlanJournalSubject.decode(record.detail).let { it.stage == stageId && it.attempt == attempt.id }
            }
            val resumption = stageTransition(id, stageId, attempt, StageEvent.Reconciled(pending))
            attempt = resumption.state.applyTo(attempt)
            if (resumption.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
            currentAttempt = attempt
            resumption.issue?.let { block(id, it) }
            if (resumption.has(StageEffect.Yield)) return
            while (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunWorker)) {
                if (!canRunStage(id, stageId)) return
                val plan = store.planFor(id)!!
                val recorded = plan.coordination.any { it.id == "${attempt.id}-turn-${attempt.turnIndex}" }
                val turn = stageTransition(id, stageId, attempt, StageEvent.TurnRequested(chatHooks != null, recorded))
                attempt = turn.state.applyTo(attempt)
                if (turn.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                turn.issue?.let { block(id, it) }
                if (turn.has(StageEffect.Yield)) return
                if (turn.has(StageEffect.Coordinate)) {
                    val decision = chatHooks!!.finished(plan, stage, attempt)
                    val resumed = stageTransition(id, stageId, attempt, StageEvent.PlannerDecided(decision, restored = true))
                    attempt = resumed.state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    if (resumed.has(StageEffect.AskUser) || resumed.has(StageEffect.WaitForEvent)) {
                        if (!hasQueuedReply(id, stageId)) return
                        attempt = stageTransition(id, stageId, attempt, StageEvent.UserAnswered).state.applyTo(attempt)
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
                attempt = stageTransition(id, stageId, attempt, StageEvent.WorkerStarting(prompt, Id.now())).state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
                attemptAuthority.requireRuntimePolicyReady()
                store.withJournaledIntent(id, PlanJournalOperation.AGENT_INTENT, stageId, attempt.id) {
                    beforeDispatch()
                    val admitted = attemptAuthority.prepareAttempt(store.planFor(id)!!, stageId, attempt)
                    attempt = stageTransition(id, stageId, attempt, StageEvent.WorkerAdmitted(admitted)).state.applyTo(attempt)
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
                    if (plan.parentSessionId.isBlank() && projects?.sessions(project.id)?.none { it.id == session.id } == true)
                        projects.dispatch(project.id, CodingMachine.Intent.CreateSession(session.copy(pendingRun = null)))
                    val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                    val activityRecorder = CodingRunRecorder()
                    var deliveryAcknowledged = false
                    stageWorkspaceNotice(workspace, attempt)?.let { notice ->
                        activityRecorder.apply(CodingEvent.Notice(notice))
                        attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                        saveAttempt(id, stageId, attempt)
                    }
                    if (!canRunStage(id, stageId)) return
                    // Preparing the session may suspend while settings are being applied.
                    // The local rejection receipt is still valid until the external port is invoked.
                    attemptAuthority.requireRuntimePolicyReady()
                    dispatching()
                    monitoredRun(record, PlanningMachine.AttemptRef.from(attempt), project.copy(path = attempt.path), session, prompt, frozen).collect { event ->
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
                    attempt = stageTransition(id, stageId, attempt, StageEvent.WorkerTurnEnded(Id.now(), submitted?.reply?.text)).state.applyTo(attempt)
                    currentAttempt = attempt
                    if (result.incomplete(attempt.report(StageRunTrack.WORK))) {
                        if (result.ended) reject()
                        // The turn is over either way: the attempt drops back to FAILED, from where
                        // it may run again, and the issue alone says whether anyone will start it.
                        val issue = classify(result.failure ?: "Поток завершился без подтверждённого результата", uncertain = !result.ended)
                        attempt = stageTransition(id, stageId, attempt, StageEvent.TransportFailed(issue, retryInputs(), workerFailed = true)).state.applyTo(attempt)
                        saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return
                    }
                    val accepted = stageTransition(id, stageId, attempt, StageEvent.WorkerAccepted(workspaces.verificationSnapshot(attempt.path), chatHooks != null))
                    attempt = accepted.state.applyTo(attempt)
                    if (accepted.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                    complete()
                }
                val decision = stageTransition(id, stageId, attempt, StageEvent.PlannerDecided(chatHooks?.finished(store.planFor(id)!!, stage, attempt)))
                attempt = decision.state.applyTo(attempt)
                saveAttempt(id, stageId, attempt)
                if (decision.has(StageEffect.AskUser) || decision.has(StageEffect.WaitForEvent)) {
                    if (!hasQueuedReply(id, stageId)) return
                    attempt = stageTransition(id, stageId, attempt, StageEvent.UserAnswered).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                }
            }
            if (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunVerifier)) {
                attempt = verifyStageAttempt(id, stage, attempt, judge, lease) ?: return
                currentAttempt = attempt
            }
            if (reduce(attempt.toState(), StageEvent.Inspect).has(StageEffect.RunMerge))
                mergeStage(id, stage, attempt, project, workspace, integration, judge, lease)

        } catch (e: CancellationException) {
            currentAttempt?.let { snapshot ->
                withContext(NonCancellable) {
                    runtime.abort(snapshot.sessionId); runtime.abort("${snapshot.sessionId}-merge")
                    // Keep streamed context and engine identity, without charging a retry.
                    val plan = store.planFor(id)
                    val saved = plan?.milestones?.firstOrNull { it.id == stageId }?.attempts?.lastOrNull()
                    if (saved != null) {
                        val waiting = stageId in chatHooks?.blockedStages(plan).orEmpty()
                        val interruption = stageTransition(id, stageId, saved, StageEvent.Interrupted(snapshot, waiting, Id.now()))
                        if (interruption.has(StageEffect.Persist)) saveAttempt(id, stageId, interruption.state.applyTo(saved))
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            val issue = if (e is NativeRunRecoveryRequired) PlanningRecoveryIssues.nativeUncertainty
                else if (e is UnsafePlanningWorkspace) PlanningIssue(IssueKind.CONFIGURATION, e.message.orEmpty(), requiresUser = true, retryBlocked = true)
                else classify(e.message ?: "Ошибка этапа")
            try {
                // Read the durable attempt: local snapshots can precede a phase transition.
                val saved = store.planFor(id)?.milestones?.firstOrNull { it.id == stageId }?.attempts?.lastOrNull()
                val failed = saved?.let { stageRetry(id, stageId, it, issue) }
                if (failed != null) saveAttempt(id, stageId, failed)
                block(id, failed?.error ?: issue.copy(requiresUser = true))
            } catch (storage: Exception) { errorState.value = storage.message; throw storage }
        } finally {
            currentAttempt?.let { a -> liveState.update { it - a.id } }
        }
    }
    private suspend fun mergeStage(id: String, stage: Milestone, original: StageAttempt, project: CodingProject,
        workspace: PlanWorkspace, integration: Mutex, judge: LlmProfile, lease: WorkspaceLease) {
        val stageId = stage.id
        val workspaces = workspaceFor(id)
        var attempt = original
        integration.withLock {
                val latest = store.planFor(id) ?: return@withLock
                if (latest.issue != null || latest.intent == ExecutionIntent.STOP || closing || store.failure.value != null) return@withLock
                attemptAuthority.requireRuntimePolicyReady()
                store.withJournaledIntent(id, PlanJournalOperation.MERGE_INTENT, stageId, attempt.id) {
                    val integrationResult = workspaces.integrate(workspace, attempt, WorkspaceOperation(lease, "integrate:${record.stream}:${record.seq}"))
                    var merged = stageTransition(id, stageId, attempt, StageEvent.MergeStarted(integrationResult)).has(StageEffect.Finish)
                    while (!merged && canRun(id)) {
                        val conflict = stageTransition(id, stageId, attempt, StageEvent.ConflictRequested(workspace.integrationPath, settings.load().agentLimits.retries))
                        if (conflict.has(StageEffect.Yield)) break
                        conflict.effects.filterIsInstance<StageEffect.Delay>().forEach { delay(it.millis) }
                        currentCoroutineContext().ensureActive()
                        attempt = conflict.state.applyTo(attempt)
                        if (conflict.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
                        if (conflict.has(StageEffect.RunConflictAgent)) {
                            attempt = stageTransition(id, stageId, attempt, StageEvent.ConflictStarted).state.applyTo(attempt)
                            saveAttempt(id, stageId, attempt)
                            attemptAuthority.requireRuntimePolicyReady()
                            store.withJournaledIntent(id, PlanJournalOperation.CONFLICT_AGENT_INTENT, stageId, attempt.id) {
                                val mergeSession = CodingSession("${attempt.sessionId}-merge", project.id, "Объединение: ${stage.title}", attempt.startedAt, attempt.mergeEngineSessionId, engine = attempt.engine ?: store.planFor(id)!!.engine ?: legacyCodingEngine(attempt.assignment.executionProfile(profiles.load())),
                                    planId = id, stageId = stageId, parentSessionId = latest.parentSessionId,
                                    planningRulesSnapshot = latest.planningRulesSnapshot,
                                    pendingRun = CodingRunCheckpoint("${attempt.id}-merge", ""))
                                var result = StageRunResult(); var lastSave = 0L; var lastDisplay = 0L
                                val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                                val activityRecorder = CodingRunRecorder()
                                monitoredRun(record, PlanningMachine.AttemptRef.from(attempt), project.copy(path = workspace.integrationPath), mergeSession,
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
                                    attempt = stageRetry(id, stageId, attempt, classify(result.failure ?: "Объединение прервано", uncertain = !result.ended))
                                    saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                                }
                                attempt = stageTransition(id, stageId, attempt, StageEvent.ConflictTurnEnded).state.applyTo(attempt); saveAttempt(id, stageId, attempt)
                                complete()
                            }
                        }
                        val verdict = verifier.verify(stage, store.planFor(id)!!.goal, attempt.mergeReport, judge)
                        if (verdict.issue != null) {
                            attempt = stageRetry(id, stageId, attempt, verdict.issue!!)
                            saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                        }
                        merged = verdict.passed && workspaces.finishConflict(workspace, attempt, WorkspaceOperation(lease, "conflict:${record.stream}:${record.seq}:${attempt.mergeRetries}"))
                        attempt = stageTransition(id, stageId, attempt, StageEvent.MergeFinished(merged)).state.applyTo(attempt)
                        saveAttempt(id, stageId, attempt)
                    }
                    if (!merged) {
                        reject()
                        val issue = PlanningIssue(IssueKind.CONFLICT, "Не удалось объединить ${stage.title}; рабочие копии сохранены", requiresUser = true)
                        attempt = stageRetry(id, stageId, attempt, issue); block(id, issue); return@withLock
                    }
                    attempt = stageTransition(id, stageId, attempt, StageEvent.Completed).state.applyTo(attempt)
                    saveAttempt(id, stageId, attempt)
                    journal(id, PlanJournalOperation.STAGE_COMPLETE, stageId, attempt.id)
                    complete()
                }
            }
    }

    private val shared = object : PlanningWorkspace by workspaces {
        override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation) = PlanWorkspace(project.path, project.path)
        override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = attempt.copy(path = project.path)
        override suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation) = ""
        override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = true
        override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = true
        override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation) = workspace.copy(applied = true)
        override suspend fun reconcile(attempt: StageAttempt, operation: WorkspaceOperation) = Unit
        override suspend fun validateIntegration(workspace: PlanWorkspace, operation: WorkspaceOperation) = Unit
        override suspend fun finishDeliveryConflict(path: String, operation: WorkspaceOperation) = false
    }
    private val direct = object : PlanningWorkspace by shared {
        override suspend fun validateExecutionPath(project: CodingProject, path: String, operation: WorkspaceOperation?) = Unit
    }
    private suspend fun workspaceFor(id: String): PlanningWorkspace {
        val plan = store.planFor(id)
        return when (plan?.worktreeEnabled) {
            true -> workspaces
            false -> direct
            null -> if (plan?.sharedWorkspace == true) shared else workspaces
        }
    }
    private suspend fun verifyStageAttempt(id: String, stage: Milestone, original: StageAttempt, judge: LlmProfile, lease: WorkspaceLease): StageAttempt? {
        val stageId = stage.id
        val workspaces = workspaceFor(id)
        var attempt = original
        val verificationPlan = store.planFor(id)!!
        val (acceptance, verdict) = reviewAcceptance(verificationPlan, stage, attempt, attempt.path,
            stage.criteria(), verificationPlan.stageVerificationReport(stageId, attempt), judge)
        attempt = stageTransition(id, stageId, attempt, StageEvent.AcceptanceRecorded(acceptance)).state.applyTo(attempt)
        saveAttempt(id, stageId, attempt)
        chatHooks?.verified(verificationPlan, stage, attempt, acceptance, judge.modelId)
        val verification = stageTransition(id, stageId, attempt, StageEvent.VerificationDecided(verdict, retryInputs()))
        if (verification.has(StageEffect.RecordVerification)) {
            store.command(id, PlanningMachine.Fact.VerificationObserved(runRef(id), stageId, PlanningMachine.AttemptRef.from(verification.state.attempt),
                verdict.passed, safeText(verdict.note), stamp()))
        }
        attempt = verification.state.applyTo(attempt)
        if (verification.has(StageEffect.Persist)) saveAttempt(id, stageId, attempt)
        verification.issue?.let { block(id, it) }
        if (verification.has(StageEffect.Yield)) return null
        attemptAuthority.requireRuntimePolicyReady()
        store.withJournaledIntent(id, PlanJournalOperation.CAPTURE_INTENT, stageId, attempt.id) {
            val commit = workspaces.capture(attempt.copy(report = stage.title + "\n" + attempt.report), WorkspaceOperation(lease, "capture:${record.stream}:${record.seq}"))
            attempt = stageTransition(id, stageId, attempt, StageEvent.Captured(commit)).state.applyTo(attempt)
            saveAttempt(id, stageId, attempt)
            complete()
        }
        return attempt
    }

    private suspend fun canRunStage(id: String, stageId: String): Boolean = canRun(id) &&
        store.planFor(id)?.let { stageId !in chatHooks?.blockedStages(it).orEmpty() } == true
    private suspend fun canRun(id: String) = !closing && store.failure.value == null && store.currentAdmission(id) == runRef(id) && store.planFor(id)?.intent == ExecutionIntent.RUN
    private suspend fun canRetry(count: Int): Boolean = PlanningRetryPolicy.canRetry(count, settings.load().agentLimits.retries)

    /** The clock and the randomness enter the retry policy here and nowhere else. */
    private suspend fun retryDecision(issue: PlanningIssue, completedRetries: Int): RetryDecision =
        PlanningRetryPolicy.decide(issue, completedRetries, settings.load().agentLimits.retries, retryClock(), Random.nextLong(500))

    private suspend fun retryInputs() = StageRetryInputs(settings.load().agentLimits.retries, retryClock(), Random.nextLong(500))

    /** Flush partial output while waiting. A coding turn may legitimately be silent during reasoning;
     * the chat-request timeout is not an inactivity deadline for the coding runtime.
     * Runtime failures and explicit cancellation remain authoritative. */
    private fun monitoredRun(intent: JournalRecord, expected: PlanningMachine.AttemptRef, project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> = channelFlow {
        val admitted = nativeRecovery.admit(intent, expected, session, profile)
        val stream = runtime.run(project, admitted.session, prompt, profile)
        val events = (admitted.binding()?.let { stream.flowOn(it) } ?: stream).produceIn(this)
        var primary: Throwable? = null
        var silentTicks = 0
        var finished = false
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
                        if (cause is NativeRunRecoveryRequired) throw cause
                        send(CodingEvent.Failed(cause.message?.takeIf { it.isNotBlank() }
                            ?: "Выполнение агента прервано"))
                    }
                    break
                }
                silentTicks = 0
                if (event is CodingEvent.Finished) finished = true else send(event)
                // Drain normal completion so runtime cleanup and independent experience checks finish.
                // Cancelling the producer at Finished would misclassify completed runs as cancellation.
            }
            nativeRecovery.verifyCompletion(admitted.request)
            if (finished) send(CodingEvent.Finished)
        } catch(failure: Throwable) { primary = failure; throw failure }
        finally { withContext(NonCancellable) {
            try {
                events.cancel()
                (events as? Job)?.join()
                nativeRecovery.observeConsumption(admitted.request)
            } catch(cleanup: Throwable) {
                val original = primary
                if(original == null) throw cleanup
                if(original !== cleanup) original.addSuppressed(cleanup)
            }
        } }
    }
    private suspend fun stageTransition(id: String, stageId: String, attempt: StageAttempt, mutation: StageMutation): StageTransition {
        val input = PlanningMachine.Fact.StageTransitioned(runRef(id), stageId, PlanningMachine.AttemptRef.from(attempt),
            mutation, StageProgress.from(safeAttempt(attempt)), stamp())
        val result = store.dispatch(id, input)
        result.rejection?.let { throw IllegalArgumentException(it.reason) }
        val transition = result.effects.filterIsInstance<PlanningMachine.Effect.StageDecision>().single().transition
        publishStageCheckpoint(checkNotNull(result.state.plan), stageId, transition.state.attempt)
        return transition
    }
    private suspend fun stageRetry(id: String, stageId: String, attempt: StageAttempt, issue: PlanningIssue): StageAttempt =
        stageTransition(id, stageId, attempt, StageEvent.TransportFailed(issue, retryInputs())).state.attempt

    /** Streaming changes carry telemetry only; all phase and authority changes use StageMutation. */
    private suspend fun saveAttempt(id: String, stageId: String, attempt: StageAttempt): Plan {
        val plan = checkNotNull(store.planFor(id))
        val current = plan.milestones.single { it.id == stageId }.attempts.last()
        val safe = safeAttempt(attempt)
        val progress = StageProgress.from(safe)
        require(progress.applyTo(current).copy(updatedAt = 0) == safe.copy(updatedAt = 0)) { "Изменение этапа требует смысловой команды" }
        if(StageProgress.from(current) == progress) return plan
        val saved = store.command(id, PlanningMachine.Fact.StageProgressObserved(runRef(id), stageId,
            PlanningMachine.AttemptRef.from(attempt), progress, stamp()))
        publishStageCheckpoint(saved, stageId, saved.milestones.single { it.id == stageId }.attempts.last())
        return saved
    }
    private suspend fun publishStageCheckpoint(plan: Plan, stageId: String, checkpoint: StageAttempt) {
        if(checkpoint.phase == AttemptPhase.COMPLETE && checkpoint.sessionGeneration > 0) projectAcceptedCheckpoint(plan, stageId, checkpoint)
        else attemptAuthority.attemptCheckpoint(plan, stageId, checkpoint)
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
            attemptAuthority.attemptCheckpoint(plan, stageId, attempt)
            store.command(plan.id, PlanningMachine.Fact.ProjectionConfirmed(marker, stamp()))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Plan's acceptance was already durable. Do not downgrade it because a separate
            // aggregate/message projection failed after that commit.
            errorState.value = "Результат сохранён; синхронизация сессии ожидает повторной попытки"
            try { store.command(plan.id, PlanningMachine.Fact.ProjectionFailed(marker, stageId, attempt.id, stamp())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (storage: Exception) { error.addSuppressed(storage) }
        }
    }
    /** A wizard answer can arrive between publishing the question and saving WAIT. */
    private suspend fun hasQueuedReply(id: String, stageId: String): Boolean = store.planFor(id)?.deliveries?.any {
        it.targetStageId == stageId && it.state == DeliveryState.QUEUED && it.replyTo != null
    } == true

    private suspend fun journal(id: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = "") =
        store.command(id, PlanningMachine.Fact.JournalObserved(operation, stageId, attemptId, stamp = stamp()))
    private suspend fun block(id: String, issue: PlanningIssue) = store.command(id,
        PlanningMachine.Fact.IssueObserved(runRef(id), issue.copy(message = safeText(issue.message)), stamp = stamp()))
    private fun stamp() = PlanningMachine.Stamp(Id.new(), Id.now())
    private suspend fun runRef(id: String): PlanningMachine.RunRef = currentCoroutineContext()[PlanningRunContext]?.ref
        ?.takeIf { it.planId == id } ?: store.currentAdmission(id) ?: error("Разрешение запуска отсутствует")
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

/** The admitted identity follows child coroutines; it is never re-read as a newer permission. */
internal class PlanningRunContext(val ref: PlanningMachine.RunRef) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PlanningRunContext>
}
