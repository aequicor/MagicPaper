package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmGateway
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.LlmToolDefinition
import io.aequicor.magicpaper.domain.LlmToolExchange
import io.aequicor.magicpaper.domain.LlmToolTurn
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.LlmTransportException
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
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
        llmRequestTimeout(timeoutSeconds)
        contentType(ContentType.Application.Json)
        headers.forEach { (key, value) -> header(key, value) }
        setBody(body)
    }
    val text = response.bodyAsText()
    usageProvider?.let { UsageParsing.report(text, it, kotlinx.serialization.json.Json) }
    if (!response.status.isSuccess()) {
        throw LlmTransportException(response.status.value, response.headers["Retry-After"],
            text.take(300).ifBlank { "пустой ответ" }, providerRejection(text))
    }
    return text
    }
    return if (timeoutSeconds <= 0) request() else withTimeout(timeoutSeconds.toLong() * 1000) { request() }
}

/**
 * Таймаут запроса из настроек профиля поверх дефолта общего клиента.
 *
 * Без `HttpTimeoutCapability` на запросе движок CIO применяет свой жёсткий
 * 15-секундный `requestTimeout` — рассуждающие модели (Qwen/DashScope и др.)
 * обрывались на середине генерации. Capability снимает двигательный потолок,
 * а фактическим ограничением остаётся `timeoutSeconds` профиля: `withTimeout`
 * вокруг запроса либо (при 0 = «без ограничения») отсутствие убийцы вообще.
 */
internal fun io.ktor.client.request.HttpRequestBuilder.llmRequestTimeout(timeoutSeconds: Int) {
    timeout {
        requestTimeoutMillis = if (timeoutSeconds > 0) timeoutSeconds * 1_000L else HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
}

/** OpenAI-совместимый /chat/completions (OpenAI, Ollama, LM Studio, vLLM, OpenRouter…). */
class OpenAiCompatibleGateway(
    private val client: HttpClient,
    private val json: Json,
) : LlmGateway {
    /** Эндпоинты, которые сами отвергли параметр усилия: повторно отправлять заведомо отклоняемый запрос нечего. */
    private val effortRefused = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
        parseResponse(send(profile) { LlmPayloads.openAi(it, messages, ModelDefaults.capability(it)) })

    override suspend fun turn(
        profile: LlmProfile,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>,
    ): LlmToolTurn {
        val body = send(profile) {
            LlmToolWire.openAiPayload(LlmPayloads.openAi(it, messages, ModelDefaults.capability(it)), tools, exchanges, it.provider, json)
        }
        return LlmToolWire.openAiResponse(body, profile.provider, json)
    }

    /**
     * Запрос с одним откатом к настройке, которую провайдер отверг сам.
     *
     * Уровень усилия — необязательная ручка: если сервер вернул 400 и назвал её в `param`,
     * запрос не был обработан, поэтому повтор без неё не удваивает расход и не теряет ответ.
     * Любой другой отказ остаётся ошибкой: догадываться о договоре провайдера транспорт не
     * должен. Отвергнутый параметр запоминается, чтобы каждое следующее сообщение не платило
     * за тот же отказ. Откат пишется в журнал на INFO: он случается не чаще раза на
     * эндпоинт и модель и меняет то, что получает человек, — прятать это в DEBUG нельзя.
     */
    private suspend fun send(profile: LlmProfile, payload: (LlmProfile) -> JsonObject): String {
        val effective = if (refusesEffort(profile)) profile.withoutEffort() else profile
        return try { request(effective, payload(effective)) }
        catch (failure: LlmTransportException) {
            if (!refusedEffort(failure) || effective.effort == EffortSelection.Default) throw failure
            AppLog.info("llm", "effort_parameter_refused", mapOf("provider" to profile.provider.name,
                "model" to profile.modelId, "param" to EFFORT_PARAMETER, "result" to "resent_without_effort"))
            effortRefused.update { it + effortKey(profile) }
            val fallback = profile.withoutEffort()
            request(fallback, payload(fallback))
        }
    }

    private fun refusesEffort(profile: LlmProfile) =
        profile.effort != EffortSelection.Default && effortKey(profile) in effortRefused.value

    private fun refusedEffort(failure: LlmTransportException) =
        failure.statusCode == 400 && failure.rejection?.param == EFFORT_PARAMETER

    private fun effortKey(profile: LlmProfile) = listOf(profile.provider.name, profile.baseUrl, profile.modelId).joinToString("|")

    private fun LlmProfile.withoutEffort() = copy(effort = EffortSelection.Default)

    private suspend fun request(profile: LlmProfile, payload: JsonObject): String {
        require(profile.configured) { "Профиль не настроен: укажите Base URL и модель." }
        val url = profile.baseUrl.trimEnd('/') + "/chat/completions"
        val headers = buildMap {
            if (profile.apiKey.isNotBlank()) put("Authorization", "Bearer " + profile.apiKey)
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

    private companion object { const val EFFORT_PARAMETER = "reasoning_effort" }
}
