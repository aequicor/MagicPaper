package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.TRUNCATED_HEADLINE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiEventParserTest {

    @Test
    fun subscriptionSummariesAreStatusOnlyInDeltasAndFinalSnapshots() {
        val delta = PiEventParser.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"Running final verification","partial":{"api":"openai-codex-responses"}}}""")
        assertEquals(CodingEvent.ThinkingDelta("Running final verification", summary = true), delta)
        val final = PiEventParser.parseEvents("""{"type":"message_end","message":{"role":"assistant","api":"openai-codex-responses","content":[{"type":"thinking","thinking":"Running final verification"}],"stopReason":"stop"}}""")
        assertEquals(listOf(CodingEvent.FinalThinking("Running final verification", summary = true)), final)
        val withoutMetadata = """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"Checking"}}"""
        assertEquals(CodingEvent.ThinkingDelta("Checking", summary = true), PiEventParser.parseEvents(withoutMetadata, summaryOnly = true).single())
    }

    @Test
    fun nativeThinkingIsNotClassifiedByTextLength() {
        val delta = PiEventParser.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"Why?","partial":{"api":"openai-completions"}}}""")
        assertEquals(CodingEvent.ThinkingDelta("Why?"), delta)
    }

    @Test
    fun finalResponsesSignatureKeepsContentSeparateFromSummary() {
        val events = PiEventParser.parseEvents("""{"type":"message_end","message":{"role":"assistant","api":"openai-responses","content":[{"type":"thinking","thinking":"Checking","thinkingSignature":"{\"summary\":[{\"text\":\"Checking\"}],\"content\":[{\"text\":\"The first condition rules out the initial approach.\"}],\"encrypted_content\":\"opaque\"}"}],"stopReason":"stop"}}""")
        assertEquals(listOf(CodingEvent.FinalThinking("Checking", summary = true),
            CodingEvent.FinalThinking("The first condition rules out the initial approach.")), events)
    }

    @Test
    fun redactedThinkingDoesNotCreateAnEmptyDisclosure() {
        val events = PiEventParser.parseEvents("""{"type":"message_end","message":{"role":"assistant","api":"anthropic-messages","content":[{"type":"thinking","thinking":"[redacted]","redacted":true,"thinkingSignature":"opaque"}],"stopReason":"stop"}}""")
        assertTrue(events.isEmpty())
    }

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
    fun thinkingDeltaIsExtracted() {
        val line = """{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Думаю о правке"}}"""
        val event = PiEventParser.parse(line)
        assertIs<CodingEvent.ThinkingDelta>(event)
        assertEquals("Думаю о правке", event.delta)
    }

    @Test
    fun nonTextDeltaIsIgnored() {
        val line = """{"type":"message_update","usage":{},"assistantMessageEvent":{"type":"toolcall_delta","delta":"..."}}"""
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
    fun messageEndCarriesThinkingBeforeText() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Сначала проверю файл"},{"type":"text","text":"Готово"}],"stopReason":"stop"}}"""
        val events = PiEventParser.parseEvents(line)
        assertIs<CodingEvent.FinalThinking>(events[0])
        assertEquals("Сначала проверю файл", (events[0] as CodingEvent.FinalThinking).text)
        assertIs<CodingEvent.FinalText>(events[1])
    }

    /**
     * Регресс того самого прогона: message_end с stopReason="length" и пустым
     * телом (всё сгорело в рассуждении) раньше разобрался в null, и рантайм
     * докладывает «Агент завершился без ответа» — причину терял именно этот шаг.
     */
    @Test
    fun emptyTurnCutByLengthIsReportedAsTruncation() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"length","usage":{"input":59969,"output":8192,"reasoning":8192}}}"""
        val events = PiEventParser.parseEvents(line)
        assertEquals(1, events.size, "причина должна быть одна и та же, что в usage")
        val event = assertIs<CodingEvent.OutputTruncated>(events.first())
        assertEquals(8192, event.outputTokens)
        assertEquals(8192, event.reasoningTokens)
        assertTrue(event.summary.contains("Ответ обрезан лимитом max_tokens"), event.summary)
        // Пустой ход — не «нет событий»: иначе рантайму не о чем сообщить.
        assertEquals(event, PiEventParser.parse(line))
    }

    @Test
    fun thinkingOnlyContentCutByLengthIsTruncationToo() {
        // Мышление — не ответ: текст сообщения так и остался незаполненным.
        val line = """{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"разбор..."}],"stopReason":"length","usage":{"output":16384,"reasoning":16384}}}"""
        val events = PiEventParser.parseEvents(line)
        // Рассуждение показываем даже когда до тела сообщения потолок не дошёл.
        assertEquals(CodingEvent.FinalThinking("разбор..."), events[0])
        assertIs<CodingEvent.OutputTruncated>(events[1])
    }

    @Test
    fun truncatedAnswerKeepsTextAndAddsNotice() {
        val line = """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Прочитал файл"}],"stopReason":"length","usage":{"output":8192,"reasoning":200}}}"""
        val events = PiEventParser.parseEvents(line)
        assertEquals(2, events.size)
        assertEquals(CodingEvent.FinalText("Прочитал файл"), events[0])
        // Обрезанный ответ не должен выглядеть целым.
        assertEquals(CodingEvent.Notice(TRUNCATED_HEADLINE), events[1])
    }

    @Test
    fun truncatedTurnWithToolCallIsNotAnEmptyTurn() {
        // Намерение выражено: правка началась, просто не хватило текста рядом.
        val line = """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"1","name":"edit","arguments":{"path":"a.txt"}}],"stopReason":"length","usage":{"output":8192}}}"""
        assertTrue(PiEventParser.parseEvents(line).isEmpty())
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
