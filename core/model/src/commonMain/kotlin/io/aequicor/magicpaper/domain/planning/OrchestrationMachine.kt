package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*

/** Closed vocabulary for the durable orchestrator record. IDs and time come from the adapter. */
sealed interface OrchestrationEvent {
    data object Restore : OrchestrationEvent
    data class InputSubmitted(val input: OrchestrationInput) : OrchestrationEvent
    data class InputEnqueued(val input: OrchestrationInput) : OrchestrationEvent
    data class UnsavedInputsRecovered(val inputs: List<OrchestrationInput>) : OrchestrationEvent
    data class ScheduledRunsObserved(val plans: List<Plan>) : OrchestrationEvent
    data class InputClaimRequested(val plans: List<Plan>) : OrchestrationEvent
    data class InputWithdrawn(val id: String) : OrchestrationEvent
    data class InputRetried(val id: String, val clarification: String = "", val clearResumeAfter: Boolean = false) : OrchestrationEvent
    data class InputStatusRecorded(val id: String, val status: OrchestrationInputStatus, val error: String = "") : OrchestrationEvent
    data class InputDecisionRecorded(val id: String, val decision: UserTurnDecision) : OrchestrationEvent
    data class LegacyRequestImported(val plan: Plan) : OrchestrationEvent
    data class PlanSelected(val id: String) : OrchestrationEvent
    data class WorkPaused(val id: String, val pause: OrchestrationPause, val mergeStages: Boolean = false) : OrchestrationEvent
    data class WorkPauseNeedsUser(val id: String) : OrchestrationEvent
    data class WorkPauseFinished(val plan: Plan, val id: String) : OrchestrationEvent
    data class ProposalConfirmed(val planId: String, val proposalId: String?) : OrchestrationEvent
    data class PlanResumed(val plan: Plan) : OrchestrationEvent
    data class QuestionRegistered(val question: OrchestrationQuestion) : OrchestrationEvent
    data class QuestionAnswered(val plan: Plan, val input: OrchestrationInput, val decision: UserTurnDecision, val at: Long) : OrchestrationEvent
    data class QuestionResolved(val id: String, val pauseId: String, val pause: OrchestrationPause?) : OrchestrationEvent
    data class RequirementsQueued(val input: OrchestrationInput, val pause: OrchestrationPause) : OrchestrationEvent
    data class QuestionsImported(val plan: Plan, val messages: List<CodingMessage>) : OrchestrationEvent
    data class AnswerEventsRecorded(val events: List<MessageEvent>) : OrchestrationEvent
    data class StageNumbersRequested(val planId: String, val stageIds: List<String>) : OrchestrationEvent
    data class SessionCommandRegistered(val command: SessionCommand) : OrchestrationEvent
    data class SessionCommandRejected(val id: String, val error: String) : OrchestrationEvent
    data class SessionCommandApplied(val id: String) : OrchestrationEvent
    data class SessionCommandDiscarded(val id: String) : OrchestrationEvent
}

sealed interface OrchestrationEffect {
    data class ProcessInput(val input: OrchestrationInput) : OrchestrationEffect
    data class PublishAnswer(val answer: OrchestrationAnswer) : OrchestrationEffect
}

data class OrchestrationAnswer(
    val question: OrchestrationQuestion,
    val combined: List<PlanningAnswer>,
    val complete: Boolean,
    val refinePlan: Boolean?,
)

data class OrchestrationTransition(val state: OrchestrationState, val effects: List<OrchestrationEffect> = emptyList()) {
    val claimedInput: OrchestrationInput? get() = effects.filterIsInstance<OrchestrationEffect.ProcessInput>().singleOrNull()?.input
    val answer: OrchestrationAnswer? get() = effects.filterIsInstance<OrchestrationEffect.PublishAnswer>().singleOrNull()?.answer
}

