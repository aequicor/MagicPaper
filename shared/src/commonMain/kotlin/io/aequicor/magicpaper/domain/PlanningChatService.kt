package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Application-owned conversation, durable requests and worker inboxes. UI never owns a run. */
class PlanningChatService(
    val store: PlanningStore, val execution: PlanningExecutionService,
    private val projects: CodingProjectRepository, private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository, private val composer: PlanComposer, private val gateway: LlmGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : PlanningExecutionHooks {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val messageLock = Mutex()
    private val deletedPlans = mutableSetOf<String>()
    private val clearingProjects = mutableSetOf<String>()
    private val requestLocks = mutableMapOf<String, Mutex>()
    private val confirmation = Mutex()
    private val refinementGuard = Mutex()
    private val refinementLocks = mutableMapOf<String, Mutex>()
    private val jobs = mutableMapOf<String, Job>()
    private val _drafts = MutableStateFlow<Map<String, CodingDraft>>(emptyMap())
    private data class CoordinatorActivity(val planId: String, val sessionId: String, val steps: List<CodingStep>)
    private val coordinatorActivity = MutableStateFlow<Map<String, CoordinatorActivity>>(emptyMap())
    val drafts: StateFlow<Map<String, CodingDraft>> = combine(_drafts, coordinatorActivity) { requests, coordinators ->
        val combined = requests.toMutableMap()
        coordinators.entries.groupBy { it.value.sessionId }.forEach { (sessionId, turns) ->
            val request = requests[sessionId] ?: CodingDraft()
            combined[sessionId] = request.copy(active = true, steps = request.steps + turns.flatMap { (id, activity) ->
                activity.steps.map { it.copy(callId = "$id:${it.callId}") }
            })
        }
        combined
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private fun changed() { _changes.update { it + 1 } }
    private fun launch(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { _error.value = e.message; changed() }
    }
    suspend fun deleteProjectSessions(projectId: String) {
        if (!clearingProjects.add(projectId)) return
        try {
            val plans = store.plans().filter { it.projectId == projectId }
            deletedPlans.addAll(plans.map { it.id })
            val sessionIds = projects.sessions(projectId).map { it.id }.toSet()
            sessionIds.forEach { jobs.remove(it)?.cancelAndJoin() }
            plans.forEach { execution.stopAndJoin(it.id) }
            plans.forEach { store.deletePlan(it.id) }
            messageLock.withLock {
                projects.sessions(projectId).forEach { projects.deleteSession(projectId, it.id) }
            }
            _drafts.update { it - sessionIds }
            changed()
        } finally { clearingProjects.remove(projectId) }
    }

    fun shutdown() { scope.cancel() }
    fun cancelRequest(sessionId: String) {
        jobs[sessionId]?.cancel()
        val coordinatingPlans = coordinatorActivity.value.values.filter { it.sessionId == sessionId }.map { it.planId }.distinct()
        launch {
            coordinatingPlans.forEach { execution.stop(it) }
            val plan = store.plans().firstOrNull { it.parentSessionId == sessionId && it.pendingRequest.isNotBlank() } ?: return@launch
            append(plan.projectId, sessionId, CodingMessage("${plan.requestId}-cancelled", CodingRole.AGENT, "Запрос планирования остановлен. Сохранённый план не изменён.", createdAt = Id.now()))
            store.update(plan.id) { it.copy(pendingRequest = "", requestId = "") }
            _drafts.update { it - sessionId }
        }
    }
    fun bootstrap() {
        execution.chatHooks = this
        launch {
            migrate()
            store.plans.collect { plans ->
                plans.filter { it.parentSessionId.isNotBlank() }.forEach { projectPlan -> publish(projectPlan) }
                changed()
            }
        }
        launch { execution.live.collect { changed() } }
        launch { drafts.collect { changed() } }
    }
    suspend fun append(projectId: String, sessionId: String, message: CodingMessage) = messageLock.withLock {
        if (projectId in clearingProjects || projects.sessions(projectId).none { it.id == sessionId }) return@withLock
        val history = projects.messages(projectId, sessionId)
        val index = history.indexOfFirst { it.id == message.id }
        val next = if (index < 0) history + message else history.map { if (it.id == message.id) message.copy(createdAt = it.createdAt) else it }
        if (next != history) { projects.saveMessages(projectId, sessionId, next); changed() }
    }
    suspend fun configure(session: CodingSession, planning: Boolean = session.planningMode, search: SearchProvider = session.searchProvider) {
        val latest = projects.sessions(session.projectId).firstOrNull { it.id == session.id } ?: session
        require(!latest.planningMode || planning) { "Режим планирования закреплён за сессией" }
        require(latest.stageId == null || planning == latest.planningMode) { "Режим сессии исполнителя менять нельзя" }
        projects.saveSession(latest.copy(planningMode = planning, searchProvider = search)); changed()
    }
    fun send(session: CodingSession, text: String, answers: List<PlanningAnswer> = emptyList(), replyTo: String? = null) {
        if (session.projectId in clearingProjects || session.planId in deletedPlans) return
        if (session.stageId != null && session.planId != null) {
            launch { queueWorker(session, text) }; return
        }
        if (jobs[session.id]?.isActive == true) return
        jobs[session.id] = launch {
            val lock = requestLocks.getOrPut(session.id) { Mutex() }
            lock.withLock {
                var plan = store.plans().firstOrNull { it.parentSessionId == session.id && it.phase != ExecutionPhase.COMPLETE }
                if (plan == null) {
                    val history = projects.messages(session.projectId, session.id)
                    val id = Id.new()
                    plan = Plan(id, session.projectId, text.trim(), parentSessionId = session.id, sessionId = session.id,
                        sharedWorkspace = true, plannerSelection = session.modelSelection,
                        searchProvider = session.searchProvider, createdAt = Id.now(), updatedAt = Id.now(),
                        tree = listOf(DecisionNode("$id-root", text.trim(), DecisionKind.GOAL)),
                        dialogue = history.map { PlanningMessage(it.id, if (it.role == CodingRole.USER) "user" else "assistant", it.text) })
                    store.save(plan)
                }
                val current = plan!!
                if (replyTo != null && projects.messages(session.projectId, session.id).any { it.planning?.replyTo == replyTo }) return@withLock
                val history = projects.messages(session.projectId, session.id)
                val question = if (replyTo == null) history.pendingPlanningQuestion(setOf(current.id)) else history.firstOrNull { it.id == replyTo }
                val effectiveReplyTo = replyTo ?: question?.id
                validateAnswers(current, effectiveReplyTo, answers, history)
                val requestId = Id.new()
                append(current.projectId, session.id, CodingMessage(requestId, CodingRole.USER, text, createdAt = Id.now(),
                    planning = PlanningChatBlock(current.id, answers = answers, replyTo = effectiveReplyTo)))
                val sourceStageId = question?.planning?.sourceStageId
                if (current.confirmedRevision != null && (sourceStageId != null || current.milestones.any { it.attempts.lastOrNull()?.error?.requiresUser == true })) {
                    val waiting = current.milestones.filter {
                        if (sourceStageId != null) it.id == sourceStageId else it.attempts.lastOrNull()?.error?.requiresUser == true
                    }
                    waiting.forEach { enqueue(current.id, session.id, it.id, text, "$requestId-answer-${it.id}", replyTo = effectiveReplyTo) }
                    store.update(current.id) { it.copy(issue = null, intent = ExecutionIntent.RUN) }
                    execution.start(current.id)
                } else refine(current.id, requestId, text)
            }
        }
    }
    private fun validateAnswers(plan: Plan, replyTo: String?, answers: List<PlanningAnswer>, history: List<CodingMessage>) {
        if (answers.isEmpty()) return
        val questions = history.firstOrNull { it.id == replyTo && it.planning?.planId == plan.id }?.planning?.questions
            ?: plan.dialogue.firstOrNull { it.id == replyTo }?.questions ?: error("Вопросы не найдены")
        require(answers.map { it.questionId }.distinct().size == answers.size && answers.size == questions.size) { "Ответьте на все вопросы" }
        questions.forEach { q ->
            val a = answers.single { it.questionId == q.id }
            require(a.selected.all { selected -> q.options.any { it.id == selected } }) { "Неизвестный вариант" }
            require(q.kind != QuestionKind.SINGLE || a.selected.distinct().size <= 1) { "Выберите один вариант" }
            require(a.selected.isNotEmpty() || a.text.isNotBlank()) { "Добавьте ответ" }
        }
    }
    private suspend fun refine(id: String, requestId: String, text: String) {
        val lock = refinementGuard.withLock { refinementLocks.getOrPut(id) { Mutex() } }
        lock.withLock { refineLocked(id, requestId, text) }
    }
    private suspend fun refineLocked(id: String, requestId: String, text: String) {
        val pending = store.update(id) { it.copy(pendingRequest = text, requestId = requestId,
            dialogue = if (it.dialogue.any { m -> m.id == requestId }) it.dialogue else it.dialogue + PlanningMessage(requestId, "user", text)) }
        val sessionId = pending.parentSessionId
        val activity = MutableStateFlow<List<CodingStep>>(emptyList())
        fun event(rawStep: CodingStep) {
            val step = rawStep.planningPreview()
            activity.update { history ->
                val index = history.indexOfLast { it.kind == step.kind && it.callId == step.callId &&
                    (step.callId.isNotBlank() || step.kind in listOf(CodingStepKind.ANSWER, CodingStepKind.THINKING)) }
                if (index >= 0) history.mapIndexed { i, old -> if (i == index) step else old } else history + step
            }
            _drafts.update { it + (sessionId to CodingDraft(steps = activity.value, active = true)) }
        }
        try {
            val roster = profiles.load()
            val session = projects.sessions(pending.projectId).first { it.id == sessionId }
            val choice = session.modelSelection ?: pending.plannerSelection
            val profile = if (choice != null) ProfileResolver.selection(choice, roster) else ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
            val effective = pending.copy(plannerSelection = choice, searchProvider = session.searchProvider)
            val firstRequest = projects.messages(pending.projectId, sessionId).count { it.role == CodingRole.USER && it.planning?.planId == pending.id } == 1 && pending.milestones.isEmpty()
            val request = if (firstRequest) "$text\nСначала задай уточняющие вопросы с вариантами в questions и дождись ответов. Пока не строй дерево этапов." else text
            val result = composer.refine(effective, request, profile, roster, store.dossiers.value, settings.load(), ::event) { event(CodingStep(CodingStepKind.INFO, it)) }
            val assistant = result.dialogue.last().copy(id = "$requestId-reply", activity = activity.value.filter { it.kind != CodingStepKind.ANSWER }.map { it.copy(running = false) })
            val snapshot = PlanVersion(pending.revision, pending.tree, pending.milestones.map { it.copy(attempts = emptyList()) }, Id.now())
            execution.applyProposal(pending, result.copy(dialogue = pending.dialogue + assistant))
            store.update(id) { it.copy(pendingRequest = "", requestId = "", plannerSelection = choice, searchProvider = session.searchProvider,
                versions = if (result.tree != pending.tree || result.milestones != pending.milestones) it.versions + snapshot else it.versions) }
            val saved = store.planFor(id)!!
            if (saved.confirmedRevision != null) {
                prepareSessions(saved)
                if (pending.canExtendAfterFinalVerification && saved.finalAttempt == null && saved.intent == ExecutionIntent.RUN)
                    execution.start(id)
            }
        } catch (e: Exception) {
            // A model/request timeout cancels its child coroutine, not this planning turn.
            // Preserve actual cancellation, but publish timeouts like other request failures.
            currentCoroutineContext().ensureActive()
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val message = if (e is TimeoutCancellationException)
                "Модель не успела завершить планирование за отведённое время. Отправьте сообщение ещё раз или увеличьте время ожидания в настройках модели."
            else e.message?.takeIf { it.isNotBlank() } ?: "Ошибка планирования"
            append(pending.projectId, sessionId, CodingMessage("$requestId-error", CodingRole.AGENT, message, failed = true, createdAt = Id.now(), steps = activity.value + CodingStep(CodingStepKind.ERROR, message)))
            store.update(id) { it.copy(pendingRequest = "", requestId = "") }
        } finally { _drafts.update { it - sessionId }; changed() }
    }
    fun chooseOption(id: String, revision: Long, choiceId: String, optionId: String) = launch {
        if (id in deletedPlans) return@launch
        execution.edit(id, revision) { selectPlanningOption(it, choiceId, optionId) }
        changed()
    }

    fun confirm(id: String) = launch { confirmation.withLock {
        val plan = store.planFor(id) ?: return@withLock
        if (plan.confirmedRevision != null) { prepareSessions(plan); execution.start(id); return@withLock }
        require(plan.wizardStep != PlanningStep.CLARIFY && plan.selectedMilestones.isNotEmpty() && DecisionCompiler.compile(plan).valid) { "План ещё не готов" }
        val saved = store.update(id) { it.copy(confirmedRevision = it.revision,
            versions = it.versions + PlanVersion(it.revision, it.tree, it.milestones.map { m -> m.copy(attempts = emptyList()) }, Id.now())) }
        prepareSessions(saved)
        append(saved.projectId, saved.parentSessionId, CodingMessage("${saved.id}-confirmed", CodingRole.AGENT,
            "План подтверждён. Планировщик распределяет ${saved.selectedMilestones.size} этапов между исполнителями; зависимые этапы ждут завершения предыдущих.", createdAt = Id.now()))
        execution.start(id)
    } }
    fun control(id: String, command: String) = launch {
        if (id in deletedPlans) return@launch
        val before = store.planFor(id) ?: return@launch
        if (command == "retry" && before.canExtendAfterFinalVerification && before.parentSessionId.isNotBlank()) {
            val parent = projects.sessions(before.projectId).firstOrNull { it.id == before.parentSessionId }
                ?: error("Сессия планировщика не найдена")
            send(parent, "Доработай план после неудачной итоговой проверки. Причина: ${before.issue?.message}. " +
                "Учти последние запросы и ответы пользователя в этом диалоге, включая ещё не применённые изменения плана. " +
                "Добавь этапы исправления и проверки в рамках текущей цели, сохрани завершённые этапы. " +
                "Если данных достаточно, продолжи выполнение; иначе задай необходимые вопросы.")
            return@launch
        }
        when (command) { "pause" -> execution.pause(id); "stop" -> execution.stop(id); "retry" -> execution.retry(id); else -> execution.start(id) }
        val plan = store.planFor(id) ?: return@launch
        val text = when (command) {
            "pause" -> "Планировщик приостановил выдачу новых заданий. Текущие ходы завершатся."
            "stop" -> "Планировщик остановил выполнение этапов."
            "retry" -> if (before.finalAttempt != null && before.selectedMilestones.all { it.completed })
                "Планировщик повторяет итоговую проверку." else "Планировщик повторно запускает незавершённые этапы."
            else -> "Планировщик возобновил распределение заданий."
        }
        append(plan.projectId, plan.parentSessionId, CodingMessage(Id.new(), CodingRole.AGENT, text, createdAt = Id.now()))
    }
    override suspend fun prepareSessions(plan: Plan) {
        if (plan.id in deletedPlans || plan.projectId in clearingProjects) return
        if (plan.parentSessionId.isBlank() || plan.confirmedRevision == null) return
        val late = plan.deliveries.firstOrNull { d -> d.state == DeliveryState.QUEUED && plan.milestones.any { it.id == d.targetStageId && it.completed } }
        if (late != null) {
            val updated = store.update(plan.id) { current ->
                val delivery = current.deliveries.firstOrNull { it.id == late.id && it.state == DeliveryState.QUEUED } ?: return@update current
                val completed = current.milestones.firstOrNull { it.id == delivery.targetStageId && it.completed } ?: return@update current
                val nextId = "${completed.id}-followup-${delivery.id}"
                val next = completed.copy(id = nextId, title = "Продолжение: ${completed.title}", description = delivery.text,
                    status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", dependsOn = listOf(completed.id))
                current.copy(deliveries = current.deliveries.map { if (it.id == delivery.id) it.copy(targetStageId = nextId) else it },
                    milestones = current.milestones + next, phase = ExecutionPhase.EXECUTING, status = PlanStatus.RUNNING,
                    finalAttempt = null, finalAttemptHistory = current.finalAttemptHistory + listOfNotNull(current.finalAttempt),
                    workspace = current.workspace?.copy(applied = false),
                    tree = current.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + nextId) else it } + DecisionNode(nextId, next.title, DecisionKind.STAGE, stageId = nextId))
            }
            if (updated.deliveries != plan.deliveries) { prepareSessions(updated); return }
        }
        val existing = projects.sessions(plan.projectId)
        plan.selectedMilestones.forEach { stage ->
            val id = stage.attempts.firstOrNull()?.sessionId ?: "plan-${plan.id}-stage-${stage.id}"
            if (existing.none { it.id == id }) projects.saveSession(CodingSession(id, plan.projectId, "${plan.goal.take(32)} · ${stage.title}", Id.now(),
                planId = plan.id, parentSessionId = plan.parentSessionId, stageId = stage.id,
                modelSelection = stage.assignment?.let { ModelSelection(it.profileId, it.modelId, it.effort) }))
            append(plan.projectId, id, CodingMessage("$id-task", CodingRole.USER, "${stage.description.ifBlank { stage.title }}\n\nКритерии: ${stage.acceptance}", createdAt = plan.createdAt))
        }
        changed()
    }
    private suspend fun queueWorker(session: CodingSession, text: String) {
        val id = enqueue(session.planId!!, session.id, session.stageId!!, text)
        append(session.projectId, session.id, CodingMessage(id, CodingRole.USER, text, createdAt = Id.now(), deliveryId = id, pendingDelivery = true))
        val plan = store.planFor(session.planId)!!
        append(plan.projectId, plan.parentSessionId, CodingMessage("$id-notice", CodingRole.AGENT, "Сообщение исполнителю «${session.name}»: $text", createdAt = Id.now()))
        if (plan.intent == ExecutionIntent.RUN) execution.start(plan.id)
    }
    private suspend fun enqueue(id: String, source: String, target: String, text: String, deliveryId: String = Id.new(), replyTo: String? = null): String {
        if (store.planFor(id)!!.deliveries.any { it.id == deliveryId }) return deliveryId
        val updated = store.update(id) { p ->
            if (p.deliveries.any { it.id == deliveryId }) return@update p
            val stage = p.milestones.firstOrNull { it.id == target } ?: error("Этап не найден")
            val followupId = "$target-followup-$deliveryId"
            val attempt = stage.attempts.lastOrNull()
            val complete = stage.completed || (attempt?.error?.requiresUser != true &&
                attempt?.phase in listOf(AttemptPhase.VERIFYING, AttemptPhase.INTEGRATING, AttemptPhase.COMPLETE))
            val destination = if (complete) followupId else target
            val followup = stage.copy(id = followupId, title = "Продолжение: ${stage.title}", description = text,
                status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", dependsOn = listOf(stage.id))
            p.copy(deliveries = p.deliveries + PlanDelivery(deliveryId, source, destination, text, replyTo = replyTo), issue = null,
                phase = if (complete) ExecutionPhase.EXECUTING else p.phase,
                finalAttempt = if (complete) null else p.finalAttempt,
                finalAttemptHistory = p.finalAttemptHistory + if (complete) listOfNotNull(p.finalAttempt) else emptyList(),
                workspace = if (complete && p.sharedWorkspace) p.workspace?.copy(applied = false) else p.workspace,
                milestones = p.milestones.map { m -> if (m.id != target || complete) m else m.copy(attempts = m.attempts.map {
                    if (it.error?.kind == IssueKind.VERIFICATION) it.retryAfterUserAction() else it.copy(error = null)
                }) } + if (complete) listOf(followup) else emptyList(),
                tree = if (complete) p.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + followupId) else it } + DecisionNode(followupId, followup.title, DecisionKind.STAGE, stageId = followupId) else p.tree)
        }
        prepareSessions(updated)
        val delivery = updated.deliveries.first { it.id == deliveryId }
        val targetSession = updated.milestones.first { it.id == delivery.targetStageId }.attempts.firstOrNull()?.sessionId ?: "plan-$id-stage-${delivery.targetStageId}"
        append(updated.projectId, targetSession, CodingMessage(deliveryId, CodingRole.USER, "От $source: $text", createdAt = Id.now(), deliveryId = deliveryId, pendingDelivery = true))
        val targetStage = updated.milestones.first { it.id == delivery.targetStageId }
        append(updated.projectId, updated.parentSessionId, CodingMessage("$deliveryId-routed", CodingRole.AGENT,
            "Планировщик поставил сообщение в очередь этапа «${targetStage.title}»: $text", createdAt = Id.now()))
        return deliveryId
    }
    override suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt): String {
        if (plan.parentSessionId.isBlank()) return ""
        val p = store.update(plan.id) { current -> current.copy(deliveries = current.deliveries.map { d ->
            if (d.targetStageId == stage.id && d.state != DeliveryState.ANSWERED) d.copy(state = DeliveryState.DELIVERED, attemptId = attempt.id, turnIndex = attempt.turnIndex) else d
        }) }
        val inbox = p.deliveries.filter { it.targetStageId == stage.id && it.state == DeliveryState.DELIVERED }
        append(p.projectId, p.parentSessionId, CodingMessage("${attempt.id}-turn-${attempt.turnIndex}-started", CodingRole.AGENT,
            "Планировщик ${if (attempt.turnIndex == 0) "передал работу исполнителю" else "продолжил работу исполнителя"} этапа «${stage.title}»." +
                if (inbox.isEmpty()) "" else " Передано сообщений: ${inbox.size}.", createdAt = Id.now()))
        return """
            Ты исполнитель этапа плана. Другие планы могут работать в этой же папке: сохраняй чужие изменения, перед правкой перечитывай файлы.
            Для передачи информации другим этапам обратись к планировщику через QUESTION и targetStageId. Не запускай других исполнителей самостоятельно.
            Последнее сообщение верни строго JSON: {"kind":"RESULT|QUESTION|BLOCKED","text":"результат, вопрос или причина блокировки","targetStageId":"id этапа или пустая строка","changedFiles":["относительный путь"]}.
            RESULT только после выполнения и проверки; QUESTION если нужен ответ; BLOCKED если продолжать нельзя. Не заменяй вопрос успешным результатом.
            Входящие сообщения: ${inbox.joinToString("\n") { "[${it.id}, от ${it.sourceSessionId}] ${it.text}" }}
        """.trimIndent()
    }
    override suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision {
        if (plan.parentSessionId.isBlank()) return StageTurnDecision(StageTurnAction.VERIFY, attempt.report)
        val activityId = "${plan.id}-${attempt.id}-turn-${attempt.turnIndex}"
        coordinatorActivity.update { it + (activityId to CoordinatorActivity(plan.id, plan.parentSessionId,
            listOf(CodingStep(CodingStepKind.INFO, "Планировщик обрабатывает этап «${stage.title}»…", running = true)))) }
        try {
            return coordinateTurn(plan, stage, attempt, activityId)
        } catch (e: Exception) {
            val cancelled = e is CancellationException && e !is TimeoutCancellationException
            val text = if (cancelled) "Работа планировщика остановлена."
                else "Ошибка планировщика: ${e.message ?: "не удалось обработать результат этапа"}"
            withContext(NonCancellable) {
                append(plan.projectId, plan.parentSessionId, CodingMessage("${attempt.id}-turn-${attempt.turnIndex}-review", CodingRole.AGENT,
                    text, createdAt = Id.now(), failed = !cancelled,
                    steps = completedCoordinatorActivity(activityId) + CodingStep(if (cancelled) CodingStepKind.INFO else CodingStepKind.ERROR, text)))
            }
            throw e
        } finally {
            coordinatorActivity.update { it - activityId }
        }
    }

    private fun coordinatorEvent(id: String, rawStep: CodingStep) {
        val step = rawStep.planningPreview()
        coordinatorActivity.update { all ->
            val activity = all[id] ?: return@update all
            val index = if (step.callId.isNotBlank()) activity.steps.indexOfLast { it.callId == step.callId && it.kind == step.kind }
                else activity.steps.lastIndex.takeIf { last -> last >= 0 && activity.steps[last].kind == step.kind &&
                    step.kind in listOf(CodingStepKind.THINKING, CodingStepKind.INFO, CodingStepKind.ANSWER) } ?: -1
            val steps = if (index < 0) activity.steps + step else activity.steps.mapIndexed { i, old -> if (i == index) step else old }
            all + (id to activity.copy(steps = steps))
        }
    }

    private fun completedCoordinatorActivity(id: String): List<CodingStep> =
        coordinatorActivity.value[id]?.steps.orEmpty().filter { it.kind != CodingStepKind.ANSWER }.map { it.copy(running = false) }

    private suspend fun coordinateTurn(plan: Plan, stage: Milestone, attempt: StageAttempt, activityId: String): StageTurnDecision {
        val eventId = "${attempt.id}-turn-${attempt.turnIndex}"
        var record = plan.coordination.firstOrNull { it.id == eventId }
        if (record == null) {
            val parsed = try { json.decodeFromString<StageReply>(attempt.report.substringAfter("```json").substringBeforeLast("```").trim()) }
                catch (_: Exception) { StageReply(StageReplyKind.BLOCKED, "Некорректный формат ответа исполнителя. Повтори ответ в указанном JSON формате; не повторяй выполненные изменения.") }
            val reply = if (parsed.text.isBlank()) StageReply(StageReplyKind.BLOCKED, "Пустой ответ исполнителя. Нужен проверяемый результат или вопрос.") else parsed
            record = CoordinationRecord(eventId, stage.id, reply)
            store.update(plan.id) { p -> p.copy(coordination = p.coordination + record,
                deliveries = p.deliveries.map { if (it.attemptId == attempt.id && it.turnIndex == attempt.turnIndex && it.state == DeliveryState.DELIVERED) it.copy(state = DeliveryState.ANSWERED) else it }) }
        }
        val reply = record.reply
        append(plan.projectId, plan.parentSessionId, CodingMessage(eventId, CodingRole.AGENT, "${stage.title}: ${reply.text}", createdAt = Id.now()))
        val records = store.planFor(plan.id)!!.coordination.filter { it.stageId == stage.id }
        val blocked = records.takeLastWhile { it.reply.kind != StageReplyKind.RESULT }.size
        if (blocked >= 3) {
            askUser(plan, stage, "$eventId-help", "Этап «${stage.title}» остановлен после трёх попыток разрешить блокировку. Нужно ваше уточнение: ${reply.text}")
            return StageTurnDecision(StageTurnAction.WAIT, reply.text)
        }
        var decision = record.decision
        var activity = record.activity
        if (decision == null) {
            val roster = profiles.load()
            val judge = plan.plannerSelection?.let { ProfileResolver.selection(it, roster) } ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
                ?: run {
                    askUser(plan, stage, "$eventId-help", "Подключите модель планировщика или уточните, как продолжить этап «${stage.title}»: ${reply.text}")
                    return StageTurnDecision(StageTurnAction.WAIT, reply.text)
                }
            val latest = store.planFor(plan.id)!!
            val peers = store.plans.value.filter { it.projectId == plan.projectId && it.id != plan.id }.joinToString("\n") { peer ->
                "План ${peer.id}: ${peer.goal}; intent=${peer.intent}; phase=${peer.phase}; status=${peer.status}; " +
                    "этапы: ${peer.selectedMilestones.joinToString { "${it.id}: ${it.status}, попыток=${it.attempts.size}" }}; " +
                    "результаты: ${peer.coordination.takeLast(8).joinToString { "${it.stageId}: ${it.reply.text}; files=${it.reply.changedFiles}" }.ifBlank { "нет сохранённых отчётов; состав изменений неизвестен" }}"
            }
            val targets = latest.selectedMilestones.map { it.id }.toSet()
            val context = latest.selectedMilestones.joinToString("\n") { "${it.id}: ${it.title}: ${it.status}: ${it.report}" }
            append(plan.projectId, plan.parentSessionId, CodingMessage("$eventId-review", CodingRole.AGENT,
                "Планировщик разбирает ${if (reply.kind == StageReplyKind.RESULT) "результат" else "обращение"} этапа «${stage.title}» и определяет следующий шаг.", createdAt = Id.now()))
            decision = coordinatorDecision(judge, listOf(LlmMessage(LlmChatRole.SYSTEM,
                "Ты координатор плана. Ответь JSON {\"reply\":\"объяснение\",\"actions\":[{\"stageId\":\"id\",\"message\":\"информация или задание\"}],\"askUser\":false,\"replan\":false}. Передай сведения между этапами. Если неизвестны требования — askUser=true и questions=[{\"id\":\"уникальный id\",\"title\":\"вопрос пользователю\",\"kind\":\"SINGLE|MULTIPLE|TEXT\",\"options\":[{\"id\":\"id варианта\",\"label\":\"вариант ответа\"}]}]. Для свободного ответа используй TEXT и options=[]. При askUser не выдавай заданий, зависящих от ответа. Не выдумывай результаты. replan=true если надо изменить ещё не начатые этапы в рамках цели. Проверь пересечения изменённых файлов с соседними планами. Если результат требует перепроверки после чужих изменений, передай исполнителю задание перепроверить его. Начатые этапы не удаляй, добавляй продолжения."),
                LlmMessage(LlmChatRole.USER, "Цель: ${plan.goal}\nЭтапы:\n$context\nДругие планы:\n$peers\nОт ${stage.id} для ${reply.targetStageId} (${reply.kind}): ${reply.text}\nФайлы: ${reply.changedFiles}\nВходящие сообщения: ${latest.deliveries.takeLast(12)}")), targets, plan, eventId, activityId)
            val savedDecision = decision
            activity = completedCoordinatorActivity(activityId)
            store.update(plan.id) { p -> p.copy(coordination = p.coordination.map { if (it.id == eventId) it.copy(decision = savedDecision, activity = activity) else it }) }
        }
        if (decision.askUser || decision.questions.isNotEmpty()) {
            askUser(plan, stage, "$eventId-coordinator", decision.reply.ifBlank { reply.text }, decision.questions, activity)
            return StageTurnDecision(StageTurnAction.WAIT, reply.text)
        }
        append(plan.projectId, plan.parentSessionId, CodingMessage("$eventId-coordinator", CodingRole.AGENT, "Планировщик: ${decision.reply}",
            steps = activity + CodingStep(CodingStepKind.ANSWER, "Планировщик: ${decision.reply}"), createdAt = Id.now()))
        decision.actions.forEachIndexed { index, action -> enqueue(plan.id, plan.parentSessionId, action.stageId, action.message, "$eventId-action-$index") }
        if (decision.replan && store.planFor(plan.id)!!.dialogue.none { it.id == "$eventId-replan-reply" })
            refine(plan.id, "$eventId-replan", "Обнови план в рамках цели. Сохрани начатые этапы и их идентификаторы, добавь необходимые продолжения. ${decision.reply}")
        if (reply.kind == StageReplyKind.RESULT) {
            val peers = store.plans.value.filter { it.projectId == plan.projectId && it.id != plan.id && it.phase != ExecutionPhase.COMPLETE }
            val overlaps = peers.flatMap { peer -> peer.coordination.filter { other -> other.reply.kind == StageReplyKind.RESULT && other.reply.changedFiles.any { it in reply.changedFiles } }.map { peer to it } }
            if (overlaps.isNotEmpty()) {
                val checks = store.planFor(plan.id)!!.journal.count { it.stageId == stage.id && it.operation == "shared-conflict-check" }
                val unseen = overlaps.filter { (_, other) -> store.plans.value.first { it.id == plan.id }.deliveries.none { it.id == "overlap-${stage.id}-${other.id}" } }
                if (unseen.isNotEmpty() && checks >= 3) {
                    val question = "Повторяющееся пересечение изменений в общей папке. Уточните, как согласовать файлы: ${reply.changedFiles.joinToString()}."
                    askUser(plan, stage, "$eventId-conflict", question)
                    return StageTurnDecision(StageTurnAction.WAIT, question)
                }
                unseen.forEach { (peer, other) ->
                    store.update(plan.id) { it.copy(journal = it.journal + PlanJournalEntry("overlap-${other.id}", Id.now(), stage.id, attempt.id, "shared-conflict-check", reply.changedFiles.joinToString())) }
                    enqueue(plan.id, peer.parentSessionId, stage.id, "Обнаружено пересечение файлов с планом ${peer.goal}: ${reply.changedFiles.filter { it in other.reply.changedFiles }}. Перечитай фактическое состояние, согласуй изменения без отката чужих файлов и повтори проверки. Не повторяй уже выполненные правки.", "overlap-${stage.id}-${other.id}")
                }
            }
            val peerEventId = "${plan.id}-${stage.id}-${reply.hashCode()}"
            store.plans.value.filter { it.projectId == plan.projectId && it.id != plan.id && it.parentSessionId.isNotBlank() && it.phase != ExecutionPhase.COMPLETE }.forEach { peer ->
                val info = "План «${plan.goal}», этап «${stage.title}»: ${reply.text}\nИзменённые файлы: ${reply.changedFiles.joinToString()}"
                append(peer.projectId, peer.parentSessionId, CodingMessage("$peerEventId-peer", CodingRole.AGENT, info, createdAt = Id.now()))
                // A delivered peer report enters the next planner/worker turn as context, not an instruction to revert files.
                peer.selectedMilestones.filter { !it.completed }.forEach { peerStage ->
                    enqueue(peer.id, plan.parentSessionId, peerStage.id, "Сведения соседнего плана. Перед продолжением перечитай затронутые файлы, не перезаписывай чужие изменения. $info", "$peerEventId-peer-${peerStage.id}")
                }
            }
        }
        if (reply.kind != StageReplyKind.RESULT && decision.actions.none { it.stageId == stage.id })
            enqueue(plan.id, plan.parentSessionId, stage.id, decision.reply, "$eventId-followup")
        val queued = store.planFor(plan.id)!!.deliveries.any { it.targetStageId == stage.id && it.state == DeliveryState.QUEUED }
        return StageTurnDecision(if (queued) StageTurnAction.CONTINUE else StageTurnAction.VERIFY, reply.text)
    }

    private suspend fun coordinatorDecision(
        judge: LlmProfile, context: List<LlmMessage>, targets: Set<String>, plan: Plan, eventId: String, activityId: String,
    ): CoordinatorReply {
        val routing = "actions[].stageId может быть только идентификатором выбранного этапа текущего плана: ${targets.joinToString()}. " +
            "Идентификаторы планов и сессий не являются адресатами actions. Новые этапы доступны после перепланирования. " +
            "Сообщение message обязательно и не может быть пустым. Прямого вызова координаторов соседних планов нет: " +
            "используй их сохранённые состояния и отчёты из контекста. Не поручай исполнителю обращаться через отсутствующий инструмент. " +
            "Отсутствие отчётов не доказывает отсутствие изменений файлов."
        val messages = context.mapIndexed { index, message ->
            if (index == 0) message.copy(content = "${message.content}\n$routing") else message
        }.toMutableList()
        repeat(3) { index ->
            val raw = gateway.completeWithActivity(judge, messages.toList()) { coordinatorEvent(activityId, it) }
            val decision = runCatching {
                json.decodeFromString<CoordinatorReply>(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            }.getOrNull()
            val problem = when {
                decision == null -> "Ответ не соответствует JSON-схеме CoordinatorReply."
                decision.reply.isBlank() -> "Отсутствует объяснение reply."
                decision.actions.any { it.stageId !in targets } -> "Недопустимые адресаты actions: ${decision.actions.filter { it.stageId !in targets }.joinToString { it.stageId }}."
                decision.actions.any { it.message.isBlank() } -> "Есть actions с пустым message."
                else -> return decision
            }
            if (index < 2) {
                coordinatorActivity.update { all -> all[activityId]?.let { current ->
                    all + (activityId to current.copy(steps = current.steps.filter { it.kind != CodingStepKind.ANSWER }))
                } ?: all }
                val text = "Планировщик исправляет формат или адресата сообщения (попытка ${index + 2} из 3)."
                coordinatorEvent(activityId, CodingStep(CodingStepKind.INFO, text, callId = "coordinator-repair-$index"))
                append(plan.projectId, plan.parentSessionId, CodingMessage("$eventId-repair-$index", CodingRole.AGENT,
                    text, createdAt = Id.now()))
                messages += LlmMessage(LlmChatRole.ASSISTANT, raw)
                messages += LlmMessage(LlmChatRole.USER,
                    "Ответ не принят: $problem Никакие actions из него не выполнены. Исправь весь ответ без повторения работы исполнителя. $routing")
            }
        }
        return CoordinatorReply("Планировщик не смог подготовить корректное сообщение за три попытки. " +
            "Результат исполнителя сохранён, новые задания не переданы. Уточните, как продолжить этап.", askUser = true)
    }

    private suspend fun askUser(plan: Plan, stage: Milestone, id: String, text: String, questions: List<PlanningQuestion> = emptyList(), activity: List<CodingStep> = emptyList()) {
        val validQuestions = questions.filter { it.title.isNotBlank() }.distinctBy { it.id }.mapIndexed { index, q ->
            q.copy(id = q.id.ifBlank { "$id-question-$index" },
                kind = if (q.options.isEmpty()) QuestionKind.TEXT else q.kind)
        }.ifEmpty { listOf(PlanningQuestion("$id-question", text)) }
        val message = "Планировщику нужен ваш ответ по этапу «${stage.title}».\n\n$text"
        append(plan.projectId, plan.parentSessionId, CodingMessage(id, CodingRole.AGENT, message, createdAt = Id.now(),
            steps = if (activity.isEmpty()) emptyList() else activity + CodingStep(CodingStepKind.ANSWER, message),
            planning = PlanningChatBlock(plan.id, questions = validQuestions, sourceStageId = stage.id)))
    }
    private suspend fun publish(plan: Plan) {
        if (plan.id in deletedPlans || plan.projectId in clearingProjects) return
        val history = projects.messages(plan.projectId, plan.parentSessionId)
        val existingGraph = history.firstOrNull { it.planning?.planId == plan.id && it.planning.graph }
        plan.dialogue.filter { it.role == "assistant" }.forEach { m ->
            if (history.none { it.id == m.id }) append(plan.projectId, plan.parentSessionId, CodingMessage(m.id, CodingRole.AGENT, m.text, steps = m.activity + CodingStep(CodingStepKind.ANSWER, m.text),
                createdAt = plan.updatedAt, planning = PlanningChatBlock(plan.id, questions = m.questions)))
        }
        if (plan.milestones.isNotEmpty() && existingGraph == null) append(plan.projectId, plan.parentSessionId, CodingMessage("${plan.id}-graph", CodingRole.AGENT,
            "План готов. Проверьте этапы и подтвердите запуск.", createdAt = plan.updatedAt, planning = PlanningChatBlock(plan.id, graph = true)))
        if (plan.phase == ExecutionPhase.COMPLETE && history.none { it.id == "${plan.id}-complete" }) append(plan.projectId, plan.parentSessionId,
            CodingMessage("${plan.id}-complete", CodingRole.AGENT, "План выполнен. ${plan.finalAttempt?.report.orEmpty()}", createdAt = plan.updatedAt))
        plan.blockingIssues(history).forEach { blocker ->
            val message = CodingMessage(blocker.messageId, CodingRole.AGENT, blocker.text, failed = true, createdAt = plan.updatedAt)
            append(plan.projectId, plan.parentSessionId, message)
            blocker.stage?.let { stage ->
                append(plan.projectId, blocker.attempt?.sessionId ?: "plan-${plan.id}-stage-${stage.id}", message)
            }
        }
        if (plan.confirmedRevision != null) {
            plan.selectedMilestones.forEach { stage ->
                val sessionId = stage.attempts.firstOrNull()?.sessionId ?: "plan-${plan.id}-stage-${stage.id}"
                if (stage.completed) append(plan.projectId, plan.parentSessionId, CodingMessage("${plan.id}-${stage.id}-completed", CodingRole.AGENT,
                    if (stage.status == MilestoneStatus.SKIPPED) "Этап «${stage.title}» пропущен."
                    else "Этап «${stage.title}» завершён и проверен. ${stage.checkNote}", createdAt = stage.updatedAt))
                stage.attempts.forEach { a ->
                    append(plan.projectId, sessionId, CodingMessage("${a.id}-prompt", CodingRole.USER, a.prompt.ifBlank { stage.description }, createdAt = a.startedAt))
                }
                messageLock.withLock {
                    val messages = projects.messages(plan.projectId, sessionId)
                    val next = messages.withStageResponses(stage.attempts.flatMap { it.chatResponses(history) })
                        .map { m -> if (m.deliveryId != null) m.copy(pendingDelivery = plan.deliveries.any { it.id == m.deliveryId && it.state == DeliveryState.QUEUED }) else m }
                    if (messages != next) projects.saveMessages(plan.projectId, sessionId, next)
                }
            }
        }
    }
    private suspend fun migrate() {
        store.plans().forEach { plan ->
            if (plan.parentSessionId.isBlank()) {
                val sessionId = "planning-${plan.id}"
                if (projects.sessions(plan.projectId).none { it.id == sessionId }) projects.saveSession(CodingSession(sessionId, plan.projectId, "План: ${plan.goal.take(40)}", plan.createdAt, planningMode = true, modelSelection = plan.plannerSelection))
                val linked = store.update(plan.id) { it.copy(parentSessionId = sessionId,
                    confirmedRevision = if (it.intent != ExecutionIntent.STOP || it.milestones.any { m -> m.attempts.isNotEmpty() }) it.revision else null,
                    versions = if (it.versions.isEmpty()) listOf(PlanVersion(it.revision, it.tree, it.milestones.map { m -> m.copy(attempts = emptyList()) }, Id.now())) else it.versions) }
                linked.dialogue.forEach { m -> append(plan.projectId, sessionId, CodingMessage(m.id, if (m.role == "user") CodingRole.USER else CodingRole.AGENT, m.text, createdAt = plan.createdAt, planning = PlanningChatBlock(plan.id, questions = m.questions))) }
                prepareSessions(linked)
            }
            if (plan.confirmedRevision != null) prepareSessions(store.planFor(plan.id)!!)
            if (plan.pendingRequest.isNotBlank()) {
                append(plan.projectId, plan.parentSessionId, CodingMessage("${plan.requestId}-interrupted", CodingRole.AGENT, "Запрос планирования был прерван. Отправьте уточнение ещё раз.", failed = true, createdAt = Id.now()))
                store.update(plan.id) { it.copy(pendingRequest = "", requestId = "") }
            }
        }
    }
}
