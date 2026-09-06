package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.TRUNCATED_HEADLINE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    fun parse(line: String): CodingEvent? = parseEvents(line).firstOrNull()

    /**
     * Разбирает строку протокола в события. Больше одного — финал сообщения,
     * где текст есть, но ответ обрезан: сначала текст, потом предупреждение.
     */
    fun parseEvents(line: String): List<CodingEvent> {
        val obj = parseObject(line) ?: return emptyList()
        if (obj.type() == "message_end") return parseMessageEnd(obj)
        return listOfNotNull(parseEvent(obj))
    }

    private fun parseObject(line: String): JsonObject? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()?.jsonObject
    }

    private fun parseEvent(obj: JsonObject): CodingEvent? {
        return when (obj.type()) {
            "session" -> CodingEvent.SessionStarted(sessionId = obj.primitive("id").orEmpty())
            // Начало ответа ассистента: прогон перешёл из «ждём модель» в «работает».
            "message_start" ->
                if (obj["message"]?.jsonObject?.primitive("role") == "assistant") {
                    CodingEvent.MessageStarted
                } else {
                    null
                }
            "agent_end" -> CodingEvent.AgentEnd
            "compaction_start" -> CodingEvent.Notice("Уплотняю контекст…")
            "compaction_end" -> if (obj["cancelled"] == JsonPrimitive(true) || obj["error"] != null) {
                CodingEvent.Notice("Уплотнение контекста не удалось")
            } else {
                CodingEvent.Notice("Контекст уплотнён")
            }
            "auto_retry_start" -> CodingEvent.Notice(
                "Сбой у провайдера, автоповтор №${obj.primitive("attempt") ?: "?"}…"
            )
            "auto_retry_end" -> if (obj["success"] == JsonPrimitive(true)) {
                CodingEvent.Notice("Автоповтор удался")
            } else {
                CodingEvent.Failed(obj.primitive("error") ?: "Провайер отказал после автоповторов")
            }
            "message_update" -> parseDelta(obj)
            // message_end даёт несколько событий — его разбирает parseEvents.
            "message_end" -> null
            "tool_execution_start" -> {
                val args = obj["args"] as? JsonObject
                CodingEvent.ToolStarted(
                    tool = obj.primitive("toolName") ?: "tool",
                    summary = toolSummary(args),
                    callId = obj.primitive("toolCallId").orEmpty(),
                    isExec = (obj.primitive("toolName") ?: "").lowercase() in EXEC_TOOLS,
                )
            }
            "tool_execution_update" -> CodingEvent.ToolProgress(
                tool = obj.primitive("toolName") ?: "tool",
                callId = obj.primitive("toolCallId").orEmpty(),
                resultPreview = resultPreview(obj["partialResult"]),
            )
            "tool_execution_end" -> CodingEvent.ToolFinished(
                tool = obj.primitive("toolName") ?: "tool",
                isError = (obj["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
                callId = obj.primitive("toolCallId").orEmpty(),
                resultPreview = resultPreview(obj["result"]),
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

    private fun parseMessageEnd(obj: JsonObject): List<CodingEvent> {
        val message = obj["message"]?.jsonObject ?: return emptyList()
        if (message.primitive("role") != "assistant") return emptyList()
        val stopReason = message.primitive("stopReason").orEmpty()
        if (stopReason == "error") {
            return listOf(CodingEvent.Failed(message.primitive("errorMessage") ?: "агент вернул ошибку"))
        }
        val blocks = message["content"]?.jsonArray
        val text = blocks
            ?.mapNotNull { block ->
                val blockObj = runCatching { block.jsonObject }.getOrNull() ?: return@mapNotNull null
                if (blockObj.type() == "text") blockObj.primitive("text") else null
            }
            ?.joinToString("")
            .orEmpty()
        // Намерение модели видно и по tool-вызовам: «пустой» ход с правкой файла —
        // не пустой ход, автопродолжать его не за чем.
        val hasToolCalls = blocks?.any { block ->
            val type = runCatching { block.jsonObject }.getOrNull()?.type()
            type == "toolCall" || type == "tool_use" || type == "tool_call"
        } ?: false
        val truncated = stopReason == "length"
        return when {
            // Потолок вывода сгорел, не дойдя до тела сообщения (обычно — в
            // рассуждении). Раньше причина терялась здесь, и прогон выглядел как
            // «Агент завершился без ответа».
            truncated && text.isBlank() && !hasToolCalls -> {
                val usage = message["usage"]?.jsonObject
                listOf(
                    CodingEvent.OutputTruncated(
                        outputTokens = usage?.int("output"),
                        reasoningTokens = usage?.int("reasoning"),
                    ),
                )
            }
            // Обрезанный, но содержательный ответ не должен выглядеть полным.
            truncated && text.isNotBlank() ->
                listOf(CodingEvent.FinalText(text), CodingEvent.Notice(TRUNCATED_HEADLINE))
            text.isBlank() -> emptyList()
            else -> listOf(CodingEvent.FinalText(text))
        }
    }

    /** Короткое описание аргументов инструмента для журнала: путь или команда. */
    private fun toolSummary(args: JsonObject?): String {
        if (args == null) return ""
        return args.primitive("path")
            ?: args.primitive("command")
            ?: args.primitive("pattern")
            ?: ""
    }

    /**
     * Начало вывода инструмента для показа в ленте: у пи результат — объект с
     * content-блоками (текст) либо массив строк; всё остальное — краткая JSON-сводка.
     */
    private fun resultPreview(element: JsonElement?): String {
        if (element == null || element is JsonNull) return ""
        val text = when (element) {
            is JsonObject -> blocksText(element["content"])
                ?: element.primitive("text")
                ?: element.primitive("errorMessage")
            is JsonArray -> blocksText(element)
            is JsonPrimitive -> if (element.isString) element.contentOrNull else null
            else -> null
        }
            ?: runCatching { element.toString() }.getOrDefault("")
        return text.trim().take(MAX_PREVIEW).let {
            if (text.trim().length > MAX_PREVIEW) "$it…" else it
        }
    }

    /** Склеивает text-блоки content[] (тот же формат, что у сообщения ассистента). */
    private fun blocksText(element: JsonElement?): String? {
        val array = element as? JsonArray ?: return null
        val parts = array.mapNotNull { block ->
            val obj = runCatching { block.jsonObject }.getOrNull()
            if (obj != null && obj.type() == "text") obj.primitive("text") else null
        }
        val joined = parts.joinToString("\n")
        return joined.ifBlank { null }
    }

    private fun JsonObject.type(): String? = primitive("type")

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Числовое поле usage (токены); отсутствующее или не-число — null. */
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private val EXEC_TOOLS: Set<String> = setOf("bash", "exec", "shell", "run")

    private const val MAX_PREVIEW = 2000
}
