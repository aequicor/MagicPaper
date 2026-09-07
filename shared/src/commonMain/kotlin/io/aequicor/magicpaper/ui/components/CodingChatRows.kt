package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.CodingMessage
import io.aequicor.magicpaper.domain.CodingRole

internal data class CodingChatRow(val message: CodingMessage, val planCard: CodingMessage? = null)

/** Join the saved plan card to its preceding explanation, including existing conversations. */
internal fun codingChatRows(messages: List<CodingMessage>): List<CodingChatRow> = buildList {
    for (message in messages) {
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
