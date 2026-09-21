package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlin.test.*

class PlanningStageAuthorityTest {
    private var sequence = 0
    private fun stamp() = PlanningMachine.Stamp("input-${++sequence}", sequence.toLong())
    private fun active(attempt: StageAttempt = attempt()): PlanningMachine.State {
        val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage",
            description = "Verify result", attempts = listOf(attempt))))
        val created = accepted(PlanningMachine.initial(plan.id), PlanningMachine.Intent.Create(plan, stamp()))
        return accepted(created, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()))
    }
    private fun attempt() = StageAttempt("attempt", "worker", StageAssignment("profile", "model"),
        phase = AttemptPhase.EXECUTING, sessionGeneration = 1, path = "/work", engineSessionId = "native-worker",
        mergeEngineSessionId = "native-merge", chatTurns = listOf(StageChatTurn(0, 1)))
    private fun PlanningMachine.State.attempt() = checkNotNull(plan).milestones.single().attempts.single()
    private fun accepted(state: PlanningMachine.State, input: PlanningMachine.Input): PlanningMachine.State {
        val transition = PlanningMachine.reduce(state, input)
        assertNull(transition.rejection, transition.rejection?.reason)
        return transition.state
    }
    private fun mutation(state: PlanningMachine.State, event: StageMutation,
        expected: PlanningMachine.AttemptRef = PlanningMachine.AttemptRef.from(state.attempt())) =
        PlanningMachine.Fact.StageTransitioned(checkNotNull(state.run).ref, "stage", expected, event, stamp = stamp())
    private fun rejected(state: PlanningMachine.State, input: PlanningMachine.Input) {
        val transition = PlanningMachine.reduce(state, input)
        assertNotNull(transition.rejection)
        assertEquals(state, transition.state)
        assertTrue(transition.effects.none { it is PlanningMachine.Effect.StageDecision })
    }

    @Test fun aStageThatDoesNotExistIsRefusedInsteadOfEscapingTheReducer() {
        val state = active()
        val ref = checkNotNull(state.run).ref
        val attempt = state.attempt()
        val expected = PlanningMachine.AttemptRef.from(attempt)
        for (input in listOf<PlanningMachine.Input>(
            PlanningMachine.Fact.StageCreated(ref, "missing", "other-attempt", "other-worker", StageAssignment("profile", "model"), 1, stamp()),
            PlanningMachine.Fact.StageProgressObserved(ref, "missing", expected, StageProgress.from(attempt), stamp()),
            PlanningMachine.Fact.StageTransitioned(ref, "missing", expected, StageEvent.EngineResolved(CodingEngine.PI), null, stamp()),
            PlanningMachine.Fact.VerificationObserved(ref, "missing", expected, true, "ok", stamp()),
            PlanningMachine.Fact.AttemptRecorded(ref, "missing", attempt, stamp = stamp()),
        )) rejected(state, input)
    }

    @Test fun aStageWithoutAnAttemptRefusesWhatNeedsOne() {
        val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", description = "Verify result")))
        val created = accepted(PlanningMachine.initial(plan.id), PlanningMachine.Intent.Create(plan, stamp()))
        val state = accepted(created, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()))
        val ref = checkNotNull(state.run).ref
        // The attempt exists nowhere on the stage, so the references below name something that was never created.
        val absent = attempt()
        val expected = PlanningMachine.AttemptRef.from(absent)
        for (input in listOf<PlanningMachine.Input>(
            PlanningMachine.Fact.StageProgressObserved(ref, "stage", expected, StageProgress.from(absent), stamp()),
            PlanningMachine.Fact.StageTransitioned(ref, "stage", expected, StageEvent.EngineResolved(CodingEngine.PI), null, stamp()),
            PlanningMachine.Fact.VerificationObserved(ref, "stage", expected, true, "ok", stamp()),
        )) rejected(state, input)
    }

    @Test fun progressCannotRebindEitherNativeSessionIdentity() {
        val state = active()
        val progress = StageProgress.from(state.attempt())
        for (changed in listOf(progress.copy(engineSessionId = "different-worker"),
            progress.copy(mergeEngineSessionId = "different-merge"))) {
            rejected(state, PlanningMachine.Fact.StageProgressObserved(checkNotNull(state.run).ref, "stage",
                PlanningMachine.AttemptRef.from(state.attempt()), changed, stamp()))
        }
        val updated = accepted(state, PlanningMachine.Fact.StageProgressObserved(checkNotNull(state.run).ref, "stage",
            PlanningMachine.AttemptRef.from(state.attempt()), progress.copy(report = "New visible output"), stamp()))
        assertEquals("New visible output", updated.attempt().report)
        assertEquals("native-worker", updated.attempt().engineSessionId)
    }

    @Test fun lateProgressCannotEraseInterruptedExternalToolEvidence() {
        val state = active(attempt().copy(pendingTool = "publish", pendingToolExternal = true,
            steps = listOf(CodingStep(CodingStepKind.TOOL, "Publish", callId = "tool", running = true))))
        val prior = state.attempt()
        val interrupted = accepted(state, mutation(state, StageEvent.Interrupted(prior, waiting = false, at = 10)))
        assertTrue(interrupted.attempt().interrupted)
        val unsafeProgress = StageProgress.from(prior).copy(pendingTool = "", pendingToolExternal = false)
        for (expected in listOf(PlanningMachine.AttemptRef.from(prior), PlanningMachine.AttemptRef.from(interrupted.attempt()))) {
            rejected(interrupted, PlanningMachine.Fact.StageProgressObserved(checkNotNull(interrupted.run).ref,
                "stage", expected, unsafeProgress, stamp()))
        }
        assertTrue(interrupted.attempt().pendingToolExternal)
        assertTrue(interrupted.attempt().steps.none { it.running })
    }

    @Test fun unknownStoppedRestoredAndPausedRunsCannotIssueNewWorkerEffects() {
        val initial = active()
        val stopped = accepted(accepted(initial, PlanningMachine.Intent.Stop(stamp())), PlanningMachine.Fact.StopConfirmed(stamp()))
        val states = listOf(
            accepted(initial, PlanningMachine.Fact.OperationUnknown(9, stamp())),
            stopped,
            accepted(initial, PlanningMachine.Fact.Restored(stamp())),
            accepted(initial, PlanningMachine.Intent.Pause(stamp())),
        )
        for (state in states) for (event in listOf<StageMutation>(StageEvent.UserAnswered, StageEvent.EventFired,
            StageEvent.PlannerDecided(StageTurnDecision(StageTurnAction.CONTINUE, "Continue")))) {
            rejected(state, mutation(state, event))
        }
    }

    @Test fun previousMergeOutcomeCannotCompleteANewerConflictRetryWithTheSamePhase() {
        val initial = active(attempt().copy(phase = AttemptPhase.INTEGRATING,
            mergePhase = AttemptPhase.EXECUTING, mergeRetries = 1))
        val previous = PlanningMachine.AttemptRef.from(initial.attempt())
        val failed = accepted(initial, mutation(initial, StageEvent.MergeFinished(false)))
        val retried = accepted(failed, mutation(failed, StageEvent.ConflictRequested("/merge", retryLimit = null)))
        val running = accepted(retried, mutation(retried, StageEvent.ConflictStarted))
        assertEquals(initial.attempt().phase, running.attempt().phase)
        assertEquals(initial.attempt().mergePhase, running.attempt().mergePhase)
        assertEquals(2, running.attempt().mergeRetries)
        rejected(running, mutation(running, StageEvent.MergeFinished(true), previous))
        val settled = accepted(running, mutation(running, StageEvent.MergeFinished(true)))
        assertEquals(2, settled.attempt().mergeRetries)
    }
}
