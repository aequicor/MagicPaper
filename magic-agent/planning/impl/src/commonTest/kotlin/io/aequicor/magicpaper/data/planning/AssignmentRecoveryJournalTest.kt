package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AssignmentRecoveryJournalTest {
    private val old = StageAssignment("old", "model")
    private val updated = old.copy(profileId = "new")
    private val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", assignment = old)))
    private fun stamp(id: String) = PlanningMachine.Stamp(id, 10)
    private fun recovery(generation: Long = 0) = AssignmentRecovery(AssignmentRecoveryRef(plan.id, plan.projectId, "", generation),
        stages = listOf(StageAssignmentChange("stage", old, updated)))
    private fun repo() = JsonPlanningRepository(InMemoryKeyValueStore(), Json)

    @Test fun semanticRecoveryIsDurableIdempotentAndDoesNotGrantExecution() = runTest {
        val events = InMemoryEventJournal()
        val repository = repo()
        val owner = DefaultPlanningStore(repository, events)
        owner.command(plan.id, PlanningMachine.Intent.Create(plan, stamp("create")))
        val command = PlanningMachine.Intent.RecoverAssignments(recovery(), stamp("repair"))
        val saved = owner.command(plan.id, command)
        assertEquals(updated, saved.milestones.single().assignment)
        assertNull(owner.currentAdmission(plan.id))
        assertNull(owner.machineStates.value[plan.id]!!.run)
        val records = events.read(plan.id)
        assertEquals(saved, owner.command(plan.id, command))
        assertEquals(records, events.read(plan.id))
        val restored = DefaultPlanningStore(repository, events)
        assertEquals(saved, restored.planFor(plan.id))
        assertNull(restored.currentAdmission(plan.id))
    }

    @Test fun replacedBindingAndNewGenerationWinOverDelayedRecovery() = runTest {
        val owner = DefaultPlanningStore(repo(), InMemoryEventJournal())
        owner.command(plan.id, PlanningMachine.Intent.Create(plan, stamp("create")))
        val chosen = old.copy(profileId = "chosen")
        val selected = owner.command(plan.id, PlanningMachine.Intent.AssignStage("stage", chosen, stamp("select")))
        assertEquals(selected, owner.command(plan.id, PlanningMachine.Intent.RecoverAssignments(recovery(), stamp("late"))))
        val active = owner.command(plan.id, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp("start")))
        val oldGeneration = AssignmentRecovery(AssignmentRecoveryRef(plan.id, plan.projectId, active.runId, 0),
            stages = listOf(StageAssignmentChange("stage", chosen, updated)))
        assertEquals(active, owner.command(plan.id, PlanningMachine.Intent.RecoverAssignments(oldGeneration, stamp("late-generation"))))
    }

    @Test fun legacyAcceptedAssignmentRecordReplaysButLiveWrapperIsDenied() = runTest {
        val events = InMemoryEventJournal()
        val candidate = plan.copy(milestones = plan.milestones.map { it.copy(assignment = updated) })
        val create = PlanningMachine.Intent.Create(plan, stamp("create"))
        val created = PlanningMachine.reduce(PlanningMachine.initial(plan.id), create).state.plan!!
        val legacy = PlanningMachine.Intent.Revise(listOf(PlanRevisionEvent.AssignmentsRecovered(created, candidate)), stamp("legacy"))
        listOf<PlanningMachine.Input>(create, legacy).forEach { input ->
            val before = events.snapshot(plan.id)
            events.append(before.revision, PLAN_INPUT_OPERATION, input.stamp.at, PlanInputCommit(input, before.revision.resetEpoch).encode())
        }
        val records = events.read(plan.id)
        val owner = DefaultPlanningStore(repo(), events)
        val restored = owner.planFor(plan.id)!!
        assertEquals(updated, restored.milestones.single().assignment)
        assertFailsWith<IllegalArgumentException> { owner.command(plan.id, legacy.copy(stamp = stamp("new-legacy"))) }
        assertEquals(records, events.read(plan.id))
        assertEquals(restored, owner.planFor(plan.id))
        assertNull(owner.currentAdmission(plan.id))
    }
}
