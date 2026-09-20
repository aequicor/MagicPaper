package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StageResumptionTest {
    private fun attempt(
        phase: AttemptPhase = AttemptPhase.PREPARED,
        interrupted: Boolean = false,
        pendingTool: String = "",
        pendingToolExternal: Boolean = false,
    ) = StageAttempt("a", "s", StageAssignment("profile", "model"), phase = phase,
        interrupted = interrupted, pendingTool = pendingTool, pendingToolExternal = pendingToolExternal)

    @Test fun independentJournalUncertaintyOutranksEveryCheckpointPhase() {
        AttemptPhase.entries.forEach { phase ->
            assertIs<StageResumption.UnknownOutcome>(attempt(phase).resumption(journalUnsettled = true))
            assertEquals(attempt(phase).resumption, attempt(phase).resumption(journalUnsettled = false))
        }
    }

    @Test fun anUnconfirmedExternalCommandOutranksEverything() {
        // Resuming would repeat an effect whose result nobody knows, so this must win over
        // an interruption and over a phase that would otherwise be runnable.
        val stopped = attempt(AttemptPhase.EXECUTING, interrupted = true, pendingTool = "shell.exec", pendingToolExternal = true)
        assertEquals(StageResumption.UnknownOutcome("shell.exec"), stopped.resumption)
    }

    @Test fun anExternalCommandWithoutANameIsNotAnUnknownOutcome() {
        // Nothing identifies the command, so there is no effect to reconcile; the attempt
        // resumes on its recorded checkpoint as usual.
        val nameless = attempt(AttemptPhase.EXECUTING, interrupted = true, pendingToolExternal = true)
        assertEquals(StageResumption.Interrupted(AttemptPhase.EXECUTING), nameless.resumption)
    }

    @Test fun anInterruptionCarriesItsCheckpointAndIsNotLiveness() {
        AttemptPhase.entries.forEach {
            assertEquals(StageResumption.Interrupted(it), attempt(it, interrupted = true).resumption, it.name)
        }
    }

    @Test fun onlyExecutionPhasesMayTakeAnotherTurn() {
        AttemptPhase.entries.forEach {
            val resumption = attempt(it).resumption
            if (it in StageResumption.RUNNABLE_PHASES) {
                assertEquals(StageResumption.Runnable(it), resumption, it.name)
                assertTrue(attempt(it).mayRun, it.name)
            } else {
                assertIs<StageResumption.Settled>(resumption, it.name)
                assertFalse(attempt(it).mayRun, it.name)
            }
        }
    }

    @Test fun verificationAndMergePhasesAreSettledNotRunnable() {
        // Verification, integration and completion belong to their own owners; an attempt in
        // one of them must not be restarted by the execution loop.
        listOf(AttemptPhase.VERIFYING, AttemptPhase.INTEGRATING, AttemptPhase.COMPLETE).forEach {
            assertEquals(StageResumption.Settled(it), attempt(it).resumption, it.name)
        }
    }
}
