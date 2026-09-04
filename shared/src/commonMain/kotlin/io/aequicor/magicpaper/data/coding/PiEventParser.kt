package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Парсер протокола пи-агента (--mode json: поток событий, по одному JSON на строку).
 * Общий код: превращает сырые строки в доменные [CodingEvent], не зная ни о процессах,
 * ни о платформах (инверсия зависимостей: рантаймы поставляют строки, домен решает).
 */
object PiEventParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Разбирает одну строку протокола. Нераспознанные и повреждённые строки дают null. */
    fun parse(line: String): CodingEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()?.jsonObject ?: return null
        return when (obj.type()) {
            "session" -> CodingEvent.SessionStarted(sessionId = obj.primitive("id").orEmpty())
            "message_update" -> parseDelta(obj)
            "message_end" -> parseMessageEnd(obj)
            "tool_execution_start" -> CodingEvent.ToolStarted(
                tool = obj.primitive("toolName") ?: "tool",
                summary = toolSummary(obj["args"] as? JsonObject),
            )
            "tool_execution_end" -> CodingEvent.ToolFinished(
                tool = obj.primitive("toolName") ?: "tool",
                isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            else -> null
        }
    }

    private fun parseDelta(obj: JsonObject): CodingEvent? {
        val event = obj["assistantMessageEvent"]?.jsonObject ?: return null
        if (event.type() != "text_delta") return null
        val delta = event.primitive("delta").orEmpty()
        return if (delta.isEmpty()) null else CodingEvent.TextDelta(delta)
    }

    private fun parseMessageEnd(obj: JsonObject): CodingEvent? {
        val message = obj["message"]?.jsonObject ?: return null
        if (message.primitive("role") != "assistant") return null
        val stopReason = message.primitive("stopReason").orEmpty()
        if (stopReason == "error") {
            return CodingEvent.Failed(message.primitive("errorMessage") ?: "агент вернул ошибку")
        }
        val text = message["content"]?.jsonArray
            ?.mapNotNull { block ->
                val blockObj = runCatching { block.jsonObject }.getOrNull() ?: return@mapNotNull null
                if (blockObj.type() == "text") blockObj.primitive("text") else null
            }
            ?.joinToString("")
            .orEmpty()
        return if (text.isBlank()) null else CodingEvent.FinalText(text)
    }

    /** Короткое описание аргументов инструмента для журнала: путь или команда. */
    private fun toolSummary(args: JsonObject?): String {
        if (args == null) return ""
        return args.primitive("path")
            ?: args.primitive("command")
            ?: args.primitive("pattern")
            ?: ""
    }

    private fun JsonObject.type(): String? = primitive("type")

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
