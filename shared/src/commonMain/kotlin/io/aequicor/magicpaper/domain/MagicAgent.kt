package io.aequicor.magicpaper.domain

/**
 * Домен: ядро ассистента. Оркестрирует порты, не знает о UI и фреймворках.
 * Стратегия ответа (стратегия из трёх веток):
 *  1. Вопросы о приложении -> встроенная документация.
 *  2. Похоже на поиск ("найди", "кто", "что такое" и т.п.) -> движок поиска.
 *  3. Иначе -> модель, с контекстом диалога.
 */
class MagicAgent(
    private val gateway: LlmGateway,
    private val searchEngine: SearchEngine,
    private val docs: DocRepository,
    private val skillLibrary: SkillLibrary = EmptySkillLibrary,
    private val skillSelector: SkillSelector = SkillSelector(),
) {

    /** Результат ответа: текст и источники (для отображения в чате). */
    data class Answer(val text: String, val sources: List<SearchHit> = emptyList())

    suspend fun answer(history: List<ChatMessage>, userText: String, settings: AppSettings): Answer {
        val trimmed = userText.trim()
        // Самонастройка: подбираем навыки под запрос до маршрутизации —
        // они усиливают любую ветку (доки, поиск, свободный диалог).
        val skills = skillSelector.select(trimmed, skillLibrary.relevantFor(trimmed))

        if (looksLikeAppQuestion(trimmed)) {
            val matches = docs.search(trimmed)
            if (matches.isNotEmpty()) {
                val context = matches.joinToString("\n\n") { "${it.article.title}\n${it.article.body}" }
                return tryModel(
                    settings = settings,
                    system = SYSTEM_PROMPT,
                    context = "Документация приложения:\n$context",
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = emptyList(),
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
                    settings = settings,
                    system = SYSTEM_PROMPT,
                    context = "Результаты поиска:\n$context",
                    skills = skills,
                    history = history,
                    userText = trimmed,
                    sources = hits,
                )
            }
        }

        return tryModel(
            settings = settings,
            system = SYSTEM_PROMPT,
            context = null,
            skills = skills,
            history = history,
            userText = trimmed,
            sources = emptyList(),
        )
    }

    private suspend fun tryModel(
        settings: AppSettings,
        system: String,
        context: String?,
        skills: List<Skill>,
        history: List<ChatMessage>,
        userText: String,
        sources: List<SearchHit>,
    ): Answer {
        if (!settings.llmConfigured) {
            return Answer(NOT_CONFIGURED_TEXT, sources)
        }
        val messages = buildList {
            add(LlmMessage("system", system))
            if (skills.isNotEmpty()) {
                add(LlmMessage("system", skillsContext(skills)))
            }
            if (context != null) add(LlmMessage("system", context))
            history.takeLast(HISTORY_LIMIT).forEach { m ->
                add(LlmMessage(if (m.role == ChatRole.USER) "user" else "assistant", m.text))
            }
            add(LlmMessage("user", userText))
        }
        return runCatching {
            Answer(gateway.complete(settings, messages), sources)
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
        const val HISTORY_LIMIT = 8
        val APP_KEYWORDS = listOf(
            "magicpaper", "настройки", "настройка", "плагин", "плагины", "профиль",
            "экспорт", "импорт", "поисковый движок", "как работает", "как удалить",
            "безопасность", "перенос", "горячие клавиши", "документация",
            "навык", "навыки", "скилл", "скиллы", "лавка", "самообучение",
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
            Я пока не подключён к магическому источнику. Откройте настройки (иконка пера вверху),
            укажите Base URL модели и её имя — например, локальный сервер.
        """.trimIndent()
    }
}
