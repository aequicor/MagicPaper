package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmToolDefinition
import io.aequicor.magicpaper.domain.LlmToolExchange
import io.aequicor.magicpaper.domain.LlmToolTurn
import io.aequicor.magicpaper.domain.ModelDefaults
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Google Generative Language API: POST {base}/models/{model}:generateContent?key=… */
class GoogleGateway(
    private val client: HttpClient,
    private val json: Json,
) : LlmGateway {

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
        parseResponse(request(profile, LlmPayloads.google(profile, messages, ModelDefaults.capability(profile))))

    override suspend fun turn(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>,
    ): LlmToolTurn {
        val base = LlmPayloads.google(profile, messages, ModelDefaults.capability(profile))
        val payload = LlmToolWire.googlePayload(base, tools, exchanges, profile.provider)
        return LlmToolWire.googleResponse(request(profile, payload), profile.provider, json, exchanges.size)
    }

    private suspend fun request(profile: LlmProfile, payload: JsonObject): String {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val encodedModel = profile.modelId.replace(" ", "")
        val url = profile.baseUrl.trimEnd('/') + "/models/$encodedModel:generateContent"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("x-goog-api-key", profile.apiKey)
        }
        return client.postJson(
            url = url,
            headers = headers,
            body = json.encodeToString(JsonObject.serializer(), payload),
            timeoutSeconds = profile.advanced.timeoutSeconds,
            usageProvider = profile.provider,
        )
    }

    private fun parseResponse(body: String): String = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("Unexpected Google response")
        val parts = ((root["candidates"] as? JsonArray)
            ?.firstOrNull() as? JsonObject)
            ?.get("content")?.let { it as? JsonObject }
            ?.get("parts")?.let { it as? JsonArray }
        val text = parts?.mapNotNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        }?.joinToString("")
        text?.ifBlank { null } ?: error("Empty Google response")
    }.getOrElse { error("Ошибка ответа модели: ${it.message}") }
}
