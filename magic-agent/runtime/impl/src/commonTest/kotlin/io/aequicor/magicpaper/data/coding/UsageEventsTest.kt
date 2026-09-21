package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.*
import kotlin.test.*

class UsageEventsTest {
    @Test fun hiddenServiceStepsNeverHideCompaction() {
        val message = CodingMessage("m", CodingRole.AGENT, "", createdAt = 1, steps = listOf(
            CodingStep(CodingStepKind.INFO, "hidden"), CodingStep(CodingStepKind.SYSTEM, "Контекст сжат", id = "compact")))
        val row = codingChatRows(listOf(message), hideSystemSteps = true).single()
        assertEquals(listOf(CodingStepKind.SYSTEM), row.message.steps.map { it.kind })
        assertEquals("m:step:compact", codingHistoryItems(listOf(row)).single().key)
    }
}
