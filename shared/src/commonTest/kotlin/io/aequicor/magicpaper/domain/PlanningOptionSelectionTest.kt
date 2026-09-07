package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.components.*
import kotlin.test.*

class PlanningOptionSelectionTest {
    private fun plan() = Plan("p", "project", "Goal", milestones = listOf(
        Milestone("a", "A", complexityPoints = 3.0), Milestone("b", "B", complexityPoints = 5.0),
        Milestone("done", "Done", complexityPoints = 2.0),
    ), tree = listOf(
        DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("choice", "done")),
        DecisionNode("choice", "Approach", DecisionKind.CHOICE, listOf("one", "two"), "one"),
        DecisionNode("one", "First", DecisionKind.OPTION, listOf("a")),
        DecisionNode("two", "Second", DecisionKind.OPTION, listOf("b")),
        DecisionNode("a", "A", DecisionKind.STAGE), DecisionNode("b", "B", DecisionKind.STAGE),
        DecisionNode("done", "Done", DecisionKind.STAGE, dependsOn = listOf("choice")),
    ))

    @Test fun switchingRouteChangesDependenciesAndWeightWithoutDeletingAlternatives() {
        val original = plan()
        val changed = selectPlanningOption(original, "choice", "two")
        assertEquals(original.milestones, changed.milestones)
        assertEquals(original.tree.map { it.id }, changed.tree.map { it.id })
        assertTrue(changed.tree.first { it.id == "choice" }.manualSelection)
        assertEquals(setOf("b"), DecisionCompiler.compile(changed).dependencies["done"])
        assertEquals(7.0, criticalPathSchedule(changed).complexityPoints)
        assertEquals(5.0, criticalPathSchedule(original).complexityPoints)
        val positions = planningAlternativePositions(changed, emptySet())
        assertEquals(changed.milestones.map { it.id }.toSet(), positions.keys)
        assertEquals(positions.size, positions.values.toSet().size)
        planningNetworkLayout(changed).edges.forEach { edge -> assertTrue(positions.getValue(edge.from).x < positions.getValue(edge.to).x) }
        assertEquals(2, planningGraphEdges(changed).count { it.kind == PlanningEdgeKind.ALTERNATIVE })
    }

    @Test fun cannotReplaceStartedWorkOrChooseUnknownOption() {
        val original = plan()
        val started = original.copy(milestones = original.milestones.map { if (it.id == "a") it.copy(status = MilestoneStatus.ACTIVE) else it })
        assertFailsWith<IllegalArgumentException> { selectPlanningOption(started, "choice", "two") }
        assertFailsWith<IllegalArgumentException> { selectPlanningOption(original, "choice", "a") }
        val invalid = original.copy(milestones = original.milestones.map { if (it.id == "b") it.copy(dependsOn = listOf("a")) else it })
        assertFailsWith<IllegalArgumentException> { selectPlanningOption(invalid, "choice", "two") }
    }

    @Test fun unknownEstimatesAndCyclesKeepAllAlternativesVisible() {
        val unknown = plan().copy(milestones = plan().milestones.map { it.copy(complexityPoints = null) })
        assertNull(criticalPathSchedule(unknown).complexityPoints)
        assertEquals(unknown.milestones.size, planningAlternativePositions(unknown, emptySet()).size)
        val cycle = unknown.copy(tree = unknown.tree.map { if (it.id == "choice") it.copy(dependsOn = listOf("done")) else it })
        assertTrue(planningNetworkLayout(cycle).cyclic)
        assertEquals(cycle.milestones.size, planningAlternativePositions(cycle, emptySet()).size)
        assertFalse("a" in planningAlternativePositions(unknown, setOf("one")))
    }

    @Test fun nestedChoiceChangesOnlyTheSelectedSubpath() {
        val base = plan()
        val nested = base.copy(milestones = base.milestones + Milestone("alt", "Alternative A", complexityPoints = 1.0),
            tree = base.tree.map { if (it.id == "one") it.copy(children = listOf("nested")) else it } + listOf(
                DecisionNode("nested", "Nested", DecisionKind.CHOICE, listOf("n1", "n2"), "n1"),
                DecisionNode("n1", "Original task", DecisionKind.OPTION, listOf("a")),
                DecisionNode("n2", "Alternative task", DecisionKind.OPTION, listOf("alt")),
                DecisionNode("alt", "Alternative A", DecisionKind.STAGE),
            ))
        val changed = selectPlanningOption(nested, "nested", "n2")
        assertEquals("one", changed.tree.first { it.id == "choice" }.selectedOptionId)
        assertEquals(setOf("alt", "done"), DecisionCompiler.compile(changed).stageIds.toSet())
        assertEquals(changed.milestones.size, planningAlternativePositions(changed, emptySet()).size)
    }

    @Test fun oldComplexityGradesAreUsableButHoursAreNotReinterpreted() {
        val source = Plan("p", "project", "Goal", milestones = listOf(
            Milestone("a", "A", assessment = StageAssessment(complexity = 3), durationHours = 100.0)))
        assertEquals(3.0, criticalPathSchedule(source).complexityPoints)
        assertNull(criticalPathSchedule(source.copy(milestones = source.milestones.map { it.copy(assessment = StageAssessment()) })).complexityPoints)
    }
}
