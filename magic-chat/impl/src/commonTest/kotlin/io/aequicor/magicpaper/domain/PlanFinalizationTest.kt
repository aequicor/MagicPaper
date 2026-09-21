package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanFinalizationTest {
    private fun plan() = Plan("plan", "project", "Move button", wizardStep = PlanningStep.REVIEW,
        milestones = listOf(Milestone("edit", "Edit"), Milestone("test", "Test", dependsOn = listOf("edit"))),
        tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, children = listOf("edit", "test")),
            DecisionNode("edit", "Edit", DecisionKind.STAGE), DecisionNode("test", "Test", DecisionKind.STAGE)))

    @Test fun addsAnExplicitCommitTaskAfterAllWorkAndDoesNotDuplicateIt() {
        val result = plan().withFinalization { "commit" }
        val final = result.milestones.single { it.isFinalization }
        assertEquals("Коммит и итог", final.title)
        assertEquals(setOf("edit", "test"), DecisionCompiler.compile(result).dependencies[final.id])
        assertContains(final.acceptance, "хешами")
        assertEquals(result, result.withFinalization { error("Do not create a second endpoint") })
        assertEquals(plan().milestones, result.milestones.filterNot { it.isFinalization })
    }

    @Test fun finalTaskFollowsTheSelectedAlternativeWithoutDependingOnInactiveStages() {
        val base = plan().copy(milestones = listOf(Milestone("a", "A"), Milestone("b", "B")),
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, children = listOf("choice")),
                DecisionNode("choice", "Choice", DecisionKind.CHOICE, children = listOf("one", "two"), selectedOptionId = "one"),
                DecisionNode("one", "One", DecisionKind.OPTION, children = listOf("a")),
                DecisionNode("two", "Two", DecisionKind.OPTION, children = listOf("b")),
                DecisionNode("a", "A", DecisionKind.STAGE), DecisionNode("b", "B", DecisionKind.STAGE)))
        val result = base.withFinalization { "commit" }
        assertEquals(setOf("a"), DecisionCompiler.compile(result).dependencies["commit"])
        val switched = result.copy(tree = result.tree.map { if (it.id == "choice") it.copy(selectedOptionId = "two") else it })
        assertTrue(DecisionCompiler.compile(switched).valid)
        assertEquals(setOf("b"), DecisionCompiler.compile(switched).dependencies["commit"])
    }

    @Test fun newWorkUpdatesAnUnstartedEndpointButNeverRewritesACompletedCommit() {
        val initial = plan().withFinalization { "commit" }
        fun extend(p: Plan) = p.copy(milestones = p.milestones + Milestone("extra", "Extra"),
            tree = p.tree.map { if (it.id == "root") it.copy(children = it.children + "extra") else it } + DecisionNode("extra", "Extra", DecisionKind.STAGE))
        val pending = extend(initial).withFinalization { error("Reuse the pending endpoint") }
        assertEquals(1, pending.milestones.count { it.isFinalization })
        assertEquals(setOf("edit", "test", "extra"), DecisionCompiler.compile(pending).dependencies["commit"])
        val completed = initial.copy(milestones = initial.milestones.map { it.copy(status = MilestoneStatus.DONE) })
        val continuation = extend(completed).withFinalization { "next-commit" }
        DecisionCompiler.validateEdit(completed, continuation)
        assertEquals(completed.milestones, continuation.milestones.take(completed.milestones.size))
        assertEquals(setOf("edit", "test", "commit", "extra"), DecisionCompiler.compile(continuation).dependencies["next-commit"])
    }

    @Test fun clarificationDoesNotChangeTheProposedWork() {
        val question = plan().copy(wizardStep = PlanningStep.CLARIFY)
        assertEquals(question, question.withFinalization { error("A question must not add tasks") })
    }
}
