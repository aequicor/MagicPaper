package io.aequicor.magicpaper.domain

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodingDraftUpdatesTest {
    @Test fun burstsKeepEveryDeltaAndPublishTheTrailingSnapshotDuringIdle() = runTest {
        val events = Channel<CodingEvent>(Channel.UNLIMITED)
        val recorder = CodingRunRecorder()
        val drafts = mutableListOf<CodingDraft>()
        val job = launch { recorder.recordDrafts(events.receiveAsFlow()).collect { drafts += it } }
        events.send(CodingEvent.MessageStarted)
        runCurrent()
        repeat(10_000) { events.send(CodingEvent.TextDelta("$it ")) }
        runCurrent()
        assertTrue(drafts.size <= 1, "Raw tokens must not allocate one UI snapshot each")
        advanceTimeBy(50); runCurrent()
        val expected = (0 until 10_000).joinToString("") { "$it " }
        assertEquals(expected, drafts.last().steps.last().title)
        val count = drafts.size
        advanceTimeBy(500); runCurrent()
        assertEquals(count, drafts.size, "An idle agent must not keep invalidating the UI")
        events.send(CodingEvent.ThinkingDelta("next thought"))
        events.close()
        job.join()
        assertEquals("next thought", drafts.last().thinking, "Closing the flow flushes the pending suffix")
        assertEquals(expected.trim(), recorder.timeline().first().title)
    }

    @Test fun toolAndFailureBoundariesAreImmediateAndCancellationRetainsPartialText() = runTest {
        val events = Channel<CodingEvent>(Channel.UNLIMITED)
        val recorder = CodingRunRecorder()
        val drafts = mutableListOf<CodingDraft>()
        val job = launch { recorder.recordDrafts(events.receiveAsFlow()).collect { drafts += it } }
        events.send(CodingEvent.TextDelta("Before tool"))
        events.send(CodingEvent.ToolStarted("read", "file.kt", callId = "call"))
        runCurrent()
        assertTrue(drafts.last().steps.last().running)
        events.send(CodingEvent.ToolFinished("read", resultPreview = "file output", isError = false, callId = "call"))
        runCurrent()
        assertEquals("file output", drafts.last().steps.last().result)
        events.send(CodingEvent.TextDelta("Pending text")); runCurrent()
        job.cancel(); job.join()
        assertEquals("Pending text", recorder.timeline().last().title)
        events.close()
    }
}
