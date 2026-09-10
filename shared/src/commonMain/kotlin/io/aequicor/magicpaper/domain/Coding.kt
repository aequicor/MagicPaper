package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.toolDisplayName
import io.aequicor.magicpaper.domain.tools.ToolCategory
import io.aequicor.magicpaper.domain.tools.ToolPhase

import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class CodingEngine(val title: String) { PI("pi"), CODEX("Codex") }

/** Used only when migrating sessions created before engine selection existed. */
fun legacyCodingEngine(profile: LlmProfile?): CodingEngine =
    if (profile?.provider == ProviderType.OPENAI_SUBSCRIPTION) CodingEngine.CODEX else CodingEngine.PI

/**
 * Проект кодинг-агента. Это не отдельный файл, а рабочая директория:
 * агент выполняет запросы внутри неё и читает её файлы.
 */
@Serializable
data class CodingProject(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    /**
     * Легаси: идентификатор сессии пи-агента единого потока проекта.
     * Переносится в [CodingSession.piSessionId] при миграции журнала,
     * новые запуски его не используют.
     */
    val piSessionId: String = "",
    val modelSelection: ModelSelection? = null,
)

/**
 * Кодинг-сессия внутри проекта: отдельная нить диалога с агентом —
 * свой контекст пи и свой журнал. В одном проекте может быть несколько сессий,
 * они могут работать параллельно.
 */
@Serializable
data class CodingSession(
    val id: String,
    val projectId: String,
    val name: String,
    val createdAt: Long,
    /** Идентификатор сессии пи-агента (контекст сессии продолжается между запусками). */
    val piSessionId: String = "",
    /**
     * Профиль подключения только для этой кодинг-сессии.
     * null = глобальный активный профиль из настроек.
     */
    val llmProfileId: String? = null,
    val modelSelection: ModelSelection? = null,
    val planId: String? = null,
    val parentSessionId: String? = null,
    val stageId: String? = null,
    val planningMode: Boolean = false,
    val researchMode: Boolean = false,
    /** Native history was reset by a mode change; seed the next context from the saved dialogue. */
    val needsHistorySeed: Boolean = false,
    val role: CodingSessionRole = CodingSessionRole.CHAT,
    val archived: Boolean = false,
    val nameManuallySet: Boolean = false,
    val stageNumber: Int? = null,
    val orchestratorNumber: Int? = null,
    val continuationOfNumber: Int? = null,
    val searchProvider: SearchProvider = SearchProvider.AUTO,
    /** Immutable after creation; null is only a legacy migration marker. */
    val engine: CodingEngine? = null,
    /** Durable request, retained until completion; STOP is an explicit user action. */
    val pendingRun: CodingRunCheckpoint? = null,

)

@Serializable
data class CodingRunCheckpoint(
    val messageId: String,
    val prompt: String,
    val attachments: List<Attachment> = emptyList(),
    val intent: ExecutionIntent = ExecutionIntent.RUN,
    val responseId: String = "",
    val stoppedByUser: Boolean = false,
    /** Persisted with the request; copies/recovery retain the same identity. */
    val runId: String = messageId,
    /** Null only in legacy checkpoints; resolved from the persisted session before execution. */
    val interactionMode: CodingInteractionMode? = null,
)

/** A persisted successful reply is authoritative even while checkpoint cleanup is pending. */
fun CodingRunCheckpoint.hasSuccessfulResponse(messages: List<CodingMessage>): Boolean =
    responseId.isNotBlank() && messages.any { it.id == responseId && it.role == CodingRole.AGENT && !it.failed }

/** Older logs have no checkpoint; only an unanswered or failed turn can be resumed. */
fun List<CodingMessage>.interruptedCodingRequest(): CodingMessage? =
    if (lastOrNull()?.let { it.role == CodingRole.USER || it.failed } == true)
        lastOrNull { it.role == CodingRole.USER } else null

/**
 * Состояние активности кодинг-сессии для индикатора-кружка.
 * Сводный приоритет задаёт aggregateCodingStatus: ожидание пользователя выше фоновой работы.
 */
