package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class PlanningRefinementRulesTest {
    private val assignment = StageAssignment("profile", "model")
    private val stage = Milestone("stage", "Stage", description = "Original", assignment = assignment,
        acceptance = "Expected result", displayNumber = 7, displayName = "User title")
    private val attempt = StageAttempt("attempt", "worker", assignment, phase = AttemptPhase.EXECUTING,
        sessionGeneration = 1, report = "Accepted progress")
    private val plan = Plan("plan", "project", "Goal", runId = "run", revision = 4,
        parentSessionId = "parent", milestones = listOf(stage), wizardStep = PlanningStep.REVIEW,
        tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf(stage.id)),
            DecisionNode(stage.id, stage.title, DecisionKind.STAGE, stageId = stage.id)))
    private fun pending(base: Plan = plan) = base.copy(requestId = "request", pendingRequest = "Refine",
        pendingRecalculationNodeId = "root", dialogue = base.dialogue + PlanningMessage("request", "user", "Refine"))
    private fun request(base: Plan, approval: Boolean = false) = base.captureRefinement(base.requestId, "admission",
        approval, null, SearchProvider.AUTO)
    private fun result(base: Plan, spec: PlanSpecification = PlanSpecification.from(base)) =
        RefinementResult(spec, PlanningMessage("request-reply", "assistant", "Updated"), PlanningStep.REVIEW)

    @Test fun wireSpecificationCannotCarryAttemptsResultsOrExecutionAuthority() {
        val injected = plan.copy(status = PlanStatus.DONE, workspace = PlanWorkspace("root", "work"),
            milestones = listOf(stage.copy(status = MilestoneStatus.DONE, attempts = listOf(attempt), report = "Forged")))
        val wire = Json.encodeToString(PlanSpecification.from(injected))
        val parsed = Json.parseToJsonElement(wire).jsonObject
        assertEquals(setOf("goal", "tree", "stages"), parsed.keys)
        val stageWire = parsed.getValue("stages").jsonArray.single().jsonObject
        assertTrue(setOf("attempts", "status", "report", "checkNote", "updatedAt", "displayNumber", "displayName").none { it in stageWire })
        val decoded = Json.decodeFromString<PlanSpecification>(wire)
        val clean = decoded.stages.single().milestone()
        assertEquals(MilestoneStatus.PENDING, clean.status)
        assertTrue(clean.attempts.isEmpty())
        assertEquals("", clean.report)
        assertNull(clean.displayNumber)
    }

    @Test fun resultRebasesOverStartedTelemetryAndFinishesRequestAtomically() {
        val base = pending()
        val captured = request(base)
        val active = base.copy(revision = 8, milestones = listOf(stage.copy(status = MilestoneStatus.ACTIVE,
            attempts = listOf(attempt.copy(report = "Latest output", turnIndex = 3)), checkNote = "Latest check")))
        val completed = active.finishRefinement(captured, result(base), 120)
        assertEquals(active.milestones, completed.milestones)
        assertEquals(active.revision, completed.revision)
        assertEquals("", completed.requestId)
        assertEquals("", completed.pendingRequest)
        assertNull(completed.pendingRecalculationNodeId)
        assertEquals(listOf("request", "request-reply"), completed.dialogue.map { it.id })
        assertEquals(active.intent, completed.intent)
        assertEquals(active.phase, completed.phase)
        assertEquals(active.workspace, completed.workspace)
    }

    @Test fun obsoleteReplyCannotClearNewRequestDifferentRunOrInterveningEdit() {
        val base = pending()
        val captured = request(base)
        val output = result(base)
        val changed = listOf(base.copy(requestId = "new", pendingRequest = "Keep this"),
            base.copy(runId = "other"), base.copy(projectId = "other"),
            base.copy(goal = "Other"), base.copy(dialogue = base.dialogue + PlanningMessage("later", "user", "Keep")))
        changed.forEach { assertFailsWith<IllegalArgumentException> { it.finishRefinement(captured, output, 120) } }
        assertEquals("request", base.requestId)
    }

    @Test fun startedStageCannotBeChangedRemovedOrReplacedBySpec() {
        val base = pending()
        val active = base.copy(milestones = listOf(stage.copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))))
        val captured = request(base)
        val edited = PlanSpecification.from(base).copy(stages = listOf(StageSpecification.from(stage).copy(description = "Changed")))
        assertFailsWith<IllegalArgumentException> { active.finishRefinement(captured, result(base, edited), 120) }
        val removed = PlanSpecification.from(base).copy(stages = emptyList(),
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL)))
        assertFailsWith<IllegalArgumentException> { active.applySpecification(removed) }
    }

    @Test fun editableGoalAndDefinitionApplyWithoutChangingSavedIdentityOrPolicy() {
        val spec = PlanSpecification.from(plan).copy(goal = "Updated goal",
            tree = plan.tree.map { if (it.id == "root") it.copy(title = "Updated goal") else it },
            stages = listOf(StageSpecification.from(stage).copy(description = "New requirement")))
        val saved = plan.applySpecification(spec)
        assertEquals("Updated goal", saved.goal)
        assertEquals("New requirement", saved.milestones.single().description)
        assertEquals(stage.displayNumber, saved.milestones.single().displayNumber)
        assertEquals(stage.displayName, saved.milestones.single().displayName)
        assertEquals(plan.id, saved.id)
        assertEquals(plan.runId, saved.runId)
        assertEquals(plan.intent, saved.intent)
        val started = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt))))
        assertFailsWith<IllegalArgumentException> { started.applySpecification(spec.copy(stages = PlanSpecification.from(plan).stages)) }
    }

    @Test fun newStagesStartWithNoOutcomeAndRevisionHistoryContainsOnlyDefinition() {
        val base = pending()
        val captured = request(base)
        val added = StageSpecification("next", "Next", dependsOn = listOf(stage.id))
        val spec = captured.basis.copy(stages = captured.basis.stages + added,
            tree = captured.basis.tree.map { if (it.id == "root") it.copy(children = it.children + added.id) else it } +
                DecisionNode(added.id, added.title, DecisionKind.STAGE, stageId = added.id))
        val saved = base.finishRefinement(captured, result(base, spec), 123)
        assertTrue(saved.milestones.last().attempts.isEmpty())
        assertEquals(MilestoneStatus.PENDING, saved.milestones.last().status)
        assertEquals(captured.baseRevision, saved.versions.single().revision)
        assertEquals(123L, saved.versions.single().at)
        assertEquals(captured.basis.stages.map(StageSpecification::milestone), saved.versions.single().milestones)
    }

    @Test fun proposalRevisesOnlySafePausedDefinitionAndLeavesCurrentRuntimeUntouched() {
        val stopped = plan.copy(confirmedRevision = 4, phase = ExecutionPhase.WAITING,
            milestones = listOf(stage.copy(status = MilestoneStatus.ACTIVE,
                attempts = listOf(attempt.copy(interrupted = true)))))
        val base = pending(stopped)
        val captured = request(base, approval = true)
        assertEquals(RefinementMode.PROPOSAL, captured.mode)
        val spec = captured.basis.copy(stages = captured.basis.stages.map { it.copy(description = "Revised") })
        val saved = base.finishRefinement(captured, result(base, spec), 120)
        assertEquals(base.milestones, saved.milestones)
        assertEquals(base.phase, saved.phase)
        assertEquals(base.intent, saved.intent)
        assertEquals("Revised", saved.proposal!!.milestones.single().description)
        assertEquals(base.milestones, saved.proposal!!.baseMilestones)
        assertFailsWith<IllegalArgumentException> { base.finishRefinement(captured, result(base, spec.copy(goal = "New goal")), 120) }
    }

    @Test fun proposalCannotModifyActivelyRunningStageOrAcceptChangedProposalBasis() {
        val base = pending(plan.copy(confirmedRevision = 4,
            milestones = listOf(stage.copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt)))))
        val captured = request(base, approval = true)
        val spec = captured.basis.copy(stages = captured.basis.stages.map { it.copy(description = "Changed") })
        assertFailsWith<IllegalArgumentException> { base.finishRefinement(captured, result(base, spec), 120) }
        val changed = base.copy(proposal = PlanProposal("other", base.runId, base.tree, base.milestones,
            base.tree, base.milestones, "Another request"))
        assertFailsWith<IllegalArgumentException> { changed.finishRefinement(captured, result(base), 120) }
    }

    @Test fun duplicateIdsExistingMessageAndExecutionStepAreRejected() {
        val base = pending()
        val captured = request(base)
        assertFailsWith<IllegalArgumentException> { base.applySpecification(captured.basis.copy(stages = captured.basis.stages + captured.basis.stages)) }
        assertFailsWith<IllegalArgumentException> { base.applySpecification(captured.basis.copy(tree = captured.basis.tree + captured.basis.tree.first())) }
        assertFailsWith<IllegalArgumentException> { base.finishRefinement(captured, result(base).copy(assistant = PlanningMessage("request", "assistant", "Overwrite")), 120) }
        assertFailsWith<IllegalArgumentException> { base.finishRefinement(captured, result(base).copy(step = PlanningStep.STATUS), 120) }
    }

    @Test fun serializedRequestAndResultReplayDeterministicallyWithoutGrantingWork() {
        val base = pending()
        val captured = Json.decodeFromString<RefinementRequest>(Json.encodeToString(request(base)))
        val output = Json.decodeFromString<RefinementResult>(Json.encodeToString(result(base)))
        val first = base.finishRefinement(captured, output, 123)
        val repeated = Json.decodeFromString<Plan>(Json.encodeToString(base)).finishRefinement(captured, output, 123)
        assertEquals(first, repeated)
        assertEquals(base.intent, first.intent)
        assertEquals(base.phase, first.phase)
        assertEquals(base.runId, first.runId)
    }
}
