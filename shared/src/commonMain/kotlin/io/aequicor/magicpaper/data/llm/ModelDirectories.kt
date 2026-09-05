package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.ModelDefaults.DiscoveredModel
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.ProviderType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Общий каркас: GET с таймаутом из профиля и проверкой статуса. */
internal suspend fun HttpClient.getText(
    url: String,
    headers: Map<String, String>,
    timeoutSeconds: Int,
): String = withTimeout(timeoutSeconds.toLong() * 1000) {
    val response = get(url) {
        headers.forEach { (key, value) -> header(key, value) }
    }
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
        error("HTTP ${response.status.value}: ${text.take(300).ifBlank { "пустой ответ" }}")
    }
    text
}

/** Разбор массива объектов с полем «id» (формат списков OpenAI и Anthropic). */
internal fun parseIdList(json: Json, body: String, arrayKey: String): List<String> = runCatching {
    val root = json.parseToJsonElement(body)
    val array = (root as? JsonObject)?.get(arrayKey) as? JsonArray ?: root as? JsonArray ?: JsonArray(emptyList())
    array.mapNotNull { item ->
        ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
    }
}.getOrElse { error("Не удалось разобрать список моделей: ${it.message}") }

/** Список моделей у OpenAI-совместимого сервера: GET {base}/models. */
class OpenAiModelDirectory(
    private val client: HttpClient,
    private val json: Json,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/models"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer " + profile.apiKey)
        }
        val body = client.getText(url, headers, profile.advanced.timeoutSeconds)
        return ModelDefaults.discover(profile.provider, parseIdList(json, body, "data"))
    }
}

/** Список моделей Anthropic: GET {base}/v1/models. */
class AnthropicModelDirectory(
    private val client: HttpClient,
    private val json: Json,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/v1/models"
        val headers = mapOf(
            "x-api-key" to profile.apiKey,
            "anthropic-version" to API_VERSION,
        )
        val body = client.getText(url, headers, profile.advanced.timeoutSeconds)
        return ModelDefaults.discover(profile.provider, parseIdList(json, body, "data"))
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
    }
}

/** Список моделей Google: GET {base}/models → models[].name («models/gemini-…»). */
class GoogleModelDirectory(
    private val client: HttpClient,
    private val json: Json,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/models"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("x-goog-api-key", profile.apiKey)
        }
        val body = client.getText(url, headers, profile.advanced.timeoutSeconds)
        val names = runCatching {
            val root = json.parseToJsonElement(body)
            val array = (root as? JsonObject)?.get("models") as? JsonArray ?: JsonArray(emptyList())
            array.mapNotNull { item ->
                val name = ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                name.removePrefix("models/").removePrefix("tunedModels/")
            }
        }.getOrElse { error("Не удалось разобрать список моделей: ${it.message}") }
        return ModelDefaults.discover(profile.provider, names)
    }
}

/** Роутер по типу провайдера — тот же приём, что у [RoutingLlmGateway]. */
class RoutingModelDirectory(
    private val directories: Map<ProviderType, ModelDirectory>,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        val directory = directories[profile.provider]
            ?: error("Нет каталога моделей для провайдера ${profile.provider}.")
        return directory.models(profile)
    }
}
