package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Pause and Stop refuse a stopped or finished plan now, but a journal written before that holds inputs the
 * reducer accepted then. Replay must take them as they were accepted: a plan that stops loading because
 * one persisted input is refused today is a data loss the refusal was never meant to cause.
 */
class PlanningEarlierStopJournalTest {
    private val rules = PlanningRulesSettings().snapshot()
    private val stages = listOf(Milestone("stage", "Stage", description = "Do it"))
    private fun repository() = JsonPlanningRepository(InMemoryKeyValueStore(), Json)

    private suspend fun append(events: EventJournal, id: String, input: PlanningMachine.Input) {
        val before = events.snapshot(id)
        val detail = PlanInputCommit(input, before.revision.resetEpoch).encode()
        if (before.records.isEmpty()) events.append(id, PLAN_INPUT_OPERATION, input.stamp.at, detail)
        else events.append(before.revision, PLAN_INPUT_OPERATION, input.stamp.at, detail)
    }

    @Test fun aStopRecordedAgainstAFinishedPlanStillLoadsAndNewOnesAreRefused() = runTest {
        val finished = Plan("plan", "project", "Goal", runId = "saved", phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE,
            milestones = stages.map { it.copy(status = MilestoneStatus.DONE) })
        val events = InMemoryEventJournal()
        append(events, finished.id, PlanningMachine.Fact.LegacyImported(finished, emptySet(), PlanningMachine.Stamp("imported", 1)))
        append(events, finished.id, PlanningMachine.Intent.Stop(PlanningMachine.Stamp("stop", 2)))
        append(events, finished.id, PlanningMachine.Fact.StopConfirmed(PlanningMachine.Stamp("stopped", 3), "stop"))
        val owner = DefaultPlanningStore(repository(), events)
        assertNotNull(owner.planFor(finished.id))
        assertNull(owner.failure.value)
        for (input in listOf(PlanningMachine.Intent.Stop(PlanningMachine.Stamp("again", 10)), PlanningMachine.Intent.Pause(PlanningMachine.Stamp("pause", 11)))) {
            assertNotNull(owner.dispatch(finished.id, input).rejection, "$input was accepted by a finished plan")
        }
    }

    @Test fun aPauseRecordedAgainstAStoppedRunStillLoads() = runTest {
        val plan = Plan("plan", "project", "Goal", milestones = stages)
        val events = InMemoryEventJournal()
        append(events, plan.id, PlanningMachine.Intent.Create(plan, PlanningMachine.Stamp("create", 1)))
        append(events, plan.id, PlanningMachine.Intent.Start("run", rules, PlanningMachine.Stamp("start", 2)))
        append(events, plan.id, PlanningMachine.Intent.Stop(PlanningMachine.Stamp("stop", 3)))
        append(events, plan.id, PlanningMachine.Fact.StopConfirmed(PlanningMachine.Stamp("stopped", 4), "stop"))
        append(events, plan.id, PlanningMachine.Intent.Pause(PlanningMachine.Stamp("pause", 5)))
        val owner = DefaultPlanningStore(repository(), events)
        assertNotNull(owner.planFor(plan.id))
        assertNull(owner.failure.value)
    }
}
