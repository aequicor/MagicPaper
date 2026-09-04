package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
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
    fun dossierRoundTripAndRewriteByProfile() = runTest {
        val first = ModelDossier(id = "d1", profileId = "prof-1", strengths = "код", rating = 3)
        repo.saveDossier(first)
        assertEquals(1, repo.dossiers().size)

        // Досье 1:1 к профилю: сохранение нового перезаписывает по профилю.
        repo.saveDossier(first.copy(strengths = "код и тесты", rating = 4, source = DossierSource.WEB))
        val loaded = repo.dossiers()
        assertEquals(1, loaded.size)
        assertEquals("код и тесты", loaded.single().strengths)
        assertEquals(4, loaded.single().rating)
    }

    @Test
    fun corruptedDataFallsBackToEmpty() = runTest {
        store.write("coding-plans", "{broken")
        assertTrue(repo.plans().isEmpty())
        store.write("model-dossiers", "{broken")
        assertTrue(repo.dossiers().isEmpty())
    }

    @Test
    fun wipeClearsEverything() = runTest {
        repo.save(plan("p1"))
        repo.saveDossier(ModelDossier(id = "d1", profileId = "prof-1", strengths = "x"))
        repo.wipe()
        assertTrue(repo.plans().isEmpty())
        assertTrue(repo.dossiers().isEmpty())
    }
}
