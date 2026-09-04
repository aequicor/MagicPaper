package io.aequicor.magicpaper.data.docs

import io.aequicor.magicpaper.domain.DocArticle
import io.aequicor.magicpaper.domain.DocMatch
import io.aequicor.magicpaper.domain.DocRepository
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Встроенная документация приложения.
 * Статьи зашиты в общий код и доступны на всех платформах без сети.
 * Поиск — простой векторный (косинусная близость по токенам), без внешних библиотек.
 */
class EmbeddedDocRepository : DocRepository {

    private val articles: List<DocArticle> = buildArticles()

    override suspend fun articles(): List<DocArticle> = articles

    override suspend fun search(query: String, limit: Int): List<DocMatch> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        val qVector = vector(qTokens)
        return articles
            .map { article ->
                val aVector = vector(tokenize(article.title + " " + article.title + " " + article.body))
                article to cosine(qVector, aVector)
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { DocMatch(it.first, it.second) }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(' ', '\n', '\t', '.', ',', ';', ':', '!', '?', '(', ')', '-', '"', '\'', '/')
            .map { it.trim() }
            .filter { it.length > 1 }

    private fun vector(tokens: List<String>): Map<String, Double> {
        val counts = tokens.groupingBy { it }.eachCount()
        val total = counts.values.sum().toDouble().coerceAtLeast(1.0)
        return counts.mapValues { (_, c) -> c / total }
    }

    private fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dot = a.entries.sumOf { (k, v) -> v * (b[k] ?: 0.0) }
        val na = sqrt(a.values.sumOf { it * it })
        val nb = sqrt(b.values.sumOf { it * it })
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (na * nb)
    }

    private fun buildArticles(): List<DocArticle> = listOf(
        DocArticle(
            id = "start",
            title = "Быстрый старт",
            body = """
                MagicPaper — это волшебный блокнот-ассистент. Напишите вопрос в поле заклинаний
                внизу, и агент ответит, при необходимости обратившись к поиску.
                Интерфейс нарочито минималистичен: один экран, один диалог, одна цель — помочь вам.
            """.trimIndent()
        ),
        DocArticle(
            id = "search",
            title = "Поисковый движок",
            body = """
                Agent умеет искать в интернете. В настройках выберите движок:
                Wikipedia (работает без ключей), Querit.ai, Google Programmable Search или AUTO.
                AUTO — сначала пробует настроенный ключевой движок, иначе Wikipedia.
                Ключи задаются на экране настроек и хранятся локально.
            """.trimIndent()
        ),
        DocArticle(
            id = "llm",
            title = "Подключение ИИ-модели",
            body = """
                MagicPaper работает с любым OpenAI-совместимым API.
                Для локальной модели укажите Base URL локального сервера (например,
                http://localhost:11434/v1 для Ollama) и имя модели.
                Поле API-ключа можно оставить пустым для локальных серверов.
                Все настройки — в экране настроек.
            """.trimIndent()
        ),
        DocArticle(
            id = "plugins",
            title = "Плагины",
            body = """
                Приложение расширяется плагинами. Каждый плагин добавляет отдельную панель
                к минималистичному интерфейсу. Включайте и выключайте их на экране плагинов.
                В комплекте: заметки (заклинания памяти), таймер фокуса и калькулятор.
            """.trimIndent()
        ),
        DocArticle(
            id = "profile",
            title = "Перенос профиля и удаление",
            body = """
                На экране настроек можно экспортировать профиль в файл: он содержит настройки,
                состояние плагинов и всю историю чатов. Импортируйте файл на другом устройстве,
                чтобы перенести всё. Приложение работает в изолированной среде пользователя и не
                требует прав администратора; при удалении программы удаляются и все данные.
            """.trimIndent()
        ),
        DocArticle(
            id = "privacy",
            title = "Безопасность данных",
            body = """
                Данные хранятся локально в папке пользователя (desktop) или в localStorage браузера.
                Приложение не требует установки и прав администратора. Ключи и история не покидают
                устройство, кроме запросов к выбранным вами поисковым и ИИ-провайдерам.
            """.trimIndent()
        ),
        DocArticle(
            id = "hotkeys",
            title = "Горячие клавиши",
            body = """
                Enter — отправить заклинание. Shift+Enter — новая строка.
                На десктопе окно можно перетаскивать за верхнюю панель.
            """.trimIndent()
        ),
        DocArticle(
            id = "skills",
            title = "Лавка навыков",
            body = """
                Навыки — это проверенные инструкции, которые агент подхватывает автоматически.
                Плагин «Лавка навыков» содержит каталог проверенных временем скиллов: резюме,
                вычитка, планы, сравнения и другие. Установка — в один клик; навык можно
                выключить переключателем или удалить. Каталог живёт в коде и работает офлайн,
                а поиск по нему находит навык по описанию задачи. Навыки входят в экспорт
                профиля и переносятся вместе с историей.
            """.trimIndent()
        ),
        DocArticle(
            id = "self-education",
            title = "Самообучение агента",
            body = """
                Плагин «Самообучение» позволяет агенту самому создавать навыки из диалога —
                как тренировка памяти. После удачного ответа нажмите «Предложить навык»:
                агент сформулирует имя, назначение и инструкцию черновика. Проверьте и
                отредактируйте текст, затем сохраните — навык появится в библиотеке и будет
                автоматически применяться в похожих задачах. Если модель не подключена,
                черновик создаётся из последнего запроса и помечается как «без модели».
                Никакой навык не сохраняется без вашего подтверждения.
            """.trimIndent()
        ),
    )
}
