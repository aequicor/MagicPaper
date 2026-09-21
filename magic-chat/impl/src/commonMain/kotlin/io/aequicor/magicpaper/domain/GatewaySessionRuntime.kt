package io.aequicor.magicpaper.domain

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.util.Id

/** Provider-backed chat. Cancellation is scoped to the request, with no native runtime. */
class GatewaySessionRuntime(
    private val toolLoop: ProviderToolLoop,
    private val chatTools: ChatToolSessions,
    private val searchEngine: SearchEngine,
    private val docs: DocRepository,
    private val skillLibrary: SkillLibrary = EmptySkillLibrary,
    private val skillSelector: SkillSelector = SkillSelector(),
    private val packageRuntime: SkillInstructionRuntime? = null,
    private val settings: suspend () -> AppSettings = { AppSettings() },
    private val readResearchPage: (suspend (String) -> String)? = null,
) : ChatBackend {
    private val requests = MutableStateFlow<Map<String, Job>>(emptyMap())

    override val questionnaires get() = chatTools.questions.requests
    override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) { chatTools.questions.respond(id, answers) }

    override fun abort(sessionId: String) { requests.value[sessionId]?.cancel() }

    override suspend fun inspectSavedResponse(request: ChatMachine.RunRef): ChatSavedResponse =
        when (val outcome = toolLoop.inspect("chat:${request.sessionId}:${request.runId}")) {
            is ProviderToolRecovery.Completed -> {
                val ref = outcome.output.ref
                check(ref.runId == "chat:${request.sessionId}:${request.runId}") { "Сохранённый ответ принадлежит другому запросу" }
                ChatSavedResponse.Completed(request,
                    ChatMachine.OutputProof(request.runId, ref.attempt, ref.identity, ref.digest), outcome.output.text)
            }
            ProviderToolRecovery.Unknown -> ChatSavedResponse.Unknown
            ProviderToolRecovery.Interrupted -> ChatSavedResponse.Interrupted
            ProviderToolRecovery.Missing -> ChatSavedResponse.Missing
        }

    override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = channelFlow {
        coroutineScope {
            val request = currentCoroutineContext().job
            requests.update { active ->
                check(session.id !in active) { "A chat request is already active for this conversation" }
                active + (session.id to request)
            }
            val requestText = session.messages.lastOrNull { it.role == ChatRole.USER }?.text ?: prompt
            val requestId = session.pendingRun?.runId ?: session.messages.lastOrNull { it.role == ChatRole.USER }?.id ?: Id.new()
            var detach: (() -> Unit)? = null
            try {
                val effectiveSettings = settings()
                val tools = chatTools.create(session, requestId, effectiveSettings, !researchRequest(requestText).sourceTask)
                detach = tools.events.observe { send(it.codingEvent()) }
                val result = withContext(tools) { answer(session.messages.dropLast(1), prompt, effectiveSettings, profile, attachments,
                    researchResources = session.resources,
                    requestText = requestText,
                    sourcesPrepared = true,
                    onSearchResults = { send(CodingEvent.ToolFinished("web.search", false, sources = it)) }) }
                send(CodingEvent.FinalText(result.text, sources = result.sources, attachments = result.attachments))
                send(CodingEvent.Finished)
            } finally {
                detach?.invoke()
                requests.update { active -> if (active[session.id] === request) active - session.id else active }
            }
        }
    }

    /** Результат ответа: текст и источники (для отображения в чате). */

    suspend fun answer(
        history: List<ChatMessage>,
        userText: String,
        settings: AppSettings,
        profile: LlmProfile?,
        attachments: List<Attachment> = emptyList(),
        operationalProfile: LlmProfile? = profile,
        researchResources: List<ResearchResource>? = null,
        onSearchResults: suspend (List<SearchHit>) -> Unit = {},
        requestText: String = userText,
        sourcesPrepared: Boolean = false,
    ): SessionAnswer {
        // Direct callers receive the same capabilities. A running chat installs its stable request scope above.
        if (currentCoroutineContext()[ToolSession] == null) {
            val id = Id.new()
            val session = ChatSession(id, "", Id.now(), Id.now(), messages = history, resources = researchResources.orEmpty())
            val tools = chatTools.create(session, id, settings, !researchRequest(requestText).sourceTask)
            return withContext(tools) { answer(history, userText, settings, profile, attachments, operationalProfile,
                researchResources, onSearchResults, requestText, sourcesPrepared) }
        }
        val trimmed = userText.trim()
        val sourceProblems = mutableListOf<String>()
        val request = researchRequest(requestText)
        val sourceAccess = ResearchSourceAccess(readResearchPage)
        val checked = researchResources?.let {
            sourceAccess.check(if (sourcesPrepared) it else request.sources(it, attachments)).toMutableList()
        }
        fun recordProblems(checks: List<ResearchSourceCheck>) {
            checks.filter { it.problem != null }.forEach {
                sourceProblems += "Не удалось прочитать источник «${it.resource.title}»: ${it.problem}. Можно повторить запрос или убрать источник."
            }
        }
        checked?.let(::recordProblems)
        fun researchContext(): String? = checked?.let {
            researchPrompt("", it.readableSources(), request) + "\n" + it.unavailableSourceContext(allowSearch = !request.sourceTask)
        }
        fun verifiedReferences(): List<SearchHit> = checked.orEmpty().readableSources().mapNotNull {
            it.url.takeIf(String::isNotBlank)?.let { url -> SearchHit(it.title, url) }
        }.distinctBy { it.url }
        if (checked != null && attachments.any { it.kind == AttachmentKind.FILE }) {
            sourceProblems += "Это подключение читает текстовые файлы и изображения. Для остальных файлов загрузите текстовую версию."
        }
        if (researchResources == null) packageRuntime?.answer(trimmed, history, profile, attachments)?.let { return SessionAnswer(it) }
        // Самонастройка: подбираем навыки под запрос до маршрутизации —
        // они усиливают любую ветку (доки, поиск, свободный диалог).
        val skills = skillSelector.select(trimmed, skillLibrary.relevantFor(trimmed))

        if (!request.sourceTask && looksLikeAppQuestion(trimmed) && (researchResources.isNullOrEmpty() || trimmed.contains("magicpaper", ignoreCase = true))) {
            val matches = docs.search(trimmed)
            if (matches.isNotEmpty()) {
                val context = matches.joinToString("\n\n") { "${it.article.title}\n${it.article.body}" }
                return tryModel(
                    profile = operationalProfile,
                    system = CHAT_SYSTEM_PROMPT,
                    context = listOfNotNull(researchContext(), "Документация приложения:\n$context").joinToString("\n\n"),
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = verifiedReferences(),
                    attachments = attachments,
                    sourceProblems = sourceProblems,
                )
            }
        }

        // ChatService owns initial discovery. Do not perform a second search after it prepared the run.
        if (!sourcesPrepared && !request.sourceTask && looksLikeSearchRequest(requestText)) {
            val hits = searchEngine.search(trimmed, settings, limit = 5)
            if (checked != null) {
                val newResources = hits.mapNotNull { hit -> researchUrl(hit.url)?.let { ResearchResource(it, hit.title, it) } }
                    .filterNot { resource -> checked.any { it.resource.key == resource.key } }
                val extra = sourceAccess.check(newResources)
                checked += extra
                recordProblems(extra)
                val references = verifiedReferences()
                onSearchResults(references.filter { reference -> hits.any { researchUrl(it.url) == reference.url } })
                return tryModel(profile, CHAT_SYSTEM_PROMPT, researchContext(), skills, history, trimmed,
                    references, attachments, sourceProblems)
            }
            onSearchResults(hits)
            if (hits.isNotEmpty()) {
                val context = hits.joinToString("\n\n") { hit ->
                    "[${hit.provider}] ${hit.title}\n${hit.snippet}\n${hit.url}"
                }
                return tryModel(profile, CHAT_SYSTEM_PROMPT, "Результаты поиска:\n$context", skills, history,
                    trimmed, hits, attachments, sourceProblems)
            }
        }

        return tryModel(
            profile = profile,
            system = CHAT_SYSTEM_PROMPT,
            context = researchContext(),
            skills = skills,
            history = history,
            userText = trimmed,
            sources = verifiedReferences(),
            attachments = attachments,
            sourceProblems = sourceProblems,
        )
    }

    private suspend fun tryModel(
        profile: LlmProfile?,
        system: String,
        context: String?,
        skills: List<Skill>,
        history: List<ChatMessage>,
        userText: String,
        sources: List<SearchHit>,
        attachments: List<Attachment>,
        sourceProblems: List<String> = emptyList(),
    ): SessionAnswer {
        if (profile == null || !profile.configured) {
            return SessionAnswer(NOT_CONFIGURED_TEXT, sources)
        }
        val messages = chatPromptMessages(profile, history, userText, attachments, skills, context, system)
        val tools = checkNotNull(currentCoroutineContext()[ToolSession]) { "Chat tool scope is required" }
        val answer = toolLoop.run("chat:${tools.context.ownerSessionId}:${tools.context.requestId}", profile, messages, tools)
        return SessionAnswer(answer + if (sourceProblems.isEmpty()) "" else
            "\n\n### Недоступные источники\n\n" + sourceProblems.joinToString("\n\n"), sources)
    }

    private fun looksLikeAppQuestion(text: String): Boolean =
        APP_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private fun looksLikeSearchRequest(text: String): Boolean =
        SEARCH_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private companion object {
        val APP_KEYWORDS = listOf(
            "magicpaper", "настройки", "настройка", "плагин", "плагины", "профиль",
            "экспорт", "импорт", "поисковый движок", "как работает", "как удалить",
            "безопасность", "перенос", "горячие клавиши", "документация",
            "навык", "навыки", "скилл", "скиллы", "лавка", "самообучение",
            "кодинг", "код-агент", "пи-агент", "проект", "проекты", "движок",
            "провайдер", "провайдеры", "источник", "магический источник",
            "усилие", "переключить модель", "сменить модель", "модель",
            "планирование", "план", "мэилстоун", "милестоун", "веха", "досье", "график",
        )
        val SEARCH_KEYWORDS = listOf(
            "найди", "поищи", "поиск", "кто такой", "кто такая", "что такое",
            "что случилось", "новости", "сколько стоит", "где найти", "расскажи о",
            "какая погода", "рецепт", "как доехать",
        )
        val NOT_CONFIGURED_TEXT = """
            Я пока не подключён к магическому источнику. Откройте настройки (⚙) →
            «Магические источники» и подключите провайдера — локальный сервер или API.
        """.trimIndent()
    }
}
