package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class PlanningSessionStatusTest {
    private val parent = CodingSession("parent", "project", "Сессия 3", 1, planningMode = true)
    private val worker = CodingSession("worker", "project", "Этап", 1,
        planId = "plan", parentSessionId = parent.id, stageId = "stage")
    private val attempt = StageAttempt("attempt", worker.id, StageAssignment("profile", "model"), phase = AttemptPhase.EXECUTING)
    private val stage = Milestone("stage", "Этап", status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))
    private val plan = Plan("plan", "project", "Цель", parentSessionId = parent.id, confirmedRevision = 1,
        intent = ExecutionIntent.RUN, milestones = listOf(stage))
    private val question = CodingMessage("question", CodingRole.AGENT, "Уточните формат", createdAt = 1,
        planning = PlanningChatBlock(plan.id, questions = listOf(PlanningQuestion("format", "Формат")), sourceStageId = stage.id))
    private val proposal = PlanProposal("proposal", plan.runId, plan.tree, plan.milestones, plan.tree,
        plan.milestones + Milestone("followup", "Проверка доработки"), "Переключить оставшуюся работу на другие модели")

    @Test fun proposalDuringExecutionDoesNotAskForAnotherAnswer() {
        val answer = CodingMessage("answer", CodingRole.USER, "PDF", createdAt = 2,
            planning = PlanningChatBlock(plan.id, replyTo = question.id))
        val executing = plan.copy(phase = ExecutionPhase.EXECUTING, proposal = proposal)
        assertFalse(executing.proposalReadyForConfirmation)
        val ui = CodingSessionUi(parent, listOf(question, answer), plan = executing)
        assertEquals(CodingSessionStatus.WORKING, ui.status)
        assertEquals(CodingSessionStatus.WORKING, ui.copy(running = true).status)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(messages = listOf(question)).status)
    }

    @Test fun proposalBecomesAConfirmationOnlyWhenCurrentRunHasSettled() {
        val finished = plan.copy(phase = ExecutionPhase.COMPLETE, proposal = proposal,
            milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))
        assertTrue(finished.proposalReadyForConfirmation)
        val ui = CodingSessionUi(parent, plan = finished)
        assertEquals(CodingSessionStatus.CONFIRMATION, ui.status)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(awaitingUser = true).status)
        assertEquals(CodingSessionStatus.BLOCKED, ui.copy(failedRequest = true).status)
        assertEquals(CodingSessionStatus.IDLE, ui.copy(plan = finished.copy(proposal = null)).status)
        assertEquals(CodingSessionStatus.CONFIRMATION,
            aggregateCodingStatus(listOf(ui.status, CodingSessionStatus.WORKING)))
    }

    @Test fun finalChecksRemainWorkingUntilAProposalCanBeConfirmed() {
        val checked = plan.copy(proposal = proposal, milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))
        for (phase in listOf(ExecutionPhase.RECOVERING, ExecutionPhase.VERIFYING, ExecutionPhase.APPLYING)) {
            val current = checked.copy(phase = phase)
            assertFalse(current.proposalReadyForConfirmation)
            assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, plan = current).status)
        }
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Требуется доработка", requiresUser = true)
        val rejected = checked.copy(phase = ExecutionPhase.WAITING, issue = issue,
            finalAttempt = attempt.copy(phase = AttemptPhase.VERIFYING, error = issue))
        assertTrue(rejected.proposalReadyForConfirmation)
        assertEquals(CodingSessionStatus.CONFIRMATION, CodingSessionUi(parent, plan = rejected).status)
        val uncertain = rejected.copy(finalAttempt = rejected.finalAttempt!!.copy(pendingToolExternal = true))
        assertFalse(uncertain.proposalReadyForConfirmation)
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(parent, plan = uncertain).status)
    }

    @Test fun unavailableProposalDoesNotHideResumeOrAnEventWait() {
        for (intent in listOf(ExecutionIntent.PAUSE, ExecutionIntent.STOP)) {
            val paused = plan.copy(phase = ExecutionPhase.EXECUTING, intent = intent, proposal = proposal)
            assertTrue(CodingSessionUi(parent, plan = paused).canResume)
            assertNotEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, plan = paused).status)
        }
        val scheduled = plan.copy(phase = ExecutionPhase.WAITING, proposal = proposal,
            milestones = listOf(stage.copy(attempts = listOf(attempt.copy(waitingForEvent = "rule")))))
        assertEquals(CodingSessionStatus.SCHEDULED, CodingSessionUi(parent, plan = scheduled).status)
        assertFalse(CodingSessionUi(parent, plan = scheduled).canResume)
    }

    @Test fun continueIsOfferedForStoppedOrBlockedWorkButNotQuestionsOrCompletedPlans() {
        for (intent in listOf(ExecutionIntent.PAUSE, ExecutionIntent.STOP)) {
            assertTrue(CodingSessionUi(parent, plan = plan.copy(intent = intent)).canResume)
            assertTrue(CodingSessionUi(worker, plan = plan.copy(intent = intent)).canResume)
        }
        val waiting = plan.copy(phase = ExecutionPhase.WAITING)
        assertTrue(CodingSessionUi(parent, plan = waiting).canResume)
        assertFalse(CodingSessionUi(parent, listOf(question), plan = waiting).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting, awaitingUser = true).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting, running = true).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting.copy(phase = ExecutionPhase.COMPLETE)).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting.copy(confirmedRevision = null)).canResume)
    }

    @Test fun queuedTaskIsGrayDespiteItsUnansweredTaskMessage() {
        val task = CodingMessage("task", CodingRole.USER, "Реализовать этап", createdAt = 1)
        val pending = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.PENDING, attempts = emptyList())))
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, listOf(task), plan = pending).status)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(worker, listOf(task), plan = plan).status)
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, listOf(task), plan = plan.copy(intent = ExecutionIntent.PAUSE)).status)
    }

    @Test fun plannerIsRedWhileAnyStageWorksAndYellowUntilQuestionIsAnswered() {
        val notice = CodingMessage("notice", CodingRole.AGENT, "Следующий этап запущен.", createdAt = 2)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, plan = plan).status)
        val waiting = CodingSessionUi(parent, listOf(question, notice), plan = plan)
        assertEquals(CodingSessionStatus.WAITING, waiting.status)
        val answer = CodingMessage("answer", CodingRole.USER, "PDF", createdAt = 3, planning = PlanningChatBlock(plan.id, replyTo = question.id))
        assertEquals(CodingSessionStatus.WORKING, waiting.copy(messages = waiting.messages + answer).status)
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(parent, plan = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))).status)
    }

    @Test fun failedInputRemainsBlockedWhileWorkersContinueAndResetsOnRetry() {
        val failed = CodingMessage("input", CodingRole.USER, "Change models", createdAt = 1,
            inputStatus = OrchestrationInputStatus.FAILED)
        val notice = CodingMessage("notice", CodingRole.AGENT, "Worker continues", createdAt = 2)
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(parent, listOf(failed, notice), plan = plan).status)
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(parent, listOf(notice), running = true,
            plan = plan, failedRequest = true).status)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent,
            listOf(failed.copy(inputStatus = OrchestrationInputStatus.PROCESSING), notice), plan = plan, running = true).status)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent,
            listOf(failed.copy(inputStatus = OrchestrationInputStatus.DONE), notice), plan = plan).status)
    }

    @Test fun stageBlockedForUserIsYellowAndRetryDelayIsGray() {
        fun withIssue(issue: PlanningIssue) = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(error = issue)))))
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(worker, plan = withIssue(PlanningIssue(IssueKind.CONFIGURATION, "Источник недоступен", requiresUser = true))).status)
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, plan = withIssue(PlanningIssue(IssueKind.TRANSIENT, "Повтор позже", retryAt = 100))).status)
    }

    @Test fun failedVerificationWithoutQuestionsBlocksBothPlanAndWorker() {
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Не подтверждено восстановление версии", requiresUser = true)
        val failed = plan.copy(status = PlanStatus.FAILED, phase = ExecutionPhase.WAITING, issue = issue,
            milestones = listOf(stage.copy(status = MilestoneStatus.FAILED,
                attempts = listOf(attempt.copy(phase = AttemptPhase.VERIFYING, error = issue, repairRetries = 2)))))
        val messages = listOf(CodingMessage("result", CodingRole.AGENT, "Результат передан в приёмку", createdAt = 2))
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(parent, messages, plan = failed).status)
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(worker, messages, plan = failed).status)
        val blocker = failed.blockingIssues(messages).single()
        assertEquals(issue.message, blocker.issue.message)
        assertContains(blocker.text, "Автоматические попытки исправления исчерпаны (2)")
        assertNull(messages.pendingPlanningQuestion())
        assertEquals(CodingSessionStatus.BLOCKED, CodingUi(projects = listOf(CodingProject("project", "Project", "/project", 1)),
            sessions = listOf(CodingSessionUi(parent, messages, plan = failed), CodingSessionUi(worker, messages, plan = failed))).statusOf("project"))
    }

    @Test fun realQuestionStaysWaitingButCoordinatorFailureWithoutQuestionIsBlocked() {
        val wait = PlanningIssue(IssueKind.CONFIGURATION, "Ожидается ответ планировщику", requiresUser = true)
        val waiting = plan.copy(issue = wait, milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true, error = wait)))))
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, listOf(question), plan = waiting).status)
        assertTrue(waiting.blockingIssues(listOf(question)).isEmpty())
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(parent, plan = waiting).status)
        assertEquals(1, waiting.blockingIssues(emptyList()).size)
        val failure = PlanningIssue(IssueKind.UNCERTAIN, "Ошибка координатора", requiresUser = true)
        val failed = waiting.copy(issue = failure, milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true, error = failure)))))
        assertEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(worker, plan = failed).status)
        assertEquals(1, failed.blockingIssues(emptyList()).size)
    }

    @Test fun handedOffWorkerIsGreenUntilExecutionResumes() {
        val handedOff = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true)))))
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(worker, running = true, plan = handedOff).status)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(worker, plan = plan).status)
        val planner = CodingSessionUi(parent, listOf(question), draft = CodingDraft(active = true), running = true, plan = handedOff)
        assertEquals(CodingSessionStatus.WAITING, planner.status)
        assertEquals(CodingSessionStatus.WAITING, planner.copy(running = false, draft = CodingDraft()).status)
    }

    @Test fun unansweredQuestionsRemainAvailableInOrderAcrossNewMessages() {
        val second = question.copy(id = "second", planning = question.planning!!.copy(questions = listOf(PlanningQuestion("other", "Другой вопрос"))))
        val answeredSecond = CodingMessage("answer", CodingRole.USER, "Ответ", createdAt = 3, planning = PlanningChatBlock(plan.id, replyTo = second.id))
        val history = listOf(question, second, answeredSecond)
        assertEquals(question.id, history.pendingPlanningQuestion()?.id)
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(history))
        assertNull(history.pendingPlanningQuestion(setOf("deleted-plan")))
    }
}
