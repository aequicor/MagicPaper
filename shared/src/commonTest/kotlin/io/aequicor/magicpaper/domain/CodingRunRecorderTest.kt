package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.PiModelsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Лента прогона: порядок шагов хронологичен, живость и финал совпадают. */
class CodingRunRecorderTest {
    @Test fun silentFlushTicksNeverBecomeTimelineEntries() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.TextDelta("Начало ответа"))
        repeat(600) { recorder.apply(CodingEvent.Notice(if (it % 2 == 0) "" else "  \n")) }
        assertEquals(listOf(CodingStep(CodingStepKind.ANSWER, "Начало ответа")), recorder.timeline())
        recorder.apply(CodingEvent.Notice("Ожидание инструмента"))
        assertTrue(recorder.timeline().any { it.kind == CodingStepKind.INFO && it.title == "Ожидание инструмента" })
        assertFalse(CodingStep(CodingStepKind.INFO, " ").isVisibleActivity)
        assertTrue(CodingStep(CodingStepKind.TOOL, "", result = "Результат").isVisibleActivity)
    }


    @Test
    fun thinkingIsKeptAsTimelineStepAndInDraft() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.MessageStarted)
        recorder.apply(CodingEvent.ThinkingDelta("Думаю: сначала прочитаю файл. "))
        // Живой черновик показывает рассуждение ещё до фиксации в ленту.
        assertTrue(recorder.draft(active = true).thinking.contains("сначала прочитаю файл"))

        recorder.apply(CodingEvent.TextDelta("Готово."))
        val timeline = recorder.timeline()
        assertEquals(
            listOf(CodingStepKind.THINKING, CodingStepKind.ANSWER),
            timeline.map { it.kind },
        )
        assertEquals("Думаю: сначала прочитаю файл.", timeline[0].title)
        // После фиксации живой хвост пуст: дублировать рассуждение в панели не нужно.
        assertEquals("", recorder.draft(active = true).thinking)
    }

    @Test
    fun finalThinkingOverridesDeltasButStaysBeforeAnswer() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.ThinkingDelta("черновик мысли"))
        recorder.apply(CodingEvent.FinalThinking("полная мысль из message_end"))
        recorder.apply(CodingEvent.FinalText("Ответ"))
        recorder.apply(CodingEvent.AgentEnd)
        val kinds = recorder.timeline().map { it.kind }
        assertEquals(listOf(CodingStepKind.THINKING, CodingStepKind.ANSWER), kinds)
        assertEquals("полная мысль из message_end", recorder.timeline()[0].title)
    }

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
    fun runStartsWaitingAndWorkingAfterModelReplies() {
        val recorder = CodingRunRecorder()
        // Прогон запущен, модель молчит — индикатор показывает «ждёт».
        assertTrue(recorder.draft(active = true).awaitingModel)

        recorder.apply(CodingEvent.MessageStarted)
        assertFalse(recorder.draft(active = true).awaitingModel)

        // После действия агент снова ждёт ответ модели (или подтверждение).
        recorder.apply(CodingEvent.ToolStarted("bash", "ls", callId = "b1"))
        recorder.apply(CodingEvent.ToolFinished("bash", isError = false, callId = "b1"))
        assertTrue(recorder.draft(active = true).awaitingModel)

        // Неактивный черновик фазу «ожидание» не показывает.
        assertFalse(recorder.draft(active = false).awaitingModel)
    }

    @Test
    fun truncatedTurnExplainsItselfAndStopsWaiting() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.MessageStarted)
        val finished = recorder.apply(CodingEvent.OutputTruncated(outputTokens = 8192, reasoningTokens = 8192))
        // Обрезка — не конец прогона: рантайм ещё может продолжить ту же сессию.
        assertFalse(finished)
        // Но и «ждём модель» вешать нельзя: модель высказалась, сколько смогла.
        assertFalse(recorder.draft(active = true).awaitingModel)
        val step = recorder.timeline().last()
        assertEquals(CodingStepKind.INFO, step.kind)
        assertEquals(
            "Ответ обрезан лимитом max_tokens — 8192 токенов вывода, из них 8192 на рассуждение",
            step.title,
        )
        assertFalse(step.ok)
    }

    @Test
    fun failedMessageKeepsTruncationReason() {
        val profile = LlmProfile(
            id = "p",
            name = "Alibaba",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            provider = ProviderType.OPENAI_COMPATIBLE,
            modelId = "qwen3.8-flash",
            advanced = AdvancedLlmOptions(maxTokens = 8192),
        )
        val recorder = CodingRunRecorder()
        // Именно этот текст даёт рантайм вместо «Агент завершился без ответа».
        recorder.apply(CodingEvent.Failed(PiModelsConfig.truncationAdvice(profile, 8192, 8192)))
        val message = recorder.message("m1", createdAt = 0L)
        assertTrue(message.failed)
        // «Заклинание не сработало» остаётся, но внутри — настоящая причина.
        assertTrue(message.text.contains("весь лимит вывода на рассуждение"), message.text)
        assertTrue(message.text.contains("8192 из 16384"), message.text)
        assertFalse(message.text.contains("без ответа"), message.text)
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
    fun noticeBecomesInfoStepWithoutChangingPhase() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.Notice("Уплотняю контекст…"))
        val timeline = recorder.timeline()
        assertEquals(CodingStepKind.INFO, timeline.single().kind)
        // Служебные сообщения фазу ожидания ответа модели не снимают.
        assertTrue(recorder.draft(active = true).awaitingModel)
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

