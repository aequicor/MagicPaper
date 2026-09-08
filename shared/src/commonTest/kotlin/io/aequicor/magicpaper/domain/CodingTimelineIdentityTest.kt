package io.aequicor.magicpaper.domain

import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingTimelineIdentityTest {
    @Test fun liveBuffersKeepTheirIdentityThroughFlushReconciliationAndPersistence() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ThinkingDelta("Thought"))
        val thoughtId = recorder.timeline().single().id
        recorder.apply(CodingEvent.TextDelta("First"))
        val answerId = recorder.timeline().last().id
        recorder.apply(CodingEvent.TextDelta(" answer"))
        assertEquals(answerId, recorder.timeline().last().id)
        recorder.apply(CodingEvent.ThinkingDelta("More thought"))
        recorder.apply(CodingEvent.TextDelta("Second answer"))
        val oldIds = recorder.timeline().map { it.id }
        recorder.apply(CodingEvent.FinalThinking("Complete thought"))
        recorder.apply(CodingEvent.FinalText("Complete answer"))
        assertEquals(listOf(thoughtId, answerId), recorder.timeline().map { it.id })
        val draft = recorder.draft(true)
        val saved = recorder.message("partial-or-final-response", 1)
        assertEquals(draft.timelineId, saved.timelineId)
        assertEquals(draft.steps, saved.steps)
        assertEquals(saved, Json.decodeFromString<CodingMessage>(Json.encodeToString(CodingMessage.serializer(), saved)))
        recorder.apply(CodingEvent.MessageStarted)
        recorder.apply(CodingEvent.TextDelta("Next"))
        assertTrue(recorder.timeline().last().id !in oldIds, "Absorbed fragments must not donate IDs to later text")
    }

    @Test fun repeatedOrEmptyToolCallIdsDoNotCollideAndProgressKeepsTheStepId() {
        val recorder = CodingRunRecorder()
        for (callId in listOf("", "", "same", "same")) {
            recorder.apply(CodingEvent.ToolStarted("read", "file", callId = callId))
            val id = recorder.timeline().last().id
            assertTrue(id.isNotBlank())
            recorder.apply(CodingEvent.ToolProgress("read", callId = callId, resultPreview = "Partial"))
            assertEquals(id, recorder.timeline().last().id)
            recorder.apply(CodingEvent.ToolFinished("read", false, callId = callId, resultPreview = "Complete"))
            assertEquals(id, recorder.timeline().last().id)
            assertFalse(recorder.timeline().last().running)
        }
        val ids = recorder.timeline().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val next = CodingRunRecorder().apply { apply(CodingEvent.ToolStarted("read", "file")) }
        assertTrue(next.timeline().single().id !in ids)
    }

    @Test fun oldLogsWithoutTimelineOrStepIdsStillLoad() {
        val message = Json.decodeFromString<CodingMessage>("""{"id":"old","role":"AGENT","text":"","createdAt":1,"steps":[{"kind":"TOOL","title":"read"}]}""")
        assertNull(message.timelineId)
        assertEquals("", message.steps.single().id)
    }
}
