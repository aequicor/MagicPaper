package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class CodingSessionRole { CHAT, ORCHESTRATOR, WORKER }
@Serializable enum class OrchestrationInputStatus { QUEUED, PROCESSING, DONE, FAILED, CANCELLED, WITHDRAWN }
@Serializable enum class UserTurnIntent { DISCUSS, CLARIFY, REFINE, ANSWER, INSTRUCT, CONTROL, SCHEDULE }
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
    /** An explicit answer to a pending clarification, not permission to start execution. */
    val refinePlan: Boolean? = null,
    /** Selected by the orchestrator; empty means no affected work. */
    val pauseStageIds: List<String> = emptyList(),
    val toolsApplied: Boolean = false,
)

@Serializable data class OrchestrationInput(
    val id: String, val text: String, val createdAt: Long,
    val answers: List<PlanningAnswer> = emptyList(), val replyTo: String? = null,
    val status: OrchestrationInputStatus = OrchestrationInputStatus.QUEUED,
    val decision: UserTurnDecision? = null, val error: String = "",
    /** Legacy composer metadata, retained for decoding only; never authorizes execution. */
    val resumeAfter: Boolean = false,
    val scheduledRuleId: String? = null,
    val sourcePlanId: String? = null,
    val sourceRunId: String? = null,
    val attempt: Int = 0,
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
    /** Original clarification, retained until the user decides whether to revise the plan. */
    val refinementRequest: String? = null,
    val forDiscussion: Boolean = false,
    /** Null retains legacy recipient-based scope; empty explicitly pauses no stages. */
    val pauseStageIds: List<String>? = null,
    val resolutionPending: Boolean = false,
    val requirementContext: String? = null,
)

@Serializable data class SessionCommand(
    val id: String, val kind: SessionCommandKind, val sessionId: String,
    val planId: String, val stageId: String = "", val name: String = "", val applied: Boolean = false,
    val error: String = "",
)

/** Keep answers separate from the diagnostic wrapper to avoid recursively echoing it. */
internal fun OrchestrationQuestion.workerAnswerText(answers: List<PlanningAnswer>, input: OrchestrationInput): String = buildString {
    appendLine("Ответ пользователя на запрос [$id]")
    if (answers.isEmpty()) {
        (partialMessages + (input.id to input.text)).values.filter { it.isNotBlank() }.distinct().forEach { appendLine(it) }
    } else {
        questions.forEach { q -> answers.firstOrNull { it.questionId == q.id }?.let { a ->
            val selected = a.selected.mapNotNull { option -> q.options.firstOrNull { it.id == option }?.label }
            val label = if (q.id == "$id-question") "Ответ" else q.title
            appendLine("$label: " + if (a.skipped) "Пропущено пользователем" else
                (selected + listOfNotNull(a.text.takeIf { it.isNotBlank() })).joinToString("; "))
        } }
        // Structured submissions already render the same answers in input.text.
        partialMessages.filterKeys { it != input.id }.values.filter { it.isNotBlank() }.distinct().forEach { appendLine(it) }
        if (input.text.isNotBlank() && (input.answers.isEmpty() ||
                input.text.trim() != interactionAnswerText(questions, input.answers).trim())) appendLine(input.text)
    }
}.trim()

@Serializable data class OrchestrationPause(val planId: String, val stageIds: List<String>, val proposalId: String? = null,
    /** A failed revision cannot silently resume work against the old requirements. */
    val requiresUser: Boolean = false)

internal fun OrchestrationState.finishWorkPause(plan: Plan, requestId: String): OrchestrationState {
    val proposal = plan.proposal
    val retained = workPauses.mapValues { (key, pause) ->
        if (pause.planId == plan.id && proposal != null && (key == requestId || pause.proposalId != null || pause.requiresUser))
            pause.copy(proposalId = proposal.id, requiresUser = false) else pause
    }
    return copy(workPauses = if (proposal == null && retained[requestId]?.requiresUser != true) retained - requestId else retained)
}

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
    val workPauses: Map<String, OrchestrationPause> = emptyMap(),
)

@Serializable data class PlanProposal(
    val id: String, val baseRunId: String, val baseTree: List<DecisionNode>,
    val baseMilestones: List<Milestone>, val tree: List<DecisionNode>,
    val milestones: List<Milestone>, val explanation: String,
)

