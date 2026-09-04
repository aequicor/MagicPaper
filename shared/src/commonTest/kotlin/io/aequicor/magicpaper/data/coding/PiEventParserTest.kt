package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PiEventParserTest {

    @Test
    fun sessionHeaderGivesSessionId() {
        val line = """{"type":"session","version":3,"id":"3f2a9c41-7b6e-4d8a-9c12-abcdef012345","timestamp":"t","cwd":"/tmp"}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.SessionStarted>(event)
        assertEquals("3f2a9c41-7b6e-4d8a-9c12-abcdef012345", event.sessionId)
    }

    @Test
    fun textDeltaIsExtracted() {
        val line = """{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Привет"}}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.TextDelta>(event)
        assertEquals("Привет", event.delta)
    }

    @Test
    fun nonTextDeltaIsIgnored() {
        val line = """{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"thinking_delta","delta":"..."}}"""
        assertNull(PiEventParser.parse(line))
    }

    @Test
    fun assistantMessageEndYieldsFinalText() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Готово"},{"type":"text","text":"."}],"stopReason":"stop"}}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.FinalText>(event)
        assertEquals("Готово.", event.text)
    }

    @Test
    fun errorMessageEndYieldsFailed() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"модель недоступна"}}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.Failed>(event)
        assertEquals("модель недоступна", event.message)
    }

    @Test
    fun userMessageEndIsIgnored() {
        val line = """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"привет"}]}}"""
        assertNull(PiEventParser.parse(line))
    }

    @Test
    fun toolStartAndEnd() {
        val start = PiEventParser.parse(
            """{"type":"tool_execution_start","toolCallId":"1","toolName":"read","args":{"path":"src/main.kt"}}"""
        )
        assertIs<CodingEvent.ToolStarted>(start)
        assertEquals("read", start.tool)
        assertEquals("src/main.kt", start.summary)

        val end = PiEventParser.parse("""{"type":"tool_execution_end","toolCallId":"1","toolName":"read","result":{},"isError":true}""")
        assertIs<CodingEvent.ToolFinished>(end)
        assertEquals(true, end.isError)
    }

    @Test
    fun garbageLinesAreIgnored() {
        assertNull(PiEventParser.parse(""))
        assertNull(PiEventParser.parse("не-джисон"))
        assertNull(PiEventParser.parse("{broken"))
        assertNull(PiEventParser.parse("""{"type":"agent_start"}"""))
    }
}
