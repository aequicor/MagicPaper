package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class QuestionKind { SINGLE, MULTIPLE, TEXT }
@Serializable data class QuestionOption(val id: String, val label: String)
@Serializable data class PlanningQuestion(val id: String, val title: String, val kind: QuestionKind = QuestionKind.TEXT, val options: List<QuestionOption> = emptyList())
@Serializable data class PlanningAnswer(val questionId: String, val selected: List<String> = emptyList(), val text: String = "")
@Serializable data class PlanningChatBlock(val planId: String, val questions: List<PlanningQuestion> = emptyList(), val answers: List<PlanningAnswer> = emptyList(), val graph: Boolean = false, val replyTo: String? = null, val sourceStageId: String? = null)
@Serializable data class PlanVersion(val revision: Long, val tree: List<DecisionNode>, val milestones: List<Milestone>, val at: Long)
@Serializable enum class DeliveryState { QUEUED, DELIVERED, ANSWERED }
@Serializable data class PlanDelivery(val id: String, val sourceSessionId: String, val targetStageId: String, val text: String, val state: DeliveryState = DeliveryState.QUEUED, val attemptId: String = "", val turnIndex: Int = 0, val replyTo: String? = null)
@Serializable enum class StageReplyKind { RESULT, QUESTION, BLOCKED }
@Serializable data class StageReply(val kind: StageReplyKind, val text: String, val targetStageId: String = "", val changedFiles: List<String> = emptyList())
@Serializable data class CoordinatorReply(val reply: String, val actions: List<CoordinatorAction> = emptyList(), val askUser: Boolean = false, val replan: Boolean = false, val questions: List<PlanningQuestion> = emptyList())
@Serializable data class CoordinatorAction(val stageId: String, val message: String)

fun List<Plan>.resolvePlan(id: String): Plan? = firstOrNull { it.id == id } ?: filter { it.projectId == id }.let {
    require(it.size <= 1) { "В проекте несколько планов; укажите идентификатор плана" }
    it.singleOrNull()
}

enum class StageTurnAction { VERIFY, CONTINUE, WAIT }
data class StageTurnDecision(val action: StageTurnAction, val report: String)
interface PlanningExecutionHooks {
    suspend fun prepareSessions(plan: Plan)
    suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt): String
    suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision
}

@Serializable data class CoordinationRecord(val id: String, val stageId: String, val reply: StageReply, val decision: CoordinatorReply? = null, val activity: List<CodingStep> = emptyList())

/** An unanswered question stays visible even when orchestration adds newer messages. */
fun List<CodingMessage>.pendingPlanningQuestion(planIds: Set<String>? = null): CodingMessage? {
    val answered = mapNotNull { it.planning?.replyTo }.toSet()
    return firstOrNull { message ->
        val block = message.planning
        block != null && block.questions.isNotEmpty() && message.id !in answered &&
            (planIds == null || block.planId in planIds)
    }
}

fun Plan.isStageWorking(stage: Milestone): Boolean = intent == ExecutionIntent.RUN &&
    stage.status == MilestoneStatus.ACTIVE && stage.attempts.lastOrNull()?.error == null &&
    stage.attempts.lastOrNull()?.awaitingPlanner != true

/** Keep the coordination envelope out of the user-facing conversation. */
fun readableStageActivity(steps: List<CodingStep>): List<CodingStep> = steps.map { step ->
    if (step.kind != CodingStepKind.ANSWER) step else {
        val reply = stageReplyOrNull(step.title)
        if (reply == null) step else step.copy(title = reply.text)
    }
}
