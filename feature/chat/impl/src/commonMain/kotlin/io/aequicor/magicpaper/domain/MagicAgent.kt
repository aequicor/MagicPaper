package io.aequicor.magicpaper.domain

/**
 * Домен: ядро ассистента. Оркестрирует порты, не знает о UI и фреймворках.
 * Стратегия ответа (стратегия из трёх веток):
 *  1. Вопросы о приложении -> встроенная документация.
 *  2. Похоже на поиск ("найди", "кто", "что такое" и т.п.) -> движок поиска.
 *  3. Иначе -> модель, с контекстом диалога.
 *
 * Модель выбирает не агент, а вызывающий слой (через [ProfileResolver]);
 * агент получает готовый [LlmProfile] — или null, если источник не подключён.
 */
class MagicAgent(
    private val gateway: LlmGateway,
    private val searchEngine: SearchEngine,
    private val docs: DocRepository,
    private val skillLibrary: SkillLibrary = EmptySkillLibrary,
    private val skillSelector: SkillSelector = SkillSelector(),
    private val packageRuntime: SkillInstructionRuntime? = null,
) {

    /** Результат ответа: текст и источники (для отображения в чате). */
    data class Answer(val text: String, val sources: List<SearchHit> = emptyList())

    suspend fun answer(
        history: List<ChatMessage>,
        userText: String,
        settings: AppSettings,
        profile: LlmProfile?,
        attachments: List<Attachment> = emptyList(),
        operationalProfile: LlmProfile? = profile,
    ): Answer {
        val trimmed = userText.trim()
        packageRuntime?.answer(trimmed, history, profile, attachments)?.let { return Answer(it) }
        // Самонастройка: подбираем навыки под запрос до маршрутизации —
        // они усиливают любую ветку (доки, поиск, свободный диалог).
        val skills = skillSelector.select(trimmed, skillLibrary.relevantFor(trimmed))

        if (looksLikeAppQuestion(trimmed)) {
            val matches = docs.search(trimmed)
            if (matches.isNotEmpty()) {
                val context = matches.joinToString("\n\n") { "${it.article.title}\n${it.article.body}" }
                return tryModel(
                    profile = operationalProfile,
                    system = SYSTEM_PROMPT,
                    context = "Документация приложения:\n$context",
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = emptyList(),
                    attachments = attachments,
                )
            }
        }

        if (looksLikeSearchRequest(trimmed)) {
            val hits = searchEngine.search(trimmed, settings, limit = 5)
            if (hits.isNotEmpty()) {
                val context = hits.joinToString("\n\n") { hit ->
                    "[${hit.provider}] ${hit.title}\n${hit.snippet}\n${hit.url}"
                }
                return tryModel(
                    profile = profile,
                    system = SYSTEM_PROMPT,
                    context = "Результаты поиска:\n$context",
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = hits,
                    attachments = attachments,
                )
            }
        }

        return tryModel(
            profile = profile,
            system = SYSTEM_PROMPT,
            context = null,
            skills = skills,
            history = history,
            userText = trimmed,
            sources = emptyList(),
            attachments = attachments,
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
    ): Answer {
        if (profile == null || !profile.configured) {
            return Answer(NOT_CONFIGURED_TEXT, sources)
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
        return runCatching {
            Answer(gateway.complete(profile, messages), sources)
        }.getOrElse { e ->
            Answer("Заклинание не сработало: ${e.message ?: "неизвестная ошибка"}.", sources)
        }
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
