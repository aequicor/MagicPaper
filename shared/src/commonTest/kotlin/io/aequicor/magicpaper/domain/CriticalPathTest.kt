package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.components.dependencyPositions
import kotlin.test.*

class CriticalPathTest {
    private fun stage(id: String, duration: Double?, vararg deps: String) =
        Milestone(id, id, complexityPoints = duration, dependsOn = deps.toList())
    private fun plan(vararg stages: Milestone) = Plan("p", "project", "Goal", milestones = stages.toList())

    @Test fun parallelBranchesHaveFloatAndCorrectLateDates() {
        val result = criticalPathSchedule(plan(stage("end", 2.0, "long", "short"),
            stage("short", 3.0, "start"), stage("start", 2.0), stage("long", 5.0, "start")))
        assertEquals(9.0, result.complexityPoints)
        assertEquals(StageTiming(2.0, 5.0, 4.0, 7.0), result.timings["short"])
        assertEquals(setOf("start", "long", "end"), result.timings.filterValues { it.critical }.keys)
        val nodes = result.order.map { DecisionNode(it, it, DecisionKind.STAGE) }
        val positions = dependencyPositions(nodes, result.dependencies)
        result.dependencies.forEach { (id, deps) -> deps.forEach { assertTrue(positions.getValue(it).x < positions.getValue(id).x) } }
        assertEquals(positions.getValue("short").x, positions.getValue("long").x)
        assertNotEquals(positions.getValue("short").y, positions.getValue("long").y)
    }

    @Test fun equalBranchesAreBothCriticalAndDisconnectedShortBranchHasFloat() {
        val result = criticalPathSchedule(plan(stage("a", 4.0), stage("b", 4.0), stage("c", 1.0), stage("end", 2.0, "a", "b")))
        assertTrue(result.timings.getValue("a").critical)
        assertTrue(result.timings.getValue("b").critical)
        assertEquals(5.0, result.timings.getValue("c").slackPoints)
    }

    @Test fun unknownAndInvalidEstimatesNeverProduceInventedDates() {
        for (duration in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = criticalPathSchedule(plan(stage("a", duration)))
            assertNull(result.complexityPoints)
            assertTrue(result.timings.isEmpty())
            assertEquals(listOf("a"), result.order)
        }
        assertTrue(criticalPathSchedule(plan(stage("a", 1.0, "b"), stage("b", 1.0, "a"))).errors.isNotEmpty())
        assertTrue(criticalPathSchedule(plan(stage("a", 1.0, "missing"))).errors.isNotEmpty())
    }

    @Test fun selectedOptionAndGroupDependenciesDriveTheSchedule() {
        val source = plan(stage("a", 2.0), stage("b", null), stage("end", 3.0)).copy(tree = listOf(
            DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("choice", "end-node")),
            DecisionNode("choice", "Choice", DecisionKind.CHOICE, listOf("one", "two"), "one"),
            DecisionNode("one", "One", DecisionKind.OPTION, listOf("a-node")),
            DecisionNode("two", "Two", DecisionKind.OPTION, listOf("b-node")),
            DecisionNode("a-node", "A", DecisionKind.STAGE, stageId = "a"),
            DecisionNode("b-node", "B", DecisionKind.STAGE, stageId = "b"),
            DecisionNode("end-node", "End", DecisionKind.STAGE, stageId = "end", dependsOn = listOf("choice")),
        ))
        val result = criticalPathSchedule(source)
        assertEquals(5.0, result.complexityPoints)
        assertEquals(setOf("a"), result.dependencies["end"])
        assertFalse("b" in result.order)
    }
}
