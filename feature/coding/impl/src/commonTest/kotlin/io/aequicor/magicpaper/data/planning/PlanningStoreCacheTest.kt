package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningStoreCacheTest {
    private fun plan(id: String) = Plan(id = id, projectId = "project-$id", goal = id,
        createdAt = 1, updatedAt = 1)

    @Test fun statusLookupsReuseCommittedPlansAndUnchangedSiblingsKeepTheirIdentity() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one")); durable.save(plan("two"))
        var reads = 0
        val store = PlanningStore(object : PlanningRepository by durable {
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
        store.update("one", expectedRevision = one.revision) { it.copy(goal = "updated") }
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

    @Test fun uncertainWriteDoesNotPublishUntilExplicitRecoveryReadsTheDurableCheckpoint() = runTest {
        val durable = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        durable.save(plan("one"))
        var failAfterCommit = true
        val store = PlanningStore(object : PlanningRepository by durable {
            override suspend fun save(plan: Plan) {
                durable.save(plan)
                if (failAfterCommit) error("Connection lost after commit")
            }
        })
        val previous = store.planFor("one")!!
        assertFailsWith<PlanningPersistenceException> { store.update("one") { it.copy(goal = "committed") } }
        assertSame(previous, store.planFor("one"))
        assertEquals("committed", durable.planFor("one")!!.goal)
        failAfterCommit = false
        store.recover()
        assertEquals("committed", store.planFor("one")!!.goal)
        assertNull(store.failure.value)
        assertFailsWith<IllegalArgumentException> { store.update("one", previous.revision) { it } }
    }
}
