package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiEventParserTest {

    @Test
    fun sessionHeaderGivesSessionId() {
        val line = """{"type":"session","version":3,"id":"3f2a9c41-7b6e-4d8a-9c12-abcdef012345","timestamp":"t","cwd":"/tmp"}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.SessionStarted>(event)
        assertEquals("3f2a9c41-7b6e-4d8a-9c12-abcdef012345", event.sessionId)
    }

    @Test
    fun assistantMessageStartIsReported() {
        val start = PiEventParser.parse(
            """{"type":"message_start","message":{"role":"assistant","content":[]}}"""
        )
        assertIs<CodingEvent.MessageStarted>(start)
        // Пользовательское message_start — не ответ модели.
        assertNull(PiEventParser.parse("""{"type":"message_start","message":{"role":"user","content":[]}}"""))
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
        assertEquals("1", start.callId)
        assertEquals(false, start.isExec)

        val bash = PiEventParser.parse(
            """{"type":"tool_execution_start","toolCallId":"2","toolName":"bash","args":{"command":"ls -la"}}"""
        )
        assertIs<CodingEvent.ToolStarted>(bash)
        assertEquals(true, bash.isExec)
        assertEquals("ls -la", bash.summary)

        val end = PiEventParser.parse(
            """{"type":"tool_execution_end","toolCallId":"1","toolName":"read","result":{"content":[{"type":"text","text":"file body"}]},"isError":true}"""
        )
        assertIs<CodingEvent.ToolFinished>(end)
        assertEquals(true, end.isError)
        assertEquals("1", end.callId)
        assertEquals("file body", end.resultPreview)
    }

    @Test
    fun toolUpdateGivesCumulativePreview() {
        val update = PiEventParser.parse(
            """{"type":"tool_execution_update","toolCallId":"9","toolName":"bash","args":{},"partialResult":{"content":[{"type":"text","text":"line1\nline2"}]}}"""
        )
        assertIs<CodingEvent.ToolProgress>(update)
        assertEquals("9", update.callId)
        assertEquals("line1\nline2", update.resultPreview)
    }

    @Test
    fun longResultPreviewIsTruncated() {
        val big = "x".repeat(3000)
        val end = PiEventParser.parse(
            """{"type":"tool_execution_end","toolCallId":"1","toolName":"read","result":{"content":[{"type":"text","text":"$big"}]},"isError":false}"""
        )
        assertIs<CodingEvent.ToolFinished>(end)
        assertTrue(end.resultPreview.length <= 2002) // 2000 + многоточие
        assertTrue(end.resultPreview.endsWith("…"))
    }

    @Test
    fun nonObjectResultFallsBackToJson() {
        val end = PiEventParser.parse(
            """{"type":"tool_execution_end","toolCallId":"1","toolName":"edit","result":{"filePath":"a.txt","replacements":2},"isError":false}"""
        )
        assertIs<CodingEvent.ToolFinished>(end)
        assertTrue(end.resultPreview.contains("replacements"))
    }

    @Test
    fun garbageLinesAreIgnored() {
        assertNull(PiEventParser.parse(""))
        assertNull(PiEventParser.parse("не-джисон"))
        assertNull(PiEventParser.parse("{broken"))
        assertNull(PiEventParser.parse("""{"type":"agent_start"}"""))
    }
}
