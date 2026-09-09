package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class QuestionKind { SINGLE, MULTIPLE, TEXT }
@Serializable data class QuestionOption(val id: String, val label: String, val description: String = "", val enabled: Boolean = true)
@Serializable data class PlanningQuestion(val id: String, val title: String, val kind: QuestionKind = QuestionKind.TEXT,
    val options: List<QuestionOption> = emptyList(), val allowCustomInput: Boolean = true, val canSkip: Boolean = true,
    val secret: Boolean = false)
@Serializable data class PlanningAnswer(val questionId: String, val selected: List<String> = emptyList(), val text: String = "", val skipped: Boolean = false)
@Serializable data class PlanningChatBlock(val planId: String, val questions: List<PlanningQuestion> = emptyList(), val answers: List<PlanningAnswer> = emptyList(), val graph: Boolean = false, val replyTo: String? = null, val sourceStageId: String? = null, val sourceSessionId: String? = null, val scopeLabel: String = "", val requestStatus: UserRequestStatus = UserRequestStatus.OPEN, val closesRequest: Boolean = true, val affectedStageIds: List<String> = emptyList(), val inputIntent: UserTurnIntent? = null)
@Serializable data class PlanVersion(val revision: Long, val tree: List<DecisionNode>, val milestones: List<Milestone>, val at: Long)
@Serializable enum class DeliveryState { QUEUED, DELIVERED, ANSWERED, CANCELLED }
@Serializable data class PlanDelivery(val id: String, val sourceSessionId: String, val targetStageId: String, val text: String, val state: DeliveryState = DeliveryState.QUEUED, val attemptId: String = "", val turnIndex: Int = 0, val replyTo: String? = null, val sourceRunId: String? = null)
@Serializable enum class StageReplyKind { RESULT, QUESTION, BLOCKED, WAIT }
@Serializable data class StageReply(val kind: StageReplyKind, val text: String, val targetStageId: String = "", val changedFiles: List<String> = emptyList(), val waitFor: MessageTrigger? = null, val resumeMessage: String = "")
@Serializable enum class CoordinatorResultAction { VERIFY, CONTINUE }
@Serializable data class CoordinatorReply(val reply: String, val actions: List<CoordinatorAction> = emptyList(), val askUser: Boolean = false, val replan: Boolean = false, val questions: List<PlanningQuestion> = emptyList(), val questionStageIds: List<String>? = null, val sessionActions: List<CoordinatorSessionAction> = emptyList(), val schedules: List<ScheduleCommand> = emptyList(),
    val resultAction: CoordinatorResultAction? = null, val continuationReason: String = "", val toolsApplied: Boolean = false)
@Serializable data class CoordinatorSessionAction(val kind: SessionCommandKind, val stageId: String, val name: String = "")
@Serializable data class CoordinatorAction(val stageId: String, val message: String)

fun List<Plan>.resolvePlan(id: String): Plan? = firstOrNull { it.id == id } ?: filter { it.projectId == id }.let {
    require(it.size <= 1) { "В проекте несколько планов; укажите идентификатор плана" }
    it.singleOrNull()
}

enum class StageTurnAction { VERIFY, CONTINUE, WAIT, WAIT_EVENT }
data class StageTurnDecision(val action: StageTurnAction, val report: String, val requestId: String? = null)
interface PlanningExecutionHooks {
    suspend fun awaitReady() = Unit
    suspend fun recoverAssignments(plan: Plan): Plan = plan
    suspend fun blockedStages(plan: Plan): Set<String> = emptySet()
    suspend fun prepareSessions(plan: Plan)
    suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt): String
    suspend fun started(plan: Plan, stage: Milestone, attempt: StageAttempt) = Unit
    suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision
}

@Serializable enum class HandoffStatus { QUEUED, PROCESSING, RESOLVED, FAILED }
@Serializable data class HandoffInfo(val eventId: String, val taskId: String, val runId: String, val status: HandoffStatus, val nextStep: String = "")
@Serializable data class CoordinationRecord(val id: String, val stageId: String, val reply: StageReply, val decision: CoordinatorReply? = null, val activity: List<CodingStep> = emptyList(),
    val runId: String = "", val attemptId: String = "", val sourceSessionId: String = "", val turnIndex: Int = 0, val createdAt: Long = 0,
    val status: HandoffStatus = HandoffStatus.QUEUED, val nextStep: String = "", val verification: StageVerification? = null,
    val actionRevision: Int = 0, val toolCallId: String? = null)
