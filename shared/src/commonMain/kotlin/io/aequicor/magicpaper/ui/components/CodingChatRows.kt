package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

internal data class CodingChatRow(
    val message: CodingMessage,
    val planCard: CodingMessage? = null,
    /** Derived from the source timeline before visibility filters change positions. */
    val stepKeys: List<String> = message.steps.mapIndexed { index, step -> step.id.ifBlank { "legacy:$index" } },
)

/** Scoped tool calls retain their card when a temporary draft becomes a saved child response. */
private val CodingStep.toolIdentity: String?
    get() = if (toolCategory != null && callId.isNotBlank() && kind in setOf(CodingStepKind.TOOL, CodingStepKind.EXEC)) "tool:$callId" else null

/** A run can contain thousands of steps; each one must be its own lazy-list item. */
internal data class CodingHistoryItem(val row: CodingChatRow, val stepIndex: Int? = null) {
    val first: Boolean get() = stepIndex == null || stepIndex == 0 || step?.kind == CodingStepKind.SYSTEM || row.message.steps.getOrNull(stepIndex - 1)?.kind == CodingStepKind.SYSTEM
    val last: Boolean get() = stepIndex == null || stepIndex == row.message.steps.lastIndex || step?.kind == CodingStepKind.SYSTEM || row.message.steps.getOrNull(stepIndex + 1)?.kind == CodingStepKind.SYSTEM
    val key: String = if (stepIndex == null) row.message.id else step?.toolIdentity ?:
        "${row.message.timelineId ?: row.message.id}:step:${row.stepKeys[stepIndex]}"
    val step: CodingStep? get() = stepIndex?.let { row.message.steps[it] }
}

internal fun codingHistoryItems(rows: List<CodingChatRow>): List<CodingHistoryItem> = buildList {
    rows.forEach { row ->
        if (row.message.role == CodingRole.AGENT && row.message.steps.isNotEmpty()) {
            row.message.steps.indices.forEach { add(CodingHistoryItem(row, it)) }
        } else add(CodingHistoryItem(row))
    }
}

/** Join the saved plan card to its preceding explanation, including existing conversations. */
internal fun codingChatRows(messages: List<CodingMessage>, hideSystemSteps: Boolean = true,
    hideThinking: Boolean = false): List<CodingChatRow> = buildList {
    for (stored in messages) {
        val indices = stored.steps.indices.filter { stored.steps[it].isVisibleInChat(hideSystemSteps) &&
            (!hideThinking || stored.steps[it].kind != CodingStepKind.THINKING) }
        val message = stored.visibleChatContent(indices) ?: continue
        val previous = lastOrNull()
        if (message.role == CodingRole.AGENT && message.planning?.graph == true &&
            previous != null && previous.planCard == null &&
            previous.message.role == CodingRole.AGENT && previous.message.planning?.graph == false &&
            previous.message.planning.planId == message.planning.planId
        ) {
            removeAt(lastIndex)
            add(previous.copy(planCard = message))
        } else add(CodingChatRow(message, stepKeys = indices.map { stored.steps[it].id.ifBlank { "legacy:$it" } }))
    }
}

/** Filter before creating lazy-list items, so hidden records leave neither bubbles nor gaps. */
private fun CodingMessage.visibleChatContent(indices: List<Int>): CodingMessage? {
    val visible = indices.map { steps[it] }
    // Modern activity is a plain-text copy of the timeline, not a second source of content.
    val legacyActivity = if (steps.isEmpty()) activity.filter { it.isNotBlank() } else emptyList()
    val fallback = text.ifBlank { if (failed && visible.isEmpty()) "Не удалось завершить работу агента." else "" }
    val hasMetadata = attachments.isNotEmpty() || route != null || pendingDelivery || inputStatus != null ||
        planning?.let { it.graph || it.questions.isNotEmpty() } == true
    if (fallback.isBlank() && visible.isEmpty() && legacyActivity.isEmpty() && !hasMetadata) return null
    return copy(text = fallback, steps = visible, activity = legacyActivity)
}

internal fun CodingStep.isVisibleInChat(hideSystemSteps: Boolean): Boolean = when (kind) {
    CodingStepKind.SUMMARY -> false // Provider summaries belong to the current status, not the transcript.
    CodingStepKind.INFO -> !hideSystemSteps && isVisibleActivity
    CodingStepKind.SYSTEM -> true
    CodingStepKind.ANSWER, CodingStepKind.THINKING -> title.isNotBlank()
    else -> true // Errors and tool calls remain visible, even without a textual result.
}

internal fun CodingDraft.visibleChatContent(hideSystemSteps: Boolean): CodingDraft = copy(
    steps = steps.filter { it.isVisibleInChat(hideSystemSteps) }.toMutableList().apply {
        if (!failedMessage.isNullOrBlank() && none { it.kind == CodingStepKind.ERROR })
            add(CodingStep(CodingStepKind.ERROR, failedMessage, ok = false))
        if (thinking.isNotBlank() && none { it.kind == CodingStepKind.THINKING && it.title == thinking })
            add(CodingStep(CodingStepKind.THINKING, thinking))
    },
)

/** Saved state wins when repository and draft updates arrive in separate frames. */
internal fun codingDraftRow(draft: CodingDraft, messages: List<CodingMessage>, fallbackId: String,
    hideSystemSteps: Boolean, busy: Boolean): CodingChatRow? {
    val identity = draft.timelineId ?: fallbackId
    val saved = draft.timelineId != null && messages.any { (it.timelineId ?: it.id) == identity }
    val draftCalls = draft.steps.mapNotNull { it.toolIdentity }.toSet()
    val savedCalls = if (draftCalls.isEmpty()) emptySet() else messages.asSequence().flatMap { it.steps.asSequence() }
        .mapNotNull { it.toolIdentity }.filter { it in draftCalls }.toSet()
    val pending = draft.steps.filter { step -> step.toolIdentity?.let { it !in savedCalls } ?: !saved }
    if (saved && pending.isEmpty()) return null
    val steps = pending.toMutableList().apply {
        if (!saved && !draft.failedMessage.isNullOrBlank() && none { it.kind == CodingStepKind.ERROR })
            add(CodingStep(CodingStepKind.ERROR, draft.failedMessage, ok = false, id = "draft-error"))
        if (!saved && draft.thinking.isNotBlank() && none { it.kind == CodingStepKind.THINKING && it.title == draft.thinking })
            add(CodingStep(CodingStepKind.THINKING, draft.thinking, id = "draft-thinking"))
    }
    return codingChatRows(listOf(CodingMessage(identity, CodingRole.AGENT, "", steps = steps,
        createdAt = 0, timelineId = identity)), hideSystemSteps, hideThinking = busy).singleOrNull()
}
