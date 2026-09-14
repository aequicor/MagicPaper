package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CompactionPhase
import io.aequicor.magicpaper.domain.CompactionStatus
import io.aequicor.magicpaper.domain.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разделение времени прогона на фазы. Модельных задержек в журнале не было:
 * `coding.tool` пишет вызовы инструментов, `UsageLedger` — токены, а «почему так
 * долго» оставалось ручным сравнением меток времени.
 */
class CodingRunLatencyTest {
    private var clock = 0L
    private val latency = CodingRunLatency(now = { clock })

    private fun at(ms: Long, event: CodingEvent): List<CodingRunLatency.ModelCall> {
        clock = ms
        return latency.apply(event)
    }

    private fun usage(input: Long = 30_000, output: Long = 120, reasoning: Long = 90, id: String = "m1") =
        CodingEvent.UsageObserved(TokenUsage(input = input, output = output, reasoning = reasoning,
            total = input + output), "message:$id")

    @Test
    fun modelCallIsMeasuredFromRequestToTheBoundaryThatEndsItsMessage() {
        at(1_000, CodingEvent.ContextUpdated(59_969, 128_000))
        assertEquals(emptyList<CodingRunLatency.ModelCall>(), at(1_000, CodingEvent.ModelRequest("r1")))
        clock = 6_000
        latency.apply(CodingEvent.MessageStarted)
        clock = 6_100
        latency.apply(usage())
        clock = 6_200
        val call = latency.apply(CodingEvent.ToolStarted("read", "src/A.kt", callId = "c1")).single()

        assertEquals("r1", call.requestId)
        assertEquals(CodingRunLatency.Kind.MODEL, call.kind)
        assertEquals(CodingRunLatency.Outcome.COMPLETED, call.outcome)
        assertEquals(5_200, call.durationMs, "Отсчёт — от запроса до границы сообщения, а не до конца прогона")
        assertEquals(5_000, call.firstResponseMs)
        assertEquals(59_969, call.contextTokens, "Размер отправленного контекста семплируется перед запросом")
        assertEquals(128_000, call.contextLimit)
        assertEquals(120, call.outputTokens)
        assertEquals(90, call.reasoningTokens)
    }

    @Test
    fun phasesSplitTheWallClockSoASlowRunBecomesAttributable() {
        at(0, CodingEvent.SessionStarted("pi-1"))
        at(500, CodingEvent.ContextUpdated(1_000, 128_000))
        at(500, CodingEvent.ModelRequest("r1"))
        at(10_400, usage())
        at(10_500, CodingEvent.ToolStarted("powershell", "gradlew build", callId = "t1"))
        at(131_500, CodingEvent.ToolFinished("powershell", isError = false, callId = "t1"))
        at(131_500, CodingEvent.ModelRequest("r2"))
        at(151_500, usage(id = "m2"))
        at(151_500, CodingEvent.FinalText("Готово"))
        clock = 152_000
        latency.apply(CodingEvent.AgentEnd)
        val (late, summary) = latency.finish()

        assertTrue(late.isEmpty(), "Открытых обращений к этому моменту нет")
        assertEquals(152_000, summary.wallMs)
        assertEquals(500, summary.startupMs, "Запуск движка до первого запроса — отдельная фаза")
        assertEquals(30_000, summary.modelMs)
        assertEquals(121_000, summary.toolMs)
        assertEquals(500, summary.otherMs, "Остальное — транспорт событий и паузы самого приложения")
        assertEquals(2, summary.modelCalls)
        assertEquals(1, summary.toolCalls)
        assertEquals(20_000, summary.slowestModelMs)
        assertEquals(121_000, summary.slowestToolMs)
        assertEquals(1_000, summary.peakContextTokens)
        assertEquals(19, summary.modelSharePercent)
        assertEquals("Прогон занял 2 мин 32 с: ответы модели 30 с (запросов 2), " +
            "инструменты 2 мин 1 с (вызовов 1).", summary.describe())
    }

    @Test
    fun compactionAndTruncationAndFailureGetTheirOwnOutcomes() {
        at(0, CodingEvent.ModelRequest("turn-1"))
        at(500, CodingEvent.ToolStarted("read", "a", callId = "c1"))
        at(500, CodingEvent.ToolFinished("read", isError = false, callId = "c1"))
        at(1_000, CodingEvent.Compaction(CompactionStatus("s1", CompactionPhase.STARTED)))
        at(1_000, CodingEvent.ModelRequest("compact-1"))
        at(9_000, CodingEvent.UsageObserved(TokenUsage(input = 900, output = 60, total = 960), "compaction:s1"))
        val compacted = at(9_000, CodingEvent.Compaction(CompactionStatus("s1", CompactionPhase.COMPLETED)))

        assertEquals(CodingRunLatency.Kind.COMPACTION, compacted.last().kind)
        assertEquals(8_000, compacted.last().durationMs)
        assertEquals(8_000, latency.summary().compactionMs)
        assertEquals(500, latency.summary().modelMs, "Сводка уплотнения не выдаётся за ответ модели")

        at(10_000, CodingEvent.ModelRequest("turn-2"))
        val truncated = at(12_000, CodingEvent.OutputTruncated(outputTokens = 16_384, reasoningTokens = 16_384))
        at(13_000, CodingEvent.ModelRequest("turn-3"))
        val failure = at(14_000, CodingEvent.Failed("Запрос остановлен"))
        val (late, summary) = latency.finish()

        assertEquals(listOf(CodingRunLatency.Outcome.TRUNCATED), truncated.map { it.outcome })
        assertEquals(listOf(CodingRunLatency.Outcome.FAILED), failure.map { it.outcome })
        assertTrue(late.isEmpty(), "Закрытые обращения больше не возвращаются повторно")
        assertEquals(1, summary.truncatedModelCalls)
        assertEquals(1, summary.failedModelCalls)
        assertEquals(3_500, summary.modelMs)
    }