/** Статусы активности кодинг-сессий: кружок должен отражать реальное состояние. */
class CodingSessionStatusTest {

    private fun user(text: String) = CodingMessage(id = "u", role = CodingRole.USER, text = text, createdAt = 1L)
    private fun agent(text: String, failed: Boolean = false) =
        CodingMessage(id = "a", role = CodingRole.AGENT, text = text, failed = failed, createdAt = 2L)

    @Test
    fun emptyLogWaitsForRequest() {
        assertEquals(CodingSessionStatus.IDLE, codingStatusOf(emptyList()))
    }

    @Test
    fun finishedAnswerWaitsForRequest() {
        assertEquals(CodingSessionStatus.IDLE, codingStatusOf(listOf(user("сделай"), agent("Готово."))))
    }

    @Test
    fun agentQuestionWaitsForConfirmation() {
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(listOf(agent("Удалить файл?"))))
        // Вопрос под markdown-обёрткой тоже считается.
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(listOf(agent("Продолжить?**"))))
        // Вопрос в последней строке многострочного ответа.
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(listOf(agent("Готово.\n\nУдалить черновик?"))))
        // А вот вопрос в середине — уже не ждущая сессия.
        assertEquals(CodingSessionStatus.IDLE, codingStatusOf(listOf(agent("Спросишь? Вот и всё, закончил."))))
    }

    @Test
    fun unansweredPromptWaits() {
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(listOf(user("задача"), user("ещё одна"))))
    }

    @Test
    fun failedRunWaits() {
        assertEquals(CodingSessionStatus.WAITING, codingStatusOf(listOf(agent("Упало", failed = true))))
    }

    @Test
    fun aggregatePicksMostUrgent() {
        assertEquals(
            CodingSessionStatus.WORKING,
            aggregateCodingStatus(listOf(CodingSessionStatus.IDLE, CodingSessionStatus.WORKING, CodingSessionStatus.WAITING)),
        )
        assertEquals(
            CodingSessionStatus.WAITING,
            aggregateCodingStatus(listOf(CodingSessionStatus.IDLE, CodingSessionStatus.WAITING)),
        )
        assertEquals(CodingSessionStatus.IDLE, aggregateCodingStatus(emptyList()))
    }
}