/** Pure state transition. The service commits the record before executing any returned effect. */
fun reduce(state: OrchestrationState, event: OrchestrationEvent): OrchestrationTransition = when (event) {
    OrchestrationEvent.Restore -> state.transition()
    is OrchestrationEvent.InputSubmitted -> {
        state.inputs.firstOrNull { it.id == event.input.id }?.let { require(it.matchesSubmission(event.input)) }
        state.enqueue(event.input).transition()
    }
    is OrchestrationEvent.InputEnqueued -> state.enqueue(event.input).transition()
    is OrchestrationEvent.UnsavedInputsRecovered -> state.copy(inputs = (state.inputs + event.inputs).distinctBy { it.id }).transition()
    is OrchestrationEvent.ScheduledRunsObserved -> state.copy(inputs = state.inputs.map { input ->
        val plan = event.plans.firstOrNull { it.id == input.sourcePlanId }
        if (input.scheduledRuleId != null && input.status in setOf(OrchestrationInputStatus.QUEUED, OrchestrationInputStatus.PROCESSING) &&
            (plan == null || plan.runId != input.sourceRunId)) input.copy(status = OrchestrationInputStatus.CANCELLED, error = "Запуск изменился до обработки сообщения") else input
    }).transition()
    is OrchestrationEvent.InputClaimRequested -> state.claimInput(event.plans)
    is OrchestrationEvent.InputWithdrawn -> state.mapInput(event.id) {
        if (it.status == OrchestrationInputStatus.QUEUED && it.scheduledRuleId == null)
            it.copy(status = OrchestrationInputStatus.WITHDRAWN, error = "") else it
    }.transition()
    is OrchestrationEvent.InputRetried -> state.mapInput(event.id) {
        if (it.status in setOf(OrchestrationInputStatus.FAILED, OrchestrationInputStatus.CANCELLED))
            it.copy(status = OrchestrationInputStatus.QUEUED, error = "",
                text = it.text + if (event.clarification.isBlank()) "" else "\n\nУточнение пользователя: ${event.clarification}",
                decision = if (event.clarification.isBlank()) it.decision else null,
                resumeAfter = if (event.clearResumeAfter) false else it.resumeAfter) else it
    }.transition()
    is OrchestrationEvent.InputStatusRecorded -> state.mapInput(event.id) { it.copy(status = event.status, error = event.error) }.transition()
    is OrchestrationEvent.InputDecisionRecorded -> state.mapInput(event.id) { it.copy(decision = event.decision) }.transition()
    is OrchestrationEvent.LegacyRequestImported -> state.importLegacyRequest(event.plan).transition()
    is OrchestrationEvent.PlanSelected -> state.copy(activePlanId = event.id).transition()
    is OrchestrationEvent.WorkPaused -> state.copy(workPauses = state.workPauses + (event.id to
        if (event.mergeStages) event.pause.copy(stageIds = (state.workPauses[event.id]?.stageIds.orEmpty() + event.pause.stageIds).distinct()) else event.pause)).transition()
    is OrchestrationEvent.WorkPauseNeedsUser -> (state.workPauses[event.id]?.let { pause ->
        state.copy(workPauses = state.workPauses + (event.id to pause.copy(requiresUser = true)))
    } ?: state).transition()
    is OrchestrationEvent.WorkPauseFinished -> state.finishWorkPause(event.plan, event.id).transition()
    is OrchestrationEvent.ProposalConfirmed -> state.copy(workPauses = state.workPauses.filterValues {
        it.planId != event.planId || it.proposalId != event.proposalId || event.proposalId == null
    }).transition()
    is OrchestrationEvent.PlanResumed -> (if (event.plan.proposal == null) state.copy(workPauses = state.workPauses.filterValues {
        it.planId != event.plan.id || !it.requiresUser
    }) else state).transition()
    is OrchestrationEvent.QuestionRegistered -> (if (state.questions.any { it.id == event.question.id }) state
        else state.copy(questions = state.questions + event.question)).transition()
    is OrchestrationEvent.QuestionAnswered -> state.answerQuestion(event)
    is OrchestrationEvent.QuestionResolved -> state.copy(
        questions = state.questions.map { if (it.id == event.id) it.copy(resolutionPending = false) else it },
        workPauses = event.pause?.let { state.workPauses + (event.pauseId to it) } ?: state.workPauses).transition()
    is OrchestrationEvent.RequirementsQueued -> state.enqueue(event.input)
        .copy(workPauses = state.workPauses + (event.input.id to event.pause)).transition()
    is OrchestrationEvent.QuestionsImported -> state.importQuestions(event.plan, event.messages).transition()
    is OrchestrationEvent.AnswerEventsRecorded -> state.recordAnswerEvents(event.events).transition()
    is OrchestrationEvent.StageNumbersRequested -> state.numberStages(event).transition()
    is OrchestrationEvent.SessionCommandRegistered -> (if (state.sessionCommands.any { it.id == event.command.id }) state
        else state.copy(sessionCommands = state.sessionCommands + event.command)).transition()
    is OrchestrationEvent.SessionCommandRejected -> state.copy(sessionCommands = state.sessionCommands.map {
        if (it.id == event.id) it.copy(error = event.error) else it
    }).transition()
    is OrchestrationEvent.SessionCommandApplied -> state.copy(sessionCommands = state.sessionCommands.map {
        if (it.id == event.id) it.copy(applied = true) else it
    }).transition()
    is OrchestrationEvent.SessionCommandDiscarded -> state.copy(sessionCommands = state.sessionCommands.filterNot { it.id == event.id }).transition()
}

