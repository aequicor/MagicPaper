package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.WireDialect
import io.ktor.http.encodeURLParameter
import io.aequicor.magicpaper.domain.ProviderModel
import io.aequicor.magicpaper.domain.connectionConfigured
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.domain.DeclaredReasoning
import io.aequicor.magicpaper.domain.ModelDefaults.DiscoveredModel
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningEffort
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Общий каркас: GET с таймаутом из профиля и проверкой статуса. */
internal suspend fun HttpClient.getText(
    url: String,
    headers: Map<String, String>,
    timeoutSeconds: Int,
): String = withTimeout(if (timeoutSeconds == 0) Long.MAX_VALUE else timeoutSeconds.toLong() * 1000) {
    val response = get(url) {
        llmRequestTimeout(timeoutSeconds)
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

/**
 * Объявления провайдера об управлении мышлением: id модели → факт из его
 * каталога. Читаем схему OpenRouter-подобных каталогов:
 *  - `reasoning.supported_efforts` (также `efforts`/`supportedEfforts`) — словарь уровней;
 *  - `reasoning.mandatory` — мышление нельзя выключить;
 *  - `supported_parameters` — перечень органов модели: есть `reasoning` — ручка есть,
 *    а вот перечень без неё означает «точно нет» (так же, как у Hermes).
 *
 * Серверы, которые отдают только `id` (Ollama, LM Studio), объявлений не дают —
 * для них остаётся эвристика [ModelDefaults]; догадываться «нет ручки» по
 * отсутствию поля нельзя.
 */
internal fun parseDeclaredReasoning(
    json: Json,
    body: String,
    arrayKey: String = "data",
): Map<String, DeclaredReasoning> = runCatching {
    val root = json.parseToJsonElement(body)
    val array = (root as? JsonObject)?.get(arrayKey) as? JsonArray ?: root as? JsonArray
        ?: return@runCatching emptyMap()
    array.mapNotNull { item ->
        val entry = item as? JsonObject ?: return@mapNotNull null
        val id = (entry["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        id to (declaredOf(entry) ?: return@mapNotNull null)
    }.toMap()
}.getOrDefault(emptyMap())

/** Факт о конкретной записи каталога; null — запись ничего не объявляет. */
private fun declaredOf(entry: JsonObject): DeclaredReasoning? {
    val capabilities = entry["capabilities"] as? JsonObject
    val effortCapability = capabilities?.get("effort") as? JsonObject
    val thinking = capabilities?.get("thinking") as? JsonObject
    if ((thinking?.get("supported") as? JsonPrimitive)?.booleanOrNull == false &&
        (effortCapability?.get("supported") as? JsonPrimitive)?.booleanOrNull != true) return DeclaredReasoning.None
    val supportedEfforts = effortCapability?.entries.orEmpty().mapNotNull { (key, value) ->
        if (((value as? JsonObject)?.get("supported") as? JsonPrimitive)?.booleanOrNull == true)
            ReasoningEffort.fromWire(key) else null
    }.toSet()
    if (supportedEfforts.isNotEmpty()) {
        val types = thinking?.get("types") as? JsonObject
        val adaptive = (((types?.get("adaptive") as? JsonObject)?.get("supported")) as? JsonPrimitive)?.booleanOrNull == true
        return DeclaredReasoning(efforts = supportedEfforts, dialect = if (adaptive) WireDialect.ADAPTIVE_EFFORT else null)
    }

    val reasoning = entry["reasoning"] as? JsonObject
    val efforts = reasoning?.efforts().orEmpty()
    val mandatory = (reasoning?.get("mandatory") as? JsonPrimitive)?.booleanOrNull
    val default = (reasoning?.get("default_effort") as? JsonPrimitive)?.contentOrNull?.let(ReasoningEffort::fromWire)
    val parameters = (entry["supported_parameters"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
    val parameterSaysYes = parameters?.any { it == "reasoning" || it.startsWith("reasoning.") || it == "reasoning_effort" }
    return when {
        efforts.isNotEmpty() || mandatory != null -> DeclaredReasoning(
            efforts = efforts,
            mandatory = mandatory,
            default = default,
        )
        parameterSaysYes == true -> DeclaredReasoning()
        // Перечень органов есть, reasoning в нём нет — каталог знает модель точно.
        !parameters.isNullOrEmpty() -> DeclaredReasoning.None
        else -> null
    }
}

/** Объявленные уровни: имена приводим к нашему словарю, незнакомые не выдумываем. */
private fun JsonObject.efforts(): Set<ReasoningEffort> {
    val array = listOf("supported_efforts", "supportedEfforts", "efforts", "levels")
        .firstNotNullOfOrNull { key -> this[key] as? JsonArray }
        ?: return emptySet()
    return array.mapNotNull { item ->
        (item as? JsonPrimitive)?.contentOrNull?.let { ReasoningEffort.fromWire(it) }
    }.toSet()
}

/** Список моделей у OpenAI-совместимого сервера: GET {base}/models. */
class OpenAiModelDirectory(
    private val client: HttpClient,
    private val json: Json,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        require(profile.connectionConfigured) { "Укажите адрес поставщика." }
        val url = profile.baseUrl.trimEnd('/') + "/models"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer " + profile.apiKey)
        }
        val body = client.getText(url, headers, profile.advanced.timeoutSeconds)
        return parseProviderModels(json, body, profile.provider)

    }
}

/** Список моделей Anthropic: GET {base}/v1/models. */
class AnthropicModelDirectory(
    private val client: HttpClient,
    private val json: Json,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        require(profile.connectionConfigured) { "Укажите адрес поставщика." }
        val url = profile.baseUrl.trimEnd('/') + "/v1/models"
        val headers = mapOf(
            "x-api-key" to profile.apiKey,
            "anthropic-version" to API_VERSION,
        )
        val result = mutableListOf<DiscoveredModel>()
        var after: String? = null
        val seen = mutableSetOf<String>()
        do {
            val pageUrl = url + "?limit=100" + (after?.let { "&after_id=${it.encodeURLParameter()}" } ?: "")
            val body = client.getText(pageUrl, headers, profile.advanced.timeoutSeconds)
            result += parseProviderModels(json, body, profile.provider)
            val root = json.parseToJsonElement(body).jsonObject
            after = if (root["has_more"]?.jsonPrimitive?.booleanOrNull == true) root["last_id"]?.jsonPrimitive?.contentOrNull else null
            require(after == null || seen.add(after)) { "Каталог повторяет страницу" }
        } while (after != null)
        return result.distinctBy { it.id }
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
        require(profile.connectionConfigured) { "Укажите адрес поставщика." }
        val url = profile.baseUrl.trimEnd('/') + "/models"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("x-goog-api-key", profile.apiKey)
        }
        val result = mutableListOf<DiscoveredModel>()
        var token: String? = null
        val seen = mutableSetOf<String>()
        do {
            val pageUrl = url + "?pageSize=1000" + (token?.let { "&pageToken=${it.encodeURLParameter()}" } ?: "")
            val body = client.getText(pageUrl, headers, profile.advanced.timeoutSeconds)
            result += parseProviderModels(json, body, profile.provider, "models")
            token = json.parseToJsonElement(body).jsonObject["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            require(token == null || seen.add(token)) { "Каталог повторяет страницу" }
        } while (token != null)
        return result.distinctBy { it.id }

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

/** Provider metadata uses nullable facts so an id-only directory remains usable. */
internal fun parseProviderModels(json: Json, body: String, provider: ProviderType, arrayKey: String = "data"): List<DiscoveredModel> {
    val root = json.parseToJsonElement(body)
    val array = (root as? JsonObject)?.get(arrayKey) as? JsonArray ?: root as? JsonArray
        ?: error("В ответе нет списка моделей")
    return array.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        fun str(key: String) = (entry[key] as? JsonPrimitive)?.contentOrNull
        fun integer(vararg keys: String) = keys.firstNotNullOfOrNull { (entry[it] as? JsonPrimitive)?.intOrNull?.takeIf { n -> n > 0 } }
        val id = (str("id") ?: str("name"))?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val methods = entry["supportedGenerationMethods"] as? JsonArray
        if (provider == ProviderType.GOOGLE && methods != null && methods.none { (it as? JsonPrimitive)?.contentOrNull == "generateContent" }) return@mapNotNull null
        val defaults = (entry["default_parameters"] as? JsonObject).orEmpty().filterValues { it != JsonNull }.toMutableMap()
        if (provider == ProviderType.GOOGLE) listOf("temperature" to "temperature", "topP" to "top_p", "topK" to "top_k").forEach { (wire, key) ->
            entry[wire]?.takeIf { it != JsonNull }?.let { defaults[key] = it }
        }
        val declared = declaredOf(entry)
        val supported = (entry["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            ?: if (provider == ProviderType.GOOGLE) defaults.keys + "max_tokens" else null
        val metadata = ProviderModel(id, str("displayName") ?: str("display_name") ?: str("name")?.takeUnless { it.startsWith("models/") } ?: id,
            integer("max_input_tokens", "context_length", "context_window", "inputTokenLimit"),
            integer("max_output_tokens", "max_tokens", "outputTokenLimit") ?: ((entry["top_provider"] as? JsonObject)?.get("max_completion_tokens") as? JsonPrimitive)?.intOrNull,
            defaults, supported, declared,
            (entry["pricing"] as? JsonObject)?.let { rates ->
                io.aequicor.magicpaper.domain.ModelPricing(rates.price("prompt"), rates.price("completion"), rates.price("input_cache_read"), rates.price("input_cache_write"))
            })
        ModelDefaults.discover(provider, listOf(id), declared?.let { mapOf(id to it) }.orEmpty(), mapOf(id to metadata)).single().copy(metadata = metadata)
    }.distinctBy { it.id }.sortedBy { it.id }
}
