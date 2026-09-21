package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class PlanningRunProjectionTest {
    private var sequence = 0
    private fun stamp() = PlanningMachine.Stamp("input-${++sequence}", sequence.toLong())
    private fun started(): PlanningMachine.State {
        val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage", description = "Do it")))
        val created = PlanningMachine.reduce(PlanningMachine.initial(plan.id), PlanningMachine.Intent.Create(plan, stamp())).state
        return PlanningMachine.reduce(created, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), stamp())).state
    }

    @Test fun savedRunIntentDoesNotDisableExplicitContinuationAfterRestore() {
        val active = started()
        val restored = PlanningMachine.reduce(active, PlanningMachine.Fact.Restored(stamp())).state
        assertEquals(ExecutionIntent.RUN, restored.plan!!.intent)
        assertEquals(PlanningRunUi(canContinue = true), planningRunUi(restored, null, false))
    }

    @Test fun onlyExactCurrentAdmissionShowsPauseAndPendingGrantDoesNotOfferDuplicateStart() {
        val active = started()
        val ref = active.run!!.ref
        assertEquals(PlanningRunUi(active = true), planningRunUi(active, ref, false))
        assertEquals(PlanningRunUi(), planningRunUi(active, null, false))
        assertEquals(PlanningRunUi(), planningRunUi(active, ref.copy(admissionId = "old"), false))
    }

    @Test fun unknownOutcomeAndPersistenceCannotOfferContinuation() {
        val active = started()
        val unknown = PlanningMachine.reduce(active, PlanningMachine.Fact.OperationUnknown(5, stamp())).state
        assertEquals(PlanningRunUi(requiresRecovery = true), planningRunUi(unknown, active.run!!.ref, false))
        val restored = PlanningMachine.reduce(active, PlanningMachine.Fact.Restored(stamp())).state
        assertEquals(PlanningRunUi(requiresRecovery = true), planningRunUi(restored, null, true))
    }
}
