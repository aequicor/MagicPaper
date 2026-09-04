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

/** Черновик ответа агента во время выполнения (живой журнал в UI). */
data class CodingDraft(
    val text: String = "",
    val activity: List<String> = emptyList(),
    val failedMessage: String? = null,
    val active: Boolean = false,
)

/** Собирает события протокола в черновик и итоговое сообщение журнала. */
class CodingRunRecorder {
    private val text = StringBuilder()
    private val activity = mutableListOf<String>()
    private var failed: String? = null

    /** Применяет событие. true, если прогон завершён. */
    fun apply(event: CodingEvent): Boolean {
        when (event) {
            is CodingEvent.SessionStarted -> Unit
            is CodingEvent.TextDelta -> text.append(event.delta)
            is CodingEvent.FinalText -> {
                text.setLength(0)
                text.append(event.text)
            }
            is CodingEvent.ToolStarted -> activity += "⚒ ${event.tool}" +
                if (event.summary.isNotEmpty()) " · ${event.summary}" else ""
            is CodingEvent.ToolFinished -> if (event.isError) activity += "⚠ ${event.tool}: ошибка"
            is CodingEvent.Failed -> failed = event.message
            is CodingEvent.Finished -> return true
        }
        return false
    }

    fun draft(active: Boolean): CodingDraft =
        CodingDraft(text = text.toString(), activity = activity.toList(), failedMessage = failed, active = active)

    fun message(id: String, createdAt: Long): CodingMessage = CodingMessage(
        id = id,
        role = CodingRole.AGENT,
        text = failed?.let { f -> if (text.isBlank()) "Заклинание не сработало: $f." else text.toString() }
            ?: text.toString().ifBlank { "Агент не оставил текста." },
        activity = activity.toList(),
        failed = failed != null,
        createdAt = createdAt,
    )
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
    data class ToolStarted(val tool: String, val summary: String) : CodingEvent

    /** Инструмент отработал. */
    data class ToolFinished(val tool: String, val isError: Boolean) : CodingEvent

    /** Ошибка выполнения. */
    data class Failed(val message: String) : CodingEvent

    /** Прогон завершён. */
    data object Finished : CodingEvent
}

/** Роли в журнале проекта. */
@Serializable
enum class CodingRole { USER, AGENT }

/** Запись журнала проекта: запрос пользователя или ответ агента с журналом инструментов. */
@Serializable
data class CodingMessage(
    val id: String,
    val role: CodingRole,
    val text: String,
    val activity: List<String> = emptyList(),
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
