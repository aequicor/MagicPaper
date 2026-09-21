package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeToolPresentation
import io.aequicor.magicpaper.backend.NativeToolPresentationResolver
import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class ClaudeStreamParserTest {
    private fun ClaudeStreamParser.all(vararg lines: String) = lines.flatMap(::parse)

    @Test fun initAnnouncesTheSessionOnce() {
        val parser = ClaudeStreamParser()
        val init = """{"type":"system","subtype":"init","session_id":"s-1","model":"claude-haiku-4-5"}"""
        assertEquals(listOf<CodingEvent>(CodingEvent.SessionStarted("s-1")), parser.parse(init))
        assertEquals(emptyList(), parser.parse(init))
    }

    @Test fun partialEventsStreamTextAndThinkingThenReportUsageOnceForTheCall() {
        val parser = ClaudeStreamParser(contextWindow = 200_000)
        val events = parser.all(
            """{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":100,"output_tokens":1,"cache_read_input_tokens":40,"cache_creation_input_tokens":10}}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hm"}}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"ok"}}}""",
            """{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}}""",
            """{"type":"stream_event","event":{"type":"message_stop"}}""",
            """{"type":"assistant","message":{"id":"m1","content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":100,"output_tokens":7}}}""",
        )
        assertEquals(CodingEvent.MessageStarted, events[0])
        assertEquals(CodingEvent.ThinkingDelta("hm"), events[1])
        assertEquals(CodingEvent.TextDelta("ok"), events[2])
        val usage = events.filterIsInstance<CodingEvent.UsageObserved>().single()
        assertEquals(TokenUsage(100, 7, 40, 10), usage.tokens)
        assertEquals("message:m1", usage.sourceId)
        assertEquals(CodingEvent.ContextUpdated(150, 200_000), events.filterIsInstance<CodingEvent.ContextUpdated>().single())
        assertEquals(CodingEvent.FinalThinking("hm"), events.filterIsInstance<CodingEvent.FinalThinking>().single())
        assertEquals("ok", events.filterIsInstance<CodingEvent.FinalText>().single().text)
    }

    @Test fun completeAssistantMessageAloneStillReportsUsage() {
        val events = ClaudeStreamParser().all(
            """{"type":"assistant","message":{"id":"m9","content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}}""")
        assertEquals(TokenUsage(5, 2, null, null), events.filterIsInstance<CodingEvent.UsageObserved>().single().tokens)
    }

    @Test fun toolCallPairsWithItsResultAndMarksShellCommands() {
        val events = ClaudeStreamParser().all(
            """{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls","description":"list"}},{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/p/a.kt"}}],"stop_reason":"tool_use"}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"a.kt\nb.kt","is_error":false},{"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":"denied"}],"is_error":true}]}}""",
        )
        assertEquals(CodingEvent.ToolStarted("Bash", "ls", "t1", isExec = true), events[0])
        assertEquals(CodingEvent.ToolStarted("Read", "/p/a.kt", "t2"), events[1])
        assertEquals(CodingEvent.ToolFinished("Bash", false, "t1", "a.kt\nb.kt"), events[2])
        assertEquals(CodingEvent.ToolFinished("Read", true, "t2", "denied"), events[3])
    }

    @Test fun applicationToolsAreNamedByTheHostResolver() {
        val parser = ClaudeStreamParser(NativeToolPresentationResolver { server, tool, _ -> NativeToolPresentation("$server/$tool", "shown") })
        val started = parser.parse(
            """{"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"t1","name":"mcp__magicpaper_agent_tools__a__b","input":{}}]}}""").single()
        assertEquals(CodingEvent.ToolStarted("magicpaper_agent_tools/a__b", "shown", "t1"), started)
        val finished = parser.parse("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"x"}]}}""").single()
        assertEquals("magicpaper_agent_tools/a__b", (finished as CodingEvent.ToolFinished).tool)
    }

    @Test fun subagentTextIsNotShownAsTheAnswer() {
        val events = ClaudeStreamParser().all(
            """{"type":"assistant","parent_tool_use_id":"t0","message":{"id":"m4","content":[{"type":"text","text":"inner"}]}}""",
            """{"type":"stream_event","parent_tool_use_id":"t0","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"inner"}}}""")
        assertTrue(events.isEmpty())
    }

    @Test fun cutOffAnswerIsNotPassedOffAsComplete() {
        val text = ClaudeStreamParser().all(
            """{"type":"assistant","message":{"id":"m5","content":[{"type":"text","text":"partial"}],"stop_reason":"max_tokens"}}""")
        assertEquals(listOf(CodingEvent.FinalText("partial"), CodingEvent.Notice(TRUNCATED_HEADLINE)), text)
        val empty = ClaudeStreamParser().all(
            """{"type":"assistant","message":{"id":"m6","content":[],"stop_reason":"max_tokens","usage":{"output_tokens":64}}}""")
        assertEquals(listOf<CodingEvent>(CodingEvent.OutputTruncated(64)), empty)
    }

    @Test fun successfulResultIsTheOnlyProofOfCompletion() {
        val parser = ClaudeStreamParser()
        assertNull(parser.result)
        assertEquals(listOf<CodingEvent>(CodingEvent.AgentEnd), parser.parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"done","session_id":"s"}"""))
        assertEquals(false, parser.result?.failed)
        assertEquals("done", parser.result?.text)
    }

    /** The lines are what Claude Code 2.1.275 wrote without a sign-in: subtype stays `success`, `is_error` decides. */
    @Test fun missingSignInIsReportedWithAnActionableMessage() {
        val parser = ClaudeStreamParser()
        val events = parser.all(
            """{"type":"assistant","message":{"model":"<synthetic>","role":"assistant","content":[{"type":"text","text":"Not logged in · Please run /login"}]},"error":"authentication_failed","is_api_error_message":true}""",
            """{"duration_api_ms":0,"is_error":true,"subtype":"success","result":"Not logged in · Please run /login","type":"result","terminal_reason":"api_error"}""")
        assertEquals(listOf<CodingEvent>(CodingEvent.AgentEnd), events)
        assertEquals(true, parser.result?.failed)
        assertContains(parser.result?.message.orEmpty(), "не авторизован")
    }

    @Test fun otherFailuresKeepOneShortLineAndNeverTheRawBody() {
        val parser = ClaudeStreamParser()
        parser.parse("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":""}""")
        assertContains(parser.result?.message.orEmpty(), "предел шагов")
        val long = ClaudeStreamParser()
        long.parse("""{"type":"result","is_error":true,"result":"API Error: 500\n${"x".repeat(2000)}"}""")
        val message = long.result?.message.orEmpty()
        assertFalse('\n' in message)
        assertTrue(message.length <= 401)
    }

    @Test fun compactionAndRetriesAreVisible() {
        val events = ClaudeStreamParser().all(
            """{"type":"system","subtype":"status","status":"compacting"}""",
            """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto"}}""",
            """{"type":"system","subtype":"api_retry","attempt":2,"max_retries":10}""")
        assertEquals(CompactionPhase.STARTED, (events[0] as CodingEvent.Compaction).status.phase)
        assertEquals(CompactionPhase.COMPLETED, (events[1] as CodingEvent.Compaction).status.phase)
        assertEquals(CodingEvent.Notice("Сбой у провайдера, автоповтор №2 из 10…"), events[2])
    }

    @Test fun damagedAndUnknownLinesAreIgnored() {
        val parser = ClaudeStreamParser()
        assertTrue(parser.all("", "plain log line", "{broken", """{"type":"rate_limit_event"}""", "[1,2]").isEmpty())
    }
}
