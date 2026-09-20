package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanOrchestrationMachineTest {
    private val json = Json { encodeDefaults = true }
    private val assignment = StageAssignment("old", "model")
    private val attempt = StageAttempt("attempt", "worker", assignment, phase = AttemptPhase.EXECUTING, sessionGeneration = 1)
    private val stage = Milestone("stage", "Stage", description = "Original requirement", assignment = assignment)
    private val plan = Plan("plan", "project", "Goal", parentSessionId = "parent", runId = "run", revision = 4,
        milestones = listOf(stage), wizardStep = PlanningStep.REVIEW)
    private fun Plan.on(event: PlanEvent) = reduce(this, event)

    @Test fun requestReplayKeepsTheSubtreeAndStaleCancellationCannotClearANewerRequest() {
        val first = plan.on(PlanRevisionEvent.RequestStarted("first", "edit", "node"))
        val replayed = first.on(PlanRevisionEvent.RequestStarted("first", "edit", null))
        assertEquals(first, replayed)
        val second = replayed.on(PlanRevisionEvent.RequestStarted("second", "new edit", null))
        assertNull(second.pendingRecalculationNodeId)
        assertEquals(second, second.on(PlanRevisionEvent.RequestCleared("first")))
        assertEquals("", second.on(PlanRevisionEvent.RequestCleared("second")).pendingRequest)
        assertEquals(listOf("first", "second"), second.dialogue.map { it.id })
    }

    @Test fun initialApprovalUsesExplicitIdentityAndTimeAndDoesNotApproveANewerRevision() {
        val event = PlanRevisionEvent.InitialConfirmed(plan.revision, false, "new-run", 123)
        val initial = plan.copy(runId = "")
        val approved = initial.on(event)
        assertEquals(approved, initial.on(event))
        assertEquals("new-run", approved.runId)
        assertEquals(123L, approved.versions.single().at)
        assertEquals(plan.revision, approved.confirmedRevision)
        assertEquals(ExecutionIntent.RUN, approved.intent)
        assertEquals(approved, approved.on(event))
        assertFailsWith<IllegalArgumentException> { plan.copy(revision = 5).on(event) }
        assertFailsWith<IllegalArgumentException> { initial.on(event.copy(openQuestions = true)) }
        assertFailsWith<IllegalArgumentException> { initial.copy(wizardStep = PlanningStep.CLARIFY).on(event) }
    }

    private fun completedProposal(): Pair<Plan, PlanProposal> {
        val done = stage.copy(status = MilestoneStatus.DONE, attempts = listOf(attempt.copy(phase = AttemptPhase.COMPLETE, resultCommit = "accepted")))
        val original = plan.copy(milestones = listOf(done), confirmedRevision = plan.revision, phase = ExecutionPhase.COMPLETE,
            finalAttempt = attempt.copy(id = "final", phase = AttemptPhase.COMPLETE), workspace = PlanWorkspace("/root", "/work", applied = true))
        val proposal = PlanProposal("proposal", original.runId, original.tree, original.milestones, original.tree,
            original.milestones + stage.copy(id = "followup", dependsOn = listOf(stage.id)), "New requirements")
        return original.copy(proposal = proposal) to proposal
    }

    @Test fun approvedFollowupArchivesTheExactCheckpointAndCommitsRequirementsBeforeExecution() {
        val (base, proposal) = completedProposal()
        val event = PlanRevisionEvent.ProposalApproved(proposal, base.revision, false, setOf(stage.id, "followup"), "next-run", false, 100)
        val next = base.on(event)
        assertEquals(next, base.on(event))
        assertEquals("next-run", next.runId)
        assertNull(next.workspace)
        assertNull(next.finalAttempt)
        assertEquals(false, next.worktreeEnabled)
        assertEquals(base.milestones, next.runHistory.single().milestones)
        assertEquals(base.finalAttempt, next.finalAttemptHistory.single())
        assertEquals("accepted", next.milestones.first().attempts.single().resultCommit)
        assertEquals(listOf(DeliveryState.ANSWERED, DeliveryState.QUEUED), next.deliveries.map { it.state })
        assertEquals("proposal-approved-followup", next.deliveries.last().id)
        assertEquals(ExecutionPhase.RECOVERING, next.phase)
        assertFailsWith<IllegalArgumentException> { next.on(event) }
    }

    @Test fun approvalRejectsReplacedProposalChangedBasisAndUnsettledCheckpointAtCommit() {
        val (base, proposal) = completedProposal()
        val event = PlanRevisionEvent.ProposalApproved(proposal, base.revision, false, emptySet(), "next", true, 100)
        assertFailsWith<IllegalArgumentException> { base.copy(proposal = proposal.copy(explanation = "replacement with same ID")).on(event) }
        assertFailsWith<IllegalArgumentException> { base.copy(runId = "other-run").on(event) }
        assertFailsWith<IllegalArgumentException> { base.copy(milestones = base.milestones.map { it.copy(description = "changed") }).on(event) }
        assertFailsWith<IllegalArgumentException> { base.copy(phase = ExecutionPhase.APPLYING).on(event) }
        assertFailsWith<IllegalArgumentException> { base.on(event.copy(openQuestions = true)) }
    }

    @Test fun decliningAStaleProposalCannotDiscardItsReplacement() {
        val (base, proposal) = completedProposal()
        val event = PlanRevisionEvent.ProposalDeclined(base.revision, proposal.id)
        assertNull(base.on(event).proposal)
        assertFailsWith<IllegalArgumentException> { base.copy(revision = base.revision + 1).on(event) }
        assertFailsWith<IllegalArgumentException> { base.copy(proposal = proposal.copy(id = "replacement")).on(event) }
    }

    @Test fun refinementOutputCanRebaseOverTelemetryButCannotRewriteStartedWork() {
        val pending = plan.on(PlanRevisionEvent.RequestStarted("request", "revise", null))
        val active = pending.copy(milestones = listOf(stage.copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt.copy(report = "new output")))))
        val result = pending.copy(dialogue = pending.dialogue + PlanningMessage("reply", "assistant", "unchanged"))
        val next = active.on(PlanRevisionEvent.ProposalApplied(pending, result))
        assertEquals(active.milestones.single(), next.milestones.single())
        assertEquals(result.dialogue, next.dialogue)
        assertFailsWith<IllegalArgumentException> {
            active.on(PlanRevisionEvent.ProposalApplied(pending, result.copy(milestones = listOf(stage.copy(description = "new spec")))))
        }
        assertFailsWith<IllegalArgumentException> { active.copy(goal = "different goal").on(PlanRevisionEvent.ProposalApplied(pending, result)) }
    }

    @Test fun assignmentRecoveryCannotOverwriteAConcurrentSelectionCompletedAttemptOrNewRun() {
        val base = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt))))
        val replacement = assignment.copy(profileId = "replacement")
        val candidate = base.copy(milestones = listOf(stage.copy(assignment = replacement, attempts = listOf(attempt.copy(assignment = replacement)))))
        val event = PlanRevisionEvent.AssignmentsRecovered(base, candidate)
        val applied = base.on(event)
        assertEquals(replacement, applied.milestones.single().attempts.single().assignment)
        val selected = assignment.copy(profileId = "user-selection")
        val concurrent = base.copy(milestones = listOf(stage.copy(assignment = selected, attempts = listOf(attempt.copy(assignment = selected, report = "keep me")))))
        assertEquals(concurrent, concurrent.on(event))
        val completed = base.copy(milestones = listOf(stage.copy(status = MilestoneStatus.DONE, attempts = listOf(attempt.copy(phase = AttemptPhase.COMPLETE)))))
        assertEquals(completed, completed.on(event))
        val nextRun = base.copy(runId = "new")
        assertEquals(nextRun, nextRun.on(event))
        val generation = base.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(sessionGeneration = 2)))))
        assertEquals(assignment, generation.on(event).milestones.single().attempts.single().assignment)
    }

    @Test fun nativeHandoffReplayPreservesTheReceiptAndConflictingReplyIsRejected() {
        val record = CoordinationRecord("attempt-turn-0", stage.id, StageReply(StageReplyKind.RESULT, "done"), toolCallId = "tool")
        val saved = plan.on(CoordinationEvent.HandoffSubmitted(record))
        assertEquals(saved, saved.on(CoordinationEvent.HandoffSubmitted(record.copy(toolCallId = "replayed-tool"))))
        assertFailsWith<HandoffConflict> { saved.on(CoordinationEvent.HandoffSubmitted(record.copy(reply = record.reply.copy(text = "changed")))) }
    }

    @Test fun legacyLinkingAndQuestionRecoveryRetainAttemptsAndNeverStartStoppedWork() {
        val waiting = attempt.copy(error = PlanningIssue(IssueKind.CONFIGURATION, "Ожидается ответ планировщику"))
        val stopped = plan.copy(parentSessionId = "", intent = ExecutionIntent.STOP, milestones = listOf(stage.copy(attempts = listOf(waiting))))
        val linked = stopped.on(PlanRevisionEvent.LegacyLinked("legacy-parent", 50))
        assertEquals(stopped.intent, linked.intent)
        assertEquals(stopped.phase, linked.phase)
        assertEquals(stopped.milestones, linked.milestones)
        assertEquals("legacy-parent", linked.parentSessionId)
        val engine = linked.on(PlanRevisionEvent.EngineRestored(CodingEngine.CODEX)).on(PlanRevisionEvent.EngineRestored(CodingEngine.PI))
        assertEquals(CodingEngine.CODEX, engine.engine)
        val question = OrchestrationQuestion("question", plan.id, "Need answer", listOf(PlanningQuestion("q", "Question")), "parent", stageIds = listOf(stage.id))
        val restored = linked.on(PlanRevisionEvent.LegacyQuestionsObserved(listOf(question)))
        assertEquals("question", restored.milestones.single().attempts.single().waitingForUser)
        assertNull(restored.milestones.single().attempts.single().error)
        assertEquals(ExecutionIntent.STOP, restored.intent)
        assertEquals(linked, json.decodeFromString<Plan>(json.encodeToString(linked)))
    }
}
