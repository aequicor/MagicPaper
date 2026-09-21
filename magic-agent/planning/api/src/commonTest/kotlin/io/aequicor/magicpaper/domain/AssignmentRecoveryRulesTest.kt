package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AssignmentRecoveryRulesTest {
    private val original = StageAssignment("old", "model")
    private val replacement = original.copy(profileId = "replacement")
    private val attempt = StageAttempt("attempt", "worker", original, phase = AttemptPhase.EXECUTING,
        sessionGeneration = 2, turnIndex = 3, report = "Keep output", pendingTool = "write", pendingToolExternal = true)
    private val stage = Milestone("stage", "Stage", assignment = original, status = MilestoneStatus.ACTIVE,
        attempts = listOf(attempt), report = "Keep stage output")
    private val plan = Plan("plan", "project", "Goal", runId = "run", milestones = listOf(stage),
        intent = ExecutionIntent.PAUSE, phase = ExecutionPhase.WAITING,
        issue = PlanningIssue(IssueKind.UNCERTAIN, "Unknown result", requiresUser = true))
    private val recovery = AssignmentRecovery(AssignmentRecoveryRef(plan.id, plan.projectId, plan.runId, 4),
        stages = listOf(StageAssignmentChange(stage.id, original, replacement)),
        attempts = listOf(AttemptAssignmentChange(stage.id, PlanningMachine.AttemptRef.from(attempt),
            original, replacement, null, null)))

    @Test fun changesOnlyBindingsAndPreservesUnknownOutcomeAndExecutionTelemetry() {
        val saved = plan.applyAssignmentRecovery(recovery, 4)
        val expectedAttempt = attempt.copy(assignment = replacement)
        assertEquals(plan.copy(milestones = listOf(stage.copy(assignment = replacement,
            attempts = listOf(expectedAttempt)))), saved)
        assertTrue(saved.milestones.single().attempts.single().pendingToolExternal)
        assertEquals(plan, plan.applyAssignmentRecovery(recovery, 5))
        assertEquals(plan.copy(runId = "new"), plan.copy(runId = "new").applyAssignmentRecovery(recovery, 4))
    }

    @Test fun staleAttemptGenerationPhaseAndRetryCounterCannotReplaceBinding() {
        val changed = listOf(attempt.copy(sessionGeneration = 3), attempt.copy(sessionId = "other"),
            attempt.copy(turnIndex = 4), attempt.copy(phase = AttemptPhase.VERIFYING),
            attempt.copy(transportRetries = 1), attempt.copy(mergeRetries = 1), attempt.copy(repairRetries = 1),
            attempt.copy(interrupted = true))
        val attemptOnly = recovery.copy(stages = emptyList())
        changed.forEach { current ->
            val live = plan.copy(milestones = listOf(stage.copy(attempts = listOf(current))))
            assertEquals(live, live.applyAssignmentRecovery(attemptOnly, 4))
        }
    }

    @Test fun concurrentSelectionAndCompletedHistoryWinWhileIndependentBindingCanRecover() {
        val chosen = original.copy(profileId = "user-selection")
        val live = plan.copy(milestones = listOf(stage.copy(assignment = chosen,
            attempts = listOf(attempt.copy(assignment = chosen)))))
        assertEquals(live, live.applyAssignmentRecovery(recovery, 4))
        val completed = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.DONE,
            attempts = listOf(attempt.copy(phase = AttemptPhase.COMPLETE)))))
        assertEquals(completed, completed.applyAssignmentRecovery(recovery, 4))
        val other = stage.copy(id = "other", assignment = original, status = MilestoneStatus.PENDING, attempts = emptyList())
        val partial = live.copy(milestones = live.milestones + other)
        val request = recovery.copy(stages = recovery.stages + StageAssignmentChange(other.id, original, replacement))
        assertEquals(partial.copy(milestones = partial.milestones.dropLast(1) + other.copy(assignment = replacement)),
            partial.applyAssignmentRecovery(request, 4))
    }

    @Test fun finalAttemptAndMergeBindingUseExactOldValuesAndCannotChangeShape() {
        val final = attempt.copy(id = "final", mergeAssignment = original)
        val change = AttemptAssignmentChange(null, PlanningMachine.AttemptRef.from(final), original, replacement,
            original, replacement)
        val live = plan.copy(finalAttempt = final)
        val request = recovery.copy(stages = emptyList(), attempts = listOf(change))
        assertEquals(live.copy(finalAttempt = final.copy(assignment = replacement, mergeAssignment = replacement)),
            live.applyAssignmentRecovery(request, 4))
        val advanced = live.copy(finalAttempt = final.copy(mergeAssignment = original.copy(profileId = "chosen")))
        assertEquals(advanced, advanced.applyAssignmentRecovery(request, 4))
        assertFailsWith<IllegalArgumentException> {
            live.applyAssignmentRecovery(request.copy(attempts = listOf(change.copy(afterMerge = null))), 4)
        }
    }

    @Test fun duplicateOrForeignTargetsAreRejectedAndSerializedInputReplaysExactly() {
        assertFailsWith<IllegalArgumentException> { plan.applyAssignmentRecovery(recovery.copy(stages = recovery.stages + recovery.stages), 4) }
        assertFailsWith<IllegalArgumentException> { plan.applyAssignmentRecovery(recovery.copy(attempts = recovery.attempts + recovery.attempts), 4) }
        assertFailsWith<IllegalArgumentException> { plan.applyAssignmentRecovery(recovery.copy(ref = recovery.ref.copy(projectId = "foreign")), 4) }
        val decoded = Json.decodeFromString<AssignmentRecovery>(Json.encodeToString(recovery))
        assertEquals(plan.applyAssignmentRecovery(recovery, 4), plan.applyAssignmentRecovery(decoded, 4))
    }
}
