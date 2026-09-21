package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanJournalReaderTest {
    private fun record(seq: Long, operation: PlanJournalOperation, detail: String = "", stream: String = "plan") =
        JournalRecord(seq, 1, stream, operation.wire, detail)

    @Test fun exactIdentitySettlesOnlyItsIntentEvenWithRepeatedAndNestedOperations() {
        val first = record(1, PlanJournalOperation.AGENT_INTENT)
        val second = record(3, PlanJournalOperation.AGENT_INTENT)
        val merge = record(7, PlanJournalOperation.MERGE_INTENT)
        val records = listOf(first, second, merge,
            record(8, PlanJournalOperation.INTENT_OUTCOME, PlanIntentOutcome(3, PlanIntentStatus.REJECTED).encode()),
            record(9, PlanJournalOperation.INTENT_OUTCOME, PlanIntentOutcome(7, PlanIntentStatus.INTERRUPTED).encode()))
        assertEquals(listOf(first), unsettledPlanIntents(records))
    }

    @Test fun stageCompletionCannotSettleUnprovenEffects() {
        val intent = record(1, PlanJournalOperation.AGENT_INTENT)
        assertEquals(listOf(intent), unsettledPlanIntents(listOf(intent, record(2, PlanJournalOperation.STAGE_COMPLETE))))
    }

    @Test fun legacyApplyCompletionDoesNotEraseAnEarlierUnknownApply() {
        val first = record(1, PlanJournalOperation.APPLY_INTENT)
        assertEquals(listOf(first), unsettledPlanIntents(listOf(first,
            record(2, PlanJournalOperation.APPLY_INTENT), record(3, PlanJournalOperation.APPLY_COMPLETE))))
    }

    @Test fun confirmedTerminationSettlesRepeatedStopRequests() {
        assertTrue(unsettledPlanIntents(listOf(record(1, PlanJournalOperation.STOP_INTENT),
            record(2, PlanJournalOperation.STOP_INTENT), record(3, PlanJournalOperation.STOP_CONFIRMED))).isEmpty())
    }

    @Test fun malformedForeignAndConflictingEvidenceFailsClosed() {
        val intent = record(1, PlanJournalOperation.AGENT_INTENT)
        val complete = record(2, PlanJournalOperation.INTENT_OUTCOME, PlanIntentOutcome(1, PlanIntentStatus.COMPLETED).encode())
        val invalid = listOf(
            listOf(intent, record(2, PlanJournalOperation.INTENT_OUTCOME, "not-json")),
            listOf(intent, complete.copy(stream = "other")),
            listOf(intent, record(2, PlanJournalOperation.INTENT_OUTCOME, PlanIntentOutcome(99, PlanIntentStatus.COMPLETED).encode())),
            listOf(intent, complete, record(3, PlanJournalOperation.INTENT_OUTCOME, PlanIntentOutcome(1, PlanIntentStatus.REJECTED).encode())),
            listOf(complete, intent),
        )
        invalid.forEach { records -> assertFails { unsettledPlanIntents(records) } }
    }

    @Test fun freshStoreSeesAnIntentThatNeverReachedThePlanCheckpoint() = runTest {
        val backing = InMemoryKeyValueStore()
        val repo = JsonPlanningRepository(backing, Json)
        repo.save(Plan("plan", "project", "Goal"))
        val events = InMemoryEventJournal()
        val intent = events.append("plan", PlanJournalOperation.PREPARE_INTENT.wire, 1)
        val restored = TestPlanningStore(JsonPlanningRepository(backing, Json), events)
        assertTrue(restored.planFor("plan")!!.journal.isEmpty())
        assertEquals(listOf(intent), restored.unsettled("plan"))
    }

    @Test fun corruptOutcomeIsVisibleAndRecoveryCannotClearItByOnlyReadingThePlan() = runTest {
        val repo = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        repo.save(Plan("plan", "project", "Goal"))
        val events = InMemoryEventJournal()
        events.append("plan", PlanJournalOperation.INTENT_OUTCOME.wire, 1, "invalid")
        val store = TestPlanningStore(repo, events)
        assertFailsWith<PlanningPersistenceException> { store.unsettled("plan") }
        assertNotNull(store.failure.value)
        assertFailsWith<PlanningPersistenceException> { store.recover() }
        assertNotNull(store.failure.value)
    }

    @Test fun humanResolutionRecordsAuthorityWithoutInventingSuccess() = runTest {
        val repo = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        repo.save(Plan("plan", "project", "Goal"))
        val events = InMemoryEventJournal()
        val intent = events.append("plan", PlanJournalOperation.CAPTURE_INTENT.wire, 1)
        val store = TestPlanningStore(repo, events)
        store.reconcileIntents(store.planFor("plan")!!, listOf(intent), setOf(intent.seq), PlanRecoveryAuthority.USER)
        assertTrue(store.unsettled("plan").isEmpty())
        val resolution = events.read("plan").single { it.operation == PlanJournalOperation.INTENT_RECONCILED.wire }.planEvidence()
        assertEquals(PlanJournalOperation.INTENT_RECONCILED.wire, resolution.operation)
        assertEquals(PlanIntentReconciliation(intent.seq, PlanRecoveryAuthority.USER), PlanIntentReconciliation.decode(resolution.detail))
    }
}
