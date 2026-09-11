package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Модель даёт только содержательные слова: список сессий показывает по ним название задачи.
 * Ответ — данные, а не инструкции: его текст не меняет правил работы ассистента.
 */
internal const val SESSION_SHORT_TITLE_PROMPT = """
Озаглавь запрос одним названием задачи: 2–3 слова на языке запроса.
Верни только название — без кавычек, Markdown, точки в конце и слов «запрос», «цель»,
«пользователь просит». Не добавляй деталей, которых нет в запросе.
"""

/** Placeholder names never describe a task, so they can neither be summarised nor replaced by a summary. */
internal fun CodingSession.isDefaultName(): Boolean =
    name == "Новая сессия" || name == "Основная" || name.startsWith("Сессия ") || name.startsWith("План:")

/**
 * Начатая сессия плана подписывается в списке двумя-тремя словами из её запроса.
 * Название принадлежит сессии и переживает перезапуск, поэтому модель вызывается один раз;
 * вызов не участвует в прогоне — его сбой оставляет прежнее [CodingSession.name].
 *
 * Owned by the application rather than composition, like [RequestPinService].
 */
class SessionTitleService(
    private val projects: CodingProjectRepository,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val gateway: LlmGateway?,
    private val scope: CoroutineScope,
    private val usageScope: (CodingSession) -> UsageScope = UsageScope::coding,
) {
    private val attempted = mutableSetOf<String>()
    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Ready titles of sessions whose request is known; the persisted field is the source of truth. */
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    /**
     * Starts naming every planning root that has begun. [request] is the user's formulation,
     * so a caller without loaded history may pass the name assigned from the first prompt.
     */
    fun sync(session: CodingSession, request: String?) {
        if (gateway == null || !session.needsShortTitle()) return
        val text = request?.takeIf { it.isNotBlank() && !isDefaultNameOf(it) } ?: return
        if (!attempted.add(session.id)) return
        scope.launch(UsageOwner(usageScope(session), updatesContext = false)) {
            try {
                val roster = profiles.load()
                val choice = session.modelSelection
                val profile = choice?.let { ProfileResolver.selection(it, roster) }
                    ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
                    ?: return@launch
                val answer = gateway.complete(profile, listOf(
                    LlmMessage(LlmChatRole.SYSTEM, SESSION_SHORT_TITLE_PROMPT),
                    LlmMessage(LlmChatRole.USER, text.take(4000)),
                ))
                val short = compactSessionTitle(answer) ?: return@launch
                val saved = projects.updateSession(session.projectId, session.id) { latest ->
                    // Ручное название и готовое суммаризированное не перезаписываются.
                    if (latest.needsShortTitle()) latest.copy(shortTitle = short) else latest
                }
                _titles.update { it + (saved.id to saved.shortTitle) }
            } catch (e: CancellationException) {
                attempted.remove(session.id) // отмена допускает следующую попытку
                throw e
            } catch (_: Exception) {
                attempted.remove(session.id) // модель недоступна — попробуем при следующем запросе
            }
        }
    }

    fun forget(sessionId: String) {
        attempted.remove(sessionId)
        _titles.update { it - sessionId }
    }

    private fun isDefaultNameOf(text: String) =
        text == "Новая сессия" || text == "Основная" || text.startsWith("Сессия ") ||
            text.startsWith("План:")
}
