package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Откат по факту отказа: провайдер сам назвал параметр, который не принимает.
 * Догадок о договоре вендора здесь нет — есть его ответ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiEffortFallbackTest {
    private val profile = LlmProfile("p", "Alibaba", provider = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://token-intl.aliyuncs.com/compatible-mode/v1", apiKey = "***", modelId = "qwen3.7-plus",
        effort = EffortSelection.of(ReasoningEffort.MEDIUM))
    private val messages = listOf(LlmMessage(LlmChatRole.USER, "привет"))
    private val answer = """{"choices":[{"message":{"content":"Ответ"}}]}"""

    @Test fun aRefusedEffortParameterIsDroppedForTheRestOfTheRun() = runTest {
        // OpenAI называет параметр полем `param`, DashScope — только сообщением: отказ один.
        for (refusal in listOf(
            """{"error":{"code":"unsupported_parameter","param":"reasoning_effort","message":"PRIVATE body"}}""",
            """{"code":"InvalidParameter","message":"Invalid value for parameter 'reasoning_effort'. PRIVATE body"}""",
        )) refusedEffortIsSentOnce(refusal)
    }

    private suspend fun TestScope.refusedEffortIsSentOnce(refusal: String) {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler { request ->
                val body = (request.body as TextContent).text
                bodies += body
                if ("reasoning_effort" in body) respond(refusal, HttpStatusCode.BadRequest) else respond(answer, HttpStatusCode.OK)
            }
        }))
        client.use {
            val gateway = OpenAiCompatibleGateway(it, Json)
            assertEquals("Ответ", gateway.complete(profile, messages), "Отвергнутый запрос не оставляет чат без ответа")
            assertEquals("Ответ", gateway.complete(profile, messages))
        }
        assertEquals(3, bodies.size, "Первый отказ, ответ без ручки и следующий запрос сразу без неё")
        assertTrue("reasoning_effort" in bodies[0])
        assertFalse("reasoning_effort" in bodies[1], bodies[1])
        assertFalse("reasoning_effort" in bodies[2], bodies[2])
        val record = AppLog.history().last { it.component == "llm" && it.event == "effort_parameter_refused" }
        assertEquals("reasoning_effort", record.fields["param"], record.line())
        assertEquals("qwen3.7-plus", record.fields["model"], record.line())
        assertEquals("resent_without_effort", record.fields["result"], record.line())
        assertFalse("PRIVATE" in record.line(), record.line())
    }

    @Test fun anyOtherRefusalStaysAFailureWithoutASecondAttempt() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler {
                calls++
                respond("""{"error":{"code":"InvalidParameter","message":"Unsupported field: stream_options"}}""",
                    HttpStatusCode.BadRequest)
            }
        }))
        val failure = assertFailsWith<LlmTransportException> {
            client.use { OpenAiCompatibleGateway(it, Json).complete(profile, messages) }
        }
        assertEquals(1, calls, "Отказ не по нашей ручке не повторяется")
        assertEquals("InvalidParameter", failure.rejection?.code)
        assertNull(failure.rejection?.param)
        assertContains(failure.safeReason(), "InvalidParameter")
    }
}