enum class CodingSessionStatus {
    /** Агент выполняет прогон — красный. */
    WORKING,

    /** Есть открытое обращение в опроснике — жёлтый. */
    WAITING,

    /** Доработка готова к подтверждению; ответа на вопрос не требуется. */
    CONFIRMATION,

    /** Выполнение остановлено из-за ошибки; это не вопрос пользователю. */
    BLOCKED,

    /** Исполнитель ждёт передачи работы оркестратором — серый. */
    QUEUED,

    /** Сессия ждёт события или времени, ответ пользователя не требуется. */
    SCHEDULED,

    /** Сессия свободна, ждёт запроса — зелёный. */
    IDLE,
}

/** History is not a request for user attention. The live interaction queue owns WAITING. */
fun codingStatusOf(history: List<CodingMessage>): CodingSessionStatus = CodingSessionStatus.IDLE

/** Сводный статус проекта: самый срочный из статусов его сессий. */
fun aggregateCodingStatus(statuses: Collection<CodingSessionStatus>): CodingSessionStatus =
    statuses.minByOrNull { when (it) {
        CodingSessionStatus.WAITING -> 0
        CodingSessionStatus.BLOCKED -> 1
        CodingSessionStatus.CONFIRMATION -> 2
        CodingSessionStatus.WORKING -> 3
        CodingSessionStatus.QUEUED -> 4
        CodingSessionStatus.SCHEDULED -> 5
        CodingSessionStatus.IDLE -> 6
    } } ?: CodingSessionStatus.IDLE

/** Фазы состояния кодинг-рантайма (движка пи-агента). */
enum class RuntimePhase { UNKNOWN, CHECKING, INSTALLING, READY, ERROR, UNSUPPORTED }

/** Снимок состояния рантайма для UI. */
data class RuntimeStatus(
    val phase: RuntimePhase,
    val detail: String = "",
    val version: String = "",
) {
    val ready: Boolean get() = phase == RuntimePhase.READY
}

/** Черновик ответа агента во время выполнения (живая лента в UI). */
data class CodingDraft(
    val steps: List<CodingStep> = emptyList(),
    val failedMessage: String? = null,
    val active: Boolean = false,
    /** Прогон запущен, но модель ещё не начала отвечать (или ждёт подтверждения). */
    val awaitingModel: Boolean = false,
    /** Живой текст текущего рассуждения модели (ещё не зафиксированный в ленту). */
    val thinking: String = "",
    /** The engine is paused until the user answers an approval request. */
    val awaitingApproval: Boolean = false,
    /** Presentation identity shared with the saved response, including interrupted runs. */
    val timelineId: String? = null,
) {
    /** Summary and detailed deltas may interleave; an answer or tool ends that reasoning phase. */
    val reasoningSummary: String get() {
        for (step in steps.asReversed()) {
            when (step.kind) {
                CodingStepKind.SUMMARY -> return step.title
                CodingStepKind.THINKING -> Unit
                else -> return ""
            }
        }
        return ""
    }
}

/** Собирает события протокола в хронологическую ленту, черновик и итоговое сообщение. */
class CodingRunRecorder {
    private val timelineId = Id.new()
    private var sequence = 0
    private fun nextStepId(): String = "$timelineId:${sequence++}"
    private var textId = nextStepId()
    private var thinkingId = nextStepId()
    /** Накопленный текст текущего (ещё не зафиксированного в ленту) фрагмента ответа. */
    private val text = StringBuilder()
    /** Накопленный текст рассуждения модели (thinking) до его фиксации в ленту. */
    private val thinking = StringBuilder()
    private val steps = mutableListOf<CodingStep>()
    private val sourceSteps = mutableMapOf<Pair<CodingStepKind, String>, String>()
    private val completedSources = mutableSetOf<Pair<CodingStepKind, String>>()
    /** Only fragments of the current model message may be replaced by its final snapshot. */
    private var messageStartIndex = 0
    private var failed: String? = null

