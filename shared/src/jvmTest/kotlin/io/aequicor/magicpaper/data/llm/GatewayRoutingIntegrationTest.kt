package io.aequicor.magicpaper.data.llm

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Интеграция транспортов с реальным HTTP: мок-сервер отвечает в формате каждого
 * провайдера, роутер выбирает транспорт по типу профиля, ответ разбирается в текст.
 * Проверяются и заголовки авторизации, и обработка ошибки.
 */
class GatewayRoutingIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient()
    private val messages = listOf(
        LlmMessage("system", "ты ассистент"),
        LlmMessage("user", "привет"),
    )

    /** Мок-сервер с тремя эндпоинтами: записывает заголовки запросов для проверки. */
    private class Mock {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val headersByPath = mutableMapOf<String, Map<String, String>>()
        var failStatus = -1

        init {
            server.createContext("/v1/chat/completions") { ex ->
                headersByPath["openai"] = ex.requestHeaders.entries.associate { it.key to it.value.firstOrNull().orEmpty() }
                val body = """{"choices":[{"index":0,"message":{"role":"assistant","content":"openai-ok"},"finish_reason":"stop"}]}"""
                respond(ex, if (failStatus > 0) failStatus else 200, body)
            }
            server.createContext("/v1/messages") { ex ->
                headersByPath["anthropic"] = ex.requestHeaders.entries.associate { it.key to it.value.firstOrNull().orEmpty() }
                val body = """{"content":[{"type":"text","text":"anthropic-ok"}],"stop_reason":"end_turn"}"""
                respond(ex, 200, body)
            }
            server.createContext("/") { ex ->
                // Google: /v1beta/models/{model}:generateContent
                if (":generateContent" in ex.requestURI.path) {
                    headersByPath["google"] = ex.requestHeaders.entries.associate { it.key to it.value.firstOrNull().orEmpty() }
                    val body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"google-ok"}]}}]}"""
                    respond(ex, 200, body)
                } else {
                    respond(ex, 404, "{}")
                }
            }
            server.start()
        }

        private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
            val data = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }

        fun baseUrl() = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    @Test
    fun routerRoutesByProviderType() {
        val mock = Mock()
        val router = RoutingLlmGateway(
            mapOf(
                ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleGateway(client, json),
                ProviderType.ANTHROPIC to AnthropicGateway(client, json),
                ProviderType.GOOGLE to GoogleGateway(client, json),
            )
        )
        try {
            val openAi = LlmProfile(
                id = "1", name = "openai", provider = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = mock.baseUrl() + "/v1", apiKey = "sk-test-openai", modelId = "gpt-5-mini",
            )
            val anthropic = LlmProfile(
                id = "2", name = "claude", provider = ProviderType.ANTHROPIC,
                baseUrl = mock.baseUrl(), apiKey = "sk-ant-test", modelId = "claude-sonnet-4-5",
            )
            val google = LlmProfile(
                id = "3", name = "gemini", provider = ProviderType.GOOGLE,
                baseUrl = mock.baseUrl() + "/v1beta", apiKey = "sk-goog-test", modelId = "gemini-2.5-flash",
            )

            val r1 = runBlocking { router.complete(openAi, messages) }
            val r2 = runBlocking { router.complete(anthropic, messages) }
            val r3 = runBlocking { router.complete(google, messages) }

            assertEquals("openai-ok", r1)
            assertEquals("anthropic-ok", r2)
            assertEquals("google-ok", r3)

            // Заголовки авторизации каждого формата.
            assertEquals("Bearer sk-test-openai", mock.headersByPath["openai"]?.get("Authorization"))
            assertEquals("sk-ant-test", mock.headersByPath["anthropic"]?.get("x-api-key") ?: mock.headersByPath["anthropic"]?.get("X-api-key"))
            assertTrue(mock.headersByPath["anthropic"]?.keys?.any { it.equals("anthropic-version", true) } == true)
            assertTrue(mock.headersByPath["google"]?.keys?.any { it.equals("x-goog-api-key", true) } == true)
        } finally {
            mock.stop()
        }
    }

    @Test
    fun httpErrorSurfacesAsMessage() {
        val mock = Mock()
        mock.failStatus = 401
        val gateway = OpenAiCompatibleGateway(client, json)
        val profile = LlmProfile(
            id = "1", name = "x", provider = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = mock.baseUrl() + "/v1", apiKey = "sk-test-x", modelId = "m",
        )
        try {
            val error = runBlocking {
                runCatching { gateway.complete(profile, messages) }.exceptionOrNull()
            }
            assertTrue(error != null, "ошибка 401 должна пробрасываться")
            assertTrue("401" in (error.message.orEmpty()), "текст: ${error.message}")
        } finally {
            mock.stop()
        }
    }
}
