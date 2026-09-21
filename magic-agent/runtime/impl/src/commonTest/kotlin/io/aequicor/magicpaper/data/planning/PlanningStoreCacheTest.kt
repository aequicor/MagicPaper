package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningStoreCacheTest {
    private fun plan(id: String) = Plan(id = id, projectId = "project-$id", goal = id,
        createdAt = 1, updatedAt = 1)

    @Test fun sharedJournalRestoresOrphanedPlansWithoutReadingOrDeletingOtherOwners() = runTest {
        val journal = InMemoryEventJournal()
        val foreign = journal.append("questionnaire:application-tools", "questionnaire.input.v1", 1, "owned elsewhere")
        val repository = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        TestPlanningStore(repository, journal).save(plan("one"))
        repository.deletePlan("one") // Accepted journal facts outlive a missing checkpoint.
        val restored = TestPlanningStore(repository, journal)
        assertEquals(listOf("one"), restored.plans().map { it.id })
        restored.wipe()
        assertEquals(listOf(foreign), journal.read(foreign.stream))
        assertTrue(TestPlanningStore(repository, journal).plans().isEmpty())
    }

    @Test fun malformedOwnedJournalStillFailsInsteadOfBeingSkippedAsAnotherOwner() = runTest {
        val journal = InMemoryEventJournal()
        journal.append("broken-plan", PLAN_STATE_OPERATION, 1, "invalid plan state")
        val store = TestPlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json), journal)
        assertFailsWith<PlanningPersistenceException> { store.plans() }
        assertNotNull(store.failure.value)
        assertEquals(1, journal.read("broken-plan").size)
    }

    @Test fun statusLookupsReuseCommittedPlansAndUnchangedSiblingsKeepTheirIdentity() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        var reads = 0
        val store = TestPlanningStore(object : PlanningCheckpointStore by durable {
            override suspend fun plans(): List<Plan> { reads++; return durable.plans() }
            override suspend fun planFor(projectId: String): Plan? { reads++; return durable.planFor(projectId) }
        })
        val one = store.planFor("one")!!
        val two = store.planFor("two")!!
        repeat(1000) {
            assertSame(one, store.planFor("one"))
            assertSame(two, store.plans().first { it.id == "two" })
        }
        assertEquals(1, reads, "Status polling must not reread and decode the complete plan archive")
        store.edit("one", expectedRevision = one.revision) { it.copy(goal = "updated") }
        assertSame(two, store.planFor("two"), "Updating one plan must not recreate every other plan")
        assertEquals("updated", durable.planFor("one")!!.goal)
        assertEquals(1, reads, "Publishing a saved plan must not decode it again")
        store.deletePlan("one")
        assertNull(store.planFor("one"))
        assertSame(two, store.planFor("two"))
        store.wipe()
        assertTrue(store.plans().isEmpty())
        assertTrue(durable.plans().isEmpty())
    }

    @Test fun acceptedJournalStateSurvivesCheckpointFailureAndRecoveryRepairsTheCache() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one"))
        var failAfterCommit = false
        val store = TestPlanningStore(object : PlanningCheckpointStore by durable {
            override suspend fun save(plan: Plan) {
                durable.save(plan)
                if (failAfterCommit) error("Connection lost after commit")
            }
        })
        val previous = store.planFor("one")!!
        failAfterCommit = true
        assertFailsWith<PlanningPersistenceException> { store.edit("one") { it.copy(goal = "committed") } }
        assertEquals("committed", store.planFor("one")!!.goal)
        assertNotNull(store.failure.value)
        assertEquals("committed", durable.planFor("one")!!.goal)
        failAfterCommit = false
        store.recover()
        assertEquals("committed", store.planFor("one")!!.goal)
        assertNull(store.failure.value)
        assertFailsWith<IllegalArgumentException> { store.edit("one", previous.revision) { it } }
    }
}
