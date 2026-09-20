package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningEventJournalTest {
    private suspend fun InMemoryEventJournal.evidence(stream: String) = read(stream)
        .filter { it.operation != PLAN_STATE_OPERATION }.map { it.planEvidence() }

    private fun plan(id: String) = Plan(id = id, projectId = "project-$id", goal = id, createdAt = 1, updatedAt = 1)

    private suspend fun fixture(id: String = "one", repo: PlanningRepository? = null): Pair<PlanningStore, InMemoryEventJournal> {
        val durable = repo ?: JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan(id)) }
        val events = InMemoryEventJournal()
        return PlanningStore(durable, events) to events
    }

    @Test fun anOperationIsRecordedInBothJournalsUnderThePlansOwnId() = runTest {
        val (store, events) = fixture()
        // The service addresses plans by project as well as by id; the stream is the plan, so a
        // record and the drop that removes it cannot end up under two different names.
        store.journal("project-one", PlanJournalOperation.AGENT_INTENT, stageId = "stage-7", attemptId = "attempt-3")

        val recorded = events.evidence("one").single()
        assertEquals(PlanJournalOperation.AGENT_INTENT.wire, recorded.operation)
        assertEquals(PlanJournalSubject("stage-7", "attempt-3"), PlanJournalSubject.decode(recorded.detail))
        assertTrue(events.evidence("project-one").isEmpty(), "Поток именуется планом, а не проектом")

        val entry = store.planFor("one")!!.journal.single()
        assertTrue(entry.records(PlanJournalOperation.AGENT_INTENT))
        assertEquals("stage-7" to "attempt-3", entry.stageId to entry.attemptId)
        assertEquals(recorded.at, entry.at, "Обе записи об одном шаге и о том же моменте")
    }

    @Test fun anOperationAboutThePlanAsAWholeCarriesNoSubject() = runTest {
        val (store, events) = fixture()
        store.journal("one", PlanJournalOperation.APPLY_INTENT)
        assertEquals("", events.evidence("one").single().detail)
        assertEquals(PlanJournalSubject(), PlanJournalSubject.decode(""))
    }

    @Test fun theRecordSurvivesTheSaveThatWasSupposedToCarryIt() = runTest {
        // This is why the append comes first. The plan's own journal is part of the plan
        // document; a save that fails takes its entry with it, and an intent nobody recorded
        // cannot be told apart from an effect nobody requested.
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        val (store, events) = fixture(repo = object : PlanningRepository by durable {
            override suspend fun save(plan: Plan) = throw IllegalStateException("диск недоступен")
        })
        assertFailsWith<PlanningPersistenceException> {
            store.journal("one", PlanJournalOperation.AGENT_INTENT, stageId = "stage-7")
        }
        assertEquals(PlanJournalOperation.AGENT_INTENT.wire, events.evidence("one").single().operation)
        assertTrue(durable.planFor("one")!!.journal.isEmpty(), "В самом плане записи не появилось")
    }

    @Test fun recordsKeepTheirOrderAcrossPlans() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.PREPARE_INTENT)
        store.journal("two", PlanJournalOperation.PREPARE_INTENT)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        val one = events.evidence("one").map { it.seq }
        val two = events.evidence("two").single().seq
        assertEquals(2, one.size)
        assertTrue(one.first() < two && two < one.last())
    }

    @Test fun deletingAPlanTakesItsRecordsAndLeavesTheOthers() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        store.journal("two", PlanJournalOperation.AGENT_INTENT)
        store.deletePlan("project-one")
        assertTrue(events.evidence("one").isEmpty(), "У удалённого плана записи ничего не отвечают")
        assertEquals(1, events.evidence("two").size)
    }

    @Test fun wipingPlansTakesEveryStreamWithThem() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        store.journal("two", PlanJournalOperation.AGENT_INTENT)
        store.wipe()
        assertTrue(events.evidence("one").isEmpty())
        assertTrue(events.evidence("two").isEmpty())
    }

    private suspend fun InMemoryEventJournal.outcomes() = evidence("one")
        .filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
        .map { PlanIntentOutcome.decode(it.detail) }

    @Test fun nestedAndRepeatedIntentsSettleTheirOwnIdentity() = runTest {
        val (store, events) = fixture()
        repeat(2) {
            store.withJournaledIntent("one", PlanJournalOperation.MERGE_INTENT, "stage", "attempt") {
                store.withJournaledIntent("one", PlanJournalOperation.CONFLICT_AGENT_INTENT, "stage", "attempt") {
                    complete()
                }
                reject()
            }
        }
        val records = events.evidence("one")
        val outcomes = events.outcomes().associateBy { it.intentSeq }
        val intents = records.filter { PlanJournalOperation.of(it.operation)?.kind == JournalEntryKind.INTENT }
        assertEquals(4, outcomes.size)
        intents.forEach { intent ->
            assertEquals(if (intent.operation == PlanJournalOperation.MERGE_INTENT.wire)
                PlanIntentStatus.REJECTED else PlanIntentStatus.COMPLETED, outcomes[intent.seq]?.status)
        }
    }

    @Test fun earlyReturnRecordsInterruptionInsteadOfSuccess() = runTest {
        val (store, events) = fixture()
        suspend fun leave(): Boolean {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { return false }
        }
        assertFalse(leave())
        assertEquals(PlanIntentStatus.INTERRUPTED, events.outcomes().single().status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledCoroutineCanStillPersistItsOutcome() = runTest {
        val (store, events) = fixture()
        val job = launch {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { awaitCancellation() }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(PlanIntentStatus.INTERRUPTED, events.outcomes().single().status)
    }

    @Test fun thrownFailureRecordsRejectionAndKeepsTheOriginalCause() = runTest {
        val (store, events) = fixture()
        val failure = IllegalArgumentException("denied")
        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { throw failure }
        })
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
    }

    @Test fun failedCheckpointDoesNotPreventIndependentOutcomeWrite() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        var fail = false
        val (store, events) = fixture(repo = object : PlanningRepository by durable {
            override suspend fun save(plan: Plan) {
                if (fail) error("disk unavailable")
                durable.save(plan)
            }
        })
        assertFailsWith<PlanningPersistenceException> {
            store.withJournaledIntent("one", PlanJournalOperation.CAPTURE_INTENT) {
                fail = true
                store.update("one") { it.copy(goal = "changed") }
                complete()
            }
        }
        assertNotNull(store.failure.value)
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
        assertEquals("one", durable.planFor("one")!!.goal)
    }

    @Test fun failedIntentCheckpointSettlesAdmissionWithoutExecutingTheEffect() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        val (store, events) = fixture(repo = object : PlanningRepository by durable {
            override suspend fun save(plan: Plan) = error("disk unavailable")
        })
        var executed = false
        assertFailsWith<PlanningPersistenceException> {
            store.withJournaledIntent("one", PlanJournalOperation.PREPARE_INTENT) { executed = true; complete() }
        }
        assertFalse(executed)
        assertEquals(PlanIntentStatus.REJECTED, events.outcomes().single().status)
        assertNotNull(store.failure.value)
    }

    @Test fun failedOutcomeWriteIsVisibleAndDoesNotReplaceCancellation() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json).also { it.save(plan("one")) }
        val memory = InMemoryEventJournal()
        val events = object : io.aequicor.magicpaper.data.storage.EventJournal by memory {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): io.aequicor.magicpaper.data.storage.JournalRecord? {
                if (operation == PlanJournalOperation.INTENT_OUTCOME.wire) error("unavailable")
                return memory.append(expected, operation, at, detail)
            }
        }
        val store = PlanningStore(durable, events)
        val cancellation = CancellationException("stopped")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            store.withJournaledIntent("one", PlanJournalOperation.AGENT_INTENT) { throw cancellation }
        })
        assertNotNull(store.failure.value)
        assertEquals(1, cancellation.suppressedExceptions.size)
        assertEquals(1, memory.evidence("one").size, "An unwritable outcome leaves evidence open")
    }

    @Test fun theSubjectSurvivesItsOwnEncoding() = runTest {
        // Identifiers are opaque to the journal; the schema must not depend on their shape.
        listOf("" to "", "stage" to "attempt", "a/b" to "c\"d", "с кириллицей" to "{\"json\":1}")
            .forEach { (stage, attempt) ->
                val encoded = PlanJournalSubject.encode(stage, attempt)
                assertEquals(PlanJournalSubject(stage, attempt), PlanJournalSubject.decode(encoded), "$stage|$attempt")
            }
        assertEquals(PlanJournalSubject(), PlanJournalSubject.decode("не разобрать"),
            "Нечитаемая подробность — запись без подробности, а не потерянная запись")
    }
}
