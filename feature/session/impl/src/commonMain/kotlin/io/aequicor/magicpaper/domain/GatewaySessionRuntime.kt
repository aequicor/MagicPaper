package io.aequicor.magicpaper.domain

/** HTTP session backend for platforms without native agent processes. */
class GatewaySessionRuntime(
    private val gateway: LlmGateway,
    private val searchEngine: SearchEngine,
    private val docs: DocRepository,
    private val skillLibrary: SkillLibrary = EmptySkillLibrary,
    private val skillSelector: SkillSelector = SkillSelector(),
    private val packageRuntime: SkillInstructionRuntime? = null,
    private val layoutEditor: LayoutEditor = UnavailableLayoutEditor,
    private val settings: suspend () -> AppSettings = { AppSettings() },
    private val readResearchPage: (suspend (String) -> String)? = null,
) : CodingRuntime by io.aequicor.magicpaper.data.coding.NoopCodingRuntime {
    override fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = kotlinx.coroutines.flow.flow {
        val result = answer(session.messages.dropLast(1), prompt, settings(), profile, attachments,
            researchResources = session.resources,
            requestText = session.messages.lastOrNull { it.role == ChatRole.USER }?.text ?: prompt,
            sourcesPrepared = true,
            onSearchResults = { emit(CodingEvent.ToolFinished("web.search", false, sources = it)) })
        emit(CodingEvent.FinalText(result.text, sources = result.sources, attachments = result.attachments))
        emit(CodingEvent.Finished)
    }

    /** Результат ответа: текст и источники (для отображения в чате). */

    suspend fun answer(
        history: List<ChatMessage>,
        userText: String,
        settings: AppSettings,
        profile: LlmProfile?,
        attachments: List<Attachment> = emptyList(),
        operationalProfile: LlmProfile? = profile,
        layoutRequest: LayoutChatRequest? = null,
        researchResources: List<ResearchResource>? = null,
        onSearchResults: suspend (List<SearchHit>) -> Unit = {},
        requestText: String = userText,
        sourcesPrepared: Boolean = false,
    ): SessionAnswer {
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
            sourceProblems += "Это подключение читает текстовые файлы и изображения. Для остальных файлов выберите движок на компьютере или загрузите текстовую версию."
        }
        layoutRequest?.let { request ->
            return LayoutChatAgent(gateway, layoutEditor).answer(request.project, request.conversationId, request.requestId,
                trimmed, history, profile, attachments)
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
                    system = SYSTEM_PROMPT,
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
                return tryModel(profile, SYSTEM_PROMPT, researchContext(), skills, history, trimmed,
                    references, attachments, sourceProblems)
            }
            onSearchResults(hits)
            if (hits.isNotEmpty()) {
                val context = hits.joinToString("\n\n") { hit ->
                    "[${hit.provider}] ${hit.title}\n${hit.snippet}\n${hit.url}"
                }
                return tryModel(profile, SYSTEM_PROMPT, "Результаты поиска:\n$context", skills, history,
                    trimmed, hits, attachments, sourceProblems)
            }
        }

        return tryModel(
            profile = profile,
            system = SYSTEM_PROMPT,
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
        val effectiveSystem = profile.advanced.systemPromptOverride.ifBlank { system }
        val messages = buildList {
            add(LlmMessage(LlmChatRole.SYSTEM, effectiveSystem))
            if (skills.isNotEmpty()) {
                add(LlmMessage(LlmChatRole.SYSTEM, skillsContext(skills)))
            }
            if (context != null) add(LlmMessage(LlmChatRole.SYSTEM, context))
            history.takeLast(profile.advanced.contextMessages).forEach { m ->
                add(
                    LlmMessage(
                        if (m.role == ChatRole.USER) LlmChatRole.USER else LlmChatRole.ASSISTANT,
                        m.text,
                        // Изображения из истории повторно в запрос не вкладываются,
                        // чтобы каждый ответ не раздувал контекст.
                    ),
                )
            }
            add(LlmMessage(LlmChatRole.USER, userText, attachments))
        }
        val answer = gateway.complete(profile, messages)
        return SessionAnswer(answer + if (sourceProblems.isEmpty()) "" else
            "\n\n### Недоступные источники\n\n" + sourceProblems.joinToString("\n\n"), sources)
    }

    private fun looksLikeAppQuestion(text: String): Boolean =
        APP_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    private fun looksLikeSearchRequest(text: String): Boolean =
        SEARCH_KEYWORDS.any { text.contains(it, ignoreCase = true) }

    /** Блок навыков для системного контекста: имя, назначение и инструкция каждого. */
    private fun skillsContext(skills: List<Skill>): String = buildString {
        appendLine("У тебя есть навыки, подходящие к этой задаче. Следуй их инструкциям:")
        skills.forEachIndexed { i, skill ->
            appendLine()
            appendLine("${i + 1}. ${skill.name} — ${skill.description}")
            append(skill.instructions.trim())
            appendLine()
        }
    }

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
        val SYSTEM_PROMPT = """
            Ты — MagicPaper, волшебный ассистент в мире мягкой магии. Отвечай кратко, ясно
            и по делу, лёгким дружелюбным тоном, без пафоса. Если дан контекст из документации
            или результатов поиска — опирайся на него и упоминай источники. Если информации
            недостаточно — честно скажи об этом.
        """.trimIndent()
        val NOT_CONFIGURED_TEXT = """
            Я пока не подключён к магическому источнику. Откройте настройки (⚙) →
            «Магические источники» и подключите провайдера — локальный сервер или API.
        """.trimIndent()
    }
}
