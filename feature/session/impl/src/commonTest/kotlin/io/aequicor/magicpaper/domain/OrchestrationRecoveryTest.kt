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

    @Test fun selectivePauseSurvivesPersistenceAndIncludesOnlyDependants() {
        val plan = Plan("p", "project", "goal", milestones = listOf(
            Milestone("research", "Research"), Milestone("design", "Design"),
            Milestone("implementation", "Implementation", dependsOn = listOf("design")),
            Milestone("review", "Review", dependsOn = listOf("implementation"))))
        val question = OrchestrationQuestion("q", "p", "Details", emptyList(), "parent",
            stageIds = listOf("research"), pauseStageIds = listOf("design"), forDiscussion = true)
        val state = OrchestrationState("parent", "project", questions = listOf(question))
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(OrchestrationState.serializer(), json.encodeToString(OrchestrationState.serializer(), state))
        assertEquals(setOf("design", "implementation", "review"), restored.pausedStages(plan))
        assertEquals(emptySet(), restored.pausedStages(plan.copy(id = "other")))
        val answered = question.copy(status = UserRequestStatus.ANSWERED)
        assertTrue(state.copy(questions = listOf(answered)).pausedStages(plan).isEmpty())
        assertEquals(restored.pausedStages(plan), state.copy(questions = listOf(answered.copy(resolutionPending = true))).pausedStages(plan))
        assertTrue(state.copy(questions = listOf(question.copy(pauseStageIds = emptyList()))).pausedStages(plan).isEmpty())
        val awaitingApproval = state.copy(questions = emptyList(), workPauses = mapOf(
            "input" to OrchestrationPause("p", listOf("design"), "proposal")))
        assertEquals(restored.pausedStages(plan), awaitingApproval.pausedStages(plan))
        // A crash after the atomic plan/inbox write must not leave an obsolete pause.
        assertTrue(awaitingApproval.pausedStages(plan.copy(deliveries = listOf(
            PlanDelivery("proposal-approved-design", "parent", "design", "Approved requirements")))).isEmpty())
    }

    @Test fun resolvingOneQuestionDoesNotReleaseAnother() {
        val plan = Plan("p", "project", "goal", milestones = listOf(Milestone("design", "Design")))
        val q = OrchestrationQuestion("q", "p", "Details", emptyList(), "parent", stageIds = listOf("design"), refinementRequest = "Change")
        val state = OrchestrationState("parent", "project", questions = listOf(q, q.copy(id = "q2", status = UserRequestStatus.ANSWERED)))
        assertEquals(setOf("design"), state.pausedStages(plan.copy(intent = ExecutionIntent.PAUSE)))
        assertEquals(setOf("design"), state.pausedStages(plan.copy(intent = ExecutionIntent.STOP)))
    }

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