    /** Прогон ждёт первого ответа модели (или продолжения после действия). */
    private var awaiting = true

    /** Применяет событие. true, если прогон завершён. */
    fun apply(event: CodingEvent): Boolean {
        when (event) {
            is CodingEvent.SessionStarted, is CodingEvent.UsageObserved, is CodingEvent.ModelRequest, is CodingEvent.ContextUpdated, is CodingEvent.SearchObserved -> Unit
            is CodingEvent.Compaction -> {
                flushMessage()
                val key = "compaction:${event.status.id}"
                val step = CodingStep(CodingStepKind.SYSTEM, event.status.text, id = key, callId = key, systemEvent = event.status,
                    running = event.status.phase == CompactionPhase.STARTED, ok = event.status.phase != CompactionPhase.FAILED)
                val index = steps.indexOfFirst { it.id == key }
                if (index >= 0) steps[index] = step else steps += step
            }
            is CodingEvent.MessageStarted -> {
                flushMessage()
                awaiting = false
            }
            is CodingEvent.TextDelta -> {
                awaiting = false
                if (event.sourceId.isNotBlank()) {
                    updateSource(CodingStepKind.ANSWER, event.sourceId, event.delta, append = true)
                    return false
                }
                // Ответ начался — рассуждение до него остаётся в прошлом.
                flushThinking()
                text.append(event.delta)
            }
            is CodingEvent.ThinkingDelta -> {
                awaiting = false
                if (event.summary) {
                    updateSource(CodingStepKind.SUMMARY, event.sourceId.ifBlank { "summary:$messageStartIndex" }, event.delta, append = !event.replace)
                    return false
                }
                if (event.sourceId.isNotBlank()) {
                    updateSource(CodingStepKind.THINKING, event.sourceId, event.delta, append = !event.replace)
                    return false
                }
                // Мысль пришла раньше текста: зафиксированный ответ остаётся в ленте.
                flushText()
                thinking.append(event.delta)
            }
            is CodingEvent.FinalThinking -> {
                awaiting = false
                if (event.summary) {
                    updateSource(CodingStepKind.SUMMARY, event.sourceId.ifBlank { "summary:$messageStartIndex" }, event.text, append = false)
                    return false
                }
                if (event.sourceId.isNotBlank()) {
                    updateSource(CodingStepKind.THINKING, event.sourceId, event.text, append = false)
                    return false
                }
                // Pi repeats thinking at message_end, after the answer has already streamed.
                // Update that message's earlier thinking without committing a second answer.
                replaceFragment(CodingStepKind.THINKING, thinking, event.text)
            }
            is CodingEvent.FinalText -> {
                awaiting = false
                if (event.sourceId.isNotBlank()) {
                    updateSource(CodingStepKind.ANSWER, event.sourceId, event.text, append = false)
                    return false
                }
                flushThinking()
                // message_end авторитетнее потоковых дельт текущего сообщения ассистента.
                replaceFragment(CodingStepKind.ANSWER, text, event.text)
            }
            is CodingEvent.ToolStarted -> {
                awaiting = false
                // Перед действием фиксируем текст: лента остаётся хронологичной.
                flushMessage()
                val existing = steps.indexOfLast { event.callId.isNotBlank() && it.callId == event.callId && it.tool == event.tool }
                val step = CodingStep(
                    kind = if (event.isExec) CodingStepKind.EXEC else CodingStepKind.TOOL,
                    title = "⚒ " + (event.title ?: (toolDisplayName(event.tool) + if (event.summary.isNotEmpty()) " · ${event.summary}" else "")),
                    tool = event.tool,
                    callId = event.callId,
                    running = true,
                    toolCategory = event.category,
                    toolPhase = ToolPhase.STARTED,
                    id = if (existing >= 0) steps[existing].id else nextStepId(),
                )
                if (existing >= 0) steps[existing] = step else steps += step
            }
            is CodingEvent.ToolProgress -> {
                val index = steps.indexOfLast {
                    (it.running || event.callId.isNotBlank()) && it.tool == event.tool &&
                        (event.callId.isBlank() || it.callId == event.callId)
                }
                if (index >= 0 && steps[index].running) {
                    steps[index] = steps[index].copy(result = event.resultPreview.ifBlank { steps[index].result }, toolPhase = event.phase ?: ToolPhase.PROGRESS)
                }
            }
            is CodingEvent.ToolFinished -> {
                // После действия агент снова ждёт ответа модели.
                awaiting = true
                val index = steps.indexOfLast {
                    (it.running || event.callId.isNotBlank()) && it.tool == event.tool &&
                        (event.callId.isBlank() || it.callId == event.callId)
                }
                if (index >= 0) {
                    steps[index] = steps[index].copy(
                        title = event.title?.let { "⚒ $it" } ?: steps[index].title,
                        running = false,
                        ok = !event.isError,
                        result = event.resultPreview,
                        toolPhase = event.phase ?: if (event.isError) ToolPhase.FAILED else ToolPhase.SUCCEEDED,
                    )
                } else if (event.isError || event.title != null) {
                    steps += CodingStep(
                        kind = CodingStepKind.TOOL,
                        title = event.title?.let { "⚒ $it" } ?: "⚠ ${event.tool}: ошибка",
                        tool = event.tool,
                        callId = event.callId,
                        result = event.resultPreview,
                        ok = !event.isError,
                        toolPhase = event.phase ?: if (event.isError) ToolPhase.FAILED else ToolPhase.SUCCEEDED,
                        id = nextStepId(),
                    )
                }
            }
            is CodingEvent.Failed -> {
                awaiting = false
                flushThinking()
                flushText()
                failed = event.message
                steps += CodingStep(kind = CodingStepKind.ERROR, title = event.message, ok = false, id = nextStepId())
            }
            is CodingEvent.Notice -> if (event.message.isNotBlank()) steps += CodingStep(kind = CodingStepKind.INFO, title = event.message, id = nextStepId())
            is CodingEvent.OutputTruncated -> {
                // Модель отвечала, но не успела: фиксируем фазу и поясняем в ленте.
                awaiting = false
                flushThinking()
                flushText()
                steps += CodingStep(kind = CodingStepKind.INFO, title = event.summary, ok = false, id = nextStepId())
            }
            is CodingEvent.AgentEnd -> {
                awaiting = false
                flushMessage()
            }
            is CodingEvent.Finished -> {
                steps.replaceAllToolsInterrupted()
                for (i in steps.indices) if (steps[i].kind == CodingStepKind.SYSTEM && steps[i].running)
                    steps[i] = steps[i].copy(running = false, title = "Сжатие контекста прервано", systemEvent = steps[i].systemEvent?.copy(phase = CompactionPhase.CANCELLED))
                return true
            }
        }
        return false
    }

