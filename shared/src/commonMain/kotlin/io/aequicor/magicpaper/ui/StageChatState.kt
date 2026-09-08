package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*

/** A checkpoint can reach the screen before its chat log has been published. */
internal fun CodingSessionUi.withStageChat(
    plan: Plan?, live: Map<String, StageAttempt>, parentMessages: List<CodingMessage>,
): CodingSessionUi {
    val stage = plan?.milestones?.firstOrNull { it.id == session.stageId } ?: return this
    val saved = stage.attempts.lastOrNull() ?: return this
    val running = plan.isStageWorking(stage)
    val attempt = live[saved.id]?.takeIf {
        running && it.chatTurns == saved.chatTurns && it.updatedAt >= saved.updatedAt
    } ?: saved
    val responses = stage.attempts.flatMap { (if (it.id == saved.id) attempt else it).chatResponses(parentMessages) }
    val currentTurn = attempt.effectiveChatTurns().lastOrNull()
    val streaming = running && attempt.phase == AttemptPhase.EXECUTING && currentTurn?.completedAt == 0L
    val currentId = attempt.chatResponseId(attempt.effectiveChatTurns().lastIndex)
    val current = responses.firstOrNull { it.id == currentId }
    val projected = messages.withStageResponses(responses)
    return copy(
        messages = if (streaming) projected.filterNot { it.id == currentId } else projected,
        draft = CodingDraft(steps = if (streaming) current?.steps.orEmpty() else emptyList(),
            active = running, awaitingApproval = draft.awaitingApproval),
        running = running,
    )
}
