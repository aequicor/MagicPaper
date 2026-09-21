package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningRunAuthorityTest {
    private var seq = 0
    private fun stamp() = PlanningMachine.Stamp("command-${++seq}", seq.toLong())
    private val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", description = "Verified result")))
    private fun initial(value: Plan = plan) = PlanningMachine.reduce(PlanningMachine.initial(value.id),
        PlanningMachine.Fact.LegacyImported(value, emptySet(), stamp())).state
    private fun accepted(state: PlanningMachine.State, input: PlanningMachine.Input) = PlanningMachine.reduce(state, input).let {
        assertNull(it.rejection); it.state
    }
    private fun start(state: PlanningMachine.State) = accepted(state, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp()))

    @Test fun oldStopCannotConfirmOrInvalidateANewerStopAfterAnotherRun() {
        val first = start(initial())
        val stopping = accepted(first, PlanningMachine.Intent.Stop(stamp()))
        val stopped = accepted(stopping, PlanningMachine.Fact.StopConfirmed(stamp(), stopping.stopId))
        val current = accepted(start(stopped), PlanningMachine.Intent.Stop(stamp()))
        assertNotEquals(stopping.stopId, current.stopId)
        for(input in listOf(PlanningMachine.Fact.StopConfirmed(stamp(), stopping.stopId), PlanningMachine.Fact.StopUnknown(stamp(), stopping.stopId))) {
            val rejected = PlanningMachine.reduce(current, input)
            assertNotNull(rejected.rejection); assertEquals(current, rejected.state)
        }
        assertFalse(checkNotNull(accepted(current, PlanningMachine.Fact.StopConfirmed(stamp(), current.stopId)).plan).stopping)
    }
    @Test fun pauseCannotConvertUnknownStopIntoPermissionToRun() {
        val stopping = accepted(start(initial()), PlanningMachine.Intent.Stop(stamp()))
        val unknown = accepted(stopping, PlanningMachine.Fact.StopUnknown(stamp(), stopping.stopId))
        assertNotNull(PlanningMachine.reduce(unknown, PlanningMachine.Intent.Pause(stamp())).rejection)
        assertNotNull(PlanningMachine.reduce(unknown, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp())).rejection)
    }
    @Test fun genericPhaseFactCannotInventCompletion() {
        val active = start(initial())
        val rejected = PlanningMachine.reduce(active, PlanningMachine.Fact.PhaseObserved(checkNotNull(active.run).ref, ExecutionPhase.COMPLETE, stamp()))
        assertNotNull(rejected.rejection); assertEquals(active, rejected.state)
    }
    @Test fun appliedRequiresCompletedStagesAndActualResultInTheSameWorkspace() {
        val workspace = PlanWorkspace("/root", "/root/integration", "base", git = true)
        val criteria = plan.acceptanceCriteria()
        val final = StageAttempt("final", "review", StageAssignment("p", "m"), phase = AttemptPhase.COMPLETE,
            acceptanceRecord = AcceptanceRecord("run", "final", "snapshot", criteria,
                criteria.map { AcceptanceFinding(it.id, CheckStatus.PASS, it.description, "Checked") }, status = AcceptanceStatus.ACCEPTED))
        val complete = plan.copy(runId = "run", workspace = workspace, finalAttempt = final,
            milestones = plan.milestones.map { it.copy(status = MilestoneStatus.DONE) })
        for((candidate, result) in listOf(
            complete to workspace,
            complete to workspace.copy(root = "/foreign", applied = true),
            complete.copy(milestones = plan.milestones) to workspace.copy(applied = true),
        )) {
            val active = start(initial(candidate))
            assertNotNull(PlanningMachine.reduce(active, PlanningMachine.Fact.Applied(checkNotNull(active.run).ref, result, stamp())).rejection)
        }
        val active = start(initial(complete))
        assertEquals(ExecutionPhase.COMPLETE, accepted(active,
            PlanningMachine.Fact.Applied(checkNotNull(active.run).ref, workspace.copy(applied = true), stamp())).plan?.phase)
    }
}
