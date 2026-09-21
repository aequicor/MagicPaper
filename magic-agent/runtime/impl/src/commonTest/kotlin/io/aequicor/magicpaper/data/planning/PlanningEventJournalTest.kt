package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningEventJournalTest {
    private var sequence = 0L
    private fun stamp() = PlanningMachine.Stamp("journal-test-${++sequence}", sequence)
    private suspend fun EventJournal.evidence(stream: String) = read(stream)
        .filter { PlanJournalOperation.of(it.operation) != null }.map { it.planEvidence() }
    private suspend fun EventJournal.outcomes() = evidence("one")
        .filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }.map { PlanIntentOutcome.decode(it.detail) }
    private fun plan(id: String) = Plan(id = id, projectId = "project-$id", goal = id,
        milestones = listOf(Milestone("stage", "Stage", description = "Do it")), createdAt = 1, updatedAt = 1)
    private suspend fun admit(store: PlanningStore, id: String = "one") {
        store.command(id, PlanningMachine.Intent.Start("run-$id-${++sequence}", PlanningRulesSettings().snapshot(), stamp()))
    }
    private suspend fun fixture(repo: PlanningCheckpointStore? = null): Pair<TestPlanningStore, InMemoryEventJournal> {
        val durable = repo ?: JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        val events = InMemoryEventJournal()
        val store = TestPlanningStore(durable, events)
        store.plans().forEach { admit(store, it.id) }
        return store to events
    }

    @Test fun anOperationIsRecordedInBothJournalsUnderThePlansOwnId() = runTest {
        val (store, events) = fixture()
        store.beginIntent("project-one", PlanJournalOperation.AGENT_INTENT, "stage", "attempt-3", checkNotNull(store.currentAdmission("one")))
        val recorded = events.evidence("one").single { it.operation == PlanJournalOperation.AGENT_INTENT.wire }
        assertEquals(PlanJournalSubject("stage", "attempt-3"), PlanJournalSubject.decode(recorded.detail))
        assertTrue(events.evidence("project-one").isEmpty())
        val entry = store.planFor("one")!!.journal.single { it.records(PlanJournalOperation.AGENT_INTENT) }
        assertEquals("stage" to "attempt-3", entry.stageId to entry.attemptId)
        assertEquals(recorded.at, entry.at)
    }
    @Test fun anOperationAboutThePlanAsAWholeCarriesNoSubject() = runTest {
        val (store, events) = fixture()
        store.beginIntent("one", PlanJournalOperation.APPLY_INTENT)
        assertEquals("", events.evidence("one").single { it.operation == PlanJournalOperation.APPLY_INTENT.wire }.detail)
        assertEquals(PlanJournalSubject(), PlanJournalSubject.decode(""))
    }
    @Test fun acceptedIntentSurvivesCheckpointFailureWithoutInventingDispatch() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        var fail = false
        val (store, events) = fixture(object : PlanningCheckpointStore by durable {
            override suspend fun save(plan: Plan) { if (fail) error("disk unavailable"); durable.save(plan) }
        })
        fail = true
        var invoked = false
        assertFailsWith<PlanningPersistenceException> {
            store.withJournaledIntent("one", PlanJournalOperation.PREPARE_INTENT) { invoked = true; complete() }
        }
        assertFalse(invoked)
        val intent = events.evidence("one").single { it.operation == PlanJournalOperation.PREPARE_INTENT.wire }
        assertTrue(events.outcomes().none { it.intentSeq == intent.seq })
        assertNull(store.currentAdmission("one"))
        assertNotNull(store.failure.value)
        assertTrue(durable.planFor("one")!!.journal.none { it.records(PlanJournalOperation.PREPARE_INTENT) })
    }
    @Test fun recordsKeepTheirOrderAcrossPlans() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(durable)
        val first = store.beginIntent("one", PlanJournalOperation.PREPARE_INTENT)
        val middle = store.beginIntent("two", PlanJournalOperation.PREPARE_INTENT)
        val last = store.beginIntent("one", PlanJournalOperation.AGENT_INTENT)
        assertTrue(first.seq < middle.seq && middle.seq < last.seq)
        assertEquals(listOf(first.seq, last.seq), events.evidence("one")
            .filter { PlanJournalOperation.of(it.operation)?.kind == JournalEntryKind.INTENT }.map { it.seq })
    }
    @Test fun deletingSettledPlanRetainsItsJournalTombstoneAndOtherPlans() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(durable)
        store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { complete() }
        val other = store.beginIntent("two", PlanJournalOperation.AGENT_INTENT)
        store.command("one", PlanningMachine.Intent.Pause(stamp()))
        store.deletePlan("project-one")
        assertNull(store.planFor("one"))
        val tombstone = events.snapshot("one")
        assertTrue(tombstone.records.isEmpty())
        assertTrue(tombstone.revision.seq > 0, "Deletion retains the closed stream identity")
        assertNotNull(store.dispatch("one", PlanningMachine.Intent.Create(plan("one"), stamp())).rejection)
        assertEquals(listOf(other), store.unsettled("two"))
        assertNull(TestPlanningStore(durable, events).planFor("one"))
    }
    @Test fun wipingRequiresSettledStoppedPlansAndThenRemovesEveryStream() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(durable)
        assertFails { store.wipe() }
        for (id in listOf("one", "two")) {
            store.withJournaledIntent(id, PlanJournalOperation.AGENT_INTENT) { complete() }
            store.command(id, PlanningMachine.Intent.Pause(stamp()))
        }
        store.wipe()
        assertTrue(events.read("one").isEmpty()); assertTrue(events.read("two").isEmpty())
    }
    @Test fun nestedAndRepeatedIntentsSettleTheirOwnIdentity() = runTest {
        val (store, events) = fixture()
        repeat(2) {
            store.withJournaledIntent("one", PlanJournalOperation.MERGE_INTENT, "stage", "attempt") {
                store.withJournaledIntent("one", PlanJournalOperation.CONFLICT_AGENT_INTENT, "stage", "attempt") { complete() }
                reject()
            }
        }
        val outcomes = events.outcomes().associateBy { it.intentSeq }
        val intents = events.evidence("one").filter { PlanJournalOperation.of(it.operation)?.kind == JournalEntryKind.INTENT }
        assertEquals(4, outcomes.size)
        intents.forEach { intent -> assertEquals(if (intent.operation == PlanJournalOperation.MERGE_INTENT.wire)
            PlanIntentStatus.REJECTED else PlanIntentStatus.COMPLETED, outcomes[intent.seq]?.status) }
    }
    @Test fun earlyReturnWithoutProofKeepsExternalOutcomeUnknown() = runTest {
        val (store, events) = fixture()
        suspend fun leave(): Boolean { store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { return false } }
        assertFalse(leave())
        assertTrue(events.outcomes().isEmpty())
        assertEquals(1, store.unsettled("one").size)
        assertNull(store.currentAdmission("one"))
    }
    @Test fun explicitPreDispatchReturnSettlesRejectionWithoutClaimingExternalSuccess() = runTest {
        val (store, events) = fixture()
        suspend fun leave(): Boolean { store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { beforeDispatch(); return false } }
        assertFalse(leave())
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
        assertTrue(store.unsettled("one").isEmpty())
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationAfterDispatchRetainsUnknownInsteadOfInferringInterruption() = runTest {
        val (store, events) = fixture()
        val job = launch { store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) {
            beforeDispatch(); dispatching(); awaitCancellation()
        } }
        runCurrent(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(events.outcomes().isEmpty()); assertEquals(1, store.unsettled("one").size)
        assertNull(store.currentAdmission("one"))
    }
    @Test fun preDispatchFailureSettlesOnlyTheNamedIntentAndKeepsOriginalCause() = runTest {
        val (store, events) = fixture()
        val failure = IllegalArgumentException("admission denied")
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { beforeDispatch(); throw failure }
        })
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
        assertTrue(store.unsettled("one").isEmpty())
    }
    @Test fun thrownTransportCannotBecomeKnownRejection() = runTest {
        val (store, events) = fixture()
        val failure = IllegalArgumentException("reply lost")
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { beforeDispatch(); dispatching(); throw failure }
        })
        assertTrue(events.outcomes().isEmpty()); assertEquals(1, store.unsettled("one").size)
    }
    @Test fun failedCheckpointDoesNotPreventIndependentKnownNoDispatchOutcomeWrite() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        var fail = false
        val (store, events) = fixture(object : PlanningCheckpointStore by durable {
            override suspend fun save(plan: Plan) { if (fail) error("disk unavailable"); durable.save(plan) }
        })
        assertFailsWith<PlanningPersistenceException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) {
                beforeDispatch(); fail = true
                store.command("one", PlanningMachine.Intent.Pause(stamp()))
            }
        }
        assertNotNull(store.failure.value)
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
        assertEquals(ExecutionIntent.RUN, durable.planFor("one")!!.intent)
    }
    @Test fun failedOutcomeWritePreservesCancellationAndLeavesUnsettledEvidence() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        val memory = InMemoryEventJournal()
        val events = object : EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (operation == PlanJournalOperation.INTENT_OUTCOME.wire) error("unavailable")
                return memory.append(expected, operation, at, detail)
            }
        }
        val store = TestPlanningStore(durable, events); store.plans(); admit(store)
        val cancellation = CancellationException("stopped")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { beforeDispatch(); throw cancellation }
        })
        assertNotNull(store.failure.value)
        assertEquals(1, cancellation.suppressedExceptions.size)
        assertEquals(1, memory.evidence("one").count { it.operation == PlanJournalOperation.AGENT_INTENT.wire })
        assertTrue(memory.outcomes().isEmpty())
    }
    @Test fun theSubjectSurvivesItsOwnEncoding() = runTest {
        listOf("" to "", "stage" to "attempt", "a/b" to "c\"d", "с кириллицей" to "{\"json\":1}").forEach { (stage, attempt) ->
            assertEquals(PlanJournalSubject(stage, attempt), PlanJournalSubject.decode(PlanJournalSubject.encode(stage, attempt)))
        }
        assertEquals(PlanJournalSubject(), PlanJournalSubject.decode("не разобрать"))
    }
}
