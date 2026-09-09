package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class UserInteractionStatusTest {
    private val parent = CodingSession("parent", "project", "Оркестратор", 1, planningMode = true)
    private val worker = CodingSession("worker", "project", "Этап", 1, parentSessionId = parent.id, planId = "plan", stageId = "stage")
    private val plain = CodingSession("plain", "project", "Сессия", 1)
    private val q = PlanningQuestion("q", "Вопрос?")
    private val plan = Plan("plan", "project", "Цель", parentSessionId = parent.id, confirmedRevision = 1)
    private fun CodingUi.project(requests: List<UserInteractionRequest>) = copy(interactions = requests,
        sessions = sessions.map { item -> item.copy(interactions = requests.filter { it.affects(item.session) }) })

    @Test fun onlyVisibleQuestionnairesProduceWaitingIncludingPermissionsOfBackgroundWorkers() {
        val approval = CodingApproval("a", "worker-merge", "project", "Этап", CodingApprovalKind.FILE_CHANGE, "Причина", "Точный diff")
        val ui = CodingUi(sessions = listOf(CodingSessionUi(parent), CodingSessionUi(worker), CodingSessionUi(plain, running = true)), approvals = listOf(approval))
        val pending = interactionCandidates(ui, listOf(plan), emptyMap(), emptyMap())
        assertFalse(pending.single().questions.single().canSkip)
        assertFalse(pending.single().questions.single().allowCustomInput)
        val projected = ui.project(pending)
        assertEquals(listOf(CodingSessionStatus.WAITING, CodingSessionStatus.WAITING, CodingSessionStatus.WORKING), projected.sessions.map { it.status })
        assertEquals(CodingSessionStatus.WAITING, projected.statusOf("project"))
        assertEquals(CodingSessionStatus.WORKING, ui.project(emptyList()).statusOf("project"))
    }

    @Test fun punctuationUnansweredMessagesAndModelLatencyAreNotUserQuestions() {
        val user = CodingMessage("u", CodingRole.USER, "Задача", createdAt = 1)
        val agent = CodingMessage("a", CodingRole.AGENT, "Продолжить?**", createdAt = 2)
        assertEquals(CodingSessionStatus.IDLE, codingStatusOf(listOf(user, agent)))
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(plain, listOf(user), running = true, draft = CodingDraft(awaitingModel = true)).status)
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(plain, listOf(agent), awaitingUser = true, draft = CodingDraft(awaitingApproval = true)).status)
    }

    @Test fun structuredQuestionStaysOpenAfterNewerMessagesAndOnlyAffectsItsStage() {
        val question = OrchestrationQuestion("request", plan.id, "", listOf(q), worker.id, listOf("stage"))
        val other = worker.copy(id = "independent", stageId = "other")
        val ui = CodingUi(sessions = listOf(CodingSessionUi(parent), CodingSessionUi(worker), CodingSessionUi(other)))
        val states = mapOf(parent.id to OrchestrationState(parent.id, parent.projectId, questions = listOf(question)))
        val pending = interactionCandidates(ui, listOf(plan), states, emptyMap())
        assertTrue(pending.single().affects(parent)); assertTrue(pending.single().affects(worker)); assertFalse(pending.single().affects(other))
        assertTrue(interactionCandidates(ui, listOf(plan), states.mapValues { it.value.copy(questions = listOf(question.copy(status = UserRequestStatus.ANSWERED))) }, emptyMap()).isEmpty())
    }

    @Test fun failureIsAnActionableQuestionButAnExplicitStopIsNot() {
        val checkpoint = CodingRunCheckpoint("u", "Task", intent = ExecutionIntent.STOP, responseId = "a")
        val item = CodingSessionUi(plain.copy(pendingRun = checkpoint), listOf(CodingMessage("a", CodingRole.AGENT, "Failure", failed = true, createdAt = 2)))
        val pending = interactionCandidates(CodingUi(sessions = listOf(item)), emptyList(), emptyMap(), emptyMap())
        assertEquals(InteractionKind.RECOVER_RUN, pending.single().kind)
        assertEquals(listOf("retry", "leave"), pending.single().questions.single().options.map { it.id })
        assertTrue(interactionCandidates(CodingUi(sessions = listOf(item.copy(session = plain.copy(pendingRun = checkpoint.copy(stoppedByUser = true))))), emptyList(), emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun successfulResponseClosesRecoveryEvenWithAStaleCheckpoint() {
        val response = CodingMessage("a", CodingRole.AGENT, "Готово", createdAt = 2)
        for (intent in listOf(ExecutionIntent.RUN, ExecutionIntent.STOP)) {
            val item = CodingSessionUi(plain.copy(pendingRun = CodingRunCheckpoint("u", "Task", responseId = "a", intent = intent)),
                listOf(CodingMessage("u", CodingRole.USER, "Task", createdAt = 1), response))
            val pending = interactionCandidates(CodingUi(sessions = listOf(item)), emptyList(), emptyMap(), emptyMap())
            assertTrue(pending.isEmpty())
            assertFalse(item.canResume)
            assertEquals(CodingSessionStatus.IDLE, item.status)
        }
    }

    @Test fun previousResponseDoesNotHideAnInterruptedNewRun() {
        val previous = CodingMessage("previous", CodingRole.AGENT, "Готово", createdAt = 2)
        for (responseId in listOf("next", "")) {
            val item = CodingSessionUi(plain.copy(pendingRun = CodingRunCheckpoint("u", "New task", responseId = responseId)),
                listOf(previous, CodingMessage("u", CodingRole.USER, "New task", createdAt = 3)))
            val pending = interactionCandidates(CodingUi(sessions = listOf(item)), emptyList(), emptyMap(), emptyMap())
            assertEquals(InteractionKind.RECOVER_RUN, pending.single().kind)
            assertTrue(item.canResume)
        }
    }

    @Test fun missingHostVerificationExplainsThePlanProblemInsteadOfAskingForCodeRepair() {
        val criterion = AcceptanceCriterion("opaque-id/layout", "Кнопка находится слева от меню", environment = EvidenceEnvironment.MANUAL)
        val record = AcceptanceRecord("run", "attempt", "snapshot", listOf(criterion), listOf(
            AcceptanceFinding(criterion.id, CheckStatus.NOT_RUN, criterion.description, "MANUAL NOT_RUN checkId missing")), status = AcceptanceStatus.PARTIAL)
        val issue = PlanningIssue(IssueKind.VERIFICATION, record.summary(), requiresUser = true)
        val stage = Milestone("stage", "Перенести кнопку", attempts = listOf(StageAttempt("attempt", worker.id,
            StageAssignment("p", "m"), phase = AttemptPhase.VERIFYING, error = issue, acceptanceRecord = record)))
        val blocked = plan.copy(milestones = listOf(stage), phase = ExecutionPhase.WAITING, issue = issue)
        val ui = CodingUi(sessions = listOf(CodingSessionUi(parent), CodingSessionUi(worker)))
        val request = interactionCandidates(ui, listOf(blocked), emptyMap(), emptyMap()).single()
        assertContains(request.details, "не хватает подтверждений")
        assertContains(request.details, criterion.description)
        assertContains(request.details, "не подключён способ подтверждения")
        assertFalse(request.details.contains("opaque-id"))
        assertFalse(request.details.contains("NOT_RUN"))
        assertEquals(listOf("Проверить автоматически", "Продолжить без проверки", "Оставить остановленной"),
            request.questions.single().options.map { it.label })
        val staleRecord = record.copy(status = AcceptanceStatus.STALE, findings = record.findings.map { it.copy(status = CheckStatus.STALE) })
        val stale = blocked.copy(milestones = listOf(stage.copy(attempts = stage.attempts.map { it.copy(acceptanceRecord = staleRecord) })))
        val staleRequest = interactionCandidates(ui, listOf(stale), emptyMap(), emptyMap()).single()
        assertEquals(request.questions.single().options, staleRequest.questions.single().options)
    }
}
