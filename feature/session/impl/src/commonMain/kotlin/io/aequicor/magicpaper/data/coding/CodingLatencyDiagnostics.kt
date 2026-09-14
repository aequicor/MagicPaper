package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.logging.AppLog

/**
 * Владелец диагностики длительности кодинг-прогонов: к кому идти с вопросом
 * «почему агент так долго».
 *
 * `coding.tool` уже пишет каждый вызов инструмента, а `UsageLedger` — токены. Между
 * ними не было ни одной записи о времени ответа модели, поэтому «долгий» прогон
 * невозможно было объяснить: оставалось лишь сравнивать метки времени в журнале.
 * Здесь — один слой, который делит стену прогона на ответы модели, уплотнение
 * контекста, инструменты, запуск движка и остальное.
 *
 * Поля только machine-кодами и числами: идентификатор обращения непрозрачный,
 * ни промптов, ни текстов ответов, ни URL провайдера. Компонент общий для обоих
 * движков: событие пишет тот, кто ведёт поток событий прогона.
 */
internal object CodingLatencyDiagnostics {
    private const val COMPONENT = "coding.llm"

    /** Одно обращение к модели (или к суммаризатору уплотнения). */
    fun log(call: CodingRunLatency.ModelCall, base: Map<String, String>) {
        AppLog.info(COMPONENT, "call.completed", buildMap {
            putAll(base)
            if (call.requestId.isNotBlank()) put("requestId", call.requestId)
            put("kind", if (call.kind == CodingRunLatency.Kind.COMPACTION) "compaction" else "model")
            put("result", call.outcome.name.lowercase())
            put("durationMs", call.durationMs.toString())
            call.firstResponseMs?.let { put("firstResponseMs", it.toString()) }
            call.contextTokens?.let { put("contextTokens", it.toString()) }
            call.contextLimit?.let { put("contextLimit", it.toString()) }
            call.inputTokens?.let { put("inputTokens", it.toString()) }
            call.outputTokens?.let { put("outputTokens", it.toString()) }
            call.reasoningTokens?.let { put("reasoningTokens", it.toString()) }
            // Долгий ответ — значимая ветка: без неё причина паузы остаётся догадкой.
            if (call.durationMs >= SLOW_CALL_MS) put("reason", "slow_model_response")
        })
    }

    /** Итог прогона: куда ушло время от запроса до его завершения. */
    fun log(summary: CodingRunLatency.Summary, base: Map<String, String>) {
        AppLog.info(COMPONENT, "run.summary", buildMap {
            putAll(base)
            put("result", "completed")
            put("wallMs", summary.wallMs.toString())
            put("modelMs", summary.modelMs.toString())
            put("compactionMs", summary.compactionMs.toString())
            put("toolMs", summary.toolMs.toString())
            put("startupMs", summary.startupMs.toString())
            put("otherMs", summary.otherMs.toString())
            put("modelCalls", summary.modelCalls.toString())
            put("toolCalls", summary.toolCalls.toString())
            put("modelSharePercent", summary.modelSharePercent.toString())
            put("slowestModelMs", summary.slowestModelMs.toString())
            put("slowestToolMs", summary.slowestToolMs.toString())
            summary.peakContextTokens?.let { put("peakContextTokens", it.toString()) }
            summary.contextLimit?.let { put("contextLimit", it.toString()) }
            if (summary.failedModelCalls > 0) put("failedModelCalls", summary.failedModelCalls.toString())
            if (summary.truncatedModelCalls > 0) put("truncatedModelCalls", summary.truncatedModelCalls.toString())
            if (summary.failedToolCalls > 0) put("failedToolCalls", summary.failedToolCalls.toString())
        })
    }

    /** Порог «долгого» ответа модели: ниже него пауза — норма, выше — факт для журнала. */
    private const val SLOW_CALL_MS = 20_000L
}
