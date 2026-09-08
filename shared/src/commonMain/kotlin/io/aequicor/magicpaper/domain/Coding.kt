package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

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
    val searchProvider: SearchProvider = SearchProvider.AUTO,

)

/**
 * Состояние активности кодинг-сессии для индикатора-кружка.
 * Порядок объявления — приоритет срочности (используется для сводинки по проекту).
 */
enum class CodingSessionStatus {
    /** Агент выполняет прогон — красный. */
    WORKING,

    /** Агент задал вопрос, не подтвердил действие или запрос без ответа — жёлтый. */
    WAITING,

    /** Выполнение остановлено из-за ошибки; это не вопрос пользователю. */
    BLOCKED,

    /** Исполнитель ждёт передачи работы планировщиком — серый. */
    QUEUED,

    /** Сессия свободна, ждёт запроса — зелёный. */
    IDLE,
}

/**
 * Статус сессии по её журналу (когда прогон не активен):
 * вопрос агента в конце ленты, незавершённый запрос или ошибка — WAITING,
 * иначе IDLE.
 */
fun codingStatusOf(messages: List<CodingMessage>): CodingSessionStatus {
    if (messages.pendingPlanningQuestion() != null) return CodingSessionStatus.WAITING
    val last = messages.lastOrNull() ?: return CodingSessionStatus.IDLE
    // Запрос отправлен, ответа нет (сбой или потерянный прогон) — ждём решения.
    if (last.role == CodingRole.USER) return CodingSessionStatus.WAITING
    if (last.failed) return CodingSessionStatus.WAITING
    // Вопрос в конце последней строки с поправкой на markdown-обёртки и кавычки.
    val tail = messages.last().text.lines().lastOrNull { it.isNotBlank() }.orEmpty()
        .trim().trimEnd('"', '*', '`', '_', '\u201D', '\u201C', '\u00BB', '\u00AB')
    return if (tail.endsWith("?") || tail.endsWith("\uFF1F")) {
        CodingSessionStatus.WAITING
    } else {
        CodingSessionStatus.IDLE
    }
}

/** Сводный статус проекта: самый срочный из статусов его сессий. */
fun aggregateCodingStatus(statuses: Collection<CodingSessionStatus>): CodingSessionStatus =
    statuses.minByOrNull { it.ordinal } ?: CodingSessionStatus.IDLE

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
)

/** Собирает события протокола в хронологическую ленту, черновик и итоговое сообщение. */
class CodingRunRecorder {
    /** Накопленный текст текущего (ещё не зафиксированного в ленту) фрагмента ответа. */
    private val text = StringBuilder()
    /** Накопленный текст рассуждения модели (thinking) до его фиксации в ленту. */
    private val thinking = StringBuilder()
    private val steps = mutableListOf<CodingStep>()
    private var failed: String? = null

    /** Прогон ждёт первого ответа модели (или продолжения после действия). */
    private var awaiting = true

    /** Применяет событие. true, если прогон завершён. */
    fun apply(event: CodingEvent): Boolean {
        when (event) {
            is CodingEvent.SessionStarted -> Unit
            is CodingEvent.MessageStarted -> awaiting = false
            is CodingEvent.TextDelta -> {
                awaiting = false
                // Ответ начался — рассуждение до него остаётся в прошлом.
                flushThinking()
                text.append(event.delta)
            }
            is CodingEvent.ThinkingDelta -> {
                awaiting = false
                // Мысль пришла раньше текста: зафиксированный ответ остаётся в ленте.
                flushText()
                thinking.append(event.delta)
            }
            is CodingEvent.FinalThinking -> {
                awaiting = false
                flushText()
                // message_end авторитетнее потоковых дельт рассуждения.
                if (event.text.isNotBlank()) {
                    thinking.setLength(0)
                    thinking.append(event.text)
                }
            }
            is CodingEvent.FinalText -> {
                awaiting = false
                flushThinking()
                // message_end авторитетнее потоковых дельт текущего сообщения ассистента.
                if (event.text.isNotBlank()) {
                    text.setLength(0)
                    text.append(event.text)
                }
            }
            is CodingEvent.ToolStarted -> {
                awaiting = false
                // Перед действием фиксируем текст: лента остаётся хронологичной.
                flushThinking()
                flushText()
                steps += CodingStep(
                    kind = if (event.isExec) CodingStepKind.EXEC else CodingStepKind.TOOL,
                    title = "⚒ ${event.tool}" + if (event.summary.isNotEmpty()) " · ${event.summary}" else "",
                    tool = event.tool,
                    callId = event.callId,
                    running = true,
                )
            }
            is CodingEvent.ToolProgress -> {
                val index = steps.indexOfLast {
                    it.running && it.tool == event.tool &&
                        (event.callId.isBlank() || it.callId == event.callId)
                }
                if (index >= 0 && event.resultPreview.isNotBlank()) {
                    steps[index] = steps[index].copy(result = event.resultPreview)
                }
            }
            is CodingEvent.ToolFinished -> {
                // После действия агент снова ждёт ответа модели.
                awaiting = true
                val index = steps.indexOfLast {
                    it.running && it.tool == event.tool &&
                        (event.callId.isBlank() || it.callId == event.callId)
                }
                if (index >= 0) {
                    steps[index] = steps[index].copy(
                        running = false,
                        ok = !event.isError,
                        result = event.resultPreview,
                    )
                } else if (event.isError) {
                    steps += CodingStep(
                        kind = CodingStepKind.TOOL,
                        title = "⚠ ${event.tool}: ошибка",
                        tool = event.tool,
                        result = event.resultPreview,
                        ok = false,
                    )
                }
            }
            is CodingEvent.Failed -> {
                awaiting = false
                flushThinking()
                flushText()
                failed = event.message
                steps += CodingStep(kind = CodingStepKind.ERROR, title = event.message, ok = false)
            }
            is CodingEvent.Notice -> if (event.message.isNotBlank()) steps += CodingStep(kind = CodingStepKind.INFO, title = event.message)
            is CodingEvent.OutputTruncated -> {
                // Модель отвечала, но не успела: фиксируем фазу и поясняем в ленте.
                awaiting = false
                flushThinking()
                flushText()
                steps += CodingStep(kind = CodingStepKind.INFO, title = event.summary, ok = false)
            }
            is CodingEvent.AgentEnd -> {
                awaiting = false
                flushThinking()
                flushText()
            }
            is CodingEvent.Finished -> return true
        }
        return false
    }

