package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json

private val stageChatJson = Json { ignoreUnknownKeys = true }

internal fun stageReplyOrNull(text: String): StageReply? = runCatching {
    stageChatJson.decodeFromString<StageReply>(text.substringAfter("```json").substringBeforeLast("```").trim())
}.getOrNull()

/** Old checkpoints already contain the final routing envelopes, so their turns can be recovered. */
internal fun StageAttempt.effectiveChatTurns(): List<StageChatTurn> {
    if (chatTurns.isNotEmpty()) return chatTurns
    val activity = steps.filter { it.isVisibleActivity }
    if (activity.isEmpty() && report.isBlank()) return emptyList()
    return buildList {
        add(StageChatTurn(0))
        activity.forEachIndexed { index, step ->
            if (index < activity.lastIndex && step.kind == CodingStepKind.ANSWER && stageReplyOrNull(step.title) != null)
                add(StageChatTurn(index + 1))
        }
    }
}

internal fun StageAttempt.chatResponseId(index: Int): String =
    if (index == 0) "$id-response" else "$id-response-$index"

/** Both live UI and persistence use the same per-turn projection, including during handoff. */
internal fun StageAttempt.chatResponses(parentMessages: List<CodingMessage> = emptyList()): List<CodingMessage> {
    val activity = steps.filter { it.isVisibleActivity }
    val turns = effectiveChatTurns()
    return turns.mapIndexedNotNull { index, turn ->
        val start = turn.startStep.coerceIn(0, activity.size)
        val end = (turns.getOrNull(index + 1)?.startStep ?: activity.size).coerceIn(start, activity.size)
        val timeline = readableStageActivity(activity.subList(start, end))
        val text = timeline.filter { it.kind == CodingStepKind.ANSWER }.joinToString("\n\n") { it.title }
            .ifBlank { if (chatTurns.isEmpty() && activity.isEmpty()) stageReplyOrNull(report)?.text ?: report else "" }
        if (timeline.isEmpty() && text.isBlank()) return@mapIndexedNotNull null
        val legacyCompletedAt = if (turn.startedAt == 0L) parentMessages.firstOrNull { it.id == "$id-turn-$index" }?.createdAt else null
        CodingMessage(chatResponseId(index), CodingRole.AGENT, text, steps = timeline,
            failed = timeline.any { it.kind == CodingStepKind.ERROR } || (index == turns.lastIndex && error != null && !awaitingPlanner),
            createdAt = turn.completedAt.takeIf { it > 0 } ?: legacyCompletedAt
                ?: turn.startedAt.takeIf { it > 0 } ?: (startedAt + index + 1))
    }
}

internal fun List<CodingMessage>.withStageResponses(responses: List<CodingMessage>): List<CodingMessage> {
    val replacements = responses.associateBy { it.id }
    val existingIds = map { it.id }.toSet()
    return (map { replacements[it.id] ?: it } + responses.filter { it.id !in existingIds }).sortedWith(compareBy<CodingMessage> { it.createdAt }.thenBy { if (it.handoff != null) 1 else 0 })
}
