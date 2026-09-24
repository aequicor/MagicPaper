package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Отказ провайдера обязан остаться разобранным: без кода и имени параметра человек видит
 * только «модель не ответила» и не может исправить настройку, а журнал — объяснить сбой.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRejectionTest {
    private val profile = LlmProfile("p", "Alibaba", provider = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://token-intl.aliyuncs.com/compatible-mode/v1", apiKey = "***", modelId = "qwen3.7-plus")

    @Test fun errorBodyYieldsMachineTokensAndNeverFreeText() {
        assertEquals(ProviderRejection("unsupported_parameter", "invalid_request_error", "reasoning_effort", ProviderRefusal.PARAMETER),
            providerRejection("""{"error":{"message":"Unsupported parameter: 'reasoning_effort'. Key: PRIVATE",
                "type":"invalid_request_error","param":"reasoning_effort","code":"unsupported_parameter"}}"""))
        // DashScope кладёт код в корень и не присылает имя параметра.
        val dashScope = providerRejection("""{"code":"InvalidParameter","message":"Range of input length should be [1, 129024]","request_id":"abc"}""")
        assertEquals("InvalidParameter", dashScope?.code)
        assertNull(dashScope?.param)
        assertEquals(ProviderRefusal.CONTEXT_LENGTH, dashScope?.refusal)
        // Форма отказа распознаётся, но текст провайдера не сохраняется.
        assertEquals(ProviderRefusal.CONTEXT_LENGTH, providerRejection(
            """{"error":{"message":"This model's maximum context length is 8192 tokens"}}""")?.refusal)
        assertEquals(ProviderRefusal.TOOLS, providerRejection(
            """{"error":{"message":"tools is not supported with this model"}}""")?.refusal)
        assertEquals(ProviderRefusal.MODEL, providerRejection(
            """{"error":{"code":"model_not_found","message":"The model `qwen-x` does not exist"}}""")?.refusal)
        // Провайдер назвал параметр в сообщении, а не полем `param`: факт тот же.
        assertEquals("reasoning_effort", providerRejection(
            """{"code":"InvalidParameter","message":"Invalid value for parameter 'reasoning_effort'."}""")?.param)
        assertEquals("reasoning_effort", providerRejection(
            """{"error":{"code":"unsupported_parameter","message":"Unsupported parameter: 'reasoning_effort' is not supported with this model."}}""")?.param)
        // Обычное слово из сообщения параметром не становится.
        assertNull(providerRejection("""{"error":{"code":"InvalidParameter","message":"The parameter is missing PRIVATE"}}""")?.param)
        // Свободный текст под видом кода отбрасывается, а не сокращается.
        assertNull(providerRejection("""{"error":{"code":"the model said PRIVATE"}}""")?.code)
        assertNull(providerRejection("HTTP/1.1 502 Bad Gateway"))
        assertNull(providerRejection(""))
        assertNull(providerRejection("""{"error":{}}"""))
    }

    @Test fun gatewayKeepsProviderTokensAndTheBodyNeverReachesTheUserMessage() = runTest {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = UnconfinedTestDispatcher(testScheduler)
            addHandler {
                respond("""{"error":{"code":"unsupported_parameter","param":"reasoning_effort","message":"PRIVATE body"}}""",
                    HttpStatusCode.BadRequest)
            }
        }))
        val failure = assertFailsWith<LlmTransportException> {
            client.use { OpenAiCompatibleGateway(it, Json).complete(profile, listOf(LlmMessage(LlmChatRole.USER, "привет"))) }
        }
        assertEquals(400, failure.statusCode)
        assertEquals("unsupported_parameter", failure.rejection?.code)
        assertEquals("reasoning_effort", failure.rejection?.param)
        assertTrue(failure.confirmedRejection, "Полученный отказ подтверждает, что ответа нет")
        assertEquals(mapOf("status" to "400", "code" to "unsupported_parameter", "param" to "reasoning_effort",
            "refusal" to "parameter"), failure.logFields())
        val reason = failure.safeReason()
        assertContains(reason, "reasoning_effort")
        assertFalse("PRIVATE" in reason, reason)
    }

    @Test fun safeReasonNamesTheActionForEveryRefusalClass() {
        assertEquals("Провайдер не принял ключ подключения. Проверьте его в настройках.", transport(401).safeReason())
        assertContains(transport(404).safeReason(), "не знает выбранную модель")
        assertContains(transport(429).safeReason(), "Повторите позже")
        assertContains(transport(400, ProviderRejection(param = "top_p", refusal = ProviderRefusal.PARAMETER)).safeReason(), "top_p")
        assertContains(transport(400, ProviderRejection(code = "InvalidParameter")).safeReason(), "InvalidParameter")
        assertContains(transport(400, ProviderRejection(code = "InvalidParameter", refusal = ProviderRefusal.CONTEXT_LENGTH)).safeReason(),
            "не помещается в контекст")
        assertContains(transport(400, ProviderRejection(refusal = ProviderRefusal.TOOLS)).safeReason(), "не принимает инструменты")
        assertEquals("Провайдер отклонил запрос. Проверьте параметры модели.", transport(400).safeReason())
        assertEquals("Провайдер не подтвердил ответ.", transport(503).safeReason())
    }

    private fun transport(status: Int, rejection: ProviderRejection? = null) =
        LlmTransportException(status, null, "тело ответа провайдера", rejection)
}
