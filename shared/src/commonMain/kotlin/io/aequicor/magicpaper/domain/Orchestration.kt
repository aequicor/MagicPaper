package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class CodingSessionRole { CHAT, ORCHESTRATOR, WORKER }
@Serializable enum class OrchestrationInputStatus { QUEUED, PROCESSING, DONE, FAILED, CANCELLED, WITHDRAWN }
@Serializable enum class UserTurnIntent { DISCUSS, REFINE, ANSWER, INSTRUCT, CONTROL, SCHEDULE }
@Serializable enum class UserRequestStatus { OPEN, ANSWERED, CANCELLED }
@Serializable enum class SessionCommandKind { CREATE, ARCHIVE, RESTORE, RENAME }

@Serializable data class UserTurnDecision(
    val intent: UserTurnIntent,
    val reply: String = "",
    val replyTo: String? = null,
    val completeAnswer: Boolean = true,
    val command: String = "",
    val stageId: String = "",
    val proposalId: String? = null,
    val requiresConfirmation: Boolean = false,
    val questions: List<PlanningQuestion> = emptyList(),
    val schedules: List<ScheduleCommand> = emptyList(),
)

@Serializable data class OrchestrationInput(
    val id: String, val text: String, val createdAt: Long,
    val answers: List<PlanningAnswer> = emptyList(), val replyTo: String? = null,
    val status: OrchestrationInputStatus = OrchestrationInputStatus.QUEUED,
    val decision: UserTurnDecision? = null, val error: String = "",
    val resumeAfter: Boolean = false,
    val scheduledRuleId: String? = null,
    val sourcePlanId: String? = null,
    val sourceRunId: String? = null,
)

@Serializable data class OrchestrationQuestion(
    val id: String, val planId: String, val text: String,
    val questions: List<PlanningQuestion>, val sourceSessionId: String,
    val stageIds: List<String> = emptyList(), val scopeLabel: String = "Для всего плана",
    val status: UserRequestStatus = UserRequestStatus.OPEN,
    val partialAnswers: List<PlanningAnswer> = emptyList(),
    val partialMessages: Map<String, String> = emptyMap(),
    val answerInputId: String? = null,
    val forPlanning: Boolean = false,
    val answeredAt: Long? = null,
    val answeredRunId: String? = null,
)

@Serializable data class SessionCommand(
    val id: String, val kind: SessionCommandKind, val sessionId: String,
    val planId: String, val stageId: String = "", val name: String = "", val applied: Boolean = false,
)

@Serializable data class OrchestrationState(
    val sessionId: String, val projectId: String,
    val version: Int = 1, val activePlanId: String? = null,
    val inputs: List<OrchestrationInput> = emptyList(),
    val questions: List<OrchestrationQuestion> = emptyList(),
    val sessionCommands: List<SessionCommand> = emptyList(),
    val messageEvents: List<MessageEvent> = emptyList(),
    /** Allocated once per stage, across every plan belonging to this orchestrator. */
    val stageNumbers: Map<String, Int> = emptyMap(),
    val nextStageNumber: Int = 1,
)

@Serializable data class PlanProposal(
    val id: String, val baseRunId: String, val baseTree: List<DecisionNode>,
    val baseMilestones: List<Milestone>, val tree: List<DecisionNode>,
    val milestones: List<Milestone>, val explanation: String,
)

/** Execution has settled enough to review a follow-up; open questions are checked separately. */
internal val Plan.proposalReadyForConfirmation: Boolean
    get() = proposal != null && (phase == ExecutionPhase.COMPLETE || canExtendAfterFinalVerification)

@Serializable data class PlanRunSnapshot(
    val runId: String, val tree: List<DecisionNode>, val milestones: List<Milestone>,
    val workspace: PlanWorkspace?, val finalAttempt: StageAttempt?, val completedAt: Long,
)

@Serializable data class SessionAddress(
    val sessionId: String, val name: String, val subtitle: String,
    val orchestratorName: String = "",
)

@Serializable data class MessageRoute(
    val source: SessionAddress, val target: SessionAddress,
    val via: SessionAddress? = null, val kind: String = "Сообщение",
    val stageLabel: String = "", val deliveryId: String? = null,
)

val CodingSession.effectiveRole: CodingSessionRole get() = when {
    stageId != null -> CodingSessionRole.WORKER
    planningMode -> CodingSessionRole.ORCHESTRATOR
    else -> role
}

fun CodingSession.subtitle(): String = when (effectiveRole) {
    CodingSessionRole.ORCHESTRATOR -> "Оркестратор" + (orchestratorNumber?.let { " $it" } ?: "")
    CodingSessionRole.WORKER -> "Исполнитель" + (stageNumber?.let { " · Этап $it" } ?: "") +
        (continuationOfNumber?.let { " · Доработка этапа $it" } ?: "")
    CodingSessionRole.CHAT -> "Диалог"
} + if (archived) " · В архиве" else ""

fun Milestone.stageLabel(): String = (displayNumber?.let { "Этап $it · " } ?: "Этап · ") + (displayName ?: title)

fun OrchestrationState.openQuestions(planId: String? = null): List<OrchestrationQuestion> = questions.filter {
    it.status == UserRequestStatus.OPEN && (planId == null || it.planId == planId)
}

fun List<Milestone>.specification(): List<Milestone> = map {
    it.copy(status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", updatedAt = 0)
}

internal fun List<PlanningQuestion>.validQuestions(): Boolean = size <= 3 && map { it.id }.distinct().size == size && all { q ->
    q.id.isNotBlank() && q.title.isNotBlank() && (q.kind == QuestionKind.TEXT || q.options.size >= 2) &&
        q.options.all { it.id.isNotBlank() && it.label.isNotBlank() } && q.options.map { it.id }.distinct().size == q.options.size
}

class OrchestrationPersistenceException(message: String, cause: Throwable) : IllegalStateException(message, cause)