private fun OrchestrationState.transition(vararg effects: OrchestrationEffect) = OrchestrationTransition(this, effects.toList())
private fun OrchestrationState.enqueue(input: OrchestrationInput) =
    if (inputs.any { it.id == input.id }) this else copy(inputs = inputs + input)
private inline fun OrchestrationState.mapInput(id: String, change: (OrchestrationInput) -> OrchestrationInput) =
    copy(inputs = inputs.map { if (it.id == id) change(it) else it })

/** Receipt identity survives changes to the worker's display name or forwarding route. */
fun OrchestrationInput.matchesSubmission(other: OrchestrationInput): Boolean {
    if (answers != other.answers || replyTo != other.replyTo || resumeAfter != other.resumeAfter) return false
    if (sourceSessionId != null || sourceText != null) return sourceSessionId != null && sourceText != null &&
        sourceSessionId == other.sourceSessionId && sourceText == other.sourceText
    return text == other.text && (other.sourceSessionId == null) == (other.sourceText == null)
}

fun OrchestrationInput.mayRun(plans: List<Plan>): Boolean = scheduledRuleId == null ||
    plans.any { it.id == sourcePlanId && it.runId == sourceRunId && it.intent == ExecutionIntent.RUN }

fun OrchestrationState.hasQueuedInput(plans: List<Plan>): Boolean =
    inputs.any { it.status == OrchestrationInputStatus.QUEUED && it.mayRun(plans) }

private fun OrchestrationState.claimInput(plans: List<Plan>): OrchestrationTransition {
    val claimed = inputs.firstOrNull {
        it.status in setOf(OrchestrationInputStatus.QUEUED, OrchestrationInputStatus.PROCESSING) && it.mayRun(plans)
    }?.let { it.copy(status = OrchestrationInputStatus.PROCESSING, error = "", attempt = it.attempt + 1) } ?: return transition()
    return mapInput(claimed.id) { claimed }.transition(OrchestrationEffect.ProcessInput(claimed))
}

private fun OrchestrationState.importLegacyRequest(plan: Plan): OrchestrationState = when {
    plan.pendingRequest.isBlank() -> this
    inputs.any { it.id == plan.requestId } -> mapInput(plan.requestId) {
        if (it.status == OrchestrationInputStatus.CANCELLED) it.copy(status = OrchestrationInputStatus.QUEUED) else it
    }
    else -> enqueue(OrchestrationInput(plan.requestId, plan.pendingRequest, plan.updatedAt))
}

private fun OrchestrationState.answerQuestion(event: OrchestrationEvent.QuestionAnswered): OrchestrationTransition {
    val input = event.input
    val decision = event.decision
    val id = decision.replyTo ?: error("Не указан вопрос, на который дан ответ")
    val question = questions.firstOrNull { it.id == id && it.planId == event.plan.id } ?: error("Запрос ответа не найден")
    if (question.status != UserRequestStatus.OPEN && question.answerInputId != input.id) return transition()
    val combined = (question.partialAnswers + input.answers).associateBy { it.questionId }.values.toList()
    require(input.answers.all { a -> question.questions.any { it.id == a.questionId } }) { "Неизвестный вопрос" }
    question.questions.forEach { q -> combined.firstOrNull { it.questionId == q.id }?.let { a ->
        require(a.selected.all { selected -> q.options.any { it.id == selected && it.enabled } }) { "Неизвестный вариант" }
        require(!a.skipped || q.canSkip && a.selected.isEmpty() && a.text.isBlank()) { "Этот вопрос нельзя пропустить" }
        require(q.allowCustomInput || a.text.isBlank()) { "Для этого вопроса выберите вариант" }
        require(q.kind != QuestionKind.SINGLE || a.selected.distinct().size <= 1) { "Выберите один вариант" }
    } }
    val complete = decision.completeAnswer && (input.answers.isEmpty() || question.questions.all { q ->
        combined.any { it.questionId == q.id && it.isComplete(q) }
    })
    val refinePlan = if (question.refinementRequest == null) null else combined.singleOrNull()?.let {
        when (it.selected.singleOrNull()) { "yes" -> true; "no" -> false; else -> null }
    } ?: decision.refinePlan
    require(question.refinementRequest == null || !complete || refinePlan != null) { "Укажите, нужно ли доработать план" }
    val next = copy(questions = questions.map {
        if (it.id == id) it.copy(partialAnswers = combined, partialMessages = it.partialMessages + (input.id to input.text),
            status = if (complete) UserRequestStatus.ANSWERED else UserRequestStatus.OPEN, resolutionPending = complete,
            answerInputId = if (complete) input.id else null, answeredAt = if (complete) event.at else null,
            answeredRunId = if (complete) event.plan.runId else null) else it
    })
    return next.transition(OrchestrationEffect.PublishAnswer(OrchestrationAnswer(question, combined, complete, refinePlan)))
}

