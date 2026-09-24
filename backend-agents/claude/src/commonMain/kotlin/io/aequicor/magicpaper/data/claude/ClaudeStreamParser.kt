package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeToolPresentation
import io.aequicor.magicpaper.backend.NativeToolPresentationResolver
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * Claude Code `--output-format stream-json`: one JSON object per line. The parser follows one stream, so it is
 * stateful: it pairs a tool result with the call that started it and reports each model call's usage once,
 * whether the call arrived as partial stream events or only as a complete assistant message.
 * It never sees a process, a prompt or a credential; unknown and damaged lines produce nothing.
 *
 * [contextWindow] is the window expected for the model; the CLI's own figure in the result's `modelUsage`
 * replaces it, since only the CLI knows what the account and the connection allow.
 */
internal class ClaudeStreamParser(
    private val presentation: NativeToolPresentationResolver = NativeToolPresentationResolver { server, tool, _ ->
        NativeToolPresentation("$server:$tool", tool)
    },
    private var contextWindow: Long? = null,
    private val now: () -> Long = io.aequicor.magicpaper.util.Id::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = mutableMapOf<String, String>()
    private val reported = mutableSetOf<String>()
    private var current: Call? = null
    private var sessionAnnounced = false
    private var terminal: Terminal? = null
    private var model: String? = null
    private var contextUsed: Long? = null

    /** Set once a `result` line was parsed: the only proof the engine finished, however the process exits. */
    val result: Terminal? get() = terminal

    /** [signedOut] is a failure of authentication, which only the host can word fully: it knows the executable's path. */
    class Terminal(val failed: Boolean, val text: String, val message: String?, val signedOut: Boolean = false)

    private class Call(val id: String, var input: TokenUsage) {
        var output: Long? = null
    }

    fun parse(line: String): List<CodingEvent> {
        val text = line.trim()
        if (!text.startsWith("{")) return emptyList()
        val event = try { json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null } ?: return emptyList()
        val nested = event["parent_tool_use_id"].let { it != null && it !is JsonNull }
        return when (event.string("type")) {
            "system" -> system(event)
            "stream_event" -> if (nested) emptyList() else stream(event["event"] as? JsonObject ?: return emptyList())
            "assistant" -> assistant(event, nested)
            "user" -> toolResults(event)
            "result" -> result(event)
            "rate_limit_event" -> planUsage(event["rate_limit_info"] as? JsonObject ?: return emptyList())
            else -> emptyList()
        }
    }

    /**
     * The documented fields describe only the window that currently binds (`rateLimitType`, `utilization`); the
     * CLI's `unifiedWindows` map carries every window and is preferred when present.
     */
    private fun planUsage(info: JsonObject): List<CodingEvent> {
        val status = info.string("status") ?: return emptyList()
        val windows = (info["unifiedWindows"] as? JsonObject)?.mapNotNull { (id, window) -> planWindow(id, window as? JsonObject) }
            ?: listOfNotNull(info.string("rateLimitType")?.let { planWindow(it, info) })
        return listOf(CodingEvent.PlanUsageObserved(PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, windows,
            limited = status == "rejected", observedAt = now())))
    }

    private fun planWindow(id: String, source: JsonObject?): PlanUsageWindow? {
        val (duration, scope) = CLAUDE_WINDOWS[id] ?: return null
        val utilization = (source?.get("utilization") as? JsonPrimitive)?.doubleOrNull ?: return null
        return PlanUsageWindow(id, utilization.toFloat().coerceIn(0f, 1f), duration, source.count("resetsAt"), scope)
    }

    private fun system(event: JsonObject): List<CodingEvent> = when (event.string("subtype")) {
        "init" -> {
            model = event.string("model")?.takeIf { it.isNotBlank() }
            buildList {
                event.string("session_id")?.takeIf { it.isNotBlank() && !sessionAnnounced }
                    ?.let { sessionAnnounced = true; add(CodingEvent.SessionStarted(it)) }
                // The window is known before the first answer, so the meter never shows a limit the CLI does not use.
                contextWindow?.let { add(CodingEvent.ContextUpdated(null, it)) }
            }
        }
        "status" -> if (event.string("status") == "compacting")
            listOf(CodingEvent.Compaction(CompactionStatus("", CompactionPhase.STARTED, "auto"))) else emptyList()
        "compact_boundary" -> listOf(CodingEvent.Compaction(CompactionStatus("", CompactionPhase.COMPLETED,
            (event["compact_metadata"] as? JsonObject)?.string("trigger").orEmpty())))
        "api_retry" -> listOf(CodingEvent.Notice(buildString {
            append("Сбой у провайдера, автоповтор №").append(event.string("attempt") ?: "?")
            event.string("max_retries")?.let { append(" из ").append(it) }
            append("…")
        }))
        else -> emptyList()
    }

    private fun stream(event: JsonObject): List<CodingEvent> = when (event.string("type")) {
        "message_start" -> {
            val message = event["message"] as? JsonObject
            current = message?.string("id")?.takeIf { it.isNotBlank() }?.let { Call(it, message["usage"].tokens()) }
            listOf(CodingEvent.MessageStarted)
        }
        "content_block_delta" -> {
            val delta = event["delta"] as? JsonObject
            when (delta?.string("type")) {
                "text_delta" -> delta.string("text")?.takeIf { it.isNotEmpty() }?.let { listOf(CodingEvent.TextDelta(it)) }.orEmpty()
                "thinking_delta" -> delta.string("thinking")?.takeIf { it.isNotEmpty() }?.let { listOf(CodingEvent.ThinkingDelta(it)) }.orEmpty()
                else -> emptyList()
            }
        }
        "message_delta" -> {
            current?.output = (event["usage"] as? JsonObject)?.count("output_tokens") ?: current?.output
            emptyList()
        }
        "message_stop" -> current?.let { call ->
            current = null
            usage("message:${call.id}", call.input.copy(output = call.output ?: call.input.output))
        }.orEmpty()
        else -> emptyList()
    }

    private fun assistant(event: JsonObject, nested: Boolean): List<CodingEvent> {
        val message = event["message"] as? JsonObject ?: return emptyList()
        if (event.string("error") == "authentication_failed") return emptyList()
        val blocks = (message["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val metrics = message.string("id")?.takeIf { !nested }?.let { usage("message:$it", message["usage"].tokens()) }.orEmpty()
        val thinking = blocks.filter { it.string("type") == "thinking" }.mapNotNull { it.string("thinking")?.takeIf(String::isNotBlank) }
        val text = blocks.filter { it.string("type") == "text" }.mapNotNull { it.string("text") }.joinToString("")
        val calls = blocks.filter { it.string("type") == "tool_use" }
        val truncated = message.string("stop_reason") == "max_tokens"
        return buildList {
            addAll(metrics)
            if (!nested && thinking.isNotEmpty()) add(CodingEvent.FinalThinking(thinking.joinToString("\n\n")))
            if (!nested) when {
                truncated && text.isBlank() && calls.isEmpty() -> add(CodingEvent.OutputTruncated(
                    (message["usage"] as? JsonObject)?.count("output_tokens")?.toInt()))
                text.isBlank() -> Unit
                else -> { add(CodingEvent.FinalText(text)); if (truncated) add(CodingEvent.Notice(TRUNCATED_HEADLINE)) }
            }
            calls.forEach { call -> toolStarted(call)?.let(::add) }
        }
    }

    private fun toolStarted(call: JsonObject): CodingEvent.ToolStarted? {
        val id = call.string("id") ?: return null
        val name = call.string("name") ?: return null
        val input = call["input"] as? JsonObject
        val shown = mcp(name)?.let { (server, tool) -> presentation.resolve(server, tool, input) }
            ?: NativeToolPresentation(name, summary(input))
        tools[id] = shown.name
        return CodingEvent.ToolStarted(shown.name, shown.summary, id, isExec = name in EXEC_TOOLS)
    }

    private fun toolResults(event: JsonObject): List<CodingEvent> {
        val content = (event["message"] as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { block ->
            val result = block as? JsonObject ?: return@mapNotNull null
            if (result.string("type") != "tool_result") return@mapNotNull null
            val id = result.string("tool_use_id").orEmpty()
            CodingEvent.ToolFinished(tools.remove(id) ?: "tool", (result["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
                id, preview(result["content"]))
        }
    }

    private fun result(event: JsonObject): List<CodingEvent> {
        val failed = (event["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
        val text = event.string("result").orEmpty()
        val message = if (failed) failure(event, text) else null
        terminal = Terminal(failed, text, message, signedOut = failed && isSignedOut(text))
        val window = reportedWindow(event["modelUsage"] as? JsonObject)?.takeIf { it != contextWindow }
        return buildList {
            window?.let { contextWindow = it; add(CodingEvent.ContextUpdated(contextUsed, it)) }
            add(CodingEvent.AgentEnd)
        }
    }

    /**
     * The window of the conversation's own model; a subagent's model is counted in `modelUsage` too, so another
     * model's entry is taken only when the conversation's model is absent from it.
     */
    private fun reportedWindow(usage: JsonObject?): Long? {
        val windows = usage.orEmpty().mapNotNull { (id, entry) ->
            (entry as? JsonObject)?.count("contextWindow")?.takeIf { it > 0 }?.let { id to it }
        }
        val own = model?.let { name -> windows.firstOrNull { it.first == name } ?: windows.firstOrNull { it.first == name.substringBefore('[') } }
        return (own ?: windows.maxByOrNull { it.second })?.second
    }

    /** Safe one-line reason: the sign-in advice for a missing login, the CLI's own short text otherwise. */
    private fun failure(event: JsonObject, text: String): String = when {
        isSignedOut(text) -> "Claude Code не авторизован. Выполните вход или укажите ключ API в подключении Anthropic."
        event.string("subtype") == "error_max_turns" -> "Claude Code остановился: достигнут предел шагов агента."
        event.string("subtype") == "error_max_budget_usd" -> "Claude Code остановился: достигнут предел расхода."
        else -> text.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
            ?.let { if (it.length <= REASON_LIMIT) it else it.take(REASON_LIMIT) + "…" }
            ?: "Claude Code завершил запрос с ошибкой без описания."
    }

    /** The CLI words a dead credential several ways: a missing login, a refused key, an expired OAuth token. */
    private fun isSignedOut(text: String) = listOf("Not logged in", "/login", "Invalid API key",
        "Failed to authenticate", "OAuth access token", "Re-authenticate", "API Error: 401")
        .any { text.contains(it, ignoreCase = true) }

    private fun usage(source: String, tokens: TokenUsage): List<CodingEvent> {
        if (tokens.totalTokens.let { it == null || it == 0L } || !reported.add(source)) return emptyList()
        val used = (tokens.input ?: 0) + (tokens.cacheRead ?: 0) + (tokens.cacheWrite ?: 0)
        contextUsed = used
        return listOf(CodingEvent.UsageObserved(tokens, source), CodingEvent.ContextUpdated(used, contextWindow))
    }

    private fun JsonElement?.tokens(): TokenUsage {
        val usage = this as? JsonObject ?: return TokenUsage()
        return TokenUsage(usage.count("input_tokens"), usage.count("output_tokens"), usage.count("cache_read_input_tokens"),
            usage.count("cache_creation_input_tokens"))
    }

    private fun preview(content: JsonElement?): String {
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.string("type") == "text" }?.string("text") }.joinToString("\n")
            else -> ""
        }.trim()
        return if (text.length > MAX_PREVIEW) text.take(MAX_PREVIEW) + "…" else text
    }

    /** `mcp__server__tool`; a server name never contains a double underscore, a tool name may. */
    private fun mcp(name: String): Pair<String, String>? {
        if (!name.startsWith("mcp__")) return null
        val rest = name.removePrefix("mcp__")
        val split = rest.indexOf("__").takeIf { it > 0 } ?: return null
        return rest.substring(0, split) to rest.substring(split + 2)
    }

    private fun summary(input: JsonObject?): String = input?.let {
        it.string("file_path") ?: it.string("command") ?: it.string("pattern") ?: it.string("path") ?: it.string("url")
            ?: it.string("query") ?: it.string("description")
    }.orEmpty().take(MAX_PREVIEW)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.count(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }

    private companion object {
        val EXEC_TOOLS = setOf("Bash", "BashOutput", "KillShell", "PowerShell")
        const val REASON_LIMIT = 400
        const val MAX_PREVIEW = 2000
        const val WEEK_MINUTES = 7 * 24 * 60L
        /** Plan windows in minutes and the model family they are restricted to; overage accounting is no allowance. */
        val CLAUDE_WINDOWS: Map<String, Pair<Long, String?>> = mapOf("five_hour" to (5 * 60L to null),
            "seven_day" to (WEEK_MINUTES to null), "seven_day_opus" to (WEEK_MINUTES to "Opus"),
            "seven_day_sonnet" to (WEEK_MINUTES to "Sonnet"))
    }
}
