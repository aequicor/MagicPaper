package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.components.*
import kotlin.test.*

class UsageEventsTest {
    @Test fun piCompactionUsesActualAbortAndErrorFields() {
        fun phase(body: String) = (PiEventParser.parse(body) as CodingEvent.Compaction).status.phase
        assertEquals(CompactionPhase.STARTED, phase("""{"type":"compaction_start","reason":"threshold"}"""))
        assertEquals(CompactionPhase.CANCELLED, phase("""{"type":"compaction_end","aborted":true,"reason":"overflow"}"""))
        assertEquals(CompactionPhase.FAILED, phase("""{"type":"compaction_end","aborted":false,"errorMessage":"failure"}"""))
        assertEquals(CompactionPhase.COMPLETED, phase("""{"type":"compaction_end","aborted":false}"""))
    }
    @Test fun piPreservesUsageOnErrorsAndCompactionSummary() {
        val failed = PiEventParser.parseEvents("""{"type":"message_end","message":{"role":"assistant","timestamp":123,"stopReason":"error","usage":{"input":50,"output":10,"cacheRead":20,"cacheWrite":5,"totalTokens":85}}}""")
        assertEquals(85L, failed.filterIsInstance<CodingEvent.UsageObserved>().single().tokens.totalTokens)
        assertTrue(failed.any { it is CodingEvent.Failed })
        val compact = PiEventParser.parseEvents("""{"type":"compaction_end","result":{"tokensBefore":200,"usage":{"input":200,"output":25}},"aborted":false}""")
        assertEquals(225L, compact.filterIsInstance<CodingEvent.UsageObserved>().single().tokens.totalTokens)
        assertTrue(compact.any { it is CodingEvent.Compaction })
    }
    @Test fun hiddenServiceStepsNeverHideCompaction() {
        val message = CodingMessage("m", CodingRole.AGENT, "", createdAt = 1, steps = listOf(
            CodingStep(CodingStepKind.INFO, "hidden"), CodingStep(CodingStepKind.SYSTEM, "Контекст сжат", id = "compact")))
        val row = codingChatRows(listOf(message), hideSystemSteps = true).single()
        assertEquals(listOf(CodingStepKind.SYSTEM), row.message.steps.map { it.kind })
        assertEquals("m:step:compact", codingHistoryItems(listOf(row)).single().key)
    }
}
