package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

internal data class CodingChatRow(val message: CodingMessage, val planCard: CodingMessage? = null)

/** Join the saved plan card to its preceding explanation, including existing conversations. */
internal fun codingChatRows(messages: List<CodingMessage>, hideSystemSteps: Boolean = true): List<CodingChatRow> = buildList {
    for (stored in messages) {
        val message = stored.visibleChatContent(hideSystemSteps) ?: continue
        val previous = lastOrNull()
        if (message.role == CodingRole.AGENT && message.planning?.graph == true &&
            previous != null && previous.planCard == null &&
            previous.message.role == CodingRole.AGENT && previous.message.planning?.graph == false &&
            previous.message.planning.planId == message.planning.planId
        ) {
            removeAt(lastIndex)
            add(previous.copy(planCard = message))
        } else add(CodingChatRow(message))
    }
}

/** Filter before creating lazy-list items, so hidden records leave neither bubbles nor gaps. */
private fun CodingMessage.visibleChatContent(hideSystemSteps: Boolean): CodingMessage? {
    val visible = steps.filter { it.isVisibleInChat(hideSystemSteps) }
    // Modern activity is a plain-text copy of the timeline, not a second source of content.
    val legacyActivity = if (steps.isEmpty()) activity.filter { it.isNotBlank() } else emptyList()
    val fallback = text.ifBlank { if (failed && visible.isEmpty()) "Не удалось завершить работу агента." else "" }
    val hasMetadata = attachments.isNotEmpty() || route != null || pendingDelivery || inputStatus != null ||
        planning?.let { it.graph || it.questions.isNotEmpty() } == true
    if (fallback.isBlank() && visible.isEmpty() && legacyActivity.isEmpty() && !hasMetadata) return null
    return copy(text = fallback, steps = visible, activity = legacyActivity)
}

internal fun CodingStep.isVisibleInChat(hideSystemSteps: Boolean): Boolean = when (kind) {
    CodingStepKind.INFO -> !hideSystemSteps && isVisibleActivity
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
