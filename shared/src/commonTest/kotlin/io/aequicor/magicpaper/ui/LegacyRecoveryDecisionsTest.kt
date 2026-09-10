package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class LegacyRecoveryDecisionsTest {
    private val issue = PlanningIssue(IssueKind.UNCERTAIN, "Native result is unknown", requiresUser = true)
    private val parent = CodingSession("parent", "project", "Plan", 1, planningMode = true)
    private val worker = CodingSession("worker", "project", "Stage", 2, parentSessionId = parent.id,
        planId = "plan", stageId = "stage", runtimeGeneration = 1)
    private val attempt = StageAttempt("attempt", worker.id, StageAssignment("profile", "model"),
        phase = AttemptPhase.FAILED, error = issue, sessionGeneration = 1, startedAt = 10, updatedAt = 20)
    private val stage = Milestone("stage", "Stage", status = MilestoneStatus.FAILED, attempts = listOf(attempt), updatedAt = 20)
    private val plan = Plan("plan", "project", "Goal", parentSessionId = parent.id, milestones = listOf(stage),
        runId = "run", confirmedRevision = 1, createdAt = 1, updatedAt = 30, phase = ExecutionPhase.WAITING,
        status = PlanStatus.STOPPED, intent = ExecutionIntent.STOP, issue = issue,
        journal = listOf(PlanJournalEntry("stop", 30, operation = "stop-confirmed")))
    private val legacyId = "blocker:plan-blocked-stage-attempt-0-${issue.hashCode()}"
    private val decision = CodingMessage("$legacyId-left", CodingRole.USER, "Оставить работу остановленной.", createdAt = 40)
    private val ui = CodingUi(sessions = listOf(
        CodingSessionUi(parent, listOf(CodingMessage(legacyId.removePrefix("blocker:"), CodingRole.AGENT,
            "Native result is unknown", createdAt = 20), decision)),
        CodingSessionUi(worker, listOf(CodingMessage("request", CodingRole.USER, "Task", createdAt = 10))),
    ))

    private fun request(current: Plan = plan, state: CodingUi = ui) =
        interactionCandidates(state, listOf(current), emptyMap(), emptyMap()).single { it.kind == InteractionKind.RECOVER_PLAN }

    @Test fun savedLegacyLeaveStaysDismissedWithoutRemovingTheDomainBlocker() {
        val saved = Json.decodeFromString<Set<String>>(Json.encodeToString(setOf(legacyId)))
        val current = request()
        val aliases = legacyRecoveryDecisionAliases(ui, listOf(plan), saved)
        assertEquals(mapOf(current.id to legacyId), aliases)
        assertNotEquals(legacyId, current.id)
        assertTrue(UserInteractionQueue().reconcile(listOf(current), saved.withLegacyRecoveryDecisions(aliases)).isEmpty())
        assertEquals(issue, plan.blockingIssues(ui.sessions.first().messages).single().issue)
        assertEquals(setOf(legacyId), saved, "Compatibility must not rewrite the saved decision during refresh")
    }

    @Test fun explicitReopenRemovesBothIdentitiesAndRemainsOpenAfterRestoringDecisions() {
        val current = request()
        val decisions = mutableSetOf(legacyId, current.id, "unrelated")
        val aliases = legacyRecoveryDecisionAliases(ui, listOf(plan), decisions)
        decisions.reopenInteractionDecisions(listOf(current.id), aliases)
        val restored = Json.decodeFromString<Set<String>>(Json.encodeToString(decisions.toSet()))
        assertEquals(setOf("unrelated"), restored)
        val afterOpen = legacyRecoveryDecisionAliases(ui, listOf(plan), restored)
        assertTrue(afterOpen.isEmpty())
        assertEquals(listOf(current), UserInteractionQueue().reconcile(listOf(current), restored.withLegacyRecoveryDecisions(afterOpen)))
    }

    @Test fun aliasNeedsTheSavedLegacyDecisionAndItsExactUserHistoryRecord() {
        for (saved in listOf(emptySet(), setOf("unrelated"), setOf(request().id))) {
            assertTrue(legacyRecoveryDecisionAliases(ui, listOf(plan), saved).isEmpty())
        }
        val parentUi = ui.sessions.first()
        val histories = listOf(parentUi.messages.filterNot { it.id == decision.id },
            parentUi.messages.map { if (it.id == decision.id) it.copy(role = CodingRole.AGENT) else it },
            parentUi.messages.map { if (it.id == decision.id) it.copy(id = "$legacyId-retry") else it },
            parentUi.messages.map { if (it.id == decision.id) it.copy(createdAt = plan.updatedAt) else it })
        for (history in histories) {
            val state = ui.copy(sessions = listOf(parentUi.copy(messages = history), ui.sessions.last()))
            assertTrue(legacyRecoveryDecisionAliases(state, listOf(plan), setOf(legacyId)).isEmpty())
        }
    }

    @Test fun runningUnconfirmedOrLaterStoppedCheckpointsRemainActionable() {
        val variants = listOf(plan.copy(intent = ExecutionIntent.RUN), plan.copy(stopping = true),
            plan.copy(status = PlanStatus.FAILED), plan.copy(journal = emptyList()), plan.copy(updatedAt = 0),
            plan.copy(updatedAt = decision.createdAt), plan.copy(updatedAt = decision.createdAt + 1),
            plan.copy(journal = plan.journal + PlanJournalEntry("later", 41, operation = "stop-confirmed")))
        for (current in variants) {
            val aliases = legacyRecoveryDecisionAliases(ui, listOf(current), setOf(legacyId))
            assertTrue(aliases.isEmpty(), current.toString())
            assertEquals(1, UserInteractionQueue().reconcile(listOf(request(current)), setOf(legacyId).withLegacyRecoveryDecisions(aliases)).size)
        }
    }

    @Test fun newerGenerationsTurnsAttemptTimesAndMessagesCannotInheritLegacyDismissal() {
        val variants = listOf(attempt.copy(sessionGeneration = 2), attempt.copy(turnIndex = 1),
            attempt.copy(updatedAt = 40), attempt.copy(updatedAt = 0),
            attempt.copy(chatTurns = listOf(StageChatTurn(0, 39, 41))))
        for (changed in variants) {
            val current = plan.copy(milestones = listOf(stage.copy(attempts = listOf(changed))))
            assertTrue(legacyRecoveryDecisionAliases(ui, listOf(current), setOf(legacyId)).isEmpty(), changed.toString())
        }
        val states = listOf(
            ui.copy(sessions = ui.sessions.map { if (it.session.id == worker.id) it.copy(session = worker.copy(runtimeGeneration = 2)) else it }),
            ui.copy(sessions = ui.sessions.map { if (it.session.id == worker.id) it.copy(running = true) else it }),
            ui.copy(sessions = ui.sessions.filterNot { it.session.id == worker.id }),
        ) + listOf(parent.id, worker.id).map { id ->
            ui.copy(sessions = ui.sessions.map { if (it.session.id == id) it.copy(messages = it.messages +
                CodingMessage("later", CodingRole.USER, "New request", createdAt = 41)) else it })
        }
        for (state in states) assertTrue(legacyRecoveryDecisionAliases(state, listOf(plan), setOf(legacyId)).isEmpty())
    }
}
