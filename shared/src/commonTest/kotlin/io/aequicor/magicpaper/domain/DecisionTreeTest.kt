package io.aequicor.magicpaper.domain

import kotlin.test.*
import io.aequicor.magicpaper.ui.components.decisionPositions

class DecisionTreeTest {
    private fun tree() = Plan("p", "project", "goal", milestones = listOf(
        Milestone("a", "A"), Milestone("b", "B"), Milestone("join", "Join"),
    ), tree = listOf(
        DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("choice", "join")),
        DecisionNode("choice", "Choose", DecisionKind.CHOICE, listOf("one", "two"), "one"),
        DecisionNode("one", "One", DecisionKind.OPTION, listOf("a")),
        DecisionNode("two", "Two", DecisionKind.OPTION, listOf("b")),
        DecisionNode("a", "A", DecisionKind.STAGE), DecisionNode("b", "B", DecisionKind.STAGE),
        DecisionNode("join", "Join", DecisionKind.STAGE, dependsOn = listOf("choice")),
    ))
    @Test fun alternativeCompilesWithJoinDependency() {
        val graph = DecisionCompiler.compile(tree())
        assertTrue(graph.valid, graph.errors.toString())
        assertEquals(listOf("a", "join"), graph.stageIds)
        assertEquals(setOf("a"), graph.dependencies["join"])
    }
    @Test fun inactiveDependencyCannotRun() {
        val p = tree().let { it.copy(milestones = it.milestones.map { m -> if (m.id == "join") m.copy(dependsOn = listOf("b")) else m }) }
        assertFalse(DecisionCompiler.compile(p).valid)
    }
    @Test fun cycleInInactiveAlternativeIsRejected() {
        val p = tree().let { it.copy(tree = it.tree.map { n -> if (n.id == "two") n.copy(children = listOf("two")) else n }) }
        assertFalse(DecisionCompiler.compile(p).valid)
    }
    @Test fun sharedStageOnlyExecutesOnce() {
        val p = tree().let { it.copy(tree = it.tree.map { n -> if (n.id == "root") n.copy(children = n.children + "a") else n }) }
        assertEquals(1, DecisionCompiler.compile(p).stageIds.count { it == "a" })
    }
    @Test fun selectionCannotRemoveStartedWork() {
        val old = tree().let { it.copy(milestones = it.milestones.map { m -> if (m.id == "a") m.copy(status = MilestoneStatus.ACTIVE) else m }) }
        val updated = old.copy(tree = old.tree.map { if (it.id == "choice") it.copy(selectedOptionId = "two") else it })
        assertFailsWith<IllegalArgumentException> { DecisionCompiler.validateEdit(old, updated) }
    }
    @Test fun differentCodingDefaultCannotOverrideStageAssignment() {
        val p = LlmProfile("profile", "Profile", baseUrl = "http://local/v1", modelId = "default", codingModelId = "coding", favoriteModels = listOf("gpt-5.4"))
        val assignment = StageAssignment("profile", "gpt-5.4", EffortSelection.of(ReasoningEffort.LOW))
        val actual = assignment.executionProfile(listOf(p)).forCoding()
        assertEquals("gpt-5.4", actual.modelId)
        assertEquals(ReasoningEffort.LOW, actual.effortSelectionFor("gpt-5.4").level)
        assertEquals("coding", p.codingModelId)
    }
    @Test fun graphGeometryHandlesTwoHundredNodesAndCollapse() {
        val nodes = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, (1..200).map { "$it" })) +
            (1..200).map { DecisionNode("$it", "Stage $it", DecisionKind.STAGE) }
        assertEquals(201, decisionPositions(nodes, emptySet()).size)
        assertEquals(1, decisionPositions(nodes, setOf("root")).size)
        assertEquals(201, decisionPositions(nodes, emptySet()).values.distinct().size)
    }
}
