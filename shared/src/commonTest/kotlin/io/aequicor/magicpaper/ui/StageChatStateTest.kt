package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class StageChatStateTest {
    private val worker = CodingSession("worker", "project", "Этап", 1, planId = "plan", stageId = "stage")
    private val first = CodingStep(CodingStepKind.ANSWER, """{"kind":"QUESTION","text":"Какой формат?"}""")
    private val final = CodingStep(CodingStepKind.ANSWER, """{"kind":"RESULT","text":"PDF готов"}""")
    private val incoming = CodingMessage("incoming", CodingRole.USER, "PDF", createdAt = 30)
    private val attempt = StageAttempt("attempt", worker.id, StageAssignment("profile", "model"),
        phase = AttemptPhase.EXECUTING, steps = listOf(first, final), updatedAt = 40,
        chatTurns = listOf(StageChatTurn(0, 10, 20), StageChatTurn(1, 35)))
    private fun plan(attempt: StageAttempt) = Plan("plan", "project", "Goal", intent = ExecutionIntent.RUN,
        milestones = listOf(Milestone("stage", "Этап", status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))))

    @Test fun handoffUsesFinalCheckpointEvenBeforeRepositoryAndLiveStateCatchUp() {
        val oldResponse = attempt.copy(steps = listOf(first), chatTurns = attempt.chatTurns.take(1)).chatResponses().single()
        val queued = CodingMessage("queued", CodingRole.USER, "Проверь ещё раз", createdAt = 45, pendingDelivery = true)
        val staleUi = CodingSessionUi(worker, listOf(oldResponse, incoming, queued), running = true)
        val live = mapOf(attempt.id to attempt)
        val streaming = staleUi.withStageChat(plan(attempt), live, emptyList())
        assertEquals(listOf(oldResponse, incoming, queued), streaming.messages)
        assertEquals("PDF готов", streaming.draft.steps.single().title)

        val completed = attempt.copy(awaitingPlanner = true, updatedAt = 50,
            chatTurns = attempt.chatTurns.dropLast(1) + attempt.chatTurns.last().copy(completedAt = 50))
        val handedOff = staleUi.withStageChat(plan(completed), live, emptyList())
        assertFalse(handedOff.running)
        assertTrue(handedOff.draft.steps.isEmpty())
        assertEquals(listOf("Какой формат?", "PDF", "Проверь ещё раз", "PDF готов"), handedOff.messages.map { it.text })
        assertEquals(handedOff.messages, handedOff.withStageChat(plan(completed), emptyMap(), emptyList()).messages)
    }

    @Test fun nextTurnIgnoresThePreviousLiveSnapshotAndKeepsCompletedReplies() {
        val next = attempt.copy(steps = listOf(first), updatedAt = 60)
        val staleLive = attempt.copy(updatedAt = 100, chatTurns = attempt.chatTurns.take(1))
        val state = CodingSessionUi(worker, listOf(incoming)).withStageChat(plan(next), mapOf(next.id to staleLive), emptyList())
        assertEquals(listOf("Какой формат?", "PDF"), state.messages.map { it.text })
        assertTrue(state.draft.steps.isEmpty())
        assertTrue(state.running)
    }

    @Test fun interruptedCheckpointStopsTheSidebarAndLiveAnswerDespiteStaleLiveState() {
        val stopped = attempt.copy(interrupted = true, updatedAt = 50,
            chatTurns = attempt.chatTurns.map { it.copy(completedAt = 50) })
        val state = CodingSessionUi(worker, running = true).withStageChat(plan(stopped), mapOf(attempt.id to attempt), emptyList())
        assertFalse(state.running)
        assertFalse(state.draft.active)
        assertEquals(CodingSessionStatus.QUEUED, state.copy(plan = plan(stopped)).status)
        assertEquals(listOf("Какой формат?", "PDF готов"), state.messages.map { it.text })
    }
}
