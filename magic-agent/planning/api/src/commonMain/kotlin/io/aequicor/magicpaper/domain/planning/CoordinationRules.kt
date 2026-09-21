package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

/** Plan-side receipts for communication with workers. Scheduling and native execution stay external. */
@Serializable sealed interface CoordinationEvent : PlanEvent {
    @Serializable data class InboxPrepared(val stageId: String, val attemptId: String, val turnIndex: Int) : CoordinationEvent
    @Serializable data class InboxDelivered(val attemptId: String, val turnIndex: Int) : CoordinationEvent
    @Serializable data class InboxAnswered(val attemptId: String, val turnIndex: Int) : CoordinationEvent
    @Serializable data class HandoffRecorded(val record: CoordinationRecord) : CoordinationEvent
    @Serializable data class HandoffSubmitted(val record: CoordinationRecord) : CoordinationEvent
    @Serializable data class ScheduledDeliveryRequested(val rule: ScheduledMessage, val parent: String) : CoordinationEvent
    @Serializable data class HandoffStatusRecorded(val id: String, val status: HandoffStatus, val text: String) : CoordinationEvent
    @Serializable data class DecisionRecorded(val id: String, val decision: CoordinatorReply, val activity: List<CodingStep>, val replacesResult: Boolean) : CoordinationEvent
    @Serializable data class DeliveryRequested(val delivery: PlanDelivery, val finalizationId: String, val retryCheckpoint: Plan? = null) : CoordinationEvent
    @Serializable data class LateDeliveryObserved(val id: String, val finalizationId: String) : CoordinationEvent
    @Serializable data class QuestionsObserved(val events: List<MessageEvent>, val ids: Set<String>) : CoordinationEvent
}

/** No new persistence shape: these transitions still produce the existing Plan checkpoint. */
fun reduce(plan: Plan, event: CoordinationEvent): Plan = when (event) {
    is CoordinationEvent.InboxPrepared -> plan.copy(deliveries = plan.deliveries.map { d ->
        if (d.targetStageId == event.stageId && d.state in setOf(DeliveryState.QUEUED, DeliveryState.DELIVERED))
            d.copy(attemptId = event.attemptId, turnIndex = event.turnIndex) else d
    })
    is CoordinationEvent.InboxDelivered -> plan.advanceInbox(event.attemptId, event.turnIndex, DeliveryState.QUEUED, DeliveryState.DELIVERED)
    is CoordinationEvent.InboxAnswered -> plan.advanceInbox(event.attemptId, event.turnIndex, DeliveryState.DELIVERED, DeliveryState.ANSWERED)
    is CoordinationEvent.HandoffRecorded -> if (plan.coordination.any { it.id == event.record.id }) plan
        else plan.copy(coordination = plan.coordination + event.record)
    is CoordinationEvent.HandoffSubmitted -> {
        val existing = plan.coordination.firstOrNull { it.id == event.record.id }
        if (existing != null && existing.reply != event.record.reply) throw HandoffConflict()
        reduce(plan, CoordinationEvent.HandoffRecorded(event.record))
    }
    is CoordinationEvent.ScheduledDeliveryRequested -> plan.deliverScheduled(event.rule, event.parent)
    is CoordinationEvent.HandoffStatusRecorded -> plan.copy(coordination = plan.coordination.map {
        if (it.id == event.id) it.copy(status = event.status, nextStep = event.text) else it
    })
    is CoordinationEvent.DecisionRecorded -> plan.recordDecision(event)
    is CoordinationEvent.DeliveryRequested -> plan.requestDelivery(event)
    is CoordinationEvent.LateDeliveryObserved -> plan.rerouteLateDelivery(event)
    is CoordinationEvent.QuestionsObserved -> plan.copy(messageEvents = (plan.messageEvents + event.events).distinctBy { it.id }, scheduleQuestionIds = event.ids)
}