/** Execution has settled enough to review a follow-up; open questions are checked separately. */
internal val Plan.proposalReadyForConfirmation: Boolean
    get() = proposal != null && !stopping && (phase == ExecutionPhase.COMPLETE || canExtendAfterFinalVerification ||
        (finalAttempt == null && phase !in setOf(ExecutionPhase.RECOVERING, ExecutionPhase.VERIFYING, ExecutionPhase.INTEGRATING, ExecutionPhase.APPLYING) &&
            (selectedMilestones.any { it.attempts.lastOrNull()?.interrupted == true } ||
                (phase == ExecutionPhase.WAITING && selectedMilestones.all { it.completed }))))

/** Only a reconciled interruption before integration may receive an approved specification revision. */
internal val Milestone.canRevisePausedSpecification: Boolean
    get() = !completed && attempts.lastOrNull()?.let { it.interrupted &&
        it.phase in setOf(AttemptPhase.PREPARED, AttemptPhase.EXECUTING, AttemptPhase.FAILED) &&
        !it.pendingToolExternal && it.mergePhase == null && !it.awaitingPlanner } == true

internal fun Plan.refinementView(): Plan = copy(milestones = milestones.map { stage ->
    if (stage.canRevisePausedSpecification) stage.copy(status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "") else stage
})

internal fun Plan.reconcileApprovedProposal(proposal: PlanProposal): Plan {
    val updated = copy(tree = proposal.tree, milestones = proposal.milestones.map { proposed ->
        val old = milestones.firstOrNull { it.id == proposed.id }
        when {
            old == null || old.attempts.isEmpty() && !old.completed -> proposed
            old.canRevisePausedSpecification -> proposed.copy(status = old.status, assignment = old.assignment,
                report = old.report, checkNote = "", attempts = old.attempts.mapIndexed { index, attempt ->
                    if (index != old.attempts.lastIndex) attempt else attempt.copy(acceptanceRecord = null, verificationSnapshot = null)
                })
            else -> old
        }
    })
    val oldGraph = DecisionCompiler.compile(this)
    val newGraph = DecisionCompiler.compile(updated)
    milestones.filter { it.canRevisePausedSpecification }.forEach { stage ->
        require(stage.id in newGraph.stageIds && oldGraph.dependencies[stage.id] == newGraph.dependencies[stage.id]) {
            "Приостановленный этап и его зависимости должны сохраняться"
        }
    }
    DecisionCompiler.validateEdit(refinementView(), updated)
    return updated
}

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
    val hops: List<SessionAddress> = emptyList(),
    val sessionDeliveryState: SessionDeliveryState? = null,
)

val CodingSession.effectiveRole: CodingSessionRole get() = when {
    stageId != null -> CodingSessionRole.WORKER
    planningMode -> CodingSessionRole.ORCHESTRATOR
    else -> role
}

fun CodingSession.subtitle(): String = when {
    sessionKind == SessionKind.IMMUNITY -> "Иммунитет"
    planningMode && (sessionKind == SessionKind.ZYGOTE || parentSessionId == null) -> "Зигота"
    parentSessionId != null || effectiveRole == CodingSessionRole.WORKER -> "Сессия" + (stageNumber?.let { " · Этап $it" } ?: "") +
        (continuationOfNumber?.let { " · Доработка этапа $it" } ?: "")
    else -> if (researchMode) "Исследование" else "Диалог"
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

/** Pause recipients and their transitive dependants, without affecting independent work. */
internal fun OrchestrationState.pausedStages(plan: Plan): Set<String> {
    val selected = plan.selectedMilestones.map { it.id }.toSet()
    val roots = questions.filter { it.planId == plan.id && (it.status == UserRequestStatus.OPEN || it.resolutionPending) }
        .flatMap { it.pauseStageIds ?: it.stageIds.ifEmpty { selected.toList() } } +
        workPauses.values.filter { pause ->
            pause.planId == plan.id && (pause.proposalId == null || plan.proposal?.id == pause.proposalId ||
                pause.stageIds.any { stageId -> plan.deliveries.none { it.id == "${pause.proposalId}-approved-$stageId" } })
        }.flatMap { it.stageIds }
    val blocked = roots.filter { it in selected }.toMutableSet()
    val dependencies = DecisionCompiler.compile(plan).dependencies
    do {
        val changed = blocked.addAll(selected.filter { stage -> dependencies[stage].orEmpty().any { it in blocked } })
    } while (changed)
    return blocked
}
