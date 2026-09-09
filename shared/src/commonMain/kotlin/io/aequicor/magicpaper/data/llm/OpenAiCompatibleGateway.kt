package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.LlmTransportException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Шлюзы к конкретным форматам провайдеров. Каждый реализует [LlmGateway]
 * для своего типа провайдера; выбор транспорта — за [RoutingLlmGateway].
 */

/** Общий каркас транспорта: запрос с таймаутом из профиля и проверкой статуса. */
internal suspend fun HttpClient.postJson(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutSeconds: Int,
    usageProvider: io.aequicor.magicpaper.domain.ProviderType? = null,
): String {
    suspend fun request(): String {
    val response = post(url) {
        contentType(ContentType.Application.Json)
        headers.forEach { (key, value) -> header(key, value) }
        setBody(body)
    }
    val text = response.bodyAsText()
    usageProvider?.let { UsageParsing.report(text, it, kotlinx.serialization.json.Json) }
    if (!response.status.isSuccess()) {
        throw LlmTransportException(response.status.value, response.headers["Retry-After"], text.take(300).ifBlank { "пустой ответ" })
    }
    return text
    }
    return if (timeoutSeconds <= 0) request() else withTimeout(timeoutSeconds.toLong() * 1000) { request() }
}

/** OpenAI-совместимый /chat/completions (OpenAI, Ollama, LM Studio, vLLM, OpenRouter…). */
class OpenAiCompatibleGateway(
    private val client: HttpClient,
    private val json: Json,
) : LlmGateway {

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = LlmPayloads.openAi(profile, messages, ModelDefaults.capability(profile))
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer " + profile.apiKey)
        }
        val body = client.postJson(
            url = url,
            headers = headers,
            body = json.encodeToString(JsonObject.serializer(), payload),
            timeoutSeconds = profile.advanced.timeoutSeconds,
            usageProvider = profile.provider,
        )
        return parseResponse(body)
    }

    private fun parseResponse(body: String): String = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("Unexpected LLM response")
        val message = (root["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { it as? JsonObject }
            ?: error("Empty LLM response")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull
        // Модели-рассудители (o-серия, deepseek-r1 в некоторых шлюзах) кладут текст
        // в reasoning_content, оставляя content пустым — без этого фолбэка план
        // молча разваливался в одну веху.
        content?.takeIf { it.isNotBlank() }
            ?: (message["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Empty LLM response")
    }.getOrElse { error("Ошибка ответа модели: ${it.message}") }
}