private fun Plan.advanceInbox(attempt: String, turn: Int, from: DeliveryState, to: DeliveryState): Plan =
    copy(deliveries = deliveries.map { if (it.attemptId == attempt && it.turnIndex == turn && it.state == from) it.copy(state = to) else it })

private fun Plan.recordDecision(event: CoordinationEvent.DecisionRecorded): Plan {
    val old = coordination.first { it.id == event.id }
    val stale = if (event.replacesResult) old.decision?.actions.orEmpty().indices.map { "${old.actionOrigin()}-action-$it" }.toSet() else emptySet()
    return copy(coordination = coordination.map { if (it.id == event.id) it.copy(decision = event.decision, activity = event.activity,
        actionRevision = it.actionRevision + if (event.replacesResult) 1 else 0) else it },
        deliveries = deliveries.map { if (it.id in stale && it.state == DeliveryState.QUEUED) it.copy(state = DeliveryState.CANCELLED) else it })
}

private fun Plan.requestDelivery(event: CoordinationEvent.DeliveryRequested): Plan {
    event.retryCheckpoint?.let { requireRetryCheckpoint(it) }
    val delivery = event.delivery
    deliveries.firstOrNull { it.id == delivery.id }?.let { previous ->
        require(previous.sourceSessionId == delivery.sourceSessionId && previous.text == delivery.text && previous.replyTo == delivery.replyTo)
        return this
    }
    val stage = milestones.firstOrNull { it.id == delivery.targetStageId } ?: error("Этап не найден")
    val attempt = stage.attempts.lastOrNull()
    val complete = stage.completed || (attempt?.error?.requiresUser != true &&
        attempt?.phase in listOf(AttemptPhase.VERIFYING, AttemptPhase.INTEGRATING, AttemptPhase.COMPLETE))
    val followup = stage.followup(delivery)
    val next = copy(deliveries = deliveries + delivery.copy(targetStageId = if (complete) followup.id else stage.id), issue = null,
        phase = if (complete) ExecutionPhase.EXECUTING else phase,
        finalAttempt = if (complete) null else finalAttempt,
        finalAttemptHistory = finalAttemptHistory + if (complete) listOfNotNull(finalAttempt) else emptyList(),
        workspace = if (complete && sharedWorkspace) workspace?.copy(applied = false) else workspace,
        milestones = milestones.map { m -> if (m.id != stage.id || complete) m else m.copy(attempts = m.attempts.map {
            if (it.error?.kind == IssueKind.VERIFICATION) it.retryAfterUserAction().copy(waitingForEvent = null)
            else it.copy(error = null, waitingForUser = null, waitingForEvent = null)
        }) } + if (complete) listOf(followup) else emptyList(),
        tree = if (complete) followupTree(followup) else tree)
    return if (complete && milestones.any { it.isFinalization }) next.withFinalization { event.finalizationId } else next
}

private fun Plan.rerouteLateDelivery(event: CoordinationEvent.LateDeliveryObserved): Plan {
    val delivery = deliveries.firstOrNull { it.id == event.id && it.state == DeliveryState.QUEUED } ?: return this
    val completed = milestones.firstOrNull { it.id == delivery.targetStageId && it.completed } ?: return this
    val followup = completed.followup(delivery)
    val next = copy(deliveries = deliveries.map { if (it.id == delivery.id) it.copy(targetStageId = followup.id) else it },
        milestones = milestones + followup, phase = ExecutionPhase.EXECUTING, status = PlanStatus.RUNNING,
        finalAttempt = null, finalAttemptHistory = finalAttemptHistory + listOfNotNull(finalAttempt),
        workspace = workspace?.copy(applied = false), tree = followupTree(followup))
    return if (milestones.any { it.isFinalization }) next.withFinalization { event.finalizationId } else next
}

private fun Milestone.followup(delivery: PlanDelivery) = copy(id = "$id-followup-${delivery.id}", displayNumber = null,
    continuationOf = id, description = delivery.text, status = MilestoneStatus.PENDING, attempts = emptyList(), report = "", checkNote = "", dependsOn = listOf(id))

