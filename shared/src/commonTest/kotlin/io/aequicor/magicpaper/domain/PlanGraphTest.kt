package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * График плана: nextPending учитывает зависимости (зависимый шаг не стартует
 * раньше предшественника), параллельные ветви остаются доступны по порядку.
 */
class PlanGraphTest {

    private fun milestone(id: String, depends: List<String> = emptyList(), status: MilestoneStatus = MilestoneStatus.PENDING) =
        Milestone(id = id, title = "шаг $id", status = status, dependsOn = depends)

    private fun plan(vararg milestones: Milestone) = Plan(
        id = "plan",
        projectId = "proj",
        goal = "цель",
        milestones = milestones.toList(),
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun dependentWaitsForPredecessor() {
        val p = plan(milestone("m1"), milestone("m2", depends = listOf("m1")))
        assertEquals("m1", p.nextPending?.id)
        // После завершения предшественника зависимый шаг разблокирован.
        val done = p.copy(milestones = listOf(p.milestones[0].copy(status = MilestoneStatus.DONE), p.milestones[1]))
        assertEquals("m2", done.nextPending?.id)
    }

    @Test
    fun parallelBranchesReadyIndependently() {
        val p = plan(milestone("a"), milestone("b"), milestone("c", depends = listOf("a", "b")))
        assertEquals("a", p.nextPending?.id)
        assertTrue(p.depsSatisfied(p.milestones[0]) && p.depsSatisfied(p.milestones[1]))
        // c ждёт обе ветви: одна завершена — всё ещё рановато.
        val half = p.copy(milestones = p.milestones.map { if (it.id == "a") it.copy(status = MilestoneStatus.DONE) else it })
        assertEquals("b", half.nextPending?.id)
        assertTrue(!half.depsSatisfied(half.milestones.first { it.id == "c" }))
    }

    @Test
    fun blockedPendingReportsFailedPredecessor() {
        val p = plan(
            milestone("m1", status = MilestoneStatus.FAILED),
            milestone("m2", depends = listOf("m1")),
        )
        // Проваленный шаг ещё перевыполняаем (nextPending = m1), а m2 — в блоке за ним.
        assertEquals(listOf("m2"), p.blockedPending.map { it.id })
    }
}
