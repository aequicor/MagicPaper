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
    /** Идентификатор сессии пи-агента (контекст проекта продолжается между запусками). */
    val piSessionId: String = "",
)

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
)

/** Собирает события протокола в хронологическую ленту, черновик и итоговое сообщение. */
class CodingRunRecorder {
    /** Накопленный текст текущего (ещё не зафиксированного в ленту) фрагмента ответа. */
    private val text = StringBuilder()
    private val steps = mutableListOf<CodingStep>()
    private var failed: String? = null

    /** Применяет событие. true, если прогон завершён. */
    fun apply(event: CodingEvent): Boolean {
        when (event) {
            is CodingEvent.SessionStarted -> Unit
            is CodingEvent.TextDelta -> text.append(event.delta)
            is CodingEvent.FinalText -> {
                // message_end авторитетнее потоковых дельт текущего сообщения ассистента.
                if (event.text.isNotBlank()) {
                    text.setLength(0)
                    text.append(event.text)
                }
            }
            is CodingEvent.ToolStarted -> {
                // Перед действием фиксируем текст: лента остаётся хронологичной.
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
                flushText()
                failed = event.message
                steps += CodingStep(kind = CodingStepKind.ERROR, title = event.message, ok = false)
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

    /** Лента прогона: зафиксированные шаги плюс живой текст в конце. */
    fun timeline(): List<CodingStep> {
        val snapshot = steps.toList()
        return if (text.isNotBlank()) snapshot + CodingStep(CodingStepKind.ANSWER, text.toString()) else snapshot
    }

    fun draft(active: Boolean): CodingDraft =
        CodingDraft(steps = timeline(), failedMessage = failed, active = active)

    fun message(id: String, createdAt: Long): CodingMessage {
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
        !ok -> "$title → ошибка"
        result.isNotBlank() -> "$title · ${result.lineSequence().firstOrNull().orEmpty().take(90)}"
        else -> title
    }

/** События выполнения кодинг-запроса (протокол пи-агента, режим --mode json). */
sealed interface CodingEvent {
    /** Заголовок сессии: идентификатор сессии пи-агента (для продолжения контекста). */
    data class SessionStarted(val sessionId: String) : CodingEvent

    /** Живой фрагмент текста ответа. */
    data class TextDelta(val delta: String) : CodingEvent

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

    /** Ошибка выполнения. */
    data class Failed(val message: String) : CodingEvent

    /** Прогон завершён. */
    data object Finished : CodingEvent
}

/** Роль строки ленты прогона: действие агента, его текст или ошибка. */
@Serializable
enum class CodingStepKind { TOOL, EXEC, ANSWER, ERROR }

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
)

/** Хранилище проектов и их журналов. */
interface CodingProjectRepository {
    suspend fun all(): List<CodingProject>
    suspend fun save(project: CodingProject)
    suspend fun delete(id: String)
    suspend fun messages(projectId: String): List<CodingMessage>
    suspend fun saveMessages(projectId: String, messages: List<CodingMessage>)
    suspend fun wipe()
}

/**
 * Бэкенд кодинг-агента. По умолчанию на десктопе — пи-агент.
 * Все зависимости изолированы в папке данных приложения и удаляются вместе с ним.
 */
interface CodingRuntime {
    /** Поддерживается ли бэкенд на этой платформе (веб и Android — нет). */
    val supported: Boolean

    /** Корень изолированного рантайма (для отображения в UI). */
    val rootPath: String

    /** Быстрая проверка состояния без установки. */
    suspend fun status(): RuntimeStatus

    /** Подготовка рантайма: проверка и автоустановка зависимостей. Последняя эмиссия — итог. */
    fun ensureReady(): Flow<RuntimeStatus>

    /** Выполнение запроса в директории проекта. Поток событий протокола. */
    fun run(project: CodingProject, prompt: String, profile: LlmProfile?): Flow<CodingEvent>

    /** Прервать текущий прогон (остановить процесс агента). */
    fun abort()

    /** Полное удаление изолированных зависимостей. */
    suspend fun uninstall()
}

/** Платформенный выбор папки проекта (на десктопе — нативный диалог). */
interface ProjectDirPicker {
    suspend fun pickDirectory(): String?
}