private fun Plan.followupTree(followup: Milestone) = tree.map {
    if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + followup.id) else it
} + DecisionNode(followup.id, followup.title, DecisionKind.STAGE, stageId = followup.id)

/** A replacement decision can reuse only a delivery already consumed by the intended stage. */
fun Plan.appliedCoordinatorDelivery(eventId: String, action: CoordinatorAction): PlanDelivery? = deliveries.firstOrNull {
    (it.id.startsWith("$eventId-action-") || it.id.startsWith("$eventId-revision-")) &&
        (it.targetStageId == action.stageId || milestones.any { task -> task.id == it.targetStageId && task.continuationOf == action.stageId }) &&
        it.text == action.message && it.state in setOf(DeliveryState.DELIVERED, DeliveryState.ANSWERED)
}

sealed interface CoordinatorContinuation {
    data class Return(val decision: StageTurnDecision) : CoordinatorContinuation
    data class Ask(val id: String, val text: String, val report: String) : CoordinatorContinuation
    data class Deliver(val delivery: PlanDelivery) : CoordinatorContinuation
    data object InspectInbox : CoordinatorContinuation
}

/** Read after schedules, commands and routed messages have committed. */
fun Plan.coordinatorContinuation(stageId: String, record: CoordinationRecord, decision: CoordinatorReply): CoordinatorContinuation {
    val reply = record.reply
    val waiting = milestones.first { it.id == stageId }.attempts.lastOrNull()?.waitingForEvent
    if (waiting != null) return CoordinatorContinuation.Return(StageTurnDecision(StageTurnAction.WAIT_EVENT, reply.text, waiting))
    if (reply.kind == StageReplyKind.WAIT && decision.actions.none { it.stageId == stageId })
        return CoordinatorContinuation.Ask("${record.id}-invalid-wait", "Не удалось назначить ожидание: ${decision.reply}", reply.text)
    if (reply.kind != StageReplyKind.RESULT && !decision.toolsApplied && decision.actions.none { it.stageId == stageId })
        return CoordinatorContinuation.Deliver(PlanDelivery("${record.id}-followup", parentSessionId, stageId, decision.reply))
    return CoordinatorContinuation.InspectInbox
}

fun Plan.coordinatedTurn(stageId: String, report: String): StageTurnDecision = StageTurnDecision(
    if (deliveries.any { it.targetStageId == stageId && it.state == DeliveryState.QUEUED }) StageTurnAction.CONTINUE else StageTurnAction.VERIFY, report)

class HandoffConflict : IllegalArgumentException("Результат этого хода уже передан")

fun Plan.canDeliverScheduled(rule: ScheduledMessage): Boolean {
    val task = milestones.firstOrNull { it.id == rule.targetTaskId } ?: return false
    val attempt = task.attempts.lastOrNull()
    return !rule.timeout && intent == ExecutionIntent.RUN && !task.completed && finalAttempt == null && phase != ExecutionPhase.COMPLETE &&
        attempt?.error?.requiresUser != true && attempt?.awaitingPlanner != true && attempt?.coordinationState?.certainlyPending != true &&
        attempt?.phase !in setOf(AttemptPhase.VERIFYING, AttemptPhase.INTEGRATING, AttemptPhase.COMPLETE)
}

private fun Plan.deliverScheduled(rule: ScheduledMessage, parent: String): Plan =
    if (!canDeliverScheduled(rule) || deliveries.any { it.id == rule.deliveryId }) this else copy(
        deliveries = deliveries + PlanDelivery(rule.deliveryId, parent, rule.targetTaskId!!, rule.payload, sourceRunId = rule.runId),
        milestones = milestones.map { task -> if (task.id != rule.targetTaskId) task else task.copy(attempts = task.attempts.map {
            it.copy(waitingForEvent = null)
        }) })