@Serializable data class StageVerification(val passed: Boolean, val note: String)

/** A RESULT hands control to verification. Sending it back requires an explicit unfinished task. */
internal fun CoordinatorReply.resultProblem(record: CoordinationRecord?): String? {
    if (record?.reply?.kind != StageReplyKind.RESULT || askUser || questions.isNotEmpty()) return null
    val selfActions = actions.filter { it.stageId == record.stageId }
    return when {
        resultAction == CoordinatorResultAction.CONTINUE && (selfActions.isEmpty() || continuationReason.isBlank()) ->
            "Для CONTINUE нужны continuationReason с невыполненным критерием и конкретное задание текущему этапу."
        selfActions.isNotEmpty() && resultAction != CoordinatorResultAction.CONTINUE ->
            "RESULT уже возвращает результат приложению. Для проверки укажи resultAction=VERIFY и убери actions текущему этапу; поручение «отметить завершённым» снова запускает исполнителя. Для реальной доработки нужны CONTINUE и continuationReason."
        else -> null
    }
}

internal fun Plan.coordinatorResultProblem(eventId: String, decision: CoordinatorReply): String? {
    val record = coordination.firstOrNull { it.id == eventId } ?: return null
    decision.resultProblem(record)?.let { return it }
    if (record.reply.kind != StageReplyKind.RESULT || decision.resultAction != CoordinatorResultAction.CONTINUE ||
        decision.askUser || decision.questions.isNotEmpty()) return null
    val attemptId = record.attemptId.ifBlank { record.id.substringBeforeLast("-turn-") }
    val unverified = coordination.filter { it.stageId == record.stageId && (it.runId.isBlank() || it.runId == runId) &&
        it.attemptId.ifBlank { it.id.substringBeforeLast("-turn-") } == attemptId }
        .takeLastWhile { it.verification == null }.count { it.reply.kind == StageReplyKind.RESULT }
    return if (unverified >= 3) "Исполнитель уже вернул три RESULT без проверки. Передай сохранённые отчёты на проверку через VERIFY; если требуется решение пользователя, задай вопрос. Ещё один автоматический CONTINUE создаёт цикл." else null
}

internal fun CoordinationRecord.actionOrigin(): String = if (actionRevision == 0) id else "$id-revision-$actionRevision"

/** A consumed user answer starts a new recovery window; queued answers do not. */
internal fun Plan.blockedTurnCount(stageId: String, attempt: StageAttempt): Int {
    var count = 0
    for (record in stageRecords(stageId, attempt)) {
        val turn = record.id.substringAfterLast("-turn-").toIntOrNull() ?: record.turnIndex
        if (deliveries.any { it.targetStageId == stageId && it.attemptId == attempt.id &&
                it.turnIndex == turn && it.replyTo != null &&
                it.state in setOf(DeliveryState.DELIVERED, DeliveryState.ANSWERED) }) count = 0
        when (record.reply.kind) {
            StageReplyKind.RESULT -> count = 0
            StageReplyKind.BLOCKED, StageReplyKind.QUESTION -> count++
            StageReplyKind.WAIT -> Unit
        }
    }
    return count
}

/** Scope evidence by attempt and run, including checkpoints written before those fields existed. */
internal fun Plan.stageRecords(stageId: String, attempt: StageAttempt): List<CoordinationRecord> = coordination.filter {
    it.stageId == stageId && (it.runId.isBlank() || it.runId == runId) &&
        it.attemptId.ifBlank { it.id.substringBeforeLast("-turn-") } == attempt.id &&
        it.id.substringAfterLast("-turn-").toIntOrNull().let { turn -> (turn ?: it.turnIndex) <= attempt.turnIndex }
}.sortedBy { it.id.substringAfterLast("-turn-").toIntOrNull() ?: it.turnIndex }

