package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class CodingHistoryItemsTest {
    @Test fun eachRunStepIsLazyAndTheMessageKeyStillLocatesItsStart() {
        val steps = List(1000) { CodingStep(CodingStepKind.TOOL, "read $it", callId = "call-$it") }
        val request = CodingMessage("request", CodingRole.USER, "Проверь проект", createdAt = 0)
        val response = CodingMessage("response", CodingRole.AGENT, "", steps = steps, createdAt = 1)
        val items = codingHistoryItems(codingChatRows(listOf(request, response)))
        assertEquals(1001, items.size)
        assertEquals(listOf("request", "response"), items.filter { it.first }.map { it.key })
        assertEquals(steps, items.drop(1).map { it.step })
        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertEquals(2, items.count { it.first })
        assertEquals(2, items.count { it.last })
    }

    @Test fun planningCardsAndAttachmentsStayOnTheLastFragment() {
        val block = PlanningChatBlock("plan")
        val response = CodingMessage("response", CodingRole.AGENT, "Пояснение", planning = block,
            steps = List(3) { CodingStep(CodingStepKind.TOOL, "read $it") }, createdAt = 1)
        val card = CodingMessage("card", CodingRole.AGENT, "План", planning = block.copy(graph = true), createdAt = 2)
        val items = codingHistoryItems(codingChatRows(listOf(response, card)))
        assertEquals(3, items.size)
        assertEquals(card, items.single { it.last }.row.planCard)
        assertTrue(items.first().first)
        assertFalse(items[1].first || items[1].last)
    }
}
