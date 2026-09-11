package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Модель даёт только содержательные слова: список сессий показывает по ним название задачи.
 * Ответ — данные, а не инструкции: его текст не меняет правил работы ассистента.
 */
internal const val SESSION_SHORT_TITLE_PROMPT = """
Озаглавь запрос одним названием задачи: 2–3 слова на языке запроса.
Верни только название — без кавычек, Markdown, точки в конце и слов «запрос», «цель»,
«пользователь просит». Не добавляй деталей, которых нет в запросе.
"""

/** A placeholder never describes a task, so it is neither named nor summarised from itself. */
internal fun String.isDefaultSessionName(): Boolean =
    this == "Новая сессия" || this == "Основная" || startsWith("Сессия ") || startsWith("План:")

/**
 * Начатая сессия плана подписывается в списке двумя-тремя словами из своего запроса.
 * Название принадлежит сессии и переживает перезапуск, поэтому модель вызывается один раз;
 * вызов не участвует в прогоне, и его сбой оставляет прежнее [CodingSession.name].
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

    /** Ready titles, published so the session list updates without reloading every history. */
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    /** Names the session if it is an untitled planning root whose request is known. */
    fun sync(session: CodingSession) {
        if (gateway == null || !session.needsShortTitle()) return
        val request = session.name.takeUnless { it.isDefaultSessionName() } ?: return
        if (!attempted.add(session.id)) return
        scope.launch(UsageOwner(usageScope(session), updatesContext = false)) {
            try {
                val roster = profiles.load()
                val profile = session.modelSelection?.let { ProfileResolver.selection(it, roster) }
                    ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
                    ?: return@launch
                val answer = gateway.complete(profile, listOf(
                    LlmMessage(LlmChatRole.SYSTEM, SESSION_SHORT_TITLE_PROMPT),
                    LlmMessage(LlmChatRole.USER, request.take(4000)),
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
                attempted.remove(session.id) // модель недоступна — попробуем при следующем обновлении
            }
        }
    }

    fun forget(sessionId: String) {
        attempted.remove(sessionId)
        _titles.update { it - sessionId }
    }
}
