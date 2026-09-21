package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningRecoveryIssueTest {
    private fun state(issue: PlanningIssue, complete: Boolean = false): PlanningMachine.State {
        val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"),
            pendingTool = "external-call", pendingToolExternal = true, error = PlanningIssue(IssueKind.UNCERTAIN, "Native outcome"))
        val plan = Plan("plan", "project", "Goal", issue = issue,
            phase = if (complete) ExecutionPhase.COMPLETE else ExecutionPhase.WAITING,
            milestones = listOf(Milestone("stage", "Stage", attempts = listOf(attempt))))
        return PlanningMachine.reduce(PlanningMachine.initial(plan.id),
            PlanningMachine.Intent.Create(plan, PlanningMachine.Stamp("create", 1))).state
    }

    @Test fun confirmationClosesOnlyExactPlanningJournalNoticesAcrossExecutionPhases() {
        for (issue in listOf(PlanningRecoveryIssues.journalUncertainty, PlanningRecoveryIssues.stoppedWithUnconfirmedEffects)) {
            for (complete in listOf(false, true)) {
                val initial = state(issue, complete)
                val result = PlanningMachine.reduce(initial, PlanningMachine.Fact.RecoveryConfirmed(PlanningMachine.Stamp("confirmed", 2)))
                assertNull(result.rejection)
                assertNull(result.state.plan!!.issue)
                assertEquals(initial.plan!!.milestones, result.state.plan!!.milestones, "Native and acceptance evidence is owned elsewhere")
                assertEquals(initial.run, result.state.run)
                assertTrue(result.effects.isEmpty())
                if (complete) assertEquals(PlanStatus.DONE, result.state.plan!!.status)
            }
        }
    }

    @Test fun unrelatedUncertaintyAndVerificationConfigurationIssuesRemainActionable() {
        for (issue in listOf(PlanningIssue(IssueKind.UNCERTAIN, "Native outcome", requiresUser = true),
            PlanningIssue(IssueKind.VERIFICATION, "Acceptance failed", requiresUser = true),
            PlanningIssue(IssueKind.CONFIGURATION, "Connection unavailable", requiresUser = true))) {
            for (complete in listOf(false, true)) {
                val initial = state(issue, complete)
                val result = PlanningMachine.reduce(initial, PlanningMachine.Fact.RecoveryConfirmed(PlanningMachine.Stamp("confirmed", 2)))
                assertEquals(issue, result.state.plan!!.issue)
                assertTrue(result.effects.isEmpty())
            }
        }
    }

    @Test fun pendingEvidenceCannotBeClearedByTheConfirmationFact() {
        val initial = state(PlanningRecoveryIssues.stoppedWithUnconfirmedEffects)
        val unknown = PlanningMachine.reduce(initial, PlanningMachine.Fact.OperationUnknown(3, PlanningMachine.Stamp("unknown", 2))).state
        val result = PlanningMachine.reduce(unknown, PlanningMachine.Fact.RecoveryConfirmed(PlanningMachine.Stamp("confirmed", 3)))
        assertNotNull(result.rejection)
        assertEquals(unknown, result.state)
        assertIs<PlanningMachine.Effect.Reject>(result.effects.single())
    }
}
