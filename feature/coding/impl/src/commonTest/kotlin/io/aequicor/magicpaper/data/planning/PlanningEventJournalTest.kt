package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningEventJournalTest {
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

        val recorded = events.read("one").single()
        assertEquals(PlanJournalOperation.AGENT_INTENT.wire, recorded.operation)
        assertEquals(PlanJournalSubject("stage-7", "attempt-3"), PlanJournalSubject.decode(recorded.detail))
        assertTrue(events.read("project-one").isEmpty(), "Поток именуется планом, а не проектом")

        val entry = store.planFor("one")!!.journal.single()
        assertTrue(entry.records(PlanJournalOperation.AGENT_INTENT))
        assertEquals("stage-7" to "attempt-3", entry.stageId to entry.attemptId)
        assertEquals(recorded.at, entry.at, "Обе записи об одном шаге и о том же моменте")
    }

    @Test fun anOperationAboutThePlanAsAWholeCarriesNoSubject() = runTest {
        val (store, events) = fixture()
        store.journal("one", PlanJournalOperation.APPLY_INTENT)
        assertEquals("", events.read("one").single().detail)
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
        assertEquals(PlanJournalOperation.AGENT_INTENT.wire, events.read("one").single().operation)
        assertTrue(durable.planFor("one")!!.journal.isEmpty(), "В самом плане записи не появилось")
    }

    @Test fun recordsKeepTheirOrderAcrossPlans() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.PREPARE_INTENT)
        store.journal("two", PlanJournalOperation.PREPARE_INTENT)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        assertContentEquals(listOf(1L, 3L), events.read("one").map { it.seq })
        assertContentEquals(listOf(2L), events.read("two").map { it.seq })
    }

    @Test fun deletingAPlanTakesItsRecordsAndLeavesTheOthers() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        store.journal("two", PlanJournalOperation.AGENT_INTENT)
        store.deletePlan("project-one")
        assertTrue(events.read("one").isEmpty(), "У удалённого плана записи ничего не отвечают")
        assertEquals(1, events.read("two").size)
    }

    @Test fun wipingPlansTakesEveryStreamWithThem() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        val (store, events) = fixture(repo = durable)
        store.journal("one", PlanJournalOperation.AGENT_INTENT)
        store.journal("two", PlanJournalOperation.AGENT_INTENT)
        store.wipe()
        assertTrue(events.read("one").isEmpty())
        assertTrue(events.read("two").isEmpty())
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
