package io.aequicor.magicpaper.domain

import kotlin.test.*

class VerificationRecoveryTest {
    private val stage = Milestone("stage", "Move button", acceptanceCriteria = listOf(
        AcceptanceCriterion("source", "Old row removed"), AcceptanceCriterion("layout", "New button position")))
    private val criteria = stage.criteria()
    private val waiver = AcceptanceWaiver("run", criteria.last(), "attempt", "before", 1)
    private val record = AcceptanceRecord("run", "attempt", "after", criteria,
        criteria.map { AcceptanceFinding(it.id, CheckStatus.STALE, it.description, "Files changed") },
        status = AcceptanceStatus.STALE, waivers = listOf(waiver))
    private val issue = PlanningIssue(IssueKind.VERIFICATION, record.summary(), requiresUser = true)
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"),
        phase = AttemptPhase.VERIFYING, error = issue, acceptanceRecord = record)
    private val plan = Plan("plan", "project", "Move button", milestones = listOf(stage.copy(attempts = listOf(attempt))),
        runId = "run", intent = ExecutionIntent.RUN, issue = issue, phase = ExecutionPhase.WAITING,
        acceptanceWaivers = listOf(waiver), journal = listOf(PlanJournalEntry("skip", 1, operation = "user-skip-verification")))

    @Test fun restoresTheExistingDecisionOnce() {
        val result = plan.restoreSkippedVerification()
        assertNull(result.issue)
        assertNull(result.milestones.single().attempts.single().error)
        assertEquals(criteria.toSet(), result.acceptanceWaivers.map { it.criterion }.toSet())
        assertEquals(result, result.restoreSkippedVerification())
    }

    @Test fun neverResumesStoppedPlansOrOtherRunsAndCriteria() {
        val changedStage = stage.copy(acceptanceCriteria = listOf(AcceptanceCriterion("new", "New requirement")), attempts = listOf(attempt))
        for (blocked in listOf(plan.copy(intent = ExecutionIntent.STOP), plan.copy(intent = ExecutionIntent.PAUSE),
            plan.copy(acceptanceWaivers = emptyList()), plan.copy(journal = emptyList()), plan.copy(runId = "next"),
            plan.copy(milestones = listOf(changedStage)),
            plan.copy(acceptanceWaivers = listOf(waiver.copy(attemptId = "other-attempt"))))) {
            assertEquals(blocked, blocked.restoreSkippedVerification())
        }
    }

    @Test fun doesNotBypassExecutionFailureOrUnknownToolResult() {
        val failedRecord = record.copy(status = AcceptanceStatus.FAILED,
            findings = criteria.map { AcceptanceFinding(it.id, CheckStatus.FAIL, it.description, "Failure") })
        for (blockedAttempt in listOf(attempt.copy(phase = AttemptPhase.EXECUTING),
            attempt.copy(pendingTool = "publish", pendingToolExternal = true),
            attempt.copy(acceptanceRecord = failedRecord), attempt.copy(mergePhase = AttemptPhase.VERIFYING))) {
            val blocked = plan.copy(milestones = listOf(stage.copy(attempts = listOf(blockedAttempt))))
            assertEquals(blocked, blocked.restoreSkippedVerification())
        }
    }
}
