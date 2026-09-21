package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.domain.DossierSource
import io.aequicor.magicpaper.domain.Milestone
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.PlanStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class JsonPlanningRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = InMemoryKeyValueStore()
    private val repo = JsonPlanningRepository(store, json)

    private fun plan(projectId: String, status: PlanStatus = PlanStatus.DRAFT, updated: Long = 1L) = Plan(
        id = "plan-$projectId",
        projectId = projectId,
        goal = "цель $projectId",
        milestones = listOf(Milestone(id = "m1", title = "шаг один")),
        status = status,
        createdAt = 1L,
        updatedAt = updated,
    )

    @Test fun siblingPlansSurviveDeletionAndCheckpointRecovery() = runTest {
        val first = plan("same").copy(id = "first")
        val second = plan("same").copy(id = "second")
        repo.save(first); repo.save(second)
        assertEquals(2, repo.plans().size)
        assertFailsWith<IllegalArgumentException> { repo.planFor("same") }
        repo.deletePlan("first")
        store.write("coding-plans", "{broken")
        store.write("coding-plans-backup", "{broken")
        assertEquals(listOf("second"), repo.plans().map { it.id })
        assertEquals("same", repo.planFor("second")?.projectId)
    }

    @Test
    fun planRoundTripAndReplacePerProject() = runTest {
        repo.save(plan("p1"))
        repo.save(plan("p2"))
        assertEquals(2, repo.plans().size)

        // Повторное сохранение плана проекта заменяет прежний (один план на проект).
        repo.save(plan("p1", status = PlanStatus.DONE, updated = 2L))
        assertEquals(2, repo.plans().size)
        assertEquals(PlanStatus.DONE, repo.planFor("p1")?.status)
        assertEquals("цель p1", repo.planFor("p1")?.goal)
    }

    @Test
    fun deletePlanRemovesOnlyItsProject() = runTest {
        repo.save(plan("p1"))
        repo.save(plan("p2"))
        repo.deletePlan("p1")
        assertNull(repo.planFor("p1"))
        assertEquals(1, repo.plans().size)
    }

    @Test
    fun corruptedPlanIsNotSilentlyLost() = runTest {
        store.write("coding-plans", "{broken")
        assertFailsWith<IllegalStateException> { repo.plans() }

    }

    @Test fun previousSnapshotRecoversCorruptCurrent() = runTest {
        repo.save(plan("p1"))
        repo.save(plan("p1", updated = 2))
        store.write("coding-plans", "{broken")
        assertEquals("p1", repo.plans().single().projectId)
    }

    @Test fun checkpointsRebuildBothLostSnapshotsAndRetainDeletion() = runTest {
        repo.save(plan("p1")); repo.save(plan("p2")); repo.deletePlan("p1")
        store.write("coding-plans", "{broken"); store.write("coding-plans-backup", "{broken")
        assertEquals(listOf("p2"), repo.plans().map { it.projectId })
    }

    @Test fun corruptOnlyCheckpointIsNotAnEmptyWorkspace() = runTest {
        store.write("coding-plan-checkpoint-lost", "{broken")
        assertFailsWith<IllegalArgumentException> { repo.plans() }
    }

    @Test fun failedCheckpointFreezesCommandsWithoutRollingBackAcceptedJournalState() = runTest {
        var unavailable = false
        val failing = object : io.aequicor.magicpaper.domain.PlanningCheckpointStore by repo {
            override suspend fun save(plan: Plan) {
                if (unavailable) error("Disk full")
                repo.save(plan)
            }
        }
        val observed = TestPlanningStore(failing)
        observed.save(plan("p1"))
        unavailable = true
        assertFailsWith<PlanningPersistenceException> { observed.edit("p1") { it.copy(goal = "accepted in journal") } }
        assertEquals("accepted in journal", observed.plans.value.single().goal)
        assertEquals("цель p1", repo.planFor("p1")!!.goal)
        unavailable = false
        assertFailsWith<PlanningPersistenceException> { observed.edit("p1") { it.copy(goal = "too soon") } }
        observed.recover()
        assertEquals("accepted in journal", repo.planFor("p1")!!.goal)
        observed.edit("p1") { it.copy(goal = "recovered") }
        assertEquals("recovered", observed.plans.value.single().goal)
        assertNull(observed.failure.value)
    }

    @Test
    fun wipeClearsOnlyPlanningData() = runTest {
        repo.save(plan("p1"))
        store.write("model-dossiers", "settings-owned bytes")
        repo.wipe()
        assertTrue(repo.plans().isEmpty())
        assertEquals("settings-owned bytes", store.read("model-dossiers"))
    }
}
