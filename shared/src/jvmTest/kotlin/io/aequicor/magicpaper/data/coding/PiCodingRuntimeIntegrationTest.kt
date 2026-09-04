package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.RuntimePhase
import java.io.File
import java.net.InetSocketAddress
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
            val events = runBlocking { runtime.run(project, "Скажи одно слово", profile).toList() }

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
