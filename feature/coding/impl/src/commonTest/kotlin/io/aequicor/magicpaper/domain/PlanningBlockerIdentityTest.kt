package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.interactionCandidates
import kotlin.test.*

class PlanningBlockerIdentityTest {
    private val issue = PlanningIssue(IssueKind.UNCERTAIN, "Previous native run must be reconciled", requiresUser = true)
    private val parent = CodingSession("parent", "project", "Plan", 1, planningMode = true, role = CodingSessionRole.ORCHESTRATOR)
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING,
        error = issue, sessionGeneration = 1, turnIndex = 0)
    private val stage = Milestone("stage", "Stage", attempts = listOf(attempt))
    private val plan = Plan("plan", "project", "Goal", parentSessionId = parent.id, runId = "run", confirmedRevision = 1,
        phase = ExecutionPhase.WAITING, issue = issue, milestones = listOf(stage))

    private fun request(plan: Plan, history: List<CodingMessage> = emptyList()): UserInteractionRequest {
        val ui = CodingUi(sessions = listOf(CodingSessionUi(parent, messages = history)))
        return interactionCandidates(ui, listOf(plan), emptyMap(), emptyMap()).single { it.kind == InteractionKind.RECOVER_PLAN }
    }

    @Test fun repeatedIssueAtANewRunTurnOrGenerationCreatesANewActionableRecovery() {
        val original = request(plan)
        val oldAnswer = CodingMessage("old-answer", CodingRole.USER, "Retry", createdAt = 0, planning = PlanningChatBlock(plan.id, replyTo = original.id))
        val variants = listOf(plan.copy(runId = "next-run"),
            plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(turnIndex = 1))))),
            plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(sessionGeneration = 2))))))
        for (current in variants) {
            val blocker = current.blockingIssues(listOf(oldAnswer)).single()
            assertEquals(issue, blocker.issue)
            assertNotEquals(plan.blockingIssues(emptyList()).single().messageId, blocker.messageId)
            val next = request(current, listOf(oldAnswer))
            assertNotEquals(original.id, next.id)
            val queue = UserInteractionQueue()
            queue.reconcile(listOf(original))
            assertEquals(listOf(next), queue.reconcile(listOf(next), dismissed = setOf(original.id)))
        }
    }

    @Test fun sameCheckpointKeepsItsIdentityAcrossTelemetryAndFreshIssueObjects() {
        val telemetry = plan.copy(revision = plan.revision + 1, updatedAt = plan.updatedAt + 100,
            milestones = listOf(stage.copy(updatedAt = stage.updatedAt + 100, attempts = listOf(attempt.copy(updatedAt = 200, error = issue.copy())))))
        assertEquals(request(plan).id, request(telemetry).id)
        assertTrue(UserInteractionQueue().reconcile(listOf(request(telemetry)), setOf(request(plan).id)).isEmpty())
    }

    @Test fun finalAndPlanLevelFailuresAreAlsoScopedToTheCurrentRun() {
        val final = plan.copy(milestones = emptyList(), finalAttempt = attempt.copy(phase = AttemptPhase.VERIFYING))
        val planOnly = plan.copy(milestones = emptyList())
        for (current in listOf(final, planOnly)) {
            val original = current.blockingIssues(emptyList()).single()
            val repeated = current.copy(runId = "next-run").blockingIssues(emptyList()).single()
            assertNotEquals(original.messageId, repeated.messageId)
            assertEquals(original.issue, repeated.issue)
        }
    }
}
