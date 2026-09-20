package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinationStateTest {
    private val attempt = StageAttempt("a", "s", StageAssignment("profile", "model"))

    /** An older attempt that looks handed over: returned, reported, nobody waiting on a person. */
    private val legacy = attempt.copy(coordinationPending = null, awaitingPlanner = true,
        report = "готово", chatTurns = listOf(StageChatTurn(0)))

    @Test fun theThirdValueIsSilenceNotAThirdAnswer() {
        assertEquals(CoordinationState.Pending, attempt.copy(coordinationPending = true).coordinationState)
        assertEquals(CoordinationState.Settled, attempt.copy(coordinationPending = false).coordinationState)
        assertEquals(CoordinationState.Unrecorded, attempt.copy(coordinationPending = null).coordinationState)
    }

    @Test fun theTwoSidesDisagreeAboutSilenceAndThatIsTheContract() {
        // A side that must not act on a guess and a side that must not close too early read the
        // same unrecorded attempt differently. Both are right where they stand.
        val silent = CoordinationState.Unrecorded
        assertFalse(silent.certainlyPending, "Событие передачи по догадке не выпускают")
        assertTrue(silent.possiblyPending, "Запись передачи по догадке не закрывают")

        assertTrue(CoordinationState.Pending.certainlyPending)
        assertTrue(CoordinationState.Pending.possiblyPending)
        assertFalse(CoordinationState.Settled.certainlyPending)
        assertFalse(CoordinationState.Settled.possiblyPending)
    }

    @Test fun aRecordedAnswerIsNeverSecondGuessed() {
        assertTrue(attempt.copy(coordinationPending = true).coordinationOwed)
        // Even an attempt carrying every sign of a handover is settled if it says so.
        assertFalse(legacy.copy(coordinationPending = false).coordinationOwed)
    }

    @Test fun anUnrecordedAttemptIsJudgedByWhatItCarries() {
        assertTrue(legacy.coordinationOwed, "Ход возвращён, отчёт есть, человека не ждут")
        assertFalse(legacy.copy(awaitingPlanner = false).coordinationOwed, "Ход не возвращали")
        assertFalse(legacy.copy(report = "").coordinationOwed, "Отвечать не на что")
        assertFalse(legacy.copy(waitingForUser = "r").coordinationOwed, "Ждут человека, а не планировщика")
    }

    @Test fun anUnaccountedTurnIsNotAHandover() {
        // The activity log must already contain the turn; otherwise the report belongs to an
        // earlier one and the planner was never given this turn to answer.
        val second = legacy.copy(turnIndex = 1, chatTurns = listOf(StageChatTurn(0)))
        assertFalse(second.coordinationOwed)
        assertTrue(second.copy(chatTurns = listOf(StageChatTurn(0), StageChatTurn(1))).coordinationOwed)
        // The first turn has nothing to account for.
        assertTrue(legacy.copy(turnIndex = 0, chatTurns = emptyList()).coordinationOwed)
    }

    @Test fun settlingTheCoordinationTakesTheTurnBackFromThePlanner() {
        val handed = attempt.handedToPlanner()
        assertEquals(CoordinationState.Pending, handed.coordinationState)
        assertTrue(handed.awaitingPlanner)

        val settled = handed.coordinationSettled()
        assertEquals(CoordinationState.Settled, settled.coordinationState)
        assertFalse(settled.awaitingPlanner, "Ход вернулся к исполнителю")
        assertFalse(settled.coordinationOwed)
    }

    @Test fun noProducerEverReturnsAnAttemptToSilence() {
        // Silence means "written before the field existed". An attempt that has spoken cannot
        // become an older attempt again, or its evidence would start deciding for it.
        val produced = listOf(legacy.handedToPlanner(), legacy.coordinationSettled(),
            legacy.awaiting(StageWaiting.Nothing), legacy.awaiting(StageWaiting.User("r")),
            legacy.awaiting(StageWaiting.Event("r")))
        produced.forEach { assertTrue(it.coordinationState != CoordinationState.Unrecorded, "$it") }
    }
}
