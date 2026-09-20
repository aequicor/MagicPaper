package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.tools.StoredToolReceipts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningJournalRecoveryTest {
    private class Fixture {
        val sessions = SessionOrganismTestFixture()
        val events = InMemoryEventJournal()
        val store = PlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json), events)
        val reconciled = mutableListOf<String>()
        var terminationFailure = false
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override suspend fun reconcile(sessionId: String) {
                if (terminationFailure) error("termination unproven")
                reconciled += sessionId
            }
        }
        val recovery = PlanningJournalRecovery(store, runtime, sessions.projects, sessions.service)
        suspend fun initialize(complete: Boolean = false) {
            sessions.initialize(CodingInteractionMode.PLANNING)
            store.save(Plan("plan", sessions.project.id, "Goal", parentSessionId = sessions.root.id,
                phase = if (complete) ExecutionPhase.COMPLETE else ExecutionPhase.WAITING,
                issue = PlanningJournalRecovery.uncertainty))
            events.append("plan", PlanJournalOperation.PREPARE_INTENT.wire, 1)
        }
    }

    @Test fun openJournalProjectsIntoQuarantineAndCannotBeSettledByEmptyNativeEvidence() = runTest {
        val f = Fixture(); f.initialize()
        assertEquals(QuarantineRecoveryOutcome.NEEDS_CONFIRMATION, f.recovery.reconcile(f.sessions.root, false))
        val quarantines = f.sessions.store.get(f.sessions.root.organismId!!).unresolvedQuarantines(f.sessions.root.id)
        assertEquals(1, quarantines.size)
        val native = SessionQuarantineRecovery(f.runtime, StoredToolReceipts(f.sessions.storage))
        f.sessions.service.reconcileUnknownOutcomes = native::reconcile
        assertEquals(QuarantineRecoveryOutcome.NEEDS_CONFIRMATION, f.sessions.service.reconcileQuarantine(f.sessions.root, false))
        assertEquals(1, f.store.unsettled("plan").size)
        assertEquals(QuarantineRecoveryOutcome.RESOLVED, f.recovery.reconcile(f.sessions.root, true))
        assertEquals(QuarantineRecoveryOutcome.NO_QUARANTINE, f.sessions.service.reconcileQuarantine(f.sessions.root, true))
        assertTrue(f.store.unsettled("plan").isEmpty())
        assertTrue(f.sessions.store.get(f.sessions.root.organismId!!).unresolvedQuarantines(f.sessions.root.id).isEmpty())
        assertTrue(f.reconciled.isNotEmpty())
        assertEquals(ExecutionPhase.WAITING, f.store.planFor("plan")!!.phase, "Confirmation must not start execution")
    }

    @Test fun confirmationRequiresTheSamePlanAndJournalThatWereInspected() = runTest {
        for (changeJournal in listOf(false, true)) {
            val f = Fixture(); f.initialize()
            f.recovery.reconcile(f.sessions.root, false)
            if (changeJournal) f.events.append("plan", PlanJournalOperation.APPLY_INTENT.wire, 2)
            else f.store.update("plan") { it.copy(goal = "New goal") }
            assertFailsWith<IllegalArgumentException> { f.recovery.reconcile(f.sessions.root, true) }
            assertEquals(if (changeJournal) 2 else 1, f.store.unsettled("plan").size)
            assertTrue(f.reconciled.isEmpty())
        }
    }

    @Test fun aNewQuarantineInvalidatesTheJournalConfirmationEvenWhenThePlanIsUnchanged() = runTest {
        val f = Fixture(); f.initialize()
        f.recovery.reconcile(f.sessions.root, false)
        val organism = f.sessions.store.get(f.sessions.root.organismId!!)
        f.sessions.service.project(f.sessions.store.quarantine(organism.id, f.sessions.root.id,
            organism.sessions.getValue(f.sessions.root.id).generation, "new-effect", "Another unconfirmed operation"))
        assertFailsWith<IllegalArgumentException> { f.recovery.reconcile(f.sessions.root, true) }
        assertEquals(1, f.store.unsettled("plan").size)
        assertTrue(f.reconciled.isEmpty())
    }

    @Test fun directConfirmationAndUnprovenTerminationCannotCloseTheJournal() = runTest {
        val f = Fixture(); f.initialize()
        assertFailsWith<IllegalArgumentException> { f.recovery.reconcile(f.sessions.root, true) }
        f.recovery.reconcile(f.sessions.root, false)
        f.terminationFailure = true
        assertFailsWith<IllegalStateException> { f.recovery.reconcile(f.sessions.root, true) }
        assertEquals(1, f.store.unsettled("plan").size)
    }

    @Test fun confirmingACompletedCheckpointPreservesCompletionWithoutReapplyingIt() = runTest {
        val f = Fixture(); f.initialize(complete = true)
        f.recovery.reconcile(f.sessions.root, false)
        f.recovery.reconcile(f.sessions.root, true)
        val saved = f.store.planFor("plan")!!
        assertEquals(ExecutionPhase.COMPLETE, saved.phase)
        assertEquals(PlanStatus.DONE, saved.status)
        assertNull(saved.issue)
    }
}
