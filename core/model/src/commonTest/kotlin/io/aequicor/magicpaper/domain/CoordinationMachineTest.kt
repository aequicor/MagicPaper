package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlin.test.*

class CoordinationMachineTest {
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)
    private val stage = Milestone("stage", "Stage", attempts = listOf(attempt))
    private val plan = Plan("plan", "project", "Goal", parentSessionId = "parent", runId = "run", milestones = listOf(stage))
    private fun Plan.on(event: CoordinationEvent) = reduce(this, event)
    private fun delivery(id: String, state: DeliveryState = DeliveryState.QUEUED) =
        PlanDelivery(id, "parent", stage.id, "Do work", state = state)

    @Test fun inboxPreparationNeverClaimsDeliveryAndReceiptsBelongToAnExactTurn() {
        val initial = plan.copy(deliveries = listOf(delivery("a"), delivery("b", DeliveryState.CANCELLED)))
        val prepared = initial.on(CoordinationEvent.InboxPrepared(stage.id, attempt.id, 2))
        assertEquals(DeliveryState.QUEUED, prepared.deliveries.first().state)
        assertEquals(attempt.id, prepared.deliveries.first().attemptId)
        assertEquals(prepared, prepared.on(CoordinationEvent.InboxDelivered(attempt.id, 1)))
        val delivered = prepared.on(CoordinationEvent.InboxDelivered(attempt.id, 2))
        val answered = delivered.on(CoordinationEvent.InboxAnswered(attempt.id, 2))
        assertEquals(listOf(DeliveryState.ANSWERED, DeliveryState.CANCELLED), answered.deliveries.map { it.state })
        assertEquals(answered, answered.on(CoordinationEvent.InboxPrepared(stage.id, attempt.id, 3)))
    }

    @Test fun replacingAnInvalidDecisionCancelsOnlyUnconsumedActionsAndRevisesTheirIdentity() {
        val eventId = "${attempt.id}-turn-0"
        val old = CoordinationRecord(eventId, stage.id, StageReply(StageReplyKind.RESULT, "done"),
            decision = CoordinatorReply("old", actions = List(3) { CoordinatorAction(stage.id, "work $it") }))
        val initial = plan.copy(coordination = listOf(old), deliveries = listOf(
            delivery("$eventId-action-0"), delivery("$eventId-action-1", DeliveryState.DELIVERED),
            delivery("$eventId-action-2", DeliveryState.ANSWERED), delivery("unrelated")))
        val next = initial.on(CoordinationEvent.DecisionRecorded(eventId, CoordinatorReply("review", resultAction = CoordinatorResultAction.VERIFY), emptyList(), true))
        assertEquals("$eventId-revision-1", next.coordination.single().actionOrigin())
        assertEquals(listOf(DeliveryState.CANCELLED, DeliveryState.DELIVERED, DeliveryState.ANSWERED, DeliveryState.QUEUED), next.deliveries.map { it.state })
        val unchangedOrigin = initial.on(CoordinationEvent.DecisionRecorded(eventId, CoordinatorReply("fixed schedule"), emptyList(), false))
        assertEquals(initial.deliveries, unchangedOrigin.deliveries)
        assertEquals(0, unchangedOrigin.coordination.single().actionRevision)
    }

    @Test fun duplicateHandoffKeepsTheStoredNativeReceiptAndDecision() {
        val stored = CoordinationRecord("handoff", stage.id, StageReply(StageReplyKind.RESULT, "native report"),
            decision = CoordinatorReply("saved"), toolCallId = "tool")
        val initial = plan.copy(coordination = listOf(stored))
        assertEquals(initial, initial.on(CoordinationEvent.HandoffRecorded(stored.copy(reply = StageReply(StageReplyKind.BLOCKED, "fallback")))))
    }

    @Test fun newInstructionDuringVerificationCreatesOneContinuationAndPreservesTheAcceptedAttempt() {
        val accepted = attempt.copy(phase = AttemptPhase.VERIFYING, resultCommit = "commit")
        val finished = plan.copy(milestones = listOf(stage.copy(attempts = listOf(accepted))),
            finalAttempt = attempt.copy(id = "final"), workspace = PlanWorkspace("/root", "/work", applied = true), sharedWorkspace = true)
        val event = CoordinationEvent.DeliveryRequested(delivery("instruction"), "finalization")
        val next = finished.on(event)
        assertEquals(accepted, next.milestones.first().attempts.single())
        assertEquals("stage-followup-instruction", next.deliveries.single().targetStageId)
        assertEquals(listOf(stage.id), next.milestones.last().dependsOn)
        assertEquals(stage.id, next.milestones.last().continuationOf)
        assertNull(next.finalAttempt)
        assertEquals("final", next.finalAttemptHistory.single().id)
        assertEquals(false, next.workspace?.applied)
        assertEquals(next, next.on(event.copy(finalizationId = "new")))
        assertFailsWith<IllegalArgumentException> { next.on(event.copy(delivery = delivery("instruction").copy(text = "different"))) }
    }

    @Test fun aLateDeliveryIsReroutedOnceAfterTheStageCompletes() {
        val completed = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.DONE)), deliveries = listOf(delivery("late")))
        val next = completed.on(CoordinationEvent.LateDeliveryObserved("late", "finalization"))
        assertEquals(2, next.milestones.size)
        assertEquals("stage-followup-late", next.deliveries.single().targetStageId)
        assertEquals(next, next.on(CoordinationEvent.LateDeliveryObserved("late", "different")))
    }

    @Test fun eventWaitingAndMissingWaitRegistrationTakePrecedenceOverWorkerContinuation() {
        val record = CoordinationRecord("turn", stage.id, StageReply(StageReplyKind.WAIT, "waiting"))
        val decision = CoordinatorReply("wait for a result")
        val waiting = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(waitingForEvent = "rule")))))
        val scheduled = assertIs<CoordinatorContinuation.Return>(waiting.coordinatorContinuation(stage.id, record, decision))
        assertEquals(StageTurnAction.WAIT_EVENT, scheduled.decision.action)
        assertEquals("rule", scheduled.decision.requestId)
        assertIs<CoordinatorContinuation.Ask>(plan.coordinatorContinuation(stage.id, record, decision))
        val blocked = record.copy(reply = StageReply(StageReplyKind.BLOCKED, "blocked"))
        assertIs<CoordinatorContinuation.Deliver>(plan.coordinatorContinuation(stage.id, blocked, decision))
        assertEquals(CoordinatorContinuation.InspectInbox, plan.coordinatorContinuation(stage.id, blocked, decision.copy(toolsApplied = true)))
        assertEquals(CoordinatorContinuation.InspectInbox, plan.coordinatorContinuation(stage.id, record.copy(reply = StageReply(StageReplyKind.RESULT, "done")), decision))
    }

    @Test fun aRevisedDecisionReusesOnlyConsumedDeliveriesForTheSameAction() {
        val record = "attempt-turn-0"
        val action = CoordinatorAction(stage.id, "Do work")
        val queued = delivery("$record-action-0")
        val other = delivery("other-turn-0-action-1", DeliveryState.ANSWERED)
        val consumed = delivery("$record-revision-1-action-0", DeliveryState.DELIVERED)
        val initial = plan.copy(deliveries = listOf(queued, other, consumed))
        assertEquals(consumed, initial.appliedCoordinatorDelivery(record, action))
        assertNull(initial.appliedCoordinatorDelivery(record, action.copy(message = "changed")))
        assertEquals(StageTurnAction.CONTINUE, initial.coordinatedTurn(stage.id, "report").action)
        assertEquals(StageTurnAction.VERIFY, plan.copy(deliveries = listOf(consumed)).coordinatedTurn(stage.id, "report").action)
    }
}
