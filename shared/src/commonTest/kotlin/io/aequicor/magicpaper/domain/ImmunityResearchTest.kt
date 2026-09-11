package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ImmunityResearchTest {
    @Test fun stoppingActiveImmunityWaitsForRuntimeConfirmationAndLeavesParentUntouched() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val id = f.root.organismId!!
        val original = f.store.get(id)
        val running = f.store.beginRun(id, original.immunityId!!)
        val stopping = f.store.requestUserStop(id, original.immunityId!!, "stop-active-immunity", archive = false)
        assertEquals(SessionObservedState.STOPPING, stopping.sessions.getValue(original.immunityId!!).observed)
        assertEquals(original.sessions.getValue(f.root.id), stopping.sessions.getValue(f.root.id))
        f.store.observe(id, original.immunityId!!, running.generation, SessionObservedState.STOPPED)
        assertEquals(SessionObservedState.STOPPED, f.store.get(id).sessions.getValue(original.immunityId!!).observed)
        val parentStopping = f.store.requestUserStop(id, f.root.id, "stop-pending-parent", archive = false)
        assertEquals(SessionObservedState.STOPPING, parentStopping.sessions.getValue(f.root.id).observed)
    }

    @Test fun passiveImmunityCanStopWithoutInventingAnActiveRuntime() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val id = f.root.organismId!!
        val original = f.store.get(id)
        val stopped = f.store.requestUserStop(id, original.immunityId!!, "stop-passive-immunity", archive = false)
        assertEquals(SessionObservedState.STOPPED, stopped.sessions.getValue(original.immunityId!!).observed)
    }

    @Test fun onlyNewRequestedSignalsAreQueuedAndFailuresAndStopsDoNotLoop() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val original = f.store.get(f.root.organismId!!)
        val session = f.projects.sessions(f.project.id).single { it.sessionKind == SessionKind.IMMUNITY }
        val legacy = ImmunitySignal("old", "root", "root", "Old complaint", 1)
        val requested = legacy.copy(id = "new", requestResearch = true)
        val organism = original.copy(signals = listOf(legacy, requested), diagnoses = listOf(
            ImmunityDiagnosis("old", "root", emptyList(), "NO_INTERVENTION", setOf("root"), 1),
            ImmunityDiagnosis("new", "root", emptyList(), "NO_INTERVENTION", setOf("root"), 2)))
        assertEquals(requested, organism.nextImmunityResearch(session, emptyList()))
        assertNull(organism.nextImmunityResearch(session.copy(pendingRun = CodingRunCheckpoint("signal-new", "test", intent = ExecutionIntent.STOP)), emptyList()))
        assertNull(organism.nextImmunityResearch(session, listOf(CodingMessage("immunity-report-new", CodingRole.AGENT, "Failure", createdAt = 3, failed = true))))
        assertNull(organism.copy(stoppedByUser = true).nextImmunityResearch(session, emptyList()))
        assertNull(organism.copy(historyDeletedIds = setOf("root")).nextImmunityResearch(session, emptyList()))
        val stopped = organism.copy(sessions = organism.sessions + (session.id to organism.sessions.getValue(session.id).copy(desired = SessionDesiredState.STOP)))
        assertNull(stopped.nextImmunityResearch(session, emptyList()))
    }
}
