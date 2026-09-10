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


    private fun CodingUi.withRequests(): CodingUi {
        val histories = sessions.filter { it.session.id == parent.id }
        val states = histories.associate { item -> parent.id to OrchestrationState(parent.id, parent.projectId,
            inputs = item.messages.filter { it.inputStatus == OrchestrationInputStatus.FAILED }.map {
                OrchestrationInput(it.id, it.text, it.createdAt, status = OrchestrationInputStatus.FAILED, error = "Ошибка обработки")
            }) }
        return copy(interactions = interactionCandidates(this, sessions.mapNotNull { it.plan }.distinctBy { it.id }, states, emptyMap()))
    }
    private val CodingSessionUi.attentionStatus: CodingSessionStatus get() {
        val items = if (session.id == parent.id) listOf(this, CodingSessionUi(worker, plan = plan))
            else listOf(CodingSessionUi(parent, messages, plan = plan), this)
        return CodingUi(sessions = items).withRequests().sessionsOf(parent.projectId).first { it.session.id == session.id }.status
    }

    @Test fun proposalDuringExecutionDoesNotAskForAnotherAnswer() {
        val answer = CodingMessage("answer", CodingRole.USER, "PDF", createdAt = 2,
            planning = PlanningChatBlock(plan.id, replyTo = question.id))
        val executing = plan.copy(phase = ExecutionPhase.EXECUTING, proposal = proposal)
        assertFalse(executing.proposalReadyForConfirmation)
        val ui = CodingSessionUi(parent, listOf(question, answer), plan = executing)
        assertEquals(CodingSessionStatus.WORKING, ui.attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, ui.copy(running = true).attentionStatus)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(messages = listOf(question)).attentionStatus)
    }

    @Test fun proposalBecomesAConfirmationOnlyWhenCurrentRunHasSettled() {
        val finished = plan.copy(phase = ExecutionPhase.COMPLETE, proposal = proposal,
            milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))
        assertTrue(finished.proposalReadyForConfirmation)
        val ui = CodingSessionUi(parent, plan = finished)
        assertEquals(CodingSessionStatus.WAITING, ui.attentionStatus)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(awaitingUser = true).attentionStatus)
        assertEquals(CodingSessionStatus.WAITING, ui.copy(failedRequest = true).attentionStatus)
        assertEquals(CodingSessionStatus.IDLE, ui.copy(plan = finished.copy(proposal = null)).attentionStatus)
        assertEquals(CodingSessionStatus.WAITING,
            aggregateCodingStatus(listOf(ui.attentionStatus, CodingSessionStatus.WORKING)))
    }

    @Test fun finalChecksRemainWorkingUntilAProposalCanBeConfirmed() {
        val checked = plan.copy(proposal = proposal, milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))
        for (phase in listOf(ExecutionPhase.RECOVERING, ExecutionPhase.VERIFYING, ExecutionPhase.APPLYING)) {
            val current = checked.copy(phase = phase)
            assertFalse(current.proposalReadyForConfirmation)
            assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, plan = current).attentionStatus)
        }
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Требуется доработка", requiresUser = true)
        val rejected = checked.copy(phase = ExecutionPhase.WAITING, issue = issue,
            finalAttempt = attempt.copy(phase = AttemptPhase.VERIFYING, error = issue))
        assertTrue(rejected.proposalReadyForConfirmation)
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, plan = rejected).attentionStatus)
        val uncertain = rejected.copy(finalAttempt = rejected.finalAttempt!!.copy(pendingToolExternal = true))
        assertFalse(uncertain.proposalReadyForConfirmation)
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, plan = uncertain).attentionStatus)
    }

    @Test fun unavailableProposalDoesNotHideResumeOrAnEventWait() {
        for (intent in listOf(ExecutionIntent.PAUSE, ExecutionIntent.STOP)) {
            val paused = plan.copy(phase = ExecutionPhase.EXECUTING, intent = intent, proposal = proposal)
            assertTrue(CodingSessionUi(parent, plan = paused).canResume)
            assertNotEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, plan = paused).attentionStatus)
        }
        val scheduled = plan.copy(phase = ExecutionPhase.WAITING, proposal = proposal,
            milestones = listOf(stage.copy(attempts = listOf(attempt.copy(waitingForEvent = "rule")))))
        assertEquals(CodingSessionStatus.SCHEDULED, CodingSessionUi(parent, plan = scheduled).attentionStatus)
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
        assertTrue(CodingSessionUi(parent, plan = waiting, awaitingUser = true).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting, running = true).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting.copy(phase = ExecutionPhase.COMPLETE)).canResume)
        assertFalse(CodingSessionUi(parent, plan = waiting.copy(confirmedRevision = null)).canResume)
    }

    @Test fun queuedTaskIsGrayDespiteItsUnansweredTaskMessage() {
        val task = CodingMessage("task", CodingRole.USER, "Реализовать этап", createdAt = 1)
        val pending = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.PENDING, attempts = emptyList())))
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, listOf(task), plan = pending).attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(worker, listOf(task), plan = plan).attentionStatus)
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, listOf(task), plan = plan.copy(intent = ExecutionIntent.PAUSE)).attentionStatus)
    }

    @Test fun plannerIsRedWhileAnyStageWorksAndYellowUntilQuestionIsAnswered() {
        val notice = CodingMessage("notice", CodingRole.AGENT, "Следующий этап запущен.", createdAt = 2)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, plan = plan).attentionStatus)
        val waiting = CodingSessionUi(parent, listOf(question, notice), plan = plan)
        assertEquals(CodingSessionStatus.WAITING, waiting.attentionStatus)
        val answer = CodingMessage("answer", CodingRole.USER, "PDF", createdAt = 3, planning = PlanningChatBlock(plan.id, replyTo = question.id))
        assertEquals(CodingSessionStatus.WORKING, waiting.copy(messages = waiting.messages + answer).attentionStatus)
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(parent, plan = plan.copy(milestones = listOf(stage.copy(status = MilestoneStatus.DONE)))).attentionStatus)
    }

    @Test fun failedInputRemainsBlockedWhileWorkersContinueAndResetsOnRetry() {
        val failed = CodingMessage("input", CodingRole.USER, "Change models", createdAt = 1,
            inputStatus = OrchestrationInputStatus.FAILED)
        val notice = CodingMessage("notice", CodingRole.AGENT, "Worker continues", createdAt = 2)
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, listOf(failed, notice), plan = plan).attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, listOf(notice), running = true,
            plan = plan, failedRequest = true).attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent,
            listOf(failed.copy(inputStatus = OrchestrationInputStatus.PROCESSING), notice), plan = plan, running = true).attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent,
            listOf(failed.copy(inputStatus = OrchestrationInputStatus.DONE), notice), plan = plan).attentionStatus)
    }

    @Test fun stageBlockedForUserIsYellowAndRetryDelayIsGray() {
        fun withIssue(issue: PlanningIssue) = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(error = issue)))))
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(worker, plan = withIssue(PlanningIssue(IssueKind.CONFIGURATION, "Источник недоступен", requiresUser = true))).attentionStatus)
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, plan = withIssue(PlanningIssue(IssueKind.TRANSIENT, "Повтор позже", retryAt = 100))).attentionStatus)
    }

    @Test fun failedVerificationWithoutQuestionsBlocksBothPlanAndWorker() {
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Не подтверждено восстановление версии", requiresUser = true)
        val failed = plan.copy(status = PlanStatus.FAILED, phase = ExecutionPhase.WAITING, issue = issue,
            milestones = listOf(stage.copy(status = MilestoneStatus.FAILED,
                attempts = listOf(attempt.copy(phase = AttemptPhase.VERIFYING, error = issue, repairRetries = 2)))))
        val messages = listOf(CodingMessage("result", CodingRole.AGENT, "Результат передан в приёмку", createdAt = 2))
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, messages, plan = failed).attentionStatus)
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(worker, messages, plan = failed).attentionStatus)
        val blocker = failed.blockingIssues(messages).single()
        assertEquals(issue.message, blocker.issue.message)
        assertContains(blocker.text, issue.message)
        assertNull(messages.pendingPlanningQuestion())
        assertEquals(CodingSessionStatus.WAITING, CodingUi(projects = listOf(CodingProject("project", "Project", "/project", 1)),
            sessions = listOf(CodingSessionUi(parent, messages, plan = failed), CodingSessionUi(worker, messages, plan = failed))).withRequests().statusOf("project"))
    }

    @Test fun realQuestionStaysWaitingButCoordinatorFailureWithoutQuestionIsBlocked() {
        val wait = PlanningIssue(IssueKind.CONFIGURATION, "Ожидается ответ планировщику", requiresUser = true)
        val waiting = plan.copy(issue = wait, milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true, error = wait)))))
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, listOf(question), plan = waiting).attentionStatus)
        assertTrue(waiting.blockingIssues(listOf(question)).isEmpty())
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(parent, plan = waiting).attentionStatus)
        assertEquals(1, waiting.blockingIssues(emptyList()).size)
        val failure = PlanningIssue(IssueKind.UNCERTAIN, "Ошибка координатора", requiresUser = true)
        val failed = waiting.copy(issue = failure, milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true, error = failure)))))
        assertEquals(CodingSessionStatus.WAITING, CodingSessionUi(worker, plan = failed).attentionStatus)
        assertEquals(1, failed.blockingIssues(emptyList()).size)
    }

    @Test fun handedOffWorkerIsQueuedUntilExecutionResumes() {
        val handedOff = plan.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(awaitingPlanner = true)))))
        assertEquals(CodingSessionStatus.QUEUED, CodingSessionUi(worker, running = true, plan = handedOff).attentionStatus)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(worker, plan = plan).attentionStatus)
        val planner = CodingSessionUi(parent, listOf(question), draft = CodingDraft(active = true), running = true, plan = handedOff)
        assertEquals(CodingSessionStatus.WAITING, planner.attentionStatus)
        assertEquals(CodingSessionStatus.WAITING, planner.copy(running = false, draft = CodingDraft()).attentionStatus)
    }

    @Test fun unansweredQuestionsRemainAvailableInOrderAcrossNewMessages() {
        val second = question.copy(id = "second", planning = question.planning!!.copy(questions = listOf(PlanningQuestion("other", "Другой вопрос"))))
        val answeredSecond = CodingMessage("answer", CodingRole.USER, "Ответ", createdAt = 3, planning = PlanningChatBlock(plan.id, replyTo = second.id))
        val history = listOf(question, second, answeredSecond)
        assertEquals(question.id, history.pendingPlanningQuestion()?.id)
        assertEquals(CodingSessionStatus.IDLE, codingStatusOf(history))
        assertNull(history.pendingPlanningQuestion(setOf("deleted-plan")))
    }
}