    /** Provider item identities survive interleaving and late/repeated final snapshots. */
    private fun updateSource(kind: CodingStepKind, sourceId: String, value: String, append: Boolean) {
        val source = kind to sourceId
        if (append && source in completedSources) return
        if (!append && kind == CodingStepKind.ANSWER) completedSources += source
        if (value.isEmpty()) return
        val id = sourceSteps[source]
        val index = if (id == null) -1 else steps.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = steps[index]
            steps[index] = old.copy(title = if (append) old.title + value else value)
        } else {
            flushThinking()
            flushText()
            val step = CodingStep(kind, value, id = nextStepId())
            sourceSteps[source] = step.id
            steps += step
        }
    }

    private fun flushMessage() {
        flushThinking()
        flushText()
        messageStartIndex = steps.size
    }

    /** Reconcile both live and already flushed fragments; never deduplicate by text equality. */
    private fun replaceFragment(kind: CodingStepKind, buffer: StringBuilder, value: String) {
        if (value.isBlank()) return
        buffer.setLength(0)
        val first = (messageStartIndex until steps.size).firstOrNull { steps[it].kind == kind }
        if (first == null) {
            buffer.append(value)
        } else {
            steps[first] = steps[first].copy(title = value.trim())
            // The live fragment was absorbed into the earlier one; never reuse its identity.
            if (kind == CodingStepKind.ANSWER) textId = nextStepId() else thinkingId = nextStepId()
            for (index in steps.lastIndex downTo first + 1) {
                if (steps[index].kind == kind) steps.removeAt(index)
            }
        }
    }

    /** Переносит накопленный текст в ленту как шаг ответа. */
    private fun flushText() {
        if (text.isBlank()) return
        steps += CodingStep(kind = CodingStepKind.ANSWER, title = text.toString().trim(), id = textId)
        text.setLength(0)
        textId = nextStepId()
    }

    /** Переносит накопленное рассуждение в ленту как шаг THINKING. */
    private fun flushThinking() {
        if (thinking.isBlank()) return
        steps += CodingStep(kind = CodingStepKind.THINKING, title = thinking.toString().trim(), id = thinkingId)
        thinking.setLength(0)
        thinkingId = nextStepId()
    }

    /** Лента прогона: зафиксированные шаги плюс живые рассуждение и текст в конце. */
    fun timeline(): List<CodingStep> {
        val snapshot = steps.toList()
        val live = buildList {
            if (thinking.isNotBlank()) add(CodingStep(CodingStepKind.THINKING, thinking.toString(), id = thinkingId))
            if (text.isNotBlank()) add(CodingStep(CodingStepKind.ANSWER, text.toString(), id = textId))
        }
        return snapshot + live
    }

    fun draft(active: Boolean): CodingDraft {
        val timeline = timeline()
        return CodingDraft(
            steps = timeline,
            failedMessage = failed,
            active = active,
            awaitingModel = active && awaiting,
            thinking = if (thinking.isNotBlank()) timeline.last { it.kind == CodingStepKind.THINKING }.title else
                steps.lastOrNull()?.takeIf { it.kind == CodingStepKind.THINKING && it.id in sourceSteps.values }?.title.orEmpty(),
            timelineId = timelineId,
        )
    }

    private fun MutableList<CodingStep>.replaceAllToolsInterrupted() {
        indices.forEach { index -> val step = this[index]
            if (step.kind in setOf(CodingStepKind.TOOL, CodingStepKind.EXEC) && step.running)
                this[index] = step.copy(running = false, ok = false, toolPhase = ToolPhase.CANCELLED)
        }
    }

    private fun MutableList<CodingStep>.replaceAllSystemsInterrupted() {
        indices.forEach { index ->
            val step = this[index]
            if (step.kind == CodingStepKind.SYSTEM && step.running)
                this[index] = step.copy(title = "Сжатие контекста прервано", running = false, systemEvent = step.systemEvent?.copy(phase = CompactionPhase.CANCELLED))
        }
    }

    fun message(id: String, createdAt: Long): CodingMessage {
        steps.replaceAllSystemsInterrupted()
        steps.replaceAllToolsInterrupted()
        flushThinking()
        flushText()
        val answerText = steps.filter { it.kind == CodingStepKind.ANSWER }
            .joinToString("\n\n") { it.title }
        return CodingMessage(
            id = id,
            role = CodingRole.AGENT,
            text = answerText.ifBlank {
                failed?.let { "Заклинание не сработало: $it." } ?: "Агент не оставил текста."
            },
            activity = steps.filter { it.kind !in listOf(CodingStepKind.ANSWER, CodingStepKind.SUMMARY) }.map { it.displayLine },
            steps = steps.toList(),
            failed = failed != null,
            createdAt = createdAt,
            timelineId = timelineId,
        )
    }
}

