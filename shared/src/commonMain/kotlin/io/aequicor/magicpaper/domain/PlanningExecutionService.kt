package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    var chatHooks: PlanningExecutionHooks? = null
    private val jobs = mutableMapOf<String, Job>()
    private val jobsLock = Mutex()
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
            while (isActive && !closing) {
                try {
                    if (store.failure.value != null) {
                        jobsLock.withLock { jobs.values.toList() }.joinAll()
                        store.recover()
                    }
                    store.plans().filter { it.intent == ExecutionIntent.RUN && it.phase != ExecutionPhase.COMPLETE && it.issue?.requiresUser != true }
                        .forEach { plan ->
                            if ((plan.issue?.retryAt ?: 0) <= Id.now()) launchProject(plan.id)
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
        if (plan.phase == ExecutionPhase.COMPLETE) return
        if (plan.intent == ExecutionIntent.RUN) { launchProject(plan.id); return }
        val graph = DecisionCompiler.compile(plan)
        require(graph.valid && graph.stageIds.isNotEmpty()) { graph.errors.joinToString("\n").ifBlank { "Нет этапов" } }
        store.update(projectId) { it.copy(intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, wizardStep = PlanningStep.STATUS,
            runId = it.runId.ifBlank { Id.new() }, issue = null, status = PlanStatus.RUNNING) }
        chatHooks?.prepareSessions(store.planFor(plan.id)!!)
        launchProject(plan.id)
    }
    suspend fun pause(projectId: String) {
        store.update(projectId) { it.copy(intent = ExecutionIntent.PAUSE) }
    }
    suspend fun stop(projectId: String) {
        val plan = store.update(projectId) { it.copy(intent = ExecutionIntent.STOP, status = PlanStatus.STOPPED) }
        plan.milestones.flatMap { it.attempts }.filter { it.phase != AttemptPhase.COMPLETE }.forEach { runtime.abort(it.sessionId) }
        jobsLock.withLock { jobs[plan.id]?.cancel() }
    }
    suspend fun stopAndJoin(planId: String) {
        stop(planId)
        val job = jobsLock.withLock { jobs[planId] }
        job?.join()
    }
    suspend fun edit(projectId: String, revision: Long, change: (Plan) -> Plan) = store.update(projectId, revision) { old ->
        validateRevision(old, change(old))
    }
    /** Rebase an LLM proposal over telemetry, never over intervening user edits or started work. */
    suspend fun applyProposal(base: Plan, proposal: Plan) = store.update(base.id) { latest ->
        require(latest.tree == base.tree && latest.goal == base.goal && latest.priorities == base.priorities && latest.dialogue == base.dialogue) {
            "Дерево изменилось во время ответа оркестратора; повторите запрос"
        }
        fun spec(m: Milestone) = m.copy(status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", updatedAt = 0)
        require(latest.milestones.map(::spec) == base.milestones.map(::spec)) { "Этапы изменились во время ответа оркестратора" }
        val rebased = latest.copy(sharedWorkspace = if (latest.confirmedRevision == null) proposal.sharedWorkspace else latest.sharedWorkspace, tree = proposal.tree, dialogue = proposal.dialogue, wizardStep = proposal.wizardStep, milestones = proposal.milestones.map { proposed ->
            latest.milestones.firstOrNull { it.id == proposed.id }?.takeIf { it.attempts.isNotEmpty() || it.status != MilestoneStatus.PENDING }?.let { current ->
                require(spec(current) == spec(proposed)) { "Этап «${current.title}» начался во время планирования" }
                current
            } ?: proposed
        })
        validateRevision(latest, rebased)
    }
    private fun validateRevision(old: Plan, updated: Plan): Plan {
        DecisionCompiler.validateEdit(old, updated)
        val previous = old.finalAttempt ?: return updated
        if (updated.tree == old.tree && updated.milestones == old.milestones) return updated
        require(old.canExtendAfterFinalVerification) {
            "Итоговая проверка уже начата. Дождитесь её завершения перед изменением плана."
        }
        // Merely changing a label or an inactive branch cannot dismiss a failed check.
        if (updated.selectedMilestones.all { it.completed }) return updated
        return updated.copy(finalAttempt = null, finalAttemptHistory = old.finalAttemptHistory + previous,
            issue = null, phase = ExecutionPhase.RECOVERING,
            status = if (updated.intent == ExecutionIntent.RUN) PlanStatus.RUNNING else PlanStatus.STOPPED)
    }
    /** Explicit retry does not erase counters; the caller fixes configuration or acknowledges uncertainty. */
    suspend fun retry(projectId: String) {
        store.update(projectId) { p -> p.copy(issue = null, intent = ExecutionIntent.RUN, phase = ExecutionPhase.RECOVERING, status = PlanStatus.RUNNING,
            finalAttempt = p.finalAttempt?.retryAfterUserAction()?.let { if (p.issue?.kind == IssueKind.UNCERTAIN) it.copy(pendingToolExternal = false, pendingTool = "") else it },
            milestones = p.milestones.map { m -> m.copy(attempts = m.attempts.map { a ->
                val acknowledged = a.retryAfterUserAction()
                if (p.issue?.kind == IssueKind.UNCERTAIN) acknowledged.copy(pendingToolExternal = false, pendingTool = "") else acknowledged
            }) }) }
        launchProject(store.planFor(projectId)!!.id)
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
        if (closing || jobs[id]?.isActive == true) return@withLock
        jobs[id] = scope.launch { executeProject(id) }
    }
    private suspend fun executeProject(id: String) {
        val workspaces = workspaceFor(id)
        var acquiredProject: CodingProject? = null
        try {
            val initial = store.planFor(id) ?: return
            val project = projects?.all()?.firstOrNull { it.id == initial.projectId }
            if (project == null) { block(id, PlanningIssue(IssueKind.CONFIGURATION, "Папка проекта не найдена")); return }
            if (!workspaces.acquire(project)) {
                errorState.value = "Проект выполняется другим экземпляром приложения"
                return
            }
            acquiredProject = project
            var plan = store.planFor(id) ?: return
            if (plan.intent != ExecutionIntent.RUN) return
            val graph = DecisionCompiler.compile(plan)
            require(graph.valid) { graph.errors.joinToString("\n") }
            val roster = profiles.load()
            secrets.update { it + roster.map { p -> p.apiKey }.filter { it.isNotBlank() } }
            plan.selectedMilestones.filter { !it.completed }.map {
                require(it.acceptance.isNotBlank() || it.description.isNotBlank()) { "Задайте критерии этапа «${it.title}»" }
                (it.attempts.lastOrNull()?.assignment ?: assignment(it, roster)).executionProfile(roster)
            }.distinctBy { it.id }.forEach { runtime.preflight(it) }
            val judge = plan.plannerSelection?.let { ProfileResolver.selection(it, roster) } ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
            require(judge?.configured == true) { "Подключите модель для проверки результата" }
            if (plan.issue != null) plan = store.update(id) { it.copy(issue = null, phase = ExecutionPhase.RECOVERING) }
            if (plan.runId.isBlank()) plan = store.update(id) { it.copy(runId = Id.new()) }
            if (plan.workspace == null) {
                journal(id, "prepare-intent")
                val workspace = workspaces.prepare(project, plan.runId)
                plan = store.update(id) { it.copy(workspace = workspace, issue = null) }
            }
            chatHooks?.prepareSessions(plan)
            val workspace = plan.workspace!!
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
                    if (plan.intent != ExecutionIntent.RUN || plan.issue != null || store.failure.value != null) {
                        active.values.toList().joinAll(); break
                    }
                    val waitingStages = chatHooks?.blockedStages(plan).orEmpty()
                    val candidates = plan.milestones.filter { m ->
                        m.id in compiled.stageIds && !m.completed && m.id !in active && m.id !in waitingStages &&
                            m.attempts.lastOrNull()?.waitingForUser == null &&
                            m.attempts.lastOrNull()?.error?.requiresUser != true &&
                            (m.attempts.lastOrNull()?.error?.retryAt ?: 0) <= Id.now() &&
                            compiled.dependencies[m.id].orEmpty().all { dep -> plan.milestones.first { it.id == dep }.completed }
                    }
                    val slots = (if (workspace.git || plan.sharedWorkspace) plan.parallelism.coerceIn(1, 8) else 1) - active.size
                    candidates.take(slots.coerceAtLeast(0)).forEach { stage ->
                        active[stage.id] = launch { executeStage(id, stage.id, project, workspace, integration, judge!!) }
                    }
                    if (active.isEmpty()) {
                        if (waitingStages.isNotEmpty()) store.update(id) { it.copy(phase = ExecutionPhase.WAITING) }
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
                if (store.planFor(id)!!.selectedMilestones.any { !it.completed }) {
                    store.update(id) { it.copy(finalAttempt = null, finalAttemptHistory = it.finalAttemptHistory + listOfNotNull(it.finalAttempt), phase = ExecutionPhase.EXECUTING) }
                    return
                }
                store.update(id) { it.copy(phase = ExecutionPhase.APPLYING) }
                journal(id, "apply-intent")
                val applied = applyResult(id, project, workspace, judge) ?: return
                store.update(id) { it.copy(workspace = applied, phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE, issue = null) }
                journal(id, "apply-complete")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            try {
                val issue = classify(e.message ?: "Ошибка исполнения")
                val saved = store.planFor(id)
                val failed = saved?.finalAttempt?.let { withRetry(it, issue) }
                if (failed != null) {
                    store.update(id) { it.copy(finalAttempt = safeAttempt(failed)) }
                    block(id, failed.error!!)
                } else if (issue.kind == IssueKind.TRANSIENT && saved != null) {
                    val count = saved.transportRetries
                    val exhausted = count >= 3
                    val next = if (exhausted) count else count + 1
                    val waiting = issue.copy(retries = next, requiresUser = exhausted,
                        retryAt = if (exhausted) 0 else Id.now() + PlanningRetryPolicy.delayMillis(next,
                            PlanningRetryPolicy.fromMessage(issue.message), Id.now(), Random.nextLong(500)))
                    store.update(id) { it.copy(transportRetries = next,
                        issue = waiting.copy(message = safeText(waiting.message)), phase = ExecutionPhase.WAITING,
                        status = if (exhausted) PlanStatus.FAILED else PlanStatus.RUNNING) }
                } else block(id, issue)
            }
            catch (storage: Exception) { errorState.value = "Не удалось сохранить состояние: ${storage.message}" }
        } finally {
            val plan = store.plans.value.firstOrNull { it.id == id }
            val ids = plan?.milestones.orEmpty().flatMap { it.attempts }.map { it.id }.toSet() + listOfNotNull(plan?.finalAttempt?.id)
            liveState.update { it - ids }
            acquiredProject?.let { withContext(NonCancellable) { workspaces.release(it) } }
        }
    }

    private suspend fun applyResult(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile): PlanWorkspace? {
        val workspaces = workspaceFor(id)
        while (canRun(id)) {
            try {
                val pending = store.planFor(id)!!.finalAttempt
                if (pending?.mergePhase != null && pending.mergePhase != AttemptPhase.COMPLETE)
                    throw WorkspaceConflict(pending.mergePath, "Продолжение проверки переноса")
                return workspaces.apply(project, workspace)
            }
            catch (conflict: WorkspaceConflict) {
                val plan = store.planFor(id)!!
                var attempt = plan.finalAttempt ?: error("Нет итоговой проверки")
                suspend fun persist() { store.update(id) { it.copy(finalAttempt = safeAttempt(attempt.copy(updatedAt = Id.now())), phase = ExecutionPhase.INTEGRATING) } }
                if (attempt.mergePhase == null || attempt.mergePhase == AttemptPhase.FAILED) {
                    if (attempt.mergeRetries >= 2) {
                        block(id, PlanningIssue(IssueKind.CONFLICT, "Не удалось разрешить конфликт переноса: ${conflict.workingPath}", requiresUser = true)); return null
                    }
                    attempt = attempt.copy(mergeRetries = attempt.mergeRetries + 1, activity = "Разрешение конфликта переноса",
                        mergeAssignment = attempt.mergeAssignment ?: attempt.assignment, mergePhase = AttemptPhase.PREPARED, mergePath = conflict.workingPath)
                    persist()
                }
                val sessionId = "${attempt.sessionId}-delivery"
                runtime.reconcile(sessionId)
                if (attempt.pendingToolExternal && attempt.pendingTool.isNotBlank()) {
                    block(id, PlanningIssue(IssueKind.UNCERTAIN, "Неизвестен результат команды переноса: ${attempt.pendingTool}", requiresUser = true)); return null
                }
                if (attempt.mergePhase != AttemptPhase.VERIFYING) {
                    attempt = attempt.copy(mergePhase = AttemptPhase.EXECUTING); persist()
                    journal(id, "delivery-conflict-intent", attemptId = attempt.id)
                    var failure: String? = null; var ended = false; var lastSave = 0L; var lastDisplay = 0L
                    try {
                        val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                        val activityRecorder = CodingRunRecorder()
                        monitoredRun(project.copy(path = conflict.workingPath), CodingSession(sessionId, project.id, "Конфликт переноса", attempt.startedAt, attempt.mergeEngineSessionId),
                            "Разреши Git merge-конфликт, сохрани пользовательские изменения и результат плана. Цель: ${plan.goal}. Если изменения уже объединены, продолжи проверки. Добавь разрешённые файлы в индекс, выполни подходящие тесты и сообщи фактические результаты. Не изменяй исходную папку вне этой рабочей копии. Предыдущий отчёт: ${attempt.mergeReport}",
                            (attempt.mergeAssignment ?: attempt.assignment).executionProfile(profiles.load())).collect { event ->
                            activityRecorder.apply(event)
                            attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                            when (event) {
                                is CodingEvent.SessionStarted -> attempt = attempt.copy(mergeEngineSessionId = event.sessionId)
                                is CodingEvent.Failed -> failure = event.message
                                is CodingEvent.FinalText -> attempt = attempt.copy(mergeReport = event.text)
                                is CodingEvent.TextDelta -> attempt = attempt.copy(mergeReport = attempt.mergeReport + event.delta)
                                is CodingEvent.ToolStarted -> attempt = attempt.copy(pendingTool = event.summary, activity = event.summary,
                                    pendingToolExternal = event.isExec && !PlanningRetryPolicy.localCheck(event.summary))
                                is CodingEvent.ToolFinished -> attempt = attempt.copy(pendingTool = "", pendingToolExternal = false)
                                CodingEvent.Finished -> ended = true
                                else -> Unit
                            }
                            if (boundary(event) || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                            if (boundary(event) || Id.now() - lastSave >= 1000) { persist(); lastSave = Id.now() }
                        }
                    } catch (e: CancellationException) { runtime.abort(sessionId); throw e }
                    if (failure != null || !ended || attempt.mergeReport.isBlank()) {
                        attempt = withRetry(attempt, classify(failure ?: "Перенос прерван", uncertain = !ended))
                        persist(); block(id, attempt.error!!); return null
                    }
                    attempt = attempt.copy(mergePhase = AttemptPhase.VERIFYING); persist()
                }
                val verdict = verifier.verify(Milestone("delivery", "Проверка переноса", description = plan.goal), plan.goal, attempt.mergeReport, judge)
                if (verdict.issue != null) {
                    attempt = withRetry(attempt, verdict.issue); persist(); block(id, attempt.error!!); return null
                }
                val valid = verdict.passed && workspaces.finishDeliveryConflict(conflict.workingPath)
                attempt = attempt.copy(mergePhase = if (valid) AttemptPhase.COMPLETE else AttemptPhase.FAILED)
                persist()
            }
        }
        return null
    }

    private suspend fun verifyIntegration(id: String, project: CodingProject, workspace: PlanWorkspace, judge: LlmProfile): Boolean {
        val workspaces = workspaceFor(id)
        var plan = store.planFor(id)!!
        if (plan.workspace?.applied == true) return true
        val finalId = "${plan.runId}-final" + if (plan.finalAttemptHistory.isEmpty()) "" else "-${plan.finalAttemptHistory.size + 1}"
        var attempt = plan.finalAttempt ?: StageAttempt(finalId, "$finalId-session",
            assignment(plan.selectedMilestones.first(), profiles.load()), path = workspace.integrationPath, startedAt = Id.now())
        if (attempt.phase == AttemptPhase.COMPLETE) return true
        suspend fun persist() { store.update(id) { it.copy(finalAttempt = safeAttempt(attempt.copy(updatedAt = Id.now())), phase = ExecutionPhase.VERIFYING) } }
        persist()
        if (attempt.phase != AttemptPhase.VERIFYING) {
            runtime.reconcile(attempt.sessionId)
            if (attempt.pendingToolExternal && attempt.pendingTool.isNotBlank()) {
                block(id, PlanningIssue(IssueKind.UNCERTAIN,
                    "Нет подтверждения результата команды итоговой проверки: ${attempt.pendingTool}", requiresUser = true))
                return false
            }
            attempt = attempt.copy(phase = AttemptPhase.EXECUTING); persist()
            journal(id, "final-verification-intent", attemptId = attempt.id)
            var failure: String? = null; var ended = false; var lastSave = 0L; var lastDisplay = 0L
            val criteria = plan.selectedMilestones.joinToString("\n") { "${it.title}: ${it.acceptance.ifBlank { it.description }}" }
            try {
                val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                val activityRecorder = CodingRunRecorder()
                monitoredRun(project.copy(path = workspace.integrationPath),
                    CodingSession(attempt.sessionId, project.id, "Итоговая проверка", attempt.startedAt, attempt.engineSessionId),
                    "Проверь объединённый результат проекта. Цель: ${plan.goal}. Критерии:\n$criteria\nЗапусти подходящие тесты и проверки. Не изменяй исходный код. Отчитайся о командах и их фактических результатах. При продолжении сначала проверь предыдущие результаты: ${attempt.report}",
                    attempt.assignment.executionProfile(profiles.load())).collect { event ->
                    activityRecorder.apply(event)
                    attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                    when (event) {
                        is CodingEvent.SessionStarted -> attempt = attempt.copy(engineSessionId = event.sessionId)
                        is CodingEvent.FinalText -> attempt = attempt.copy(report = event.text)
                        is CodingEvent.TextDelta -> attempt = attempt.copy(report = attempt.report + event.delta)
                        is CodingEvent.ToolStarted -> attempt = attempt.copy(activity = event.summary, pendingTool = event.summary,
                            pendingToolExternal = event.isExec && !PlanningRetryPolicy.localCheck(event.summary))
                        is CodingEvent.ToolFinished -> attempt = attempt.copy(pendingTool = "", pendingToolExternal = false)
                        is CodingEvent.Failed -> failure = event.message
                        CodingEvent.Finished -> ended = true
                        else -> Unit
                    }
                    if (boundary(event) || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                    if (boundary(event) || Id.now() - lastSave >= 1000) { persist(); lastSave = Id.now() }
                }
            } catch (e: CancellationException) { runtime.abort(attempt.sessionId); throw e }
            if (failure != null || !ended || attempt.report.isBlank()) {
                attempt = withRetry(attempt, classify(failure ?: "Итоговая проверка прервана", uncertain = !ended))
                persist(); block(id, attempt.error!!); return false
            }
            attempt = attempt.copy(phase = AttemptPhase.VERIFYING); persist()
        }
        workspaces.validateIntegration(workspace)
        val verdict = verifier.verify(Milestone("final", "Итоговая проверка", description = plan.goal), plan.goal, attempt.report, judge)
        if (!verdict.passed) {
            attempt = withRetry(attempt, verdict.issue ?: PlanningIssue(IssueKind.VERIFICATION, verdict.note, requiresUser = true))
            persist(); block(id, attempt.error!!); return false
        }
        attempt = attempt.copy(phase = AttemptPhase.COMPLETE, error = null); persist()
        return true
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
            var stage = store.planFor(id)!!.milestones.first { it.id == stageId }
            var attempt = stage.attempts.lastOrNull() ?: StageAttempt(Id.new(), if (store.planFor(id)!!.parentSessionId.isNotBlank()) "plan-$id-stage-$stageId" else Id.new(), assignment(stage, profiles.load()), startedAt = Id.now())
            currentAttempt = attempt
            if (stage.attempts.isEmpty()) saveAttempt(id, stageId, attempt)
            if (attempt.path.isBlank()) {
                journal(id, "stage-workspace-intent", stageId, attempt.id)
                attempt = integration.withLock { workspaces.stage(project, workspace, attempt) }
                saveAttempt(id, stageId, attempt)
            }
            currentAttempt = attempt
            workspaces.reconcile(attempt)
            runtime.reconcile(attempt.sessionId)
            runtime.reconcile("${attempt.sessionId}-merge")
            if (attempt.pendingToolExternal && attempt.pendingTool.isNotBlank()) {
                block(id, PlanningIssue(IssueKind.UNCERTAIN, "Нет подтверждения результата команды: ${attempt.pendingTool}. Проверьте её последствия перед повтором.", requiresUser = true))
                return
            }
            while (attempt.phase in listOf(AttemptPhase.PREPARED, AttemptPhase.EXECUTING, AttemptPhase.FAILED)) {
                if (!canRun(id)) return
                val frozen = attempt.assignment.executionProfile(profiles.load())
                val plan = store.planFor(id)!!
                // A completed runtime turn may have been checkpointed before its coordinator finished.
                // Resume the durable decision, never re-run its file operations just to redeliver a reply.
                val recorded = plan.coordination.firstOrNull { it.id == "${attempt.id}-turn-${attempt.turnIndex}" }
                if (recorded != null && chatHooks != null) {
                    attempt = attempt.copy(awaitingPlanner = true)
                    saveAttempt(id, stageId, attempt)
                    val resumed = chatHooks!!.finished(plan, stage, attempt)
                    attempt = attempt.copy(report = resumed.report, turnIndex = attempt.turnIndex + 1,
                        awaitingPlanner = resumed.action == StageTurnAction.WAIT,
                        phase = if (resumed.action == StageTurnAction.VERIFY) AttemptPhase.VERIFYING else AttemptPhase.EXECUTING,
                        waitingForUser = if (resumed.action == StageTurnAction.WAIT) resumed.requestId ?: "legacy" else null, error = null)
                    saveAttempt(id, stageId, attempt)
                    if (resumed.action == StageTurnAction.WAIT) {
                        if (!hasQueuedReply(id, stageId)) return
                        attempt = attempt.copy(error = null, waitingForUser = null)
                        saveAttempt(id, stageId, attempt)
                    }
                    if (resumed.action == StageTurnAction.VERIFY) break
                    continue
                }
                val context = DecisionCompiler.compile(plan).dependencies[stageId].orEmpty().joinToString("\n") { dep ->
                    plan.milestones.first { it.id == dep }.let { "${it.title}: ${it.report}" }
                }
                val extraInstructions = chatHooks?.instructions(plan, stage, attempt).orEmpty()
                val prompt = """
                    Общая цель: ${plan.goal}
                    Этап: ${stage.title}
                    Задача: ${stage.description}
                    Критерии проверки: ${stage.acceptance.ifBlank { stage.description }}
                    Результаты предшественников: $context
                    Предыдущая работа и диагностика: ${attempt.report}\n${attempt.error?.message.orEmpty()}
                    Продолжай с фактического состояния файлов; сначала проверь уже выполненные изменения.
                    Работай только в этой рабочей папке. Не выполняй внешних публикаций.
                    Выполни проверки критериев и в конце укажи команды, результаты и изменённые файлы.
                """.trimIndent() + "\n" + extraInstructions
                attempt = attempt.copy(phase = AttemptPhase.EXECUTING, error = null, prompt = prompt, awaitingPlanner = false,
                    chatTurns = attempt.effectiveChatTurns() + StageChatTurn(attempt.steps.count { it.isVisibleActivity }, Id.now()))
                saveAttempt(id, stageId, attempt)
                journal(id, "agent-intent", stageId, attempt.id)
                var failure: String? = null
                var ended = false
                var lastSave = 0L
                var lastDisplay = 0L
                val session = CodingSession(attempt.sessionId, project.id, "План: ${stage.title}", attempt.startedAt, attempt.engineSessionId)
                if (plan.parentSessionId.isBlank()) projects?.saveSession(session)
                val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                val activityRecorder = CodingRunRecorder()
                var deliveryAcknowledged = false
                monitoredRun(project.copy(path = attempt.path), session, prompt, frozen).collect { event ->
                    if (!deliveryAcknowledged && (event is CodingEvent.SessionStarted || event is CodingEvent.MessageStarted ||
                            event is CodingEvent.TextDelta || event is CodingEvent.FinalText)) {
                        chatHooks?.started(store.planFor(id)!!, stage, attempt)
                        deliveryAcknowledged = true
                    }
                    activityRecorder.apply(event)
                    attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                    when (event) {
                        is CodingEvent.SessionStarted -> {
                            attempt = attempt.copy(engineSessionId = event.sessionId)
                            saveAttempt(id, stageId, attempt)
                        }
                        is CodingEvent.TextDelta -> attempt = attempt.copy(report = attempt.report + event.delta)
                        is CodingEvent.FinalText -> attempt = attempt.copy(report = event.text)
                        is CodingEvent.ToolStarted -> {
                            attempt = attempt.copy(activity = "${event.tool}: ${event.summary}", pendingTool = event.summary,
                                pendingToolExternal = event.isExec && !PlanningRetryPolicy.localCheck(event.summary))
                            saveAttempt(id, stageId, attempt)
                        }
                        is CodingEvent.ToolFinished -> {
                            attempt = attempt.copy(activity = "${event.tool}: ${event.resultPreview.take(1500)}", pendingTool = "", pendingToolExternal = false)
                            saveAttempt(id, stageId, attempt)
                        }
                        is CodingEvent.Notice -> if (event.message.isNotBlank()) attempt = attempt.copy(activity = event.message)
                        is CodingEvent.Failed -> failure = event.message
                        CodingEvent.Finished -> ended = true
                        else -> Unit
                    }
                    currentAttempt = attempt
                    if (boundary(event) || Id.now() - lastDisplay >= 100) {
                        val preview = safeAttempt(attempt.copy(updatedAt = Id.now()))
                        liveState.update { it + (attempt.id to preview) }; lastDisplay = Id.now()
                    }
                    if (Id.now() - lastSave >= 1000) { saveAttempt(id, stageId, attempt); lastSave = Id.now() }
                }
                attempt = attempt.copy(chatTurns = attempt.chatTurns.dropLast(1) + attempt.chatTurns.last().copy(completedAt = Id.now()))
                currentAttempt = attempt
                if (failure != null || !ended || attempt.report.isBlank()) {
                    val issue = classify(failure ?: "Поток завершился без подтверждённого результата", uncertain = !ended)
                    if (issue.kind == IssueKind.TRANSIENT && attempt.transportRetries < 3) {
                        val count = attempt.transportRetries + 1
                        val wait = PlanningRetryPolicy.delayMillis(count, PlanningRetryPolicy.fromMessage(failure.orEmpty()), Id.now(), Random.nextLong(500))
                        attempt = attempt.copy(phase = AttemptPhase.FAILED, transportRetries = count,
                            error = issue.copy(retryAt = Id.now() + wait, retries = count))
                        saveAttempt(id, stageId, attempt)
                        block(id, attempt.error!!)
                        return
                    }
                    attempt = attempt.copy(phase = AttemptPhase.FAILED, error = issue.copy(requiresUser = true))
                    saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return
                }
                if (chatHooks != null) {
                    attempt = attempt.copy(awaitingPlanner = true)
                    saveAttempt(id, stageId, attempt)
                }
                val decision = chatHooks?.finished(store.planFor(id)!!, stage, attempt)
                if (decision != null) {
                    attempt = attempt.copy(report = decision.report, turnIndex = attempt.turnIndex + 1,
                        awaitingPlanner = decision.action == StageTurnAction.WAIT)
                    if (decision.action != StageTurnAction.VERIFY) {
                        attempt = attempt.copy(phase = AttemptPhase.EXECUTING, error = null,
                            waitingForUser = if (decision.action == StageTurnAction.WAIT) decision.requestId ?: "legacy" else null)
                        saveAttempt(id, stageId, attempt)
                        if (decision.action == StageTurnAction.WAIT) {
                            if (!hasQueuedReply(id, stageId)) return
                            attempt = attempt.copy(error = null, waitingForUser = null)
                            saveAttempt(id, stageId, attempt)
                        }
                        continue
                    }
                }
                attempt = attempt.copy(phase = AttemptPhase.VERIFYING)
                saveAttempt(id, stageId, attempt)
            }
            if (attempt.phase == AttemptPhase.VERIFYING) {
                val verdict = verifier.verify(stage, store.planFor(id)!!.goal, attempt.report, judge)
                if (verdict.issue != null) {
                    val issue = if (verdict.issue.kind == IssueKind.TRANSIENT && attempt.transportRetries < 3) {
                        attempt = attempt.copy(transportRetries = attempt.transportRetries + 1)
                        verdict.issue.copy(retries = attempt.transportRetries, retryAt = Id.now() + PlanningRetryPolicy.delayMillis(attempt.transportRetries,
                            PlanningRetryPolicy.fromMessage(verdict.issue.message), Id.now(), Random.nextLong(500)))
                    } else verdict.issue.copy(requiresUser = verdict.issue.requiresUser || verdict.issue.kind == IssueKind.TRANSIENT)
                    saveAttempt(id, stageId, attempt.copy(error = issue))
                    block(id, issue)
                    return
                }
                if (!verdict.passed) {
                    if (attempt.repairRetries < 2) {
                        attempt = attempt.copy(phase = AttemptPhase.FAILED, repairRetries = attempt.repairRetries + 1,
                            error = PlanningIssue(IssueKind.VERIFICATION, verdict.note))
                        saveAttempt(id, stageId, attempt)
                        // The scheduler picks the same durable attempt up on its next pass.
                        return
                    }
                    attempt = attempt.copy(error = PlanningIssue(IssueKind.VERIFICATION, verdict.note, requiresUser = true))
                    saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return
                }
                store.update(id) { p -> p.copy(milestones = p.milestones.map { if (it.id == stageId) it.copy(checkNote = safeText(verdict.note)) else it }) }
                journal(id, "capture-intent", stageId, attempt.id)
                attempt = attempt.copy(resultCommit = workspaces.capture(attempt), phase = AttemptPhase.INTEGRATING)
                saveAttempt(id, stageId, attempt)
            }
            if (attempt.phase == AttemptPhase.INTEGRATING) integration.withLock {
                val latest = store.planFor(id) ?: return@withLock
                if (latest.issue != null || latest.intent == ExecutionIntent.STOP || closing || store.failure.value != null) return@withLock
                journal(id, "merge-intent", stageId, attempt.id)
                var merged = workspaces.integrate(workspace, attempt)
                // A resolver may have committed before the crash: ancestry alone is not verification.
                if (attempt.mergePhase != null && attempt.mergePhase != AttemptPhase.COMPLETE) merged = false
                while (!merged && canRun(id)) {
                    if (attempt.mergePhase == null || attempt.mergePhase == AttemptPhase.FAILED) {
                        if (attempt.mergeRetries >= 2) break
                        attempt = attempt.copy(mergeRetries = attempt.mergeRetries + 1, mergePhase = AttemptPhase.PREPARED,
                            mergeAssignment = attempt.mergeAssignment ?: attempt.assignment, mergePath = workspace.integrationPath,
                            activity = "Агент разрешает конфликт объединения")
                        saveAttempt(id, stageId, attempt)
                    }
                    if (attempt.mergePhase != AttemptPhase.VERIFYING) {
                        attempt = attempt.copy(mergePhase = AttemptPhase.EXECUTING)
                        saveAttempt(id, stageId, attempt)
                        journal(id, "conflict-agent-intent", stageId, attempt.id)
                        val mergeSession = CodingSession("${attempt.sessionId}-merge", project.id, "Объединение: ${stage.title}", attempt.startedAt, attempt.mergeEngineSessionId)
                        var failure: String? = null; var ended = false; var lastSave = 0L; var lastDisplay = 0L
                        val activityHistory = attempt.steps.filter { it.isVisibleActivity }
                        val activityRecorder = CodingRunRecorder()
                        monitoredRun(project.copy(path = workspace.integrationPath), mergeSession,
                            "Разреши текущий Git merge-конфликт, сохрани результаты обеих ветвей, добавь разрешённые файлы в индекс. Если объединение уже сделано, продолжи проверки. Критерии: ${stage.acceptance.ifBlank { stage.description }}. Предыдущий отчёт: ${attempt.mergeReport}. Отчитайся о фактических проверках.",
                            (attempt.mergeAssignment ?: attempt.assignment).executionProfile(profiles.load())).collect { event ->
                            activityRecorder.apply(event)
                            attempt = attempt.copy(steps = activityHistory + activityRecorder.timeline())
                            when (event) {
                                is CodingEvent.SessionStarted -> attempt = attempt.copy(mergeEngineSessionId = event.sessionId)
                                is CodingEvent.Failed -> failure = event.message
                                is CodingEvent.TextDelta -> attempt = attempt.copy(mergeReport = attempt.mergeReport + event.delta)
                                is CodingEvent.FinalText -> attempt = attempt.copy(mergeReport = event.text)
                                is CodingEvent.ToolStarted -> attempt = attempt.copy(activity = event.summary, pendingTool = event.summary,
                                    pendingToolExternal = event.isExec && !PlanningRetryPolicy.localCheck(event.summary))
                                is CodingEvent.ToolFinished -> attempt = attempt.copy(activity = event.resultPreview.take(1500), pendingTool = "", pendingToolExternal = false)
                                CodingEvent.Finished -> ended = true
                                else -> Unit
                            }
                            if (boundary(event) || Id.now() - lastDisplay >= 100) { publish(attempt); lastDisplay = Id.now() }
                            if (boundary(event) || Id.now() - lastSave >= 1000) { saveAttempt(id, stageId, attempt); lastSave = Id.now() }
                        }
                        if (failure != null || !ended || attempt.mergeReport.isBlank()) {
                            attempt = withRetry(attempt, classify(failure ?: "Объединение прервано", uncertain = !ended))
                            saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                        }
                        attempt = attempt.copy(mergePhase = AttemptPhase.VERIFYING); saveAttempt(id, stageId, attempt)
                    }
                    val verdict = verifier.verify(stage, store.planFor(id)!!.goal, attempt.mergeReport, judge)
                    if (verdict.issue != null) {
                        attempt = withRetry(attempt, verdict.issue)
                        saveAttempt(id, stageId, attempt); block(id, attempt.error!!); return@withLock
                    }
                    merged = verdict.passed && workspaces.finishConflict(workspace, attempt)
                    attempt = attempt.copy(mergePhase = if (merged) AttemptPhase.COMPLETE else AttemptPhase.FAILED)
                    saveAttempt(id, stageId, attempt)
                }
                if (!merged) {
                    val issue = PlanningIssue(IssueKind.CONFLICT, "Не удалось объединить ${stage.title}; рабочие копии сохранены", requiresUser = true)
                    saveAttempt(id, stageId, attempt.copy(error = issue)); block(id, issue); return@withLock
                }
                attempt = attempt.copy(phase = AttemptPhase.COMPLETE, error = null)
                saveAttempt(id, stageId, attempt)
                journal(id, "stage-complete", stageId, attempt.id)
            }
        } catch (e: CancellationException) {
            currentAttempt?.let { runtime.abort(it.sessionId); runtime.abort("${it.sessionId}-merge") }
            throw e
        } catch (e: Exception) {
            val issue = classify(e.message ?: "Ошибка этапа")
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
        override suspend fun acquire(project: CodingProject) = true
        override suspend fun release(project: CodingProject) = Unit
    }
    private suspend fun workspaceFor(id: String) = if (store.planFor(id)?.sharedWorkspace == true) shared else workspaces
    private suspend fun canRun(id: String) = !closing && store.failure.value == null && store.planFor(id)?.intent == ExecutionIntent.RUN
    private fun withRetry(attempt: StageAttempt, issue: PlanningIssue): StageAttempt {
        if (issue.kind != IssueKind.TRANSIENT) return attempt.copy(error = issue)
        if (attempt.transportRetries >= 3) return attempt.copy(error = issue.copy(retries = attempt.transportRetries, requiresUser = true))
        val count = attempt.transportRetries + 1
        val wait = PlanningRetryPolicy.delayMillis(count, PlanningRetryPolicy.fromMessage(issue.message), Id.now(), Random.nextLong(500))
        return attempt.copy(transportRetries = count, error = issue.copy(retries = count, retryAt = Id.now() + wait))
    }
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
                val event = received.getOrNull() ?: break
                silentTicks = 0
                send(event)
                if (event == CodingEvent.Finished) break
            }
        } finally { events.cancel() }
    }
    private suspend fun saveAttempt(id: String, stageId: String, attempt: StageAttempt) = store.update(id) { p ->
        p.copy(phase = ExecutionPhase.EXECUTING, milestones = p.milestones.map { m ->
            if (m.id != stageId) m else m.copy(
                status = when { attempt.phase == AttemptPhase.COMPLETE -> MilestoneStatus.DONE; attempt.error?.requiresUser == true -> MilestoneStatus.FAILED; else -> MilestoneStatus.ACTIVE },
                report = safeText(attempt.report),
                attempts = m.attempts.filterNot { it.id == attempt.id } + safeAttempt(attempt.copy(updatedAt = Id.now())),
            )
        })
    }
    /** A wizard answer can arrive between publishing the question and saving WAIT. */
    private suspend fun hasQueuedReply(id: String, stageId: String): Boolean = store.planFor(id)?.deliveries?.any {
        it.targetStageId == stageId && it.state == DeliveryState.QUEUED && it.replyTo != null
    } == true

    private suspend fun journal(id: String, operation: String, stageId: String = "", attemptId: String = "") = store.update(id) {
        it.copy(journal = it.journal + PlanJournalEntry(Id.new(), Id.now(), stageId, attemptId, operation))
    }
    private suspend fun block(id: String, issue: PlanningIssue) = store.update(id) {
        it.copy(issue = issue.copy(message = safeText(issue.message)), phase = ExecutionPhase.WAITING, status = if (issue.requiresUser) PlanStatus.FAILED else PlanStatus.RUNNING)
    }
    private fun safeText(text: String) = PlanningDiagnostics.redact(text, secrets.value)
    private fun boundary(event: CodingEvent) = event !is CodingEvent.TextDelta && !(event is CodingEvent.Notice && event.message.isBlank())
    private fun publish(attempt: StageAttempt) {
        val safe = safeAttempt(attempt)
        liveState.update { state ->
            val old = state[attempt.id]
            if (old?.report == safe.report && old.activity == safe.activity && old.mergeReport == safe.mergeReport && old.steps == safe.steps) state
            else state + (attempt.id to safe.copy(updatedAt = Id.now()))
        }
    }
    private fun safeAttempt(a: StageAttempt) = a.copy(prompt = safeText(a.prompt), report = safeText(a.report), activity = safeText(a.activity),
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
        return PlanningIssue(kind, message.take(2000), requiresUser = kind !in listOf(IssueKind.CONFIGURATION, IssueKind.TRANSIENT))
    }
}
