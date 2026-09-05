package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Интеграционный тест полного цикла: изолированная установка пи-агента и
 * прогон запроса через мок-сервер модели. Требует сети (npm-реестр) и
 * включается переменной окружения MAGICPAPER_PI_IT=true,
 * чтобы сборка оставалась герметичной.
 */
class PiCodingRuntimeIntegrationTest {

    private val enabled = System.getenv("MAGICPAPER_PI_IT") == "true" ||
        System.getProperty("magicpaper.pi.it") == "true"

    @Test
    fun installAndRunWithIsolatedRuntime() {
        if (!enabled) {
            println("Пропущено: включите MAGICPAPER_PI_IT=true")
            return
        }
        val workRoot = createTempDir("magicpaper-pi-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val mock = startMockModelServer()
        try {
            val profile = LlmProfile(
                id = "it",
                name = "мок-сервер",
                provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${mock.address.port}/v1",
                modelId = "mock-model",
                apiKey = "test-key",
            )
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))

            val statuses = runBlocking { runtime.ensureReady().toList() }
            val last = statuses.last()
            println("Фазы установки: " + statuses.joinToString(" -> ") { it.phase.toString() })
            assertEquals(RuntimePhase.READY, last.phase, "итог установки: ${last.detail}")

            val project = CodingProject(id = "p1", name = "demo", path = projectDir.absolutePath, createdAt = 1L)
            val session = CodingSession(id = "s1", projectId = "p1", name = "Основная", createdAt = 1L)
            val events = runBlocking { runtime.run(project, session, "Скажи одно слово", profile).toList() }

            assertTrue(events.any { it is CodingEvent.SessionStarted }, "нет заголовка сессии")
            val finals = events.filterIsInstance<CodingEvent.FinalText>()
            assertTrue(finals.isNotEmpty(), "нет финального текста; события: $events")
            assertTrue(finals.last().text.contains("PONG"), "текст: ${finals.last().text}")
            assertTrue(events.last() is CodingEvent.Finished)

            runBlocking { runtime.uninstall() }
            assertTrue(!File(workRoot, "coding").exists(), "установка не удалена полностью")
        } finally {
            mock.stop(0)
            workRoot.deleteRecursively()
        }
    }

    /**
     * Проверка типографики через РЕДАКТИРОВАНИЕ пи-агентом (живой движок).
     *
     * Мок-модель дважды вызывает инструмент edit на файле с «ёлочками»,
     * тире и ё: сначала с испорченным oldText (ASCII-дефис вместо тире —
     * так делает модель-«неряха»), затем с дословным. До защиты кодировки
     * первая попытка уходила в fuzzy-режим и переписывала ВЕСЬ файл из
     * нормализованной копии (тире и кавычки деградировали молча); после —
     * обязана дать честную ошибку, а дословная правка не тронуть соседние строки.
     */
    @Test
    fun editToolPreservesRussianTypography() {
        if (!enabled) {
            println("Пропущено: включите MAGICPAPER_PI_IT=true")
            return
        }
        val workRoot = createTempDir("magicpaper-pi-edit-it")
        val projectDir = File(workRoot, "project").apply { mkdirs() }
        val notes = File(projectDir, "notes.txt")
        val original =
            "Глава первая — «Шалость удалась»\n" +
                "Ёж бежал по пергаменту.\n" +
                "Счёт: 42\n" +
                "Итог — «магическая бумага», а не просто лист.\n"
        notes.writeText(original, Charsets.UTF_8)
        val mock = startEditMockModelServer()
        try {
            val profile = LlmProfile(
                id = "it",
                name = "мок-сервер",
                provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${mock.address.port}/v1",
                modelId = "mock-model",
                apiKey = "test-key",
            )
            val runtime = PiCodingRuntime(rootDir = File(workRoot, "coding"))
            val statuses = runBlocking { runtime.ensureReady().toList() }
            assertEquals(RuntimePhase.READY, statuses.last().phase, "итог установки: ${statuses.last().detail}")

            val project = CodingProject(id = "p1", name = "demo", path = projectDir.absolutePath, createdAt = 1L)
            val session = CodingSession(id = "s1", projectId = "p1", name = "Основная", createdAt = 1L)
            val events = runBlocking { runtime.run(project, session, "Поправь итог в notes.txt", profile).toList() }

            // Первая попытка: oldText с ASCII-дефисом вместо тире. Патченный бандл не должен ни применять её через fuzzy, ни портить файл.
            val edits = events.filterIsInstance<CodingEvent.ToolFinished>().filter { it.tool == "edit" }
            assertTrue(edits.size >= 2, "ожидали минимум два вызова edit; события: $events")
            assertTrue(edits.first().isError, "первая правка обязана завершиться ошибкой несовпадения: ${edits.first()}")
            assertFalse(
                "Итог - «магическая бумага»." in notes.readText(Charsets.UTF_8),
                "испорченная правка с дефисом не должна была примениться",
            )

            // Итог: дословная правка применена, типографика всех остальных строк цела.
            val expected =
                "Глава первая — «Шалость удалась»\n" +
                    "Ёж бежал по пергаменту.\n" +
                    "Счёт: 42\n" +
                    "Итог — «магическая бумага» и чернила.\n"
            assertEquals(expected, notes.readText(Charsets.UTF_8))

            runBlocking { runtime.uninstall() }
        } finally {
            mock.stop(0)
            workRoot.deleteRecursively()
        }
    }

    /**
     * Мок-сервер для теста типографики: запрос 0 — tool_call edit с испорченным
     * oldText (дефис вместо тире), запрос 1 — тот же edit с дословным текстом,
     * дальше — финальный текст. Формат — стандартные стриминговые tool_calls.
     */
    private fun startEditMockModelServer(): HttpServer {
        val requestNo = AtomicInteger(0)
        val badEditArgs = editArgsJson(
            oldText = "Итог - «магическая бумага», а не просто лист.",
            newText = "Итог - «магическая бумага».",
        )
        val goodEditArgs = editArgsJson(
            oldText = "Итог — «магическая бумага», а не просто лист.",
            newText = "Итог — «магическая бумага» и чернила.",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes() // вычитываем тело до ответа
            val no = requestNo.getAndIncrement()
            when {
                no <= 1 -> {
                    val callId = "call_edit_$no"
                    val args = if (no == 0) badEditArgs else goodEditArgs
                    val escaped = args.replace("\\", "\\\\").replace("\"", "\\\"")
                    val chunks = listOf(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"$callId","type":"function","function":{"name":"edit","arguments":""}}]},"index":0}]}""",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$escaped"}}]},"index":0}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"tool_calls","index":0}]}""",
                    )
                    val payload = chunks.joinToString("\n\n", prefix = "", postfix = "") { "data: $it" } +
                        "\n\ndata: [DONE]\n\n"
                    val data = payload.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.use { it.write(data) }
                }
                else -> {
                    val data = """{"id":"c$no","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"PONG: типографика цела"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                        .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.use { it.write(data) }
                }
            }
        }
        server.start()
        return server
    }

    /** Аргументы инструмента edit; кириллица и «ёлочки» в JSON не требуют экранирования. */
    private fun editArgsJson(oldText: String, newText: String): String =
        "{\"path\":\"notes.txt\",\"edits\":[{\"oldText\":\"$oldText\",\"newText\":\"$newText\"}]}"

    /** Мок OpenAI-совместимого сервера со стримингом SSE. */
    private fun startMockModelServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val stream = "\"stream\":true" in body.replace(" ", "")
            val chunks = listOf(
                """{"choices":[{"delta":{"role":"assistant"},"index":0}]}""",
                """{"choices":[{"delta":{"content":"PONG: протокол "},"index":0}]}""",
                """{"choices":[{"delta":{"content":"работает"},"index":0}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop","index":0}]}""",
            )
            if (stream) {
                val payload = chunks.joinToString("\n\n", prefix = "", postfix = "") { "data: $it" } +
                    "\n\ndata: [DONE]\n\n"
                val data = payload.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } else {
                val data = """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"PONG: протокол работает"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                    .toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
        }
        server.start()
        return server
    }

    private fun createTempDir(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix).toFile()
}
