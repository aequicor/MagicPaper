package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.AppLogEntry
import io.aequicor.magicpaper.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Агент очень долго работает» — это диагноз, а диагноз должен находиться в журнале
 * без ручного сравнения меток времени. Записи содержат только длительности, счётчики,
 * machine-коды и непрозрачные идентификаторы: промпты, ответы модели и хосты
 * провайдера в `INFO` не попадают.
 */
class CodingLatencyDiagnosticsTest {
    private val base = mapOf(
        "projectId" to "project-1",
        "sessionId" to "session-1",
        "model" to "qwen3.5-flash",
        "provider" to "OPENAI_COMPATIBLE",
        "mode" to "CODE",
        "strategy" to "medium",
    )
    private val call = CodingRunLatency.ModelCall(
        requestId = "pi-request-42",
        kind = CodingRunLatency.Kind.MODEL,
        outcome = CodingRunLatency.Outcome.COMPLETED,
        durationMs = 41_208,
        firstResponseMs = 8_512,
        contextTokens = 59_969,
        contextLimit = 128_000,
        inputTokens = 59_969,
        outputTokens = 1_402,
        reasoningTokens = 1_180,
    )

    private fun entries(event: String): List<AppLogEntry> =
        AppLog.history().filter { it.component == "coding.llm" && it.event == event }

    private fun withInfoLevel(block: () -> Unit) {
        val previous = AppLog.level
        AppLog.level = LogLevel.INFO
        try { block() } finally { AppLog.level = previous }
    }

    @Test
    fun slowModelCallExplainsTheWaitWithoutAnyPayload() = withInfoLevel {
        CodingLatencyDiagnostics.log(call, base)
        val fields = entries("call.completed").last().fields

        assertEquals("41208", fields["durationMs"])
        assertEquals("8512", fields["firstResponseMs"])
        assertEquals("model", fields["kind"])
        assertEquals("completed", fields["result"])
        assertEquals("slow_model_response", fields["reason"], "Долгий ответ — значимая ветка, а не случайность")
        assertEquals("59969", fields["contextTokens"], "Рост контекста — причина роста длительности")
        assertEquals("1180", fields["reasoningTokens"])
        assertEquals("medium", fields["strategy"])
        assertTrue(fields.getValue("requestId").startsWith("id-"), "Обращение коррелируется непрозрачным id")
        assertFalse("pi-request-42" in entries("call.completed").last().line())
    }

    @Test
    fun fastCallIsNotMarkedSlowAndCompactionIsNamedAsCompaction() = withInfoLevel {
        CodingLatencyDiagnostics.log(call.copy(durationMs = 3_200), base)
        CodingLatencyDiagnostics.log(call.copy(kind = CodingRunLatency.Kind.COMPACTION, durationMs = 61_000,
            outcome = CodingRunLatency.Outcome.INCOMPLETE, firstResponseMs = null), base)
        val records = entries("call.completed").takeLast(2)

        assertEquals(listOf("model", "compaction"), records.map { it.fields["kind"] })
        assertEquals(null, records.first().fields["reason"], "Обычная пауза не выдаётся за проблему")
        assertEquals("incomplete", records.last().fields["result"])
        assertFalse("firstResponseMs" in records.last().fields, "Ответа не было — числа не выдумываются")
    }

    @Test
    fun runSummarySplitsTheWallClockInsideTheFieldBudget() = withInfoLevel {
        CodingLatencyDiagnostics.log(
            CodingRunLatency.Summary(wallMs = 919_276, startupMs = 2_100, modelMs = 592_400, compactionMs = 41_000,
                toolMs = 283_776, otherMs = 0, modelCalls = 38, toolCalls = 38, failedToolCalls = 1,
                failedModelCalls = 0, truncatedModelCalls = 2, peakContextTokens = 118_400,
                contextLimit = 128_000, slowestModelMs = 49_017, slowestToolMs = 127_308),
            base,
        )
        val entry = entries("run.summary").last()

        assertEquals("919276", entry.fields["wallMs"])
        assertEquals("592400", entry.fields["modelMs"])
        assertEquals("283776", entry.fields["toolMs"])
        assertEquals("68", entry.fields["modelSharePercent"])
        assertEquals("127308", entry.fields["slowestToolMs"])
        assertEquals("2", entry.fields["truncatedModelCalls"])
        assertFalse("failedModelCalls" in entry.fields, "Нулевые счётчики в журнал не пишутся")
        assertTrue(entry.fields.size <= 24, "Запись целиком проходит границу полей: ${entry.fields.size}")
    }
}
