package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CompactionPhase
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Хронометраж одного кодинг-прогона: сколько ждали модель, сколько — инструменты
 * и сколько осталось на запуск движка, разбор потока и само приложение.
 *
 * Почему отдельный владелец, а не поля [io.aequicor.magicpaper.domain.UsageLedger].
 * Токены отвечают на вопрос «сколько потрачено», а не «почему пользователь столько
 * ждал»: прогон из 38 быстрых инструментов и 38 ответов модели по 40 с выглядит в
 * учёте так же, как короткий. Границы фаз берутся из потока [CodingEvent] по времени
 * их прибытия в приложение, то есть измеряется ровно та задержка, которую видел
 * пользователь, — вместе с транспортировкой вывода из процесса движка.
 *
 * Один запуск движка — один экземпляр; автопродолжения обрезанного ответа продолжают
 * тот же прогон, поэтому итог считается по всем попыткам сразу.
 *
 * @param now часы прогона в миллисекундах; в тестах подменяются, чтобы границы фаз
 *   оставались проверяемыми без реального ожидания.
 */
class CodingRunLatency(
    private val origin: TimeMark = TimeSource.Monotonic.markNow(),
    private val now: () -> Long = { origin.elapsedNow().inWholeMilliseconds },
) {
    /** Что измеряет запись: обычный ответ модели или сводку уплотнения контекста. */
    enum class Kind { MODEL, COMPACTION }

    /** Исход закрытого обращения к модели; `INCOMPLETE` — граница без данных usage. */
    enum class Outcome { COMPLETED, TRUNCATED, FAILED, INCOMPLETE }

    /** Одно обращение к модели: от запроса до конца его сообщения в потоке. */
    data class ModelCall(
        val requestId: String,
        val kind: Kind,
        val outcome: Outcome,
        val durationMs: Long,
        /** От запроса до первого сигнала ответа; null — ответа дождаться не удалось. */
        val firstResponseMs: Long?,
        val contextTokens: Long?,
        val contextLimit: Long?,
        val inputTokens: Long?,
        val outputTokens: Long?,
        val reasoningTokens: Long?,
    )

    /** Итог прогона. Фазы могут перекрываться (параллельные инструменты), поэтому
     * их сумма не обязана равняться `wallMs`. */
    data class Summary(
        val wallMs: Long,
        /** Запуск движка и загрузка сессии до первого запроса к модели. */
        val startupMs: Long,
        val modelMs: Long,
        val compactionMs: Long,
        val toolMs: Long,
        /** Всё прочее: транспорт событий, разбор потока, паузы самого приложения. */
        val otherMs: Long,
        val modelCalls: Int,
        val toolCalls: Int,
        val failedToolCalls: Int,
        val failedModelCalls: Int,
        val truncatedModelCalls: Int,
        /** Последний измеренный объём контекста: он ушёл в модель следующим запросом. */
        val contextTokens: Long?,
        val peakContextTokens: Long?,
        val contextLimit: Long?,
        val slowestModelMs: Long,
        val slowestToolMs: Long,
    ) {
        /** Доля времени ответа модели в общем времени прогона — ради неё и ведут хронометраж. */
        val modelSharePercent: Int
            get() = if (wallMs <= 0) 0
            else ((modelMs + compactionMs) * 100 / wallMs).toInt().coerceIn(0, 100)

        /** Насколько заполнен контекст сейчас; null — предел или объём неизвестны. */
        val contextPercent: Int?
            get() = contextTokens?.let { used ->
                contextLimit?.takeIf { it > 0 }?.let { (used * 100 / it).toInt().coerceAtLeast(0) }
            }

        /**
         * Что сказать пользователю кроме разбивки времени: только то, на что он может
         * повлиять. Длина контекста — главный источник медленных ответов в длинной
         * сессии (задержка растёт от запроса к запросу), а сбросить контекст может
         * только сам пользователь новой сессией.
         */
        val advice: String?
            get() {
                val percent = contextPercent
                return when {
                    compactionMs > 0 -> "Часть истории сессии уже заменена сводкой: агент"
                        .plus(" перечитывает файлы заново, и каждый ход дорожает.")
                        .plus(" Новая сессия начнётся с пустого контекста.")
                    percent != null && percent >= CONTEXT_SATURATED_PERCENT ->
                        "Контекст заполнен на $percent%: каждый следующий ответ модели будет"
                            .plus(" дольше. Чтобы сбросить его, начните новую сессию.")
                    else -> null
                }
            }

        /**
         * Короткая расшифровка длительности для пользователя: где прошло время
         * долгого прогона и что с этим можно сделать. Сырые токены и технические
         * причины сюда не попадают — подробности остаются в диагностике.
         * Фаза, которой не наблюдали, в текст не попадает: пустой счётчик после
         * неудачного запуска движка не должен выглядеть как «модель не думала».
         */
        fun describe(): String {
            val restMs = (otherMs - startupMs).coerceAtLeast(0)
            val parts = buildList {
                if (modelCalls > 0) add("ответы модели ${duration(modelMs)} (запросов $modelCalls)")
                if (compactionMs > 0) add("уплотнение контекста ${duration(compactionMs)}")
                if (toolCalls > 0) add("инструменты ${duration(toolMs)} (вызовов $toolCalls)")
                if (startupMs >= MIN_REPORT_MS) add("запуск движка ${duration(startupMs)}")
                if (restMs >= MIN_REPORT_MS) add("остальное ${duration(restMs)}")
            }
            return buildString {
                append("Прогон занял ").append(duration(wallMs))
                if (parts.isEmpty()) append('.') else append(": ").append(parts.joinToString(", ")).append('.')
                advice?.let { append(' ').append(it) }
            }
        }
    }

    private class OpenCall(
        val requestId: String,
        val kind: Kind,
        val startedAt: Long,
        val contextTokens: Long?,
        val contextLimit: Long?,
    ) {
        var firstResponseAt: Long? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var reasoningTokens: Long? = null
        var sawUsage = false
        var failed = false
        var truncated = false

        fun record(at: Long) = ModelCall(
            requestId = requestId,
            kind = kind,
            outcome = when {
                failed -> Outcome.FAILED
                truncated -> Outcome.TRUNCATED
                sawUsage -> Outcome.COMPLETED
                else -> Outcome.INCOMPLETE
            },
            durationMs = (at - startedAt).coerceAtLeast(0),
            firstResponseMs = firstResponseAt?.let { (it - startedAt).coerceAtLeast(0) },
            contextTokens = contextTokens,
            contextLimit = contextLimit,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
        )
    }

    private val startedAt = now()
    private val openCalls = LinkedHashMap<String, OpenCall>()
    /**
     * Открытые вызовы инструментов: по callId, а без него — очередью по имени
     * (закрытие в порядке открытия: другой пары для безымянного вызова нет).
     */
    private val openTools = LinkedHashMap<String, ArrayDeque<Long>>()
    private var compactionActive = false
    private var firstRequestAt: Long? = null
    private var modelMs = 0L
    private var compactionMs = 0L
    private var toolMs = 0L
    private var modelCalls = 0
    private var toolCalls = 0
    private var failedToolCalls = 0
    private var failedModelCalls = 0
    private var truncatedModelCalls = 0
    private var peakContextTokens: Long? = null
    /** Последний семпл контекста: именно он ушёл в промпт, пик для этого не годится. */
    private var lastContextTokens: Long? = null
    private var contextLimit: Long? = null
    private var slowestModelMs = 0L
    private var slowestToolMs = 0L

    /**
     * Принимает событие прогона и возвращает обращения модели, закрытые им же.
     * Вызовы инструментов здесь только накапливаются: каждый из них уже логирует
     * владелец квитанции (`coding.tool`), повторять ту же запись на другом слое
     * нельзя.
     */
    fun apply(event: CodingEvent): List<ModelCall> {
        val instant = now()
        return when (event) {
            is CodingEvent.ModelRequest -> {
                if (firstRequestAt == null) firstRequestAt = instant
                // Запрос без закрытого предыдущего — значит граница сообщения не пришла
                // (обрыв ответа или смена режима): предыдущее обращение считается неполным.
                val closed = closeAll(instant)
                openCalls[event.id.ifBlank { "request-${openCalls.size}" }] = OpenCall(
                    requestId = event.id,
                    kind = if (compactionActive) Kind.COMPACTION else Kind.MODEL,
                    startedAt = instant,
                    // Контекст семплируется перед запросом — это размер отправленного промпта.
                    contextTokens = lastContextTokens,
                    contextLimit = contextLimit,
                )
                closed
            }

            is CodingEvent.ContextUpdated -> {
                event.limit?.let { contextLimit = it }
                event.used?.let { used ->
                    lastContextTokens = used
                    peakContextTokens = maxOf(peakContextTokens ?: 0L, used)
                }
                emptyList()
            }

            is CodingEvent.Compaction -> when (event.status.phase) {
                CompactionPhase.STARTED -> {
                    compactionActive = true
                    emptyList()
                }
                else -> {
                    val closed = closeAll(instant)
                    compactionActive = false
                    closed
                }
            }

            is CodingEvent.UsageObserved -> {
                activeCall()?.takeIf { call ->
                    event.sourceId.startsWith(
                        if (call.kind == Kind.COMPACTION) "compaction:" else "message:"
                    )
                }?.let { call ->
                    event.tokens.input?.let { call.inputTokens = it }
                    event.tokens.output?.let { call.outputTokens = it }
                    event.tokens.reasoning?.let { call.reasoningTokens = it }
                    call.sawUsage = true
                }
                emptyList()
            }

            // Первый сигнал, что модель начала отвечать: граница «ждём TTFB» → «работает».
            CodingEvent.MessageStarted, is CodingEvent.TextDelta, is CodingEvent.ThinkingDelta -> {
                activeCall()?.let { call ->
                    if (call.firstResponseAt == null) call.firstResponseAt = instant
                }
                emptyList()
            }

            is CodingEvent.ToolStarted -> {
                openTools.getOrPut(event.callId.ifBlank { event.tool }) { ArrayDeque() }.addLast(instant)
                closeAll(instant)
            }

            is CodingEvent.ToolFinished -> {
                val key = event.callId.ifBlank { event.tool }
                val queue = openTools[key]
                if (queue != null && queue.isNotEmpty()) {
                    val elapsed = (instant - queue.removeFirst()).coerceAtLeast(0)
                    toolMs += elapsed
                    toolCalls++
                    slowestToolMs = maxOf(slowestToolMs, elapsed)
                    if (event.isError) failedToolCalls++
                    if (queue.isEmpty()) openTools.remove(key)
                }
                emptyList()
            }

            is CodingEvent.OutputTruncated -> {
                activeCall()?.truncated = true
                closeAll(instant)
            }

            is CodingEvent.Failed -> {
                activeCall()?.failed = true
                closeAll(instant)
            }

            is CodingEvent.FinalText -> closeAll(instant)
            CodingEvent.AgentEnd -> closeAll(instant)

            is CodingEvent.SessionStarted, is CodingEvent.ToolProgress, is CodingEvent.FinalThinking,
            is CodingEvent.SearchObserved, is CodingEvent.Notice, CodingEvent.Finished, is CodingEvent.PlanUsageObserved,
            -> emptyList()
        }
    }

    /**
     * Закрывает незавершённые обращения (конец прогона, обрыв потока, отмена) и
     * возвращает итог. После `finish()` экземпляр больше не принимает события.
     */
    fun finish(): Pair<List<ModelCall>, Summary> {
        val instant = now()
        val closed = closeAll(instant)
        openTools.clear()
        return closed to summaryAt(instant)
    }

    /** Итог на текущий момент, не закрывая открытые обращения. */
    fun summary(): Summary = summaryAt(now())

    private fun summaryAt(instant: Long): Summary {
        val startup = (firstRequestAt?.minus(startedAt) ?: 0L).coerceAtLeast(0L)
        return Summary(
            wallMs = (instant - startedAt).coerceAtLeast(0),
            startupMs = startup,
            modelMs = modelMs,
            compactionMs = compactionMs,
            toolMs = toolMs,
            otherMs = (instant - startedAt - startup - modelMs - compactionMs - toolMs).coerceAtLeast(0),
            modelCalls = modelCalls,
            toolCalls = toolCalls,
            failedToolCalls = failedToolCalls,
            failedModelCalls = failedModelCalls,
            truncatedModelCalls = truncatedModelCalls,
            contextTokens = lastContextTokens,
            peakContextTokens = peakContextTokens,
            contextLimit = contextLimit,
            slowestModelMs = slowestModelMs,
            slowestToolMs = slowestToolMs,
        )
    }

    private fun activeCall(): OpenCall? = openCalls.values.lastOrNull()

    private fun closeAll(at: Long): List<ModelCall> {
        if (openCalls.isEmpty()) return emptyList()
        val closed = openCalls.values.map { call -> call.record(at) }
        openCalls.clear()
        closed.forEach { record ->
            when (record.kind) {
                Kind.MODEL -> {
                    modelMs += record.durationMs
                    slowestModelMs = maxOf(slowestModelMs, record.durationMs)
                    if (record.outcome == Outcome.FAILED) failedModelCalls++
                    if (record.outcome == Outcome.TRUNCATED) truncatedModelCalls++
                }
                Kind.COMPACTION -> compactionMs += record.durationMs
            }
            modelCalls++
        }
        return closed
    }

    private companion object {
        /** Короткие паузы пользователю не интересны: в сводку попадают только значимые. */
        const val MIN_REPORT_MS = 5_000L

        /** С этого заполнения контекста замедление ответов — не случайность, а следствие. */
        const val CONTEXT_SATURATED_PERCENT = 85
    }
}

/** Длительность человеческим языком: миллисекунды, секунды, минуты, часы. */
private fun duration(ms: Long): String {
    if (ms < 1_000) return "$ms мс"
    val totalSeconds = ms / 1_000
    if (totalSeconds < 60) return "$totalSeconds с"
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append(hours).append(" ч")
        if (minutes > 0) append(if (hours > 0) " " else "").append(minutes).append(" мин")
        if (seconds > 0 && hours == 0L) append(if (hours > 0 || minutes > 0) " " else "").append(seconds).append(" с")
    }
}
