package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class DecisionPlannerTest {
    private val profile = LlmProfile("agent", "Agent", baseUrl = "http://test/v1", modelId = "gpt-5.4")
    private class Gateway(private vararg val replies: String) : LlmGateway {
        var calls = 0
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = replies[calls++.coerceAtMost(replies.lastIndex)]
    }
    private fun plan() = Plan("p", "project", "goal", milestones = listOf(
        Milestone("a", "A", acceptance = "Check A"), Milestone("b", "B", acceptance = "Check B"),
    ), tree = listOf(
        DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("choice")),
        DecisionNode("choice", "Choose", DecisionKind.CHOICE, listOf("one", "two"), "one"),
        DecisionNode("one", "Quality", DecisionKind.OPTION, listOf("a"), assessment = StageAssessment(quality = 3, speed = 1)),
        DecisionNode("two", "Speed", DecisionKind.OPTION, listOf("b"), assessment = StageAssessment(quality = 1, speed = 3)),
        DecisionNode("a", "A", DecisionKind.STAGE), DecisionNode("b", "B", DecisionKind.STAGE),
    ))
    private fun response(p: Plan) = """{"reply":"Evaluated alternatives","tree":${Json.encodeToString(ListSerializer(DecisionNode.serializer()), p.tree)},"milestones":${Json.encodeToString(ListSerializer(Milestone.serializer()), p.milestones)}}"""

    @Test fun prioritiesChangeRecommendationAndManualChoiceIsPreserved() {
        val planner = DecisionPlanner(Gateway())
        val p = plan()
        assertEquals("one", planner.recommendChoices(p).tree.first { it.id == "choice" }.selectedOptionId)
        val fast = p.copy(priorities = PlanningPriorities(quality = 0, speed = 3, economy = 0, safety = 0))
        assertEquals("two", planner.recommendChoices(fast).tree.first { it.id == "choice" }.selectedOptionId)
        val manual = fast.copy(tree = fast.tree.map { if (it.id == "choice") it.copy(manualSelection = true) else it })
        assertEquals("one", planner.recommendChoices(manual).tree.first { it.id == "choice" }.selectedOptionId)
        assertEquals(0.0, fast.priorities.score(StageAssessment()))
    }

    @Test fun refinementRepairsInvalidJsonAndKeepsManualAssignment() = runTest {
        val assignment = StageAssignment("agent", "gpt-5.4", EffortSelection.of(ReasoningEffort.LOW), manual = true)
        val original = plan().let { p -> p.copy(milestones = p.milestones.map { if (it.id == "a") it.copy(assignment = assignment) else it }) }
        val gateway = Gateway("{broken", response(original))
        val result = PlanComposer(gateway).refine(original, "Develop alternatives", profile, listOf(profile), emptyList())
        assertEquals(2, gateway.calls)
        assertEquals(assignment, result.milestones.first { it.id == "a" }.assignment)
        assertTrue(DecisionCompiler.compile(result).valid)
    }

    @Test fun correctionLimitLeavesTheLastValidPlanUntouched() = runTest {
        val gateway = Gateway("{}")
        val original = plan()
        assertFailsWith<IllegalStateException> { PlanComposer(gateway).refine(original, "Refine", profile, listOf(profile), emptyList()) }
        assertEquals(3, gateway.calls)
        assertEquals(plan(), original)
    }

    @Test fun targetedRecalculationPreservesUnrelatedBranch() = runTest {
        val original = plan()
        val proposal = original.copy(tree = original.tree.map { if (it.id == "b") it.copy(title = "unwanted change") else it },
            milestones = original.milestones.map { it.copy(description = "proposed change") })
        val result = PlanComposer(Gateway(response(proposal))).recalculate(original, "a", profile, listOf(profile), emptyList())
        assertEquals(original.tree.first { it.id == "b" }, result.tree.first { it.id == "b" })
        assertEquals(original.milestones.first { it.id == "b" }, result.milestones.first { it.id == "b" })
        assertEquals("proposed change", result.milestones.first { it.id == "a" }.description)
    }

    @Test fun verifierRequiresVerdictSchemaAndNeverAcceptsEmptyReport() = runTest {
        val gateway = Gateway("{}", """{"passed":true,"note":"actual tests passed"}""")
        val verifier = LlmMilestoneVerifier(gateway)
        assertFalse(verifier.verify(plan().milestones.first(), "goal", "", profile).passed)
        assertEquals(0, gateway.calls)
        assertTrue(verifier.verify(plan().milestones.first(), "goal", "checks passed", profile).passed)
        assertEquals(2, gateway.calls)
    }
    @Test fun plannerChoosesOnlyFavoritesAndKeepsItsSupportedEffortRecommendation() = runTest {
        val p = profile.copy(modelLibraryVersion = 1, favoriteModels = listOf("gpt-5.4"))
        val proposal = plan().let { it.copy(milestones = it.milestones.map { stage -> stage.copy(
            assignment = StageAssignment(p.id, "gpt-5.4", EffortSelection.of(ReasoningEffort.LOW), explanation = "A small edit")) }) }
        val result = PlanComposer(Gateway(response(proposal))).refine(plan(), "Choose models", p, listOf(p), emptyList())
        assertTrue(result.milestones.all { it.assignment?.modelId == "gpt-5.4" && it.assignment?.effort?.level == ReasoningEffort.LOW })
    }

    @Test fun plannerCannotAssignAnUnfavoritedModelEvenIfItIsOperationalDefault() = runTest {
        val p = profile.copy(modelId = "not-favorite", favoriteModels = listOf("gpt-5.4"), modelLibraryVersion = 1)
        val proposal = plan().let { it.copy(milestones = it.milestones.map { stage -> stage.copy(
            assignment = StageAssignment(p.id, "not-favorite", EffortSelection.of(ReasoningEffort.HIGH))) }) }
        val result = PlanComposer(Gateway(response(proposal))).refine(plan(), "Choose models", p, listOf(p), emptyList())
        assertTrue(result.milestones.all { it.assignment?.modelId == "gpt-5.4" })
    }

}
