package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

/**
 * Шлюз к OpenAI-совместимому /chat/completions.
 * Подходит для OpenAI, Ollama (/v1), LM Studio, vLLM и т.п.
 */
class OpenAiCompatibleGateway(
    private val client: HttpClient,
    private val json: Json,
) : LlmGateway {

    override suspend fun complete(settings: AppSettings, messages: List<LlmMessage>): String {
        require(settings.llmConfigured) { "Не настроена модель: укажите Base URL и имя модели в настройках." }
        val url = settings.llmBaseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", settings.llmModel)
            put("stream", false)
            put("messages", buildJsonArray {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            if (settings.llmApiKey.isNotBlank()) {
                header("Authorization", "Bearer " + settings.llmApiKey)
            }
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        val body = response.bodyAsText()
        return runCatching {
            val root = json.parseToJsonElement(body).let { it as? JsonObject }
                ?: error("Unexpected LLM response")
            val content = (root["choices"] as? JsonArray)
                ?.firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("message")
                ?.let { it as? JsonObject }
                ?.get("content")
                ?.let { it as? JsonPrimitive }
                ?.content
            content ?: error("Empty LLM response")
        }.getOrElse { error("Ошибка ответа модели: ${it.message}") }
    }
}
