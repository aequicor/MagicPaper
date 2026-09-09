package io.aequicor.magicpaper.domain

import kotlin.test.*

class OrchestrationRecoveryTest {
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), turnIndex = 3)
    private fun plan() = Plan("plan", "project", "goal", coordination = (0..3).map {
        CoordinationRecord("attempt-turn-$it", "stage", StageReply(StageReplyKind.BLOCKED, "Unfinished"),
            attemptId = attempt.id, turnIndex = it)
    })
    private fun answer(state: DeliveryState = DeliveryState.ANSWERED) =
        PlanDelivery("answer", "parent", "stage", "Continue", state, attempt.id, 3, replyTo = "help")

    @Test fun recoveryLimitRemainsUntilAnswerIsConsumed() {
        assertEquals(3, plan().blockedTurnCount("stage", attempt.copy(turnIndex = 2)))
        assertEquals(4, plan().copy(deliveries = listOf(answer(DeliveryState.QUEUED))).blockedTurnCount("stage", attempt))
        assertEquals(4, plan().copy(deliveries = listOf(answer(DeliveryState.CANCELLED))).blockedTurnCount("stage", attempt))
        assertEquals(4, plan().copy(deliveries = listOf(answer().copy(replyTo = null))).blockedTurnCount("stage", attempt))
    }

    @Test fun userAnswerAllowsTwoMoreAutomaticRecoveryTurns() {
        val resumed = plan().copy(deliveries = listOf(answer()))
        assertEquals(1, resumed.blockedTurnCount("stage", attempt))
        val later = resumed.copy(coordination = resumed.coordination + (4..5).map {
            CoordinationRecord("attempt-turn-$it", "stage", StageReply(StageReplyKind.QUESTION, "Still blocked"),
                attemptId = attempt.id, turnIndex = it)
        })
        assertEquals(2, later.blockedTurnCount("stage", attempt.copy(turnIndex = 4)))
        assertEquals(3, later.blockedTurnCount("stage", attempt.copy(turnIndex = 5)))
        assertEquals(1, resumed.copy(deliveries = listOf(answer(DeliveryState.DELIVERED))).blockedTurnCount("stage", attempt))
    }

    @Test fun unrelatedAnswersAndPreviousAttemptsDoNotAffectRecovery() {
        assertEquals(4, plan().copy(deliveries = listOf(answer().copy(targetStageId = "other"))).blockedTurnCount("stage", attempt))
        assertEquals(0, plan().blockedTurnCount("stage", attempt.copy(id = "new-attempt")))
        val oldRun = plan().copy(coordination = plan().coordination.map { it.copy(runId = "old-run") }, runId = "new-run")
        assertEquals(0, oldRun.blockedTurnCount("stage", attempt))
        val legacy = plan().copy(coordination = plan().coordination.map { it.copy(attemptId = "", turnIndex = 0) }, deliveries = listOf(answer()))
        assertEquals(1, legacy.blockedTurnCount("stage", attempt))
    }

    @Test fun resultResetsRecoveryCount() {
        val result = plan().copy(coordination = plan().coordination.map {
            if (it.turnIndex == 2) it.copy(reply = StageReply(StageReplyKind.RESULT, "Done")) else it
        })
        assertEquals(1, result.blockedTurnCount("stage", attempt))
    }

    @Test fun answerDoesNotEchoDiagnosticOrDuplicateQuestionnaireRendering() {
        val question = OrchestrationQuestion("help", "plan", "Нужен ваш ответ: repeated diagnostic",
            listOf(PlanningQuestion("help-question", "repeated diagnostic")), "parent")
        val answers = listOf(PlanningAnswer("help-question", text = "Продолжай исправление"))
        val input = OrchestrationInput("input", interactionAnswerText(question.questions, answers), 1, answers)
        val text = question.workerAnswerText(answers, input)
        assertFalse(text.contains("diagnostic"))
        assertEquals(1, Regex("Продолжай исправление").findAll(text).count())
        assertContains(question.workerAnswerText(emptyList(), input.copy(text = "Сначала проверь сборку", answers = emptyList())), "Сначала проверь сборку")
    }
}