private fun OrchestrationState.numberStages(event: OrchestrationEvent.StageNumbersRequested): OrchestrationState {
    val numbers = stageNumbers.toMutableMap()
    var next = nextStageNumber
    event.stageIds.distinct().forEach { stage ->
        val key = "${event.planId}:$stage"
        if (key !in numbers) { numbers[key] = next; next++ }
    }
    return copy(stageNumbers = numbers, nextStageNumber = next)
}

private fun OrchestrationState.importQuestions(plan: Plan, messages: List<CodingMessage>): OrchestrationState {
    val imported = messages.filter { it.planning?.questions?.isNotEmpty() == true && it.planning!!.planId == plan.id &&
        questions.none { q -> q.id == it.id } }.map { m ->
        val block = m.planning!!
        val stages = block.affectedStageIds.ifEmpty { listOfNotNull(block.sourceStageId) }
        OrchestrationQuestion(m.id, plan.id, m.text, block.questions, block.sourceSessionId ?: plan.parentSessionId,
            stages, block.scopeLabel.ifBlank { if (stages.isEmpty()) "Для всего плана" else stages.joinToString("; ") { stageId ->
                (plan.milestones + plan.proposal?.milestones.orEmpty()).firstOrNull { it.id == stageId }?.stageLabel() ?: "Этап" } },
            status = if (messages.any { it.planning?.replyTo == m.id && it.planning!!.closesRequest }) UserRequestStatus.ANSWERED else block.requestStatus,
            forPlanning = plan.dialogue.any { it.id == m.id },
            forDiscussion = inputs.any { "${it.id}-reply" == m.id && it.decision?.intent == UserTurnIntent.DISCUSS },
            pauseStageIds = workPauses[m.id.removeSuffix("-reply")]?.stageIds ?: inputs.firstOrNull { "${it.id}-reply" == m.id }?.decision?.let {
                it.pauseStageIds.takeIf { ids -> ids.isNotEmpty() || it.intent == UserTurnIntent.DISCUSS }
            })
    }
    return if (imported.isEmpty()) this else copy(questions = questions + imported)
}

/** Descriptions only: the caller supplies collision-free IDs and the fallback timestamp. */
fun OrchestrationState.pendingAnswerEvents(at: Long): List<MessageEvent> = questions.filter {
    it.status == UserRequestStatus.ANSWERED && it.answeredRunId != null &&
        messageEvents.none { event -> event.sourceKey == "question:${it.id}:${it.answerInputId}" }
}.map { q -> MessageEvent("", "question:${q.id}:${q.answerInputId}", q.planId, q.answeredRunId!!,
    MessageEventKind.QUESTION_ANSWERED, q.answeredAt ?: at, q.partialMessages.values.joinToString("\n"), questionId = q.id) }

private fun OrchestrationState.recordAnswerEvents(events: List<MessageEvent>): OrchestrationState {
    var next = this
    events.forEach { event ->
        val existing = next.messageEvents.firstOrNull { it.sourceKey == event.sourceKey }
        if (existing == null) {
            require(event.id.isNotBlank() && next.messageEvents.none { it.id == event.id }) { "Duplicate answer event identity" }
            require(next.pendingAnswerEvents(event.at).any { it.copy(id = event.id) == event }) { "Answer event has no matching question" }
            next = next.copy(messageEvents = next.messageEvents + event)
        } else require(existing == event) { "Answer event changed" }
    }
    return next
}
