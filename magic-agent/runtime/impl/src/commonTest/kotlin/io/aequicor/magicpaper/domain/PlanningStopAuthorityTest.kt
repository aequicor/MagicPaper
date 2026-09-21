package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningStopAuthorityTest {
    @Test fun earlierStopCannotConfirmTheNextStopWhileItsReconciliationIsStillPending() = runTest {
        val storage = InMemoryKeyValueStore()
        val checkpoints = JsonPlanningRepository(storage, Json)
        checkpoints.save(Plan("plan", "project", "Goal", runId = "old-run", milestones = listOf(
            Milestone("stage", "Stage", attempts = listOf(StageAttempt("attempt", "worker", StageAssignment("p", "m")))))))
        val owner = DefaultPlanningStore(checkpoints, InMemoryEventJournal())
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        var reconciliations = 0
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override suspend fun reconcile(sessionId: String) {
                when(++reconciliations) { 1 -> first.await(); 2 -> second.await() }
            }
        }
        var projected = 0
        val ports = TestPlanningExecutionPorts().also { it.onStoppedCheckpoint = { projected++ } }
        val service = PlanningExecutionService(owner, runtime, null, JsonLlmProfileRepository(storage, Json),
            JsonSettingsRepository(storage, Json), object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = error("No verification during stop")
            }, workspaces = LocalPlanningWorkspace(), scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { ports.chatHooks })
        val oldStop = launch { service.stop("plan") }; runCurrent()
        val oldId = checkNotNull(owner.machineStates.value["plan"]?.stopId)
        val currentStop = launch { service.stop("plan") }; runCurrent()
        val currentId = checkNotNull(owner.machineStates.value["plan"]?.stopId)
        assertNotEquals(oldId, currentId)
        first.complete(Unit); oldStop.join(); runCurrent()
        assertTrue(checkNotNull(owner.planFor("plan")).stopping)
        assertEquals(currentId, owner.machineStates.value["plan"]?.stopId)
        assertEquals(0, projected)
        second.complete(Unit); currentStop.join(); runCurrent()
        assertFalse(checkNotNull(owner.planFor("plan")).stopping)
        assertEquals(1, projected)
        service.shutdown()
    }

    @Test fun stoppingAFinishedPlanChangesNothingAndDoesNotFail() = runTest {
        // Archiving or deleting a session stops every plan it owns, finished or not, and waits for each stop.
        val storage = InMemoryKeyValueStore()
        val checkpoints = JsonPlanningRepository(storage, Json)
        checkpoints.save(Plan("plan", "project", "Goal", runId = "run", phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE,
            milestones = listOf(Milestone("stage", "Stage", status = MilestoneStatus.DONE))))
        val owner = DefaultPlanningStore(checkpoints, InMemoryEventJournal())
        val runtime = object : CodingRuntime by NoopCodingRuntime { override val supported = true }
        var projected = 0
        val ports = TestPlanningExecutionPorts().also { it.onStoppedCheckpoint = { projected++ } }
        val service = PlanningExecutionService(owner, runtime, null, JsonLlmProfileRepository(storage, Json),
            JsonSettingsRepository(storage, Json), object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = error("No verification during stop")
            }, workspaces = LocalPlanningWorkspace(), scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { ports.chatHooks })
        val finished = checkNotNull(owner.planFor("plan"))
        service.stopAndJoin("plan"); runCurrent()
        assertEquals(finished, owner.planFor("plan"))
        assertEquals(PlanStatus.DONE, checkNotNull(owner.planFor("plan")).status)
        assertEquals(0, projected)
        service.shutdown()
    }
}
