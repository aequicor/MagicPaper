package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningRefinementAuthorityTest {
    private val original = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage")))
    private fun stamp(id: String) = PlanningMachine.Stamp(id, 10)
    private fun created() = PlanningMachine.reduce(PlanningMachine.initial(original.id),
        PlanningMachine.Intent.Create(original, stamp("create"))).state
    private fun begin(id: String) = PlanningMachine.Intent.BeginRefinement(PlanningMessage("message", "user", "Refine"),
        null, false, null, SearchProvider.AUTO, stamp(id))
    private fun output(state: PlanningMachine.State) = RefinementResult(PlanSpecification.from(state.plan!!),
        PlanningMessage("reply", "assistant", "Answer"), PlanningStep.REVIEW)

    @Test fun repeatedMessageGetsFreshAuthorityAndOldCompletionCannotClearIt() {
        val first = PlanningMachine.reduce(created(), begin("first")).state
        val second = PlanningMachine.reduce(first, begin("second")).state
        assertEquals(first.plan!!.dialogue, second.plan!!.dialogue)
        assertNotEquals(first.refinement!!.ref, second.refinement!!.ref)
        val stale = PlanningMachine.reduce(second, PlanningMachine.Fact.RefinementCompleted(first.refinement!!.ref,
            output(first), stamp("old-completion")))
        assertNotNull(stale.rejection)
        assertEquals(second, stale.state)
        val current = PlanningMachine.reduce(second, PlanningMachine.Fact.RefinementCompleted(second.refinement!!.ref,
            output(second), stamp("current-completion")))
        assertNull(current.rejection)
        assertNull(current.state.refinement)
        assertEquals("", current.state.plan!!.requestId)
        assertTrue(current.effects.isEmpty())
        assertNull(current.state.run)
    }

    @Test fun lateCancellationCannotDiscardReplacementAndCurrentCancellationPreservesInput() {
        val first = PlanningMachine.reduce(created(), begin("first")).state
        val second = PlanningMachine.reduce(first, begin("second")).state
        assertEquals(second, PlanningMachine.reduce(second,
            PlanningMachine.Intent.CancelRefinement(first.refinement!!.ref, stamp("old-cancel"))).state)
        val cancelled = PlanningMachine.reduce(second,
            PlanningMachine.Intent.CancelRefinement(second.refinement!!.ref, stamp("cancel"))).state
        assertNull(cancelled.refinement)
        assertEquals(second.plan!!.dialogue, cancelled.plan!!.dialogue)
        assertEquals("", cancelled.plan!!.pendingRequest)
    }

    @Test fun concurrentSpecificationEditRejectsReplyWithoutErasingPendingRequest() {
        val started = PlanningMachine.reduce(created(), begin("first")).state
        val plan = started.plan!!
        val edited = PlanningMachine.reduce(started, PlanningMachine.Intent.Edit(plan.revision,
            plan.copy(goal = "New goal"), stamp("edit"))).state
        val response = PlanningMachine.reduce(edited, PlanningMachine.Fact.RefinementCompleted(started.refinement!!.ref,
            output(started), stamp("completion")))
        assertNotNull(response.rejection)
        assertEquals(edited, response.state)
        assertEquals("message", response.state.plan!!.requestId)
    }

    @Test fun acceptedSerializedInputsRebuildCapturedRequestAndCompletionWithoutExecution() {
        val create = PlanningMachine.Intent.Create(original, stamp("create"))
        val start = begin("first")
        val active = PlanningMachine.reduce(PlanningMachine.reduce(PlanningMachine.initial(original.id), create).state, start).state
        val finish = PlanningMachine.Fact.RefinementCompleted(active.refinement!!.ref, output(active), stamp("finish"))
        val inputs: List<PlanningMachine.Input> = listOf(create, start, finish)
        val serialized = inputs.map { Json.encodeToString(PlanningMachine.Input.serializer(), it) }
        val replayed = serialized.fold(PlanningMachine.initial(original.id)) { state, input ->
            val result = PlanningMachine.reduce(state, Json.decodeFromString(PlanningMachine.Input.serializer(), input))
            assertNull(result.rejection)
            assertTrue(result.effects.isEmpty())
            result.state
        }
        assertEquals(PlanningMachine.reduce(active, finish).state, replayed)
        assertNull(replayed.run)
    }
}
