package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningMachineTest {
    private var sequence = 0
    private fun stamp() = PlanningMachine.Stamp("input-${sequence++}", sequence.toLong())
    private val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", description = "Do it")))
    private fun seeded(value: Plan = plan) = PlanningMachine.reduce(PlanningMachine.initial(value.id), PlanningMachine.Intent.Create(value, stamp())).state
    private fun start(state: PlanningMachine.State) = PlanningMachine.reduce(state,
        PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()))

    @Test fun importedRunAndStopHaveNoExecutionOutputs() {
        for(intent in ExecutionIntent.entries) {
            val t = PlanningMachine.reduce(PlanningMachine.initial(plan.id), PlanningMachine.Fact.LegacyImported(
                plan.copy(intent = intent, runId = "saved", stopping = intent == ExecutionIntent.STOP), emptySet(), stamp()))
            assertTrue(t.effects.isEmpty()); assertNull(t.state.run)
        }
    }
    @Test fun onlyExplicitStartIssuesExactAdmissionAndDuplicateStartRejects() {
        val result = start(seeded())
        val output = assertIs<PlanningMachine.Effect.RunRequested>(result.effects.single())
        assertEquals(result.state.run?.ref, output.ref)
        assertEquals("plan", output.ref.planId); assertEquals("run", output.ref.runId); assertEquals(1, output.ref.generation)
        assertNotNull(start(result.state).rejection)
    }
    @Test fun replayRestoreNeverReissuesExecution() {
        val active = start(seeded()).state
        val restored = PlanningMachine.reduce(active, PlanningMachine.Fact.Restored(stamp()))
        assertTrue(restored.effects.isEmpty()); assertEquals(PlanningMachine.RunPhase.INTERRUPTED, restored.state.run?.phase)
        val fresh = start(restored.state)
        assertNull(fresh.rejection); assertEquals(2, fresh.state.generation)
        assertEquals("run", fresh.state.plan?.runId)
    }
    @Test fun unknownOutcomeBlocksStartRetryAndDelete() {
        val active = start(seeded()).state
        val unknown = PlanningMachine.reduce(active, PlanningMachine.Fact.OperationUnknown(9, stamp())).state
        assertNotNull(start(unknown).rejection)
        assertNotNull(PlanningMachine.reduce(unknown, PlanningMachine.Intent.Retry(checkNotNull(unknown.plan), emptyMap(), "run",
            PlanningRulesSettings().snapshot(), stamp())).rejection)
        assertNotNull(PlanningMachine.reduce(unknown, PlanningMachine.Intent.Delete(stamp())).rejection)
        val restored = PlanningMachine.reduce(unknown, PlanningMachine.Fact.Restored(stamp())).state
        assertEquals(PlanningMachine.RunPhase.UNKNOWN, restored.run?.phase)
    }
    @Test fun explicitReconciliationDoesNotAdmitButAllowsNewCommand() {
        val unknown = PlanningMachine.reduce(start(seeded()).state, PlanningMachine.Fact.OperationUnknown(9, stamp())).state
        val reconciled = PlanningMachine.reduce(unknown, PlanningMachine.Fact.EvidenceObserved(emptySet(), stamp()))
        assertTrue(reconciled.effects.isEmpty()); assertEquals(PlanningMachine.RunPhase.INTERRUPTED, reconciled.state.run?.phase)
        assertNull(start(reconciled.state).rejection)
    }
    @Test fun priorAdmissionCannotPublishAfterNewExplicitAdmission() {
        val first = start(seeded()).state
        val paused = PlanningMachine.reduce(first, PlanningMachine.Intent.Pause(stamp())).state
        val second = start(paused).state
        val old = PlanningMachine.reduce(second, PlanningMachine.Fact.WorkspacePrepared(checkNotNull(first.run).ref,
            PlanWorkspace("/source", "/work"), stamp()))
        assertNotNull(old.rejection); assertNull(old.state.plan?.workspace)
    }
    @Test fun editCannotInventExecutionOrAttemptOutcome() {
        val initial = seeded()
        val bad = checkNotNull(initial.plan).copy(intent = ExecutionIntent.RUN)
        assertNotNull(PlanningMachine.reduce(initial, PlanningMachine.Intent.Edit(bad.revision, bad, stamp())).rejection)
        val attempt = StageAttempt("a", "session", StageAssignment("profile", "model"), phase = AttemptPhase.COMPLETE)
        val injection = checkNotNull(initial.plan).copy(milestones = plan.milestones.map { it.copy(attempts = listOf(attempt)) })
        assertNotNull(PlanningMachine.reduce(initial, PlanningMachine.Intent.Edit(injection.revision, injection, stamp())).rejection)
    }
    @Test fun pauseAndStopRevokeStartPermissionWithoutErasingIdentity() {
        val initial = start(seeded()).state
        val paused = PlanningMachine.reduce(initial, PlanningMachine.Intent.Pause(stamp())).state
        assertEquals(initial.run?.ref, paused.run?.ref); assertEquals(PlanningMachine.RunPhase.PAUSED, paused.run?.phase)
        val stopped = PlanningMachine.reduce(paused, PlanningMachine.Intent.Stop(stamp()))
        assertIs<PlanningMachine.Effect.StopRequested>(stopped.effects.single()); assertTrue(checkNotNull(stopped.state.plan).stopping)
        assertNotNull(start(stopped.state).rejection)
    }
    @Test fun unknownPersistenceDominatesEvenOtherwiseValidCommands() {
        val initial = seeded()
        val unknown = PlanningMachine.reduce(initial, PlanningMachine.Fact.PersistenceUnknown(stamp())).state
        for(input in listOf<PlanningMachine.Input>(PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()),
            PlanningMachine.Intent.AssignStage("stage", StageAssignment("p", "m"), stamp()), PlanningMachine.Fact.Restored(stamp()))) {
            assertNotNull(PlanningMachine.reduce(unknown, input).rejection)
        }
    }
    @Test fun appliedRequiresAcceptedFinalEvidence() {
        val active = start(seeded()).state
        val applied = PlanningMachine.reduce(active, PlanningMachine.Fact.Applied(checkNotNull(active.run).ref, PlanWorkspace("/s", "/w"), stamp()))
        assertNotNull(applied.rejection); assertNotEquals(ExecutionPhase.COMPLETE, applied.state.plan?.phase)
    }
    @Test fun livePauseResumeKeepsTheAdmittedRunAndRejectsStaleResume() {
        val active = start(seeded()).state
        val ref = checkNotNull(active.run).ref
        val paused = PlanningMachine.reduce(active, PlanningMachine.Intent.Pause(stamp())).state
        val resumed = PlanningMachine.reduce(paused, PlanningMachine.Intent.Resume(ref, stamp()))
        assertNull(resumed.rejection); assertEquals(ref, resumed.state.run?.ref)
        assertEquals(active.generation, resumed.state.generation)
        val unknown = PlanningMachine.reduce(paused, PlanningMachine.Fact.OperationUnknown(8, stamp())).state
        assertNotNull(PlanningMachine.reduce(unknown, PlanningMachine.Intent.Resume(ref, stamp())).rejection)
    }
    @Test fun olderAttemptCannotBecomeCurrentAfterANewerAttemptWasRecorded() {
        val old = StageAttempt("old", "old-session", StageAssignment("p", "m"))
        val current = old.copy(id = "current", sessionId = "current-session")
        val active = start(seeded(plan.copy(milestones = listOf(plan.milestones.single().copy(attempts = listOf(old, current)))))).state
        val rejected = PlanningMachine.reduce(active, PlanningMachine.Fact.AttemptRecorded(checkNotNull(active.run).ref,
            "stage", old.copy(report = "Late old response"), stamp = stamp()))
        assertNotNull(rejected.rejection); assertEquals(active, rejected.state)
    }
    @Test fun finalAttemptClearRequiresTheExactCurrentCheckpoint() {
        val final = StageAttempt("final", "review-session", StageAssignment("p", "m"), sessionGeneration = 2)
        val active = start(seeded(plan.copy(finalAttempt = final))).state
        val ref = checkNotNull(active.run).ref
        for(stale in listOf(final.copy(id = "old"), final.copy(sessionGeneration = 1), final.copy(turnIndex = final.turnIndex + 1))) {
            val rejected = PlanningMachine.reduce(active, PlanningMachine.Fact.FinalAttemptCleared(ref, stale, stamp()))
            assertNotNull(rejected.rejection); assertEquals(final, rejected.state.plan?.finalAttempt)
        }
        assertNull(PlanningMachine.reduce(active, PlanningMachine.Fact.FinalAttemptCleared(ref, final, stamp())).state.plan?.finalAttempt)
    }

}
