package io.aequicor.magicpaper.domain

import kotlin.test.*

class TaskIdentifiersTest {
    @Test fun newAliasesAreRewrittenAcrossTheGraphWhileExistingIdentitiesSurvive() {
        val old = Plan("p", "project", "Goal", milestones = listOf(Milestone("existing", "Existing")))
        val draft = old.copy(milestones = old.milestones + listOf(Milestone("a", "Duplicate"), Milestone("b", "Duplicate", dependsOn = listOf("a", "existing"), continuationOf = "existing")),
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, children = listOf("existing", "a", "b")),
                DecisionNode("existing", "Existing", DecisionKind.STAGE, stageId = "existing"),
                DecisionNode("a", "Duplicate", DecisionKind.STAGE, stageId = "a"), DecisionNode("b", "Duplicate", DecisionKind.STAGE, stageId = "b", dependsOn = listOf("a"))),
            dialogue = listOf(PlanningMessage("q", "assistant", "Question", questionStageIds = listOf("a", "b"))))
        val ids = ArrayDeque(listOf("existing", "a", "generated-a", "generated-b"))
        val saved = draft.allocateTaskIdentifiers(old) { ids.removeFirst() }
        assertEquals(listOf("existing", "generated-a", "generated-b"), saved.milestones.map { it.id })
        assertEquals(listOf("generated-a", "existing"), saved.milestones.last().dependsOn)
        assertEquals("existing", saved.milestones.last().continuationOf)
        assertEquals(listOf("existing", "generated-a", "generated-b"), saved.tree.first().children)
        assertEquals(listOf("generated-a", "generated-b"), saved.dialogue.single().questionStageIds)
        assertTrue(DecisionCompiler.compile(saved).valid)
        assertEquals(saved, saved.allocateTaskIdentifiers(saved) { error("Existing identities must not be reissued") })
    }
}
