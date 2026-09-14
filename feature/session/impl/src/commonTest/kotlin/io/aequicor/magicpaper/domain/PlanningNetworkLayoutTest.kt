package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.components.*
import kotlin.test.*

class PlanningNetworkLayoutTest {
    @Test fun parallelTasksShareColumnsAndAllTerminalTasksReachTheFinish() {
        val source = DecisionCompiler.migrate(Plan("p", "project", "Goal", milestones = listOf(
            Milestone("start-work", "Prepare"),
            Milestone("left", "Left", dependsOn = listOf("start-work")),
            Milestone("right", "Right", dependsOn = listOf("start-work")),
            Milestone("join", "Join", dependsOn = listOf("left", "right")),
            Milestone("independent", "Independent"),
        )))
        val layout = planningNetworkLayout(source)
        assertEquals(layout.positions.getValue("left").x, layout.positions.getValue("right").x)
        assertEquals(layout.positions.getValue("start-work").x, layout.positions.getValue("independent").x)
        assertEquals(setOf("start-work", "independent"), layout.sources)
        assertEquals(setOf("join", "independent"), layout.sinks)
        assertTrue(layout.start.x < layout.positions.values.minOf { it.x })
        assertTrue(layout.finish.x > layout.positions.values.maxOf { it.x })
        assertFalse(layout.cyclic)
        layout.edges.forEach { assertTrue(layout.positions.getValue(it.from).x < layout.positions.getValue(it.to).x) }
    }

    @Test fun treeNestingDoesNotDelayParallelTasksAndSharedStagesAppearOnce() {
        val source = Plan("p", "project", "Goal", milestones = listOf(Milestone("a", "A"), Milestone("b", "B")), tree = listOf(
            DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("a", "group")),
            DecisionNode("a", "A", DecisionKind.STAGE),
            DecisionNode("group", "Group", DecisionKind.GROUP, listOf("nested")),
            DecisionNode("nested", "Nested", DecisionKind.GROUP, listOf("b", "a-copy")),
            DecisionNode("b", "B", DecisionKind.STAGE),
            DecisionNode("a-copy", "Shared A", DecisionKind.STAGE, stageId = "a"),
        ))
        val layout = planningNetworkLayout(source)
        assertEquals(2, layout.positions.size)
        assertEquals(layout.positions.getValue("a").x, layout.positions.getValue("b").x)
        assertEquals(setOf("a", "b"), layout.sources)
        assertEquals(layout.sources, layout.sinks)
    }

    @Test fun emptyPlanStillHasDistinctStartAndFinish() {
        val layout = planningNetworkLayout(Plan("p", "project", "Goal"))
        assertTrue(layout.positions.isEmpty())
        assertTrue(layout.finish.x > layout.start.x)
    }
}