/** Строка журнала «что делал агент» для свёрнутого вида. */
val CodingStep.displayLine: String
    get() = when {
        kind == CodingStepKind.ANSWER -> title
        kind == CodingStepKind.THINKING -> "💭 ${title.lineSequence().firstOrNull().orEmpty().take(90)}"
        !ok -> "$title → ошибка"
        result.isNotBlank() -> "$title · ${result.lineSequence().firstOrNull().orEmpty().take(90)}"
        else -> title
    }

/** Общая формулировка обрезки: её показывают Notice, лента и автопродолжение. */
const val TRUNCATED_HEADLINE = "Ответ обрезан лимитом max_tokens"

/** События выполнения кодинг-запроса (протокол пи-агента, режим --mode json). */
sealed interface CodingEvent {
    /** Заголовок сессии: идентификатор сессии пи-агента (для продолжения контекста). */
    data class SessionStarted(val sessionId: String) : CodingEvent

    /**
     * Модель начала отвечать (agent_start / message_start): смена фазы
     * «ждём ответа модели» на «работает» для индикатора сессии.
     */
    data object MessageStarted : CodingEvent

    /** Живой фрагмент текста; sourceId identifies a provider item when streams interleave. */
    data class TextDelta(val delta: String, val sourceId: String = "") : CodingEvent

