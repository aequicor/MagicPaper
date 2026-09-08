package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class QuestionKind { SINGLE, MULTIPLE, TEXT }
@Serializable data class QuestionOption(val id: String, val label: String, val description: String = "", val enabled: Boolean = true)
@Serializable data class PlanningQuestion(val id: String, val title: String, val kind: QuestionKind = QuestionKind.TEXT,
    val options: List<QuestionOption> = emptyList(), val allowCustomInput: Boolean = true, val canSkip: Boolean = true,
    val secret: Boolean = false)
@Serializable data class PlanningAnswer(val questionId: String, val selected: List<String> = emptyList(), val text: String = "", val skipped: Boolean = false)
@Serializable data class PlanningChatBlock(val planId: String, val questions: List<PlanningQuestion> = emptyList(), val answers: List<PlanningAnswer> = emptyList(), val graph: Boolean = false, val replyTo: String? = null, val sourceStageId: String? = null, val sourceSessionId: String? = null, val scopeLabel: String = "", val requestStatus: UserRequestStatus = UserRequestStatus.OPEN, val closesRequest: Boolean = true, val affectedStageIds: List<String> = emptyList())
@Serializable data class PlanVersion(val revision: Long, val tree: List<DecisionNode>, val milestones: List<Milestone>, val at: Long)
@Serializable enum class DeliveryState { QUEUED, DELIVERED, ANSWERED, CANCELLED }
@Serializable data class PlanDelivery(val id: String, val sourceSessionId: String, val targetStageId: String, val text: String, val state: DeliveryState = DeliveryState.QUEUED, val attemptId: String = "", val turnIndex: Int = 0, val replyTo: String? = null, val sourceRunId: String? = null)
@Serializable enum class StageReplyKind { RESULT, QUESTION, BLOCKED, WAIT }
@Serializable data class StageReply(val kind: StageReplyKind, val text: String, val targetStageId: String = "", val changedFiles: List<String> = emptyList(), val waitFor: MessageTrigger? = null, val resumeMessage: String = "")
@Serializable data class CoordinatorReply(val reply: String, val actions: List<CoordinatorAction> = emptyList(), val askUser: Boolean = false, val replan: Boolean = false, val questions: List<PlanningQuestion> = emptyList(), val questionStageIds: List<String>? = null, val sessionActions: List<CoordinatorSessionAction> = emptyList(), val schedules: List<ScheduleCommand> = emptyList())
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
    val status: HandoffStatus = HandoffStatus.QUEUED, val nextStep: String = "")

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