internal fun List<CoordinationRecord>.evidenceText(): String = joinToString("\n\n") { record ->
    buildString {
        appendLine("Ход ${record.id} (${record.reply.kind}): ${record.reply.text}")
        if (record.reply.changedFiles.isNotEmpty()) appendLine("Файлы: ${record.reply.changedFiles.joinToString()}")
        record.verification?.let { appendLine("Проверка приложения: ${if (it.passed) "PASS" else "FAILED"}. ${it.note}") }
    }.trimEnd()
}

internal fun Plan.stageVerificationReport(stageId: String, attempt: StageAttempt): String {
    val records = stageRecords(stageId, attempt)
    if (records.size <= 1) return if (records.singleOrNull()?.toolCallId != null) records.evidenceText() else attempt.report
    return "История отчётов текущей попытки по порядку (прежние проверки не означают принятие нового результата):\n" +
        records.evidenceText() + "\n\nПоследний отчёт исполнителя:\n${attempt.report}"
}

/** An unanswered question stays visible even when orchestration adds newer messages. */
fun List<CodingMessage>.pendingPlanningQuestion(planIds: Set<String>? = null): CodingMessage? {
    val answered = filter { it.planning?.closesRequest != false }.mapNotNull { it.planning?.replyTo }.toSet()
    return firstOrNull { message ->
        val block = message.planning
        block != null && block.questions.isNotEmpty() && block.requestStatus == UserRequestStatus.OPEN && message.id !in answered &&
            (planIds == null || block.planId in planIds)
    }
}

fun Plan.isStageWorking(stage: Milestone): Boolean = intent == ExecutionIntent.RUN &&
    stage.status == MilestoneStatus.ACTIVE && stage.attempts.lastOrNull()?.error == null &&
    stage.attempts.lastOrNull()?.awaitingPlanner != true && stage.attempts.lastOrNull()?.waitingForUser == null && stage.attempts.lastOrNull()?.waitingForEvent == null

/** Keep the coordination envelope out of the user-facing conversation. */
fun readableStageActivity(steps: List<CodingStep>): List<CodingStep> = steps.map { step ->
    if (step.kind != CodingStepKind.ANSWER) step else {
        val reply = stageReplyOrNull(step.title)
        if (reply == null) step else step.copy(title = reply.text)
    }
}

/** Project old checkpoints without inventing a new pending transfer for an already resolved turn. */
internal fun Plan.handoffForDisplay(record: CoordinationRecord): CoordinationRecord {
    val normalized = normalizeHandoff(record)
    record.verification?.let { verdict ->
        return normalized.copy(status = HandoffStatus.RESOLVED,
            nextStep = if (verdict.passed) "Проверка результата пройдена. ${verdict.note}" else "Проверка результата не пройдена. ${verdict.note}")
    }
    return normalized
}

private fun Plan.normalizeHandoff(record: CoordinationRecord): CoordinationRecord {
    if (record.runId.isNotBlank()) return record
    val attemptId = record.attemptId.ifBlank { record.id.substringBeforeLast("-turn-") }
    val turn = record.id.substringAfterLast("-turn-", "0").toIntOrNull() ?: record.turnIndex
    val past = runHistory.firstOrNull { run -> run.milestones.any { task -> task.attempts.any { it.id == attemptId } } }
    val attempt = (milestones + past?.milestones.orEmpty()).flatMap { it.attempts }.firstOrNull { it.id == attemptId }
    val pending = attempt?.let { it.turnIndex == turn && it.awaitingPlanner && it.waitingForUser == null && it.coordinationPending != false } == true
    val status = if (record.status == HandoffStatus.QUEUED && record.decision != null && !pending) HandoffStatus.RESOLVED else record.status
    return record.copy(runId = past?.runId ?: runId, attemptId = attemptId, sourceSessionId = record.sourceSessionId.ifBlank { attempt?.sessionId.orEmpty() },
        turnIndex = turn, createdAt = record.createdAt.takeIf { it > 0 } ?: attempt?.chatTurns?.getOrNull(turn)?.completedAt?.takeIf { it > 0 } ?: attempt?.startedAt ?: createdAt,
        status = status, nextStep = record.nextStep.ifBlank { if (status == HandoffStatus.RESOLVED) "Решение оркестратора сохранено" else "" })
}