    /** Живая мысль; replace carries a combined snapshot of indexed provider paragraphs. */
    data class ThinkingDelta(val delta: String, val sourceId: String = "", val replace: Boolean = false,
        val summary: Boolean = false) : CodingEvent

    /** Итоговый текст рассуждения (авторитетный, из блоков thinking сообщения message_end). */
    data class FinalThinking(val text: String, val sourceId: String = "", val summary: Boolean = false) : CodingEvent

    /** Итоговый текст ассистента (авторитетный, из события message_end). */
    data class FinalText(val text: String, val sourceId: String = "") : CodingEvent

    /** Агент начал вызывать инструмент (читает/пишет файл, выполняет команду и т.п.). */
    data class ToolStarted(
        val tool: String,
        val summary: String,
        val callId: String = "",
        /** Инструмент выполняет shell-команду (её вывод интересен пользователю целиком). */
        val isExec: Boolean = false,
        val category: ToolCategory? = null,
        val title: String? = null,
    ) : CodingEvent

    /**
     * Инструмент отработал; resultPreview — начало вывода/ошибки от агента.
     */
    data class ToolFinished(
        val tool: String,
        val isError: Boolean,
        val callId: String = "",
        val resultPreview: String = "",
        val phase: ToolPhase? = null,
        val title: String? = null,
    ) : CodingEvent

    /**
     * Прогресс инструмента: partialResult — накопленный вывод целиком
     * (не дельта, по протоколу пи — просто заменяем показ).
     */
    data class ToolProgress(
        val tool: String,
        val callId: String = "",
        val resultPreview: String = "",
        val phase: ToolPhase? = null,
    ) : CodingEvent

    /**
     * Модель ушла в рассуждение и израсходовала потолок вывода
     * (`stopReason:"length"`, ни текста, ни tool-вызовов): выразить намерение
     * ей было нечем. Раньше это терялось и выглядело как «Агент завершился
     * без ответа» — теперь отдельное событие, чтобы рантайм мог продолжить
     * сессию, а UI — честно объяснить причину.
     */
    data class OutputTruncated(
        val outputTokens: Int? = null,
        val reasoningTokens: Int? = null,
    ) : CodingEvent {
        /** Подпись для ленты: где именно модель упёрлась. */
        val summary: String
            get() = buildString {
                append(TRUNCATED_HEADLINE)
                val spent = outputTokens ?: return@buildString
                append(" — ").append(spent).append(" токенов вывода")
                reasoningTokens?.let {
                    if (it > 0) append(", из них ").append(it).append(" на рассуждение")
                }
            }
    }

    /**
     * Служебное сообщение жизненного цикла движка (уплотнение контекста, автоповтор
     * после сбоя провайдера) — показывается в ленте, чтобы «тихие» фазы были видны.
     */
    data class Notice(val message: String) : CodingEvent

    data class UsageObserved(val tokens: TokenUsage, val sourceId: String, val cumulative: TokenUsage? = null,
        val cost: UsageCost? = null, val accounting: Boolean = true) : CodingEvent
    data class ModelRequest(val id: String) : CodingEvent
    data class ContextUpdated(val used: Long?, val limit: Long?, val approximate: Boolean = false) : CodingEvent
    data class Compaction(val status: CompactionStatus) : CodingEvent
    data class SearchObserved(val id: String, val pages: Long = 0, val requests: Long = 1, val content: Boolean = false) : CodingEvent