    /** Переносит накопленный текст в ленту как шаг ответа. */
    private fun flushText() {
        if (text.isBlank()) return
        steps += CodingStep(kind = CodingStepKind.ANSWER, title = text.toString().trim())
        text.setLength(0)
    }

    /** Переносит накопленное рассуждение в ленту как шаг THINKING. */
    private fun flushThinking() {
        if (thinking.isBlank()) return
        steps += CodingStep(kind = CodingStepKind.THINKING, title = thinking.toString().trim())
        thinking.setLength(0)
    }

    /** Лента прогона: зафиксированные шаги плюс живые рассуждение и текст в конце. */
    fun timeline(): List<CodingStep> {
        val snapshot = steps.toList()
        val live = buildList {
            if (thinking.isNotBlank()) add(CodingStep(CodingStepKind.THINKING, thinking.toString()))
            if (text.isNotBlank()) add(CodingStep(CodingStepKind.ANSWER, text.toString()))
        }
        return snapshot + live
    }

    fun draft(active: Boolean): CodingDraft =
        CodingDraft(
            steps = timeline(),
            failedMessage = failed,
            active = active,
            awaitingModel = active && awaiting,
            thinking = thinking.toString(),
        )

    fun message(id: String, createdAt: Long): CodingMessage {
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
            activity = steps.filter { it.kind != CodingStepKind.ANSWER }.map { it.displayLine },
            steps = steps.toList(),
            failed = failed != null,
            createdAt = createdAt,
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

    /** Живой фрагмент текста ответа. */
    data class TextDelta(val delta: String) : CodingEvent

    /** Живой фрагмент рассуждения модели (thinking-дельта протокола пи). */
    data class ThinkingDelta(val delta: String) : CodingEvent

    /** Итоговый текст рассуждения (авторитетный, из блоков thinking сообщения message_end). */
    data class FinalThinking(val text: String) : CodingEvent

    /** Итоговый текст ассистента (авторитетный, из события message_end). */
    data class FinalText(val text: String) : CodingEvent

    /** Агент начал вызывать инструмент (читает/пишет файл, выполняет команду и т.п.). */
    data class ToolStarted(
        val tool: String,
        val summary: String,
        val callId: String = "",
        /** Инструмент выполняет shell-команду (её вывод интересен пользователю целиком). */
        val isExec: Boolean = false,
    ) : CodingEvent

    /**
     * Инструмент отработал; resultPreview — начало вывода/ошибки от агента.
     */
    data class ToolFinished(
        val tool: String,
        val isError: Boolean,
        val callId: String = "",
        val resultPreview: String = "",
    ) : CodingEvent

    /**
     * Прогресс инструмента: partialResult — накопленный вывод целиком
     * (не дельта, по протоколу пи — просто заменяем показ).
     */
    data class ToolProgress(
        val tool: String,
        val callId: String = "",
        val resultPreview: String = "",
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

    /** Движок сообщил, что прогон завершён (agent_end) — текста могло и не быть. */
    data object AgentEnd : CodingEvent

    /** Ошибка выполнения. */
    data class Failed(val message: String) : CodingEvent

    /** Прогон завершён. */
    data object Finished : CodingEvent
}

/** Роль строки ленты прогона: действие агента, его текст, рассуждение или ошибка. */
@Serializable
enum class CodingStepKind { TOOL, EXEC, ANSWER, THINKING, ERROR, INFO }

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

)

/** Хранилище проектов, их сессий и журналов. */
interface CodingProjectRepository {
    suspend fun all(): List<CodingProject>
    suspend fun save(project: CodingProject)
    suspend fun delete(id: String)

    /** Сессии проекта в порядке создания (первая — «основная»). */
    suspend fun sessions(projectId: String): List<CodingSession>
    suspend fun saveSession(session: CodingSession)
    suspend fun deleteSession(projectId: String, sessionId: String)

    suspend fun messages(projectId: String, sessionId: String): List<CodingMessage>
    suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>)
    suspend fun wipe()
}

/**
 * Бэкенд кодинг-агента. По умолчанию на десктопе — пи-агент.
 * Все зависимости изолированы в папке данных приложения и удаляются вместе с ним.
 */
interface CodingRuntime {
    val approvals: kotlinx.coroutines.flow.StateFlow<List<CodingApproval>> get() = noCodingApprovals
    suspend fun respondApproval(id: String, decision: CodingApprovalDecision) = Unit
    /** Verify engine prerequisites without starting a stage executor. */
    suspend fun preflight(profile: LlmProfile) = Unit
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
