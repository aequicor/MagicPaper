package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.ProviderRefusal
import io.aequicor.magicpaper.domain.ProviderRejection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Машинные поля отказа из тела ошибки провайдера.
 *
 * Разбираются обе формы: OpenAI (`{"error":{"code","type","param"}}`) и DashScope
 * (`{"code":"InvalidParameter","request_id":…}`). Свободный текст не сохраняется: по его
 * форме выбирается признак [ProviderRefusal], а само сообщение может содержать фрагмент
 * запроса и не должно покидать транспорт. Неразобранное тело не теряет сбой — вызывающий
 * код передаёт его дальше как `LlmTransportException` со статусом.
 */
internal fun providerRejection(body: String, json: Json = REJECTION_JSON): ProviderRejection? {
    val root = try {
        json.parseToJsonElement(body.take(ERROR_BODY_SCAN)) as? JsonObject
    } catch (format: SerializationException) {
        null
    } catch (empty: IllegalArgumentException) {
        null
    } ?: return null
    val error = (root["error"] as? JsonObject) ?: root
    val code = token(error["code"])
    val message = text(error["message"])
    // Провайдер называет отвергнутый параметр полем `param` не всегда: DashScope пишет его
    // в сообщение. Факт один, поэтому источник не важен — важно, что это машинный токен.
    val param = token(error["param"]) ?: namedParameter(message)
    val rejection = ProviderRejection(code = code, type = token(error["type"]), param = param,
        refusal = refusal(code, param, message))
    return rejection.takeIf { it.code != null || it.type != null || it.param != null || it.refusal != ProviderRefusal.OTHER }
}

/**
 * Признак отказа. Шаблоны узкие и проверяют форму сообщения провайдера, а не его содержимое:
 * всё, что им не соответствует, остаётся [ProviderRefusal.OTHER] и показывается общим текстом.
 * Переполнение контекста важнее имени параметра — провайдеры сообщают о нём как об
 * «недопустимом параметре», а действие человека другое.
 */
private fun refusal(code: String?, param: String?, message: String?): ProviderRefusal {
    val tokens = listOfNotNull(code, param).joinToString(" ").lowercase()
    val text = message.orEmpty().lowercase()
    return when {
        CONTEXT_LENGTH.containsMatchIn(text) || "context_length_exceeded" in tokens -> ProviderRefusal.CONTEXT_LENGTH
        TOOLS.containsMatchIn(text) -> ProviderRefusal.TOOLS
        MODEL.containsMatchIn(text) || "model_not_found" in tokens -> ProviderRefusal.MODEL
        param != null || "parameter" in tokens -> ProviderRefusal.PARAMETER
        else -> ProviderRefusal.OTHER
    }
}

/** Значение принимается только машинным токеном; всё остальное отбрасывается, а не сокращается. */
private fun token(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull?.trim()
    ?.takeIf { TOKEN.matches(it) }

/**
 * Имя параметра, которое провайдер взял в кавычки. Кавычки обязательны: без них под «параметр»
 * попало бы обычное слово из сообщения, а вместе с ним и текст запроса.
 */
private fun namedParameter(message: String?): String? = message?.let { NAMED_PARAMETER.find(it) }?.let { match ->
    match.groupValues.firstOrNull { it.isNotBlank() && it != match.groupValues[0] }?.takeIf { TOKEN.matches(it) }
}

/** Текст читается только для выбора признака и ограничен; в отказ он не попадает. */
private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }
    ?.contentOrNull?.take(MESSAGE_SCAN)

private val TOKEN = Regex("[A-Za-z0-9_.:-]{1,64}")
private val NAMED_PARAMETER = Regex(
    """(?:parameter|param|field|argument)\s*(?::\s*)?['"`]([A-Za-z0-9_.:-]{1,64})['"`]|['"`]([A-Za-z0-9_.:-]{1,64})['"`]\s+is not supported""",
    RegexOption.IGNORE_CASE)
private val CONTEXT_LENGTH = Regex("maximum context length|context (?:length|window)|input (?:length|tokens?)|" +
    "too many tokens|prompt is too long|reduce the length|context_length_exceeded")
private val TOOLS = Regex("(?:tools?|functions?|function calling)[^.\\n]{0,60}(?:not supported|unsupported|not enabled|disabled|invalid)")
private val MODEL = Regex("model[^.\\n]{0,60}(?:not (?:found|exist|supported|available)|does not exist|unknown|no such)")
private const val ERROR_BODY_SCAN = 8_192
private const val MESSAGE_SCAN = 512
private val REJECTION_JSON = Json { ignoreUnknownKeys = true }