    /** Движок сообщил, что прогон завершён (agent_end) — текста могло и не быть. */
    data object AgentEnd : CodingEvent

    /** Ошибка выполнения. */
    data class Failed(val message: String) : CodingEvent

    /** Прогон завершён. */
    data object Finished : CodingEvent
}

/** Роль строки ленты прогона: действие агента, его текст, рассуждение или ошибка. */
@Serializable
enum class CodingStepKind { TOOL, EXEC, ANSWER, THINKING, ERROR, INFO, SUMMARY, SYSTEM }

/** Строка ленты прогона кодинг-агента (chronological timeline). */
@Serializable
data class CodingStep(
    val kind: CodingStepKind,
    val title: String,
    val tool: String = "",
    val callId: String = "",
    /** Начало вывода инструмента (результат read/exec) — для раскрытия в UI. */
    val result: String = "",
    val ok: Boolean = true,
    val running: Boolean = false,
    /** Stable across streaming, final reconciliation and persistence; empty in old logs. */
    val id: String = "",
    val systemEvent: CompactionStatus? = null,
    val toolCategory: ToolCategory? = null,
    val toolPhase: ToolPhase? = null,
    /** A merged live draft can contain steps from several independently saved replies. */
    val sourceTimelineId: String? = null,
)

/** Роли в журнале проекта. */
@Serializable
enum class CodingRole { USER, AGENT }

/** Запись журнала проекта: запрос пользователя или ответ агента с лентой действий. */
@Serializable
data class CodingMessage(
    val id: String,
    val role: CodingRole,
    val text: String,
    val activity: List<String> = emptyList(),
    /** Хронологическая лента прогона (действия, текст, ошибки) — для раскрытия в UI. */
    val steps: List<CodingStep> = emptyList(),
    val failed: Boolean = false,
    val createdAt: Long,
    /** Файлы, прикреплённые к запросу (сами лежат в изолированной папке рантайма). */
    val attachments: List<AttachmentMeta> = emptyList(),
    val planning: PlanningChatBlock? = null,
    val deliveryId: String? = null,
    val pendingDelivery: Boolean = false,
    val route: MessageRoute? = null,
    val inputStatus: OrchestrationInputStatus? = null,
    val handoff: HandoffInfo? = null,
    val scheduledRuleId: String? = null,
    val timelineId: String? = null,
    val systemContext: Boolean = false,
    /** Уведомление приложения для пользователя; не входит в историю для модели. */
    val systemNotice: Boolean = false,

)

/** Хранилище проектов, их сессий и журналов. */
interface CodingProjectRepository {
    suspend fun all(): List<CodingProject>
    suspend fun save(project: CodingProject)
    suspend fun delete(id: String)

    /** Сессии проекта в порядке создания (первая — «основная»). */
    suspend fun sessions(projectId: String): List<CodingSession>
    suspend fun saveSession(session: CodingSession)
    /** Atomic read/modify/write in persistent implementations. */
    suspend fun updateSession(projectId: String, sessionId: String, update: (CodingSession) -> CodingSession): CodingSession {
        val latest = sessions(projectId).firstOrNull { it.id == sessionId } ?: error("Сессия удалена")
        return update(latest).also { saveSession(it) }
    }
    suspend fun deleteSession(projectId: String, sessionId: String)

    suspend fun messages(projectId: String, sessionId: String): List<CodingMessage>
    suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>)
    suspend fun orchestration(sessionId: String): OrchestrationState? = null
    suspend fun saveOrchestration(state: OrchestrationState) { error("Хранилище оркестратора недоступно") }
    suspend fun wipe()
}

/**
 * Бэкенд кодинг-агента. По умолчанию на десктопе — пи-агент.
 * Все зависимости изолированы в папке данных приложения и удаляются вместе с ним.
 */
