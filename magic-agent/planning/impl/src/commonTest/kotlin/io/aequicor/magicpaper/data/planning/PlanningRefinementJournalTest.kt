package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningRefinementJournalTest {
    private val plan = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Stage")))
    private fun stamp(id: String, at: Long = 10) = PlanningMachine.Stamp(id, at)
    private fun begin(id: String) = PlanningMachine.Intent.BeginRefinement(PlanningMessage("request", "user", "Refine"),
        null, false, null, SearchProvider.AUTO, stamp(id))
    private fun repository() = JsonPlanningRepository(InMemoryKeyValueStore(), Json)

    @Test fun oldRefinementWrappersRemainReplayOnlyAndCannotAppendLive() = runTest {
        val events = InMemoryEventJournal()
        val owner = DefaultPlanningStore(repository(), events)
        val current = owner.command(plan.id, PlanningMachine.Intent.Create(plan, stamp("create")))
        val wrappers = listOf<PlanEvent>(PlanRevisionEvent.RequestStarted("request", "text", null),
            PlanRevisionEvent.RequestCleared("request"), PlanRevisionEvent.ProposalApplied(current, current),
            PlanRevisionEvent.ProposalPrepared(current, current, current, PlanningMessage("reply", "assistant", "text")),
            PlanRevisionEvent.RefinementFinished(current, current, null, SearchProvider.AUTO, 10))
        val before = events.read(plan.id)
        wrappers.forEachIndexed { index, event ->
            assertFailsWith<IllegalArgumentException> {
                owner.dispatch(plan.id, PlanningMachine.Intent.Revise(listOf(event), stamp("legacy-$index")))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            owner.dispatch(plan.id, PlanningMachine.Intent.RefineRequested(PlanningMessage("message", "user", "text"), stamp("old-panel")))
        }
        assertEquals(before, events.read(plan.id))
        assertEquals(current, owner.planFor(plan.id))
    }

    @Test fun alreadyAcceptedLegacyRefinementReplaysWithoutNewAppendOrAdmission() = runTest {
        val events = InMemoryEventJournal()
        val inputs: List<PlanningMachine.Input> = listOf(
            PlanningMachine.Intent.Create(plan, stamp("create", 1)),
            PlanningMachine.Intent.Revise(listOf(PlanRevisionEvent.RequestStarted("request", "Refine", null)), stamp("legacy", 2)))
        inputs.forEach { input ->
            val revision = events.snapshot(plan.id).revision
            events.append(revision, PLAN_INPUT_OPERATION, input.stamp.at, PlanInputCommit(input, revision.resetEpoch).encode())
        }
        val before = events.read(plan.id)
        val owner = DefaultPlanningStore(repository(), events)
        val restored = owner.planFor(plan.id)!!
        assertEquals("Refine", restored.pendingRequest)
        assertEquals("request", restored.requestId)
        assertNull(owner.machineStates.value[plan.id]!!.refinement)
        assertNull(owner.currentAdmission(plan.id))
        assertEquals(before, events.read(plan.id))
    }

    @Test fun reopenedCapturedRequestRequiresFreshExactAttemptAndLostAckCommitsOnce() = runTest {
        val memory = InMemoryEventJournal()
        var lose = false
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val saved = memory.append(expected, operation, at, detail)
                if (lose) { lose = false; error("lost acknowledgement") }
                return saved
            }
        }
        val repo = repository()
        val first = DefaultPlanningStore(repo, events)
        first.command(plan.id, PlanningMachine.Intent.Create(plan, stamp("create")))
        val initial = first.dispatch(plan.id, begin("begin-one")).state
        val initialRef = initial.refinement!!.ref
        val reopened = DefaultPlanningStore(repo, events)
        assertEquals(initial.plan, reopened.planFor(plan.id))
        assertNull(reopened.currentAdmission(plan.id))
        val replacement = reopened.dispatch(plan.id, begin("begin-two")).state
        val output = RefinementResult(PlanSpecification.from(replacement.plan!!), PlanningMessage("reply", "assistant", "Done"))
        assertNotNull(reopened.dispatch(plan.id, PlanningMachine.Fact.RefinementCompleted(initialRef, output, stamp("late"))).rejection)
        val completion = PlanningMachine.Fact.RefinementCompleted(replacement.refinement!!.ref, output, stamp("complete"))
        lose = true
        val completed = reopened.command(plan.id, completion)
        reopened.command(plan.id, completion)
        assertEquals(1, memory.read(plan.id).count { PlanInputCommit.from(it)?.input?.stamp?.id == "complete" })
        assertEquals("", completed.requestId)
        val final = DefaultPlanningStore(repo, events)
        assertEquals(completed, final.planFor(plan.id))
        assertNull(final.currentAdmission(plan.id))
    }
}
