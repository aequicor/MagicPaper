package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Лента прогона: порядок шагов хронологичен, живость и финал совпадают. */
class CodingRunRecorderTest {

    @Test
    fun timelineKeepsChronologicalOrder() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("Сейчас посмотрю файл. "))
        recorder.apply(CodingEvent.ToolStarted("read", "src/main.kt", callId = "c1"))
        recorder.apply(CodingEvent.ToolFinished("read", isError = false, callId = "c1", resultPreview = "fun main() {}"))
        recorder.apply(CodingEvent.TextDelta("Готово, код простой."))

        val timeline = recorder.timeline()
        assertEquals(
            listOf(CodingStepKind.ANSWER, CodingStepKind.TOOL, CodingStepKind.ANSWER),
            timeline.map { it.kind },
        )
        assertEquals("Сейчас посмотрю файл.", timeline[0].title) // хвостовой пробел срезан при фиксации
        assertEquals("fun main() {}", timeline[1].result)
        assertFalse(timeline[1].running)
        assertTrue(timeline[1].ok)
        assertEquals("Готово, код простой.", timeline[2].title)
    }

    @Test
    fun finalTextReplacesDeltasOfCurrentFragment() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("Черно"))
        recorder.apply(CodingEvent.TextDelta("вик"))
        recorder.apply(CodingEvent.FinalText("Чистовик ответа"))
        recorder.apply(CodingEvent.ToolStarted("edit", "a.txt", callId = "e1"))

        val timeline = recorder.timeline()
        // Финальный текст авторитетнее: в ленте до действия — он, а не склеенные дельты.
        assertEquals("Чистовик ответа", timeline.first { it.kind == CodingStepKind.ANSWER }.title)
    }

    @Test
    fun toolProgressUpdatesRunningStep() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ToolStarted("bash", "ls -la", callId = "b1", isExec = true))
        recorder.apply(CodingEvent.ToolProgress("bash", callId = "b1", resultPreview = "file1"))
        recorder.apply(CodingEvent.ToolProgress("bash", callId = "b1", resultPreview = "file1\nfile2"))
        recorder.apply(CodingEvent.ToolFinished("bash", isError = false, callId = "b1", resultPreview = "file1\nfile2"))

        val timeline = recorder.timeline()
        assertEquals(1, timeline.size)
        assertEquals(CodingStepKind.EXEC, timeline[0].kind)
        assertEquals("file1\nfile2", timeline[0].result)
        assertFalse(timeline[0].running)
    }

    @Test
    fun parallelCallsCorrelateById() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ToolStarted("read", "a.txt", callId = "r1"))
        recorder.apply(CodingEvent.ToolStarted("read", "b.txt", callId = "r2"))
        recorder.apply(CodingEvent.ToolFinished("read", isError = true, callId = "r2", resultPreview = "нет такого"))

        val timeline = recorder.timeline()
        assertEquals(true, timeline[0].running) // r1 ещё работает
        assertEquals(false, timeline[1].running)
        assertFalse(timeline[1].ok)
        assertEquals("нет такого", timeline[1].result)
    }

    @Test
    fun failedRunMarksErrorStepAndMessage() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("Думаю…"))
        recorder.apply(CodingEvent.Failed("лимит токенов"))

        val draft = recorder.draft(active = false)
        assertEquals("лимит токенов", draft.failedMessage)
        assertEquals(CodingStepKind.ERROR, draft.steps.last().kind)

        val message = recorder.message("m1", 123L)
        assertTrue(message.failed)
        assertEquals("Думаю…", message.text)
        assertTrue(message.activity.any { it.contains("лимит токенов → ошибка") })
    }

    @Test
    fun messageWithoutAnyTextGetsPlaceholder() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ToolStarted("read", "x", callId = "1"))
        recorder.apply(CodingEvent.ToolFinished("read", isError = false, callId = "1"))
        val message = recorder.message("m", 0L)
        assertEquals("Агент не оставил текста.", message.text)
        assertEquals(1, message.activity.size)
    }
}
