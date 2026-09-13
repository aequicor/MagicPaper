package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.di.appHttpClient
import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Регрессия на обрыв медленных моделей (Qwen/DashScope и др.):
 * движок CIO режет запросы без HttpTimeoutCapability на 15 секундах,
 * поэтому LLM-транспорт обязан выставлять capability из timeoutSeconds
 * профиля (0 = INFINITE: двигательный потолок снят, убийцы нет).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmTransportTimeoutTest {

    private fun profile(timeoutSeconds: Int) = LlmProfile(
        id = "p", name = "Alibaba", provider = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://dashscope.example/compatible-mode/v1",
        apiKey = "***", modelId = "qwen3.8-max",
        advanced = AdvancedLlmOptions(timeoutSeconds = timeoutSeconds),
    )

    /** Клиент, записывающий capability таймаута каждого запроса. */
    private fun TestScope.capturingClient(body: String): Pair<HttpClient, MutableList<Long>> {
        val captured = mutableListOf<Long>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { request ->
                captured += request.getCapabilityOrNull<HttpTimeoutConfig>(HttpTimeoutCapability)?.requestTimeoutMillis
                    ?: error("запрос ушёл без HttpTimeoutCapability — CIO оборвал бы его на 15 с")
                respond(body)
            }
        }))
        return client to captured
    }

    @Test fun chatRequestCarriesProfileTimeout() = runTest {
        val (client, captured) = capturingClient("""{"choices":[{"message":{"content":"ок"}}]}""")
        client.use {
            OpenAiCompatibleGateway(it, Json).complete(profile(60), listOf(LlmMessage(LlmChatRole.USER, "привет")))
        }
        assertEquals(listOf(60_000L), captured)
    }

    @Test fun unlimitedProfileTimeoutDisablesEngineCeiling() = runTest {
        val (client, captured) = capturingClient("""{"choices":[{"message":{"content":"ок"}}]}""")
        client.use {
            OpenAiCompatibleGateway(it, Json).complete(profile(0), listOf(LlmMessage(LlmChatRole.USER, "привет")))
        }
        assertEquals(listOf(HttpTimeoutConfig.INFINITE_TIMEOUT_MS), captured)
    }

    @Test fun modelDirectoryCarriesProfileTimeout() = runTest {
        val (client, captured) = capturingClient("""{"data":[{"id":"qwen3.8-max"}]}""")
        client.use {
            OpenAiModelDirectory(it, Json).models(profile(60))
        }
        assertEquals(listOf(60_000L), captured)
    }

    @Test fun llmRequestTimeoutHelperMapsSeconds() {
        val zero = HttpRequestBuilder().apply { llmRequestTimeout(0) }
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
            zero.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
        val custom = HttpRequestBuilder().apply { llmRequestTimeout(90) }
        assertEquals(90_000L, custom.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
    }

    @Test fun appHttpClientInstallsHttpTimeoutPlugin() {
        // Клиент общего назначения обязан нести плагин таймаутов: без него
        // движок CIO режет любой запрос (поиск, каталоги) своими 15 секундами.
        appHttpClient().use { client ->
            assertNotNull(client.plugin(HttpTimeout))
        }
    }
}
