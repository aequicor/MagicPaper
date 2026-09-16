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
    ): SessionAnswer {
        val trimmed = userText.trim()
        val sourceProblems = mutableListOf<String>()
        val researchContext = researchResources?.let { resources ->
            buildString {
                appendLine(researchPrompt("", resources))
                for (resource in resources.filter { it.url.isNotEmpty() }) {
                    if (readResearchPage == null) {
                        sourceProblems += "Не удалось прочитать источник «${resource.title}»: чтение сайтов недоступно в этом подключении."
                        appendLine("Содержимое ${resource.url} недоступно: чтение сайтов не поддерживается этим подключением.")
                    } else {
                        try { appendLine("Источник ${resource.url}:\n${readResearchPage.invoke(resource.url)}") }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            io.aequicor.magicpaper.logging.AppLog.error("chat", "source.read.failed", failure, mapOf("resourceId" to resource.id))
                            appendLine("Источник ${resource.url} не удалось прочитать. Сообщи об этом в ответе.")
                            sourceProblems += "Не удалось прочитать источник «${resource.title}». Можно повторить запрос или убрать источник."
                        }
                    }
                }
                if (attachments.any { it.kind == AttachmentKind.FILE }) {
                    sourceProblems += "Это подключение читает текстовые файлы и изображения. Для остальных файлов выберите движок на компьютере или загрузите текстовую версию."
                    appendLine(sourceProblems.last())
                }
            }
        }
        layoutRequest?.let { request ->
            return LayoutChatAgent(gateway, layoutEditor).answer(request.project, request.conversationId, request.requestId,
                trimmed, history, profile, attachments)
        }
        if (researchResources.isNullOrEmpty()) packageRuntime?.answer(trimmed, history, profile, attachments)?.let { return SessionAnswer(it) }
        // Самонастройка: подбираем навыки под запрос до маршрутизации —
        // они усиливают любую ветку (доки, поиск, свободный диалог).
        val skills = skillSelector.select(trimmed, skillLibrary.relevantFor(trimmed))

        if (looksLikeAppQuestion(trimmed) && (researchResources.isNullOrEmpty() || trimmed.contains("magicpaper", ignoreCase = true))) {
            val matches = docs.search(trimmed)
            if (matches.isNotEmpty()) {
                val context = matches.joinToString("\n\n") { "${it.article.title}\n${it.article.body}" }
                return tryModel(
                    profile = operationalProfile,
                    system = SYSTEM_PROMPT,
                    context = listOfNotNull(researchContext, "Документация приложения:\n$context").joinToString("\n\n"),
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = emptyList(),
                    attachments = attachments,
                    sourceProblems = sourceProblems,
                )
            }
        }

        if (looksLikeSearchRequest(trimmed)) {
            val hits = searchEngine.search(trimmed, settings, limit = 5)
            onSearchResults(hits)
            if (hits.isNotEmpty()) {
                val context = hits.joinToString("\n\n") { hit ->
                    "[${hit.provider}] ${hit.title}\n${hit.snippet}\n${hit.url}"
                }
                return tryModel(
                    profile = profile,
                    system = SYSTEM_PROMPT,
                    context = listOfNotNull(researchContext, "Результаты поиска:\n$context").joinToString("\n\n"),
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = hits,
                    attachments = attachments,
                    sourceProblems = sourceProblems,
                )
            }
        }

        return tryModel(
            profile = profile,
            system = SYSTEM_PROMPT,
            context = researchContext,
            skills = skills,
            history = history,
            userText = trimmed,
            sources = emptyList(),
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
