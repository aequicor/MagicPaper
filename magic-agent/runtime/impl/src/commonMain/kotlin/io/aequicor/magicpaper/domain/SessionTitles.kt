package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.logging.AppLog
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
Назови задачу, которую поручили в этом запросе, а не перескажи его слова: от 2 до 10 слов
на языке запроса, существительное или действие (что сделать с чем). Если вместе с просьбой прислали
журнал, JSON, лог или код, игнорируй вставленные данные и назови саму просьбу.
Верни только название — без кавычек, Markdown, точки в конце, эмодзи и слов «запрос», «цель»,
«пользователь просит». Не добавляй деталей, которых нет в запросе.
"""

/**
 * Начатая сессия подписывается в списке словами задачи (до десяти). Название принадлежит сессии
 * и переживает перезапуск, поэтому модель вызывается один раз; вызов не участвует в прогоне,
 * и его сбой оставляет прежнее [CodingSession.name].
 *
 * Модель получает полный первый запрос, а не [CodingSession.name]: название могло быть обрезано
 * до локальной свёртки, и суммаризировать его — значит суммаризировать обрезок.
 *
 * Owned by the application rather than composition, like [RequestPinService].
 */
class SessionTitleService(
    private val projects: CodingProjectOwner,
    private val profiles: LlmProfileRepository,
    private val settings: SettingsRepository,
    private val gateway: LlmGateway?,
    private val scope: CoroutineScope,
    private val usageScope: (CodingSession) -> UsageScope = UsageScope::coding,
) {
    private val attempted = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val _titles = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Ready titles, published so the session list updates without reloading every history. */
    val titles: StateFlow<Map<String, String>> = _titles.asStateFlow()

    /** Names the session if it is an untitled root whose request is known. */
    fun sync(session: CodingSession) {
        val gateway = this.gateway ?: return
        if (!session.needsShortTitle()) return
        if ((attempts[session.id] ?: 0) >= MAX_TITLE_ATTEMPTS) return
        if (!attempted.add(session.id)) return
        scope.launch(UsageOwner(usageScope(session), updatesContext = false)) {
            val fields = mapOf("sessionId" to session.id, "projectId" to session.projectId)
            val request = requestOf(session)
            val profile = if (request == null) null else chosenProfile(session)
            if (request == null || profile == null) {
                // Запроса или модели ещё нет — название откладывается, а не теряется.
                attempted.remove(session.id)
                AppLog.debug("session", "title.skipped", fields + ("reason" to
                    if (request == null) "request-not-persisted" else "model-unavailable"))
                return@launch
            }
            val attempt = (attempts[session.id] ?: 0) + 1
            attempts[session.id] = attempt
            try {
                val requestId = io.aequicor.magicpaper.util.Id.new()
                projects.dispatch(session.projectId, CodingMachine.Fact.TitleRequested(CodingMachine.ref(session), requestId))
                val answer = gateway.complete(profile, listOf(
                    LlmMessage(LlmChatRole.SYSTEM, SESSION_SHORT_TITLE_PROMPT),
                    LlmMessage(LlmChatRole.USER, request.take(4000)),
                ))
                val short = compactSessionTitle(answer)
                if (short == null) {
                    // Ответ непригоден как название: список сохраняет прежнее имя, попытка учтена.
                    AppLog.error("session", "title.unusable", fields + ("attempt" to attempt.toString()))
                    return@launch
                }
                val saved = projects.acceptSession(session, CodingMachine.Fact.TitleObserved(CodingMachine.ref(session), requestId, short))
                _titles.update { it + (saved.id to saved.shortTitle) }
                attempts[session.id] = MAX_TITLE_ATTEMPTS // название готово
                AppLog.info("session", "title.assigned", fields + ("attempt" to attempt.toString()))
            } catch (e: CancellationException) {
                attempted.remove(session.id) // отмена допускает следующую попытку
                throw e
            } catch (e: Exception) {
                attempted.remove(session.id) // модель недоступна — попробуем при следующем обновлении
                AppLog.error("session", "title.failed", e, fields + ("attempt" to attempt.toString()))
            }
        }
    }

    /** Сначала выбор самой сессии, затем операционный профиль — тот же порядок, что и у прогона. */
    private suspend fun chosenProfile(session: CodingSession): LlmProfile? {
        val roster = profiles.load()
        return session.modelSelection?.let { ProfileResolver.selection(it, roster) }
            ?: ProfileResolver.resolve(null as ChatSession?, settings.load(), roster)
    }

    /** Полный текст первой задачи: живой прогон, затем журнал, затем уже сохранённое название. */
    private suspend fun requestOf(session: CodingSession): String? {
        session.pendingRun?.prompt?.takeIf { it.isNotBlank() }?.let { return it }
        session.queuedPrompts.firstOrNull { it.prompt.isNotBlank() }?.let { return it.prompt }
        projects.messages(session.projectId, session.id)
            .firstOrNull { it.role == CodingRole.USER && it.text.isNotBlank() }?.let { return it.text }
        return session.name.takeUnless { it.isDefaultSessionName() }
    }

    /** Called after the owning service has joined all title producers. */
    fun clear() { attempted.clear(); attempts.clear(); _titles.value = emptyMap() }

    fun forget(sessionId: String) {
        attempted.remove(sessionId)
        attempts.remove(sessionId)
        _titles.update { it - sessionId }
    }

    private companion object {
        /** Стойкий сбой модели не должен вызывать её при каждой перезагрузке списка. */
        const val MAX_TITLE_ATTEMPTS = 3
    }
}
