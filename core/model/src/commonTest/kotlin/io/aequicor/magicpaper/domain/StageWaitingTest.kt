package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageWaitingTest {
    private val attempt = StageAttempt("a", "s", StageAssignment("profile", "model"))

    @Test fun oneDecisionProducesOneWait() {
        assertEquals(StageWaiting.User("r"), StageWaiting.of(StageTurnAction.WAIT, "r"))
        assertEquals(StageWaiting.Event("r"), StageWaiting.of(StageTurnAction.WAIT_EVENT, "r"))
        assertEquals(StageWaiting.Nothing, StageWaiting.of(StageTurnAction.VERIFY, "r"))
        assertEquals(StageWaiting.Nothing, StageWaiting.of(StageTurnAction.CONTINUE, "r"))
    }

    @Test fun aWaitWithoutARequestIdStillBlocksTheAttempt() {
        // Older plans recorded a wait with no request id. Degrading it into "waiting for
        // nothing" would let the attempt continue without the answer it was stopped for.
        val waiting = StageWaiting.of(StageTurnAction.WAIT, null)
        assertEquals(StageWaiting.User(StageWaiting.LEGACY_REQUEST), waiting)
        assertTrue(waiting.awaitsPlanner)
    }

    @Test fun theAttemptCannotWaitForTwoThingsAtOnce() {
        StageTurnAction.entries.forEach { action ->
            val applied = attempt.awaiting(StageWaiting.of(action, "r"))
            assertFalse(applied.waitingForUser != null && applied.waitingForEvent != null, action.name)
            assertEquals(action == StageTurnAction.WAIT, applied.awaitingPlanner, action.name)
            assertEquals(false, applied.coordinationPending, action.name)
            assertEquals(StageWaiting.of(action, "r"), applied.waiting, action.name)
        }
    }

    @Test fun anEventWithoutARequestIdRecordsNoWait() {
        // The engine did not identify the event; nothing can arrive to release the attempt,
        // so the attempt must not be left looking blocked.
        val applied = attempt.awaiting(StageWaiting.of(StageTurnAction.WAIT_EVENT, null))
        assertNull(applied.waitingForEvent)
        assertEquals(StageWaiting.Nothing, applied.waiting)
    }

    @Test fun handingTheTurnToThePlannerIsNotAWait() {
        val handed = attempt.handedToPlanner()
        assertTrue(handed.awaitingPlanner)
        assertEquals(true, handed.coordinationPending)
        assertEquals(StageWaiting.Nothing, handed.waiting)
    }

    @Test fun aReplyClearsTheWaitWithoutChangingWhoseTurnItIs() {
        val waiting = attempt.awaiting(StageWaiting.User("r")).copy(error = PlanningIssue(IssueKind.TRANSIENT, "сбой"))
        val replied = waiting.replied()
        assertEquals(StageWaiting.Nothing, replied.waiting)
        assertNull(replied.error)
        assertEquals(waiting.awaitingPlanner, replied.awaitingPlanner)
    }
}
