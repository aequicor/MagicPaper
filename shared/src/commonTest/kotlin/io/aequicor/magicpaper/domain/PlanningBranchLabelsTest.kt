package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.components.planningStageBranches
import kotlin.test.*

class PlanningBranchLabelsTest {
    @Test fun labelsNestedAndUnselectedPathsAndRecognizesSharedTasks() {
        val plan = Plan("p", "project", "Goal", tree = listOf(
            DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("choice")),
            DecisionNode("choice", "Approach", DecisionKind.CHOICE, listOf("one", "two"), "one"),
            DecisionNode("one", "Library", DecisionKind.OPTION, listOf("nested", "shared")),
            DecisionNode("two", "Custom", DecisionKind.OPTION, listOf("b", "shared")),
            DecisionNode("nested", "Library choice", DecisionKind.CHOICE, listOf("x", "y"), "x"),
            DecisionNode("x", "Local", DecisionKind.OPTION, listOf("a")),
            DecisionNode("y", "Remote", DecisionKind.OPTION, listOf("c")),
            DecisionNode("a", "A", DecisionKind.STAGE), DecisionNode("b", "B", DecisionKind.STAGE),
            DecisionNode("c", "C", DecisionKind.STAGE), DecisionNode("shared", "Shared", DecisionKind.STAGE),
        ))
        val labels = planningStageBranches(plan)
        assertEquals(listOf("Library", "Local"), labels.getValue("a").labels.map { it.title })
        assertEquals(listOf("Library", "Remote"), labels.getValue("c").labels.map { it.title })
        assertEquals(listOf("Custom"), labels.getValue("b").labels.map { it.title })
        assertTrue(labels.getValue("shared").shared)
        assertFalse(labels.getValue("a").shared)
        val switched = plan.copy(tree = plan.tree.map { if (it.id == "choice") it.copy(selectedOptionId = "two") else it })
        assertEquals(labels, planningStageBranches(switched))
    }

    @Test fun aSingleLegacyOptionDoesNotIntroduceAlternativeLabels() {
        val plan = DecisionCompiler.migrate(Plan("p", "project", "Goal", milestones = listOf(Milestone("a", "A"))))
        assertTrue(planningStageBranches(plan).isEmpty())
    }
}
