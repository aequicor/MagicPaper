package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class CodingChatRowsTest {
    private val reply = CodingMessage("reply", CodingRole.AGENT, "Explanation", createdAt = 1,
        planning = PlanningChatBlock("plan"))
    private val card = CodingMessage("card", CodingRole.AGENT, "Ready", createdAt = 2,
        planning = PlanningChatBlock("plan", graph = true))

    @Test fun existingPlanAndExplanationShareOneRowWithStableKey() {
        val before = codingChatRows(listOf(reply)).single()
        val after = codingChatRows(listOf(reply, card)).single()
        assertEquals(before.message.id, after.message.id)
        assertEquals(reply, after.message)
        assertEquals(card, after.planCard)
    }

    @Test fun unrelatedMessagesAndStandaloneCardsStaySeparate() {
        val user = CodingMessage("user", CodingRole.USER, "Next", createdAt = 2)
        assertEquals(3, codingChatRows(listOf(reply, user, card)).size)
        assertEquals(2, codingChatRows(listOf(reply, card.copy(planning = PlanningChatBlock("other", graph = true)))).size)
        assertNull(codingChatRows(listOf(card)).single().planCard)
    }
}
