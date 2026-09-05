package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Anthropic Messages API: POST {base}/v1/messages. */
class AnthropicGateway(
    private val client: HttpClient,
    private val json: Json,
) : LlmGateway {

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/v1/messages"
        val payload = LlmPayloads.anthropic(profile, messages, ModelDefaults.capability(profile))
        val headers = buildMap {
            put("x-api-key", profile.apiKey)
            put("anthropic-version", API_VERSION)
        }
        val body = client.postJson(
            url = url,
            headers = headers,
            body = json.encodeToString(JsonObject.serializer(), payload),
            timeoutSeconds = profile.advanced.timeoutSeconds,
        )
        return parseResponse(body)
    }

    /** content — массив блоков; склеиваем текстовые. */
    private fun parseResponse(body: String): String = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("Unexpected Anthropic response")
        val text = (root["content"] as? JsonArray)
            ?.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull
                } else {
                    null
                }
            }
            ?.joinToString("")
        text?.ifBlank { null } ?: error("Empty Anthropic response")
    }.getOrElse { error("Ошибка ответа модели: ${it.message}") }

    private companion object {
        const val API_VERSION = "2023-06-01"
    }
}