interface CodingRuntime {
    suspend fun sessionContext(project: CodingProject, session: CodingSession, profile: LlmProfile?): String =
        sessionContextReport(profile, "Проект: ${project.path}\nДвижок: ${session.engine}", "Промпт недоступен для этого движка.", "Сведения о навыках недоступны.")

    val questionnaires: kotlinx.coroutines.flow.StateFlow<List<UserInteractionRequest>> get() = noRuntimeQuestionnaires
    suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) { error("Опросник недоступен") }
    val computerUse: ComputerUse? get() = null
    val projectSkills: ProjectSkills? get() = null
    val approvals: kotlinx.coroutines.flow.StateFlow<List<CodingApproval>> get() = noCodingApprovals
    suspend fun respondApproval(id: String, decision: CodingApprovalDecision) = Unit
    /** Verify engine prerequisites without starting a stage executor. */
    suspend fun preflight(profile: LlmProfile) = Unit
    suspend fun preflight(engine: CodingEngine, profile: LlmProfile) = preflight(profile)
    suspend fun status(engine: CodingEngine): RuntimeStatus = status()
    fun ensureReady(engine: CodingEngine): Flow<RuntimeStatus> = ensureReady()
    suspend fun uninstall(engine: CodingEngine) = uninstall()
    /** Reconcile a prior run before reusing its workspace after application restart. */
    suspend fun reconcile(sessionId: String) = Unit
    /** Поддерживается ли бэкенд на этой платформе (веб и Android — нет). */
    val supported: Boolean

    /** Корень изолированного рантайма (для отображения в UI). */
    val rootPath: String

    /** Быстрая проверка состояния без установки. */
    suspend fun status(): RuntimeStatus

    /** Подготовка рантайма: проверка и автоустановка зависимостей. Последняя эмиссия — итог. */
    fun ensureReady(): Flow<RuntimeStatus>

    /**
     * Выполнение запроса в директории проекта в контексте кодинг-сессии
     * (её piSessionId продолжает историю). Несколько прогонов разных сессий
     * могут идти параллельно. Поток событий протокола.
     * Вложения рантайм раскладывает в изолированную папку и подставляет пути в промпт.
     */
    fun run(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        profile: LlmProfile?,
        attachments: List<Attachment> = emptyList(),
    ): Flow<CodingEvent>

    /** Fresh planning context with enforced read-only tools; never falls back to execution. */
    fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> =
        kotlinx.coroutines.flow.flow {
            emit(CodingEvent.Failed("Чтение проекта при планировании недоступно на этой платформе."))
            emit(CodingEvent.Finished)
        }

    /** Прервать прогон конкретной сессии (остановить её процесс агента). */
    fun abort(sessionId: String)

    /** Прервать все прогоны (снятие зависимостей, закрытие). */
    fun abortAll()

    /** Полное удаление изолированных зависимостей. */
    suspend fun uninstall()
}

/** Платформенный выбор папки проекта (на десктопе — нативный диалог). */
interface ProjectDirPicker {
    suspend fun pickDirectory(): String?
}

/** Empty service ticks are transport housekeeping, including in older saved timelines. */
val CodingStep.isVisibleActivity: Boolean
    get() = kind != CodingStepKind.INFO || title.isNotBlank()

/** Assign a useful title once, preserving explicit and worker names. */
fun CodingSession.namedFromPrompt(prompt: String): CodingSession {
    val defaultName = name == "Новая сессия" || name == "Основная" || name.startsWith("Сессия ") || name.startsWith("План:")
    val title = prompt.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(60)
    return if (!nameManuallySet && parentSessionId == null && defaultName && title.isNotBlank()) copy(name = title) else this
}

/** Includes archived workers and nested descendants. */
fun List<CodingSession>.sessionTreeIds(rootId: String): Set<String> {
    val ids = mutableSetOf(rootId)
    do {
        val added = filter { it.parentSessionId in ids }.map { it.id }.filter { it !in ids }
        ids.addAll(added)
    } while (added.isNotEmpty())
    return ids
}