    @Test
    fun supersededCallIsClosedOnceAndUnknownOutcomeIsNotInvented() {
        at(0, CodingEvent.ModelRequest("r1"))
        val superseded = at(1_000, CodingEvent.ModelRequest("r2"))
        assertEquals(1, superseded.size, "Прошедший запрос закрывается при появлении нового")
        assertEquals(CodingRunLatency.Outcome.INCOMPLETE, superseded.single().outcome,
            "Без usage и текста исход неизвестен — выдумывать «успех» нельзя")

        at(1_000, CodingEvent.ToolStarted("read", "a", callId = ""))
        at(1_500, CodingEvent.ToolStarted("read", "b", callId = ""))
        at(2_500, CodingEvent.ToolFinished("read", isError = true, callId = ""))
        val (late, summary) = latency.finish()

        assertTrue(late.isEmpty(), "Открытых обращений не осталось")
        assertEquals(1_500, summary.toolMs, "Вызовы без callId закрываются в порядке открытия")
        assertEquals(1, summary.toolCalls)
        assertEquals(1, summary.failedToolCalls)
    }

    @Test
    fun onlySignificantPhasesReachTheUserFacingBreakdown() {
        at(0, CodingEvent.ModelRequest("r1"))
        at(60_000, CodingEvent.FinalText("Готово"))
        clock = 60_000
        val summary = latency.summary()

        assertEquals("Прогон занял 1 мин: ответы модели 1 мин (запросов 1).", summary.describe())
    }

    @Test
    fun compactionIsNamedSeparatelyAndPointsAtTheSessionItself() {
        at(0, CodingEvent.ModelRequest("r1"))
        at(1_000, CodingEvent.Compaction(CompactionStatus("s1", CompactionPhase.STARTED)))
        at(1_000, CodingEvent.ModelRequest("compact-1"))
        at(21_000, CodingEvent.Compaction(CompactionStatus("s1", CompactionPhase.COMPLETED)))
        clock = 21_000

        assertEquals("Прогон занял 21 с: ответы модели 1 с (запросов 2), уплотнение контекста 20 с."
            .plus(" Часть истории сессии уже заменена сводкой: агент перечитывает файлы заново,"
                .plus(" и каждый ход дорожает. Новая сессия начнётся с пустого контекста.")),
            latency.summary().describe())
    }

    @Test
    fun unobservedPhasesAreNotReportedAsZero() {
        at(0, CodingEvent.ToolStarted("powershell", "gradlew build", callId = "t1"))
        at(90_000, CodingEvent.ToolFinished("powershell", isError = false, callId = "t1"))
        clock = 90_000

        assertEquals("Прогон занял 1 мин 30 с: инструменты 1 мин 30 с (вызовов 1).",
            latency.summary().describe())
    }

    /**
     * Замедление из-за длины контекста — единственная причина долгого прогона,
     * на которую пользователь влияет сам, поэтому она называется вместе с числами.
     */
    @Test
    fun saturatedContextIsReportedByItsCurrentSizeAndSuggestsANewSession() {
        at(0, CodingEvent.ContextUpdated(118_400, 128_000))
        at(0, CodingEvent.ModelRequest("r1"))
        at(45_000, CodingEvent.FinalText("Готово"))
        clock = 45_000
        val summary = latency.summary()

        assertEquals(92, summary.contextPercent)
        assertEquals(118_400, summary.contextTokens)
        assertTrue(summary.describe().endsWith("Контекст заполнен на 92%: каждый следующий ответ"
            .plus(" модели будет дольше. Чтобы сбросить его, начните новую сессию.")), summary.describe())
    }

    @Test
    fun roomyContextGetsNoAdviceAndPeakIsNotMistakenForCurrentSize() {
        at(0, CodingEvent.ContextUpdated(20_000, 128_000))
        at(0, CodingEvent.ModelRequest("r1"))
        at(5_000, CodingEvent.FinalText("Готово"))
        // После уплотнения контекста стало меньше: совет про переполнение уже не про него.
        at(6_000, CodingEvent.ContextUpdated(9_000, 128_000))
        clock = 6_000
        val summary = latency.summary()

        assertEquals(null, summary.advice)
        assertEquals(9_000, summary.contextTokens, "Совет строится на последнем семпле, а не на пике")
        assertEquals(20_000, summary.peakContextTokens)
        assertEquals("Прогон занял 6 с: ответы модели 5 с (запросов 1).", summary.describe())
    }
}
