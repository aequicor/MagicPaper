package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** REST tool dialects. Opaque assistant content is retained, including provider reasoning signatures. */
internal object LlmToolWire {
    fun openAiPayload(base: JsonObject, tools: List<LlmToolDefinition>, exchanges: List<LlmToolExchange>, provider: ProviderType, json: Json): JsonObject {
        validate(tools, exchanges, provider)
        val messages = base.array("messages").toMutableList()
        exchanges.forEach { exchange ->
            val assistant = exchange.turn.continuation
            verifyCalls(exchange, openAiMessage(assistant, provider, json))
            messages += assistant
            orderedResults(exchange).forEach { result -> messages += buildJsonObject {
                put("role", "tool"); put("tool_call_id", result.callId)
                put("content", if (result.isError) buildJsonObject { put("isError", true); put("content", result.content) }.toString() else result.content.resultText())
            } }
        }
        return JsonObject(base + buildMap {
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools.map { tool -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", tool.name); put("description", tool.description); put("parameters", tool.inputSchema) })
            } }))
        })
    }

    fun openAiResponse(body: String, provider: ProviderType, json: Json): LlmToolTurn {
        val choice = parseObject(body, json).array("choices").firstOrNull().objectValue("missing_choice")
        if (choice.string("finish_reason") in setOf("length", "content_filter")) fail("incomplete_turn")
        val turn = openAiMessage(choice["message"].objectValue("missing_message"), provider, json)
        if (choice.string("finish_reason") == "tool_calls" && turn.calls.isEmpty()) fail("missing_calls")
        return turn
    }

    private fun openAiMessage(message: JsonObject, provider: ProviderType, json: Json): LlmToolTurn {
        if (message.string("role")?.let { it != "assistant" } == true) fail("invalid_assistant_role")
        if (message["function_call"] != null && message["function_call"] != JsonNull) fail("unsupported_legacy_call")
        val calls = message.optionalArray("tool_calls").map { element ->
            val call = element.objectValue("invalid_call")
            if (call.string("type") != "function") fail("unsupported_call_type")
            val function = call["function"].objectValue("missing_function")
            LlmToolCall(call.requiredString("id"), function.requiredString("name"), parseObject(function.requiredString("arguments"), json))
        }
        val text = when (val content = message["content"]) {
            null, JsonNull -> message.string("refusal").orEmpty()
            is JsonPrimitive -> content.takeIf { it.isString }?.content ?: fail("invalid_text")
            is JsonArray -> content.joinToString("") { part ->
                val block = part.objectValue("invalid_content")
                if (block.string("type") != "text") fail("unsupported_content")
                block.string("text") ?: fail("invalid_text")
            }
            else -> fail("invalid_content")
        }
        return checkedTurn(text, calls, JsonObject(message + ("role" to JsonPrimitive("assistant"))), provider)
    }

    fun anthropicPayload(base: JsonObject, tools: List<LlmToolDefinition>, exchanges: List<LlmToolExchange>, provider: ProviderType): JsonObject {
        validate(tools, exchanges, provider)
        val messages = base.array("messages").toMutableList()
        exchanges.forEach { exchange ->
            verifyCalls(exchange, anthropicMessage(exchange.turn.continuation, provider))
            messages += exchange.turn.continuation
            messages += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(orderedResults(exchange).map { result -> buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", result.callId)
                    put("content", result.content.resultText())
                    if (result.isError) put("is_error", true)
                } }))
            }
        }
        return JsonObject(base + buildMap {
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) put("tools", JsonArray(tools.map { tool -> buildJsonObject {
                put("name", tool.name); put("description", tool.description); put("input_schema", tool.inputSchema)
            } }))
        })
    }

    fun anthropicResponse(body: String, provider: ProviderType, json: Json): LlmToolTurn {
        val root = parseObject(body, json)
        if (root.string("stop_reason") in setOf("max_tokens", "model_context_window_exceeded", "pause_turn")) fail("incomplete_turn")
        val message = buildJsonObject { put("role", "assistant"); put("content", root.array("content")) }
        val turn = anthropicMessage(message, provider)
        if (root.string("stop_reason") == "tool_use" && turn.calls.isEmpty()) fail("missing_calls")
        return turn
    }

    private fun anthropicMessage(message: JsonObject, provider: ProviderType): LlmToolTurn {
        if (message.string("role") != "assistant") fail("invalid_assistant_role")
        val calls = mutableListOf<LlmToolCall>()
        val text = StringBuilder()
        message.array("content").forEach { element ->
            val block = element.objectValue("invalid_content")
            when (block.string("type")) {
                "text" -> text.append(block.string("text") ?: fail("invalid_text"))
                "tool_use" -> calls += LlmToolCall(block.requiredString("id"), block.requiredString("name"), block["input"].objectValue("invalid_arguments"))
                "thinking", "redacted_thinking" -> Unit // Opaque blocks must round-trip unchanged, never become answer text.
                else -> fail("unsupported_content")
            }
        }
        return checkedTurn(text.toString(), calls, message, provider)
    }

    fun googlePayload(base: JsonObject, tools: List<LlmToolDefinition>, exchanges: List<LlmToolExchange>, provider: ProviderType): JsonObject {
        validate(tools, exchanges, provider)
        val contents = base.array("contents").toMutableList()
        exchanges.forEachIndexed { turnIndex, exchange ->
            val message = exchange.turn.continuation
            verifyCalls(exchange, googleContent(message, provider, turnIndex))
            contents += message
            val originalCalls = message.array("parts").mapNotNull { (it as? JsonObject)?.get("functionCall") as? JsonObject }
            contents += buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(orderedResults(exchange).mapIndexed { callIndex, result -> buildJsonObject {
                    put("functionResponse", buildJsonObject {
                        put("name", result.name)
                        // Synthesized local correlation IDs must not be invented on the wire.
                        originalCalls[callIndex].string("id")?.let { put("id", it) }
                        put("response", when {
                            result.isError -> buildJsonObject { put("error", result.content) }
                            result.content is JsonObject -> result.content
                            else -> buildJsonObject { put("result", result.content) }
                        })
                    })
                } }))
            }
        }
        return JsonObject(base + buildMap {
            put("contents", JsonArray(contents))
            if (tools.isNotEmpty()) put("tools", buildJsonArray { add(buildJsonObject {
                put("functionDeclarations", JsonArray(tools.map { tool -> buildJsonObject {
                    put("name", tool.name); put("description", tool.description); put("parametersJsonSchema", tool.inputSchema)
                } }))
            }) })
        })
    }

    fun googleResponse(body: String, provider: ProviderType, json: Json, turnIndex: Int): LlmToolTurn {
        val candidate = parseObject(body, json).array("candidates").firstOrNull().objectValue("missing_candidate")
        val finish = candidate.string("finishReason")
        if (finish != null && finish != "STOP") fail("incomplete_turn")
        return googleContent(candidate["content"].objectValue("missing_content"), provider, turnIndex)
    }

    private fun googleContent(content: JsonObject, provider: ProviderType, turnIndex: Int): LlmToolTurn {
        if (content.string("role")?.let { it != "model" } == true) fail("invalid_assistant_role")
        val calls = mutableListOf<LlmToolCall>()
        val text = StringBuilder()
        content.array("parts").forEach { element ->
            val part = element.objectValue("invalid_content")
            if (part["executableCode"] != null || part["codeExecutionResult"] != null) fail("unsupported_call_type")
            part["functionCall"]?.let { raw ->
                val call = raw.objectValue("invalid_call")
                val id = if ("id" in call) call.requiredString("id") else "google-$turnIndex-${calls.size}"
                val args = if ("args" in call) call["args"].objectValue("invalid_arguments") else JsonObject(emptyMap())
                calls += LlmToolCall(id, call.requiredString("name"), args)
            }
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull != true) part.string("text")?.let(text::append)
        }
        return checkedTurn(text.toString(), calls, JsonObject(content + ("role" to JsonPrimitive("model"))), provider)
    }

    private fun validate(tools: List<LlmToolDefinition>, exchanges: List<LlmToolExchange>, provider: ProviderType) {
        require(tools.all { it.name.isNotBlank() } && tools.map { it.name }.distinct().size == tools.size) { "Invalid tool catalog" }
        exchanges.forEach { exchange ->
            require(exchange.turn.provider == provider) { "Tool continuation belongs to another provider" }
            val calls = exchange.turn.calls
            require(calls.isNotEmpty() && calls.map { it.id }.distinct().size == calls.size) { "Invalid tool continuation identities" }
            require(exchange.results.map { it.callId }.toSet() == calls.map { it.id }.toSet() && exchange.results.size == calls.size) { "Every call requires exactly one result" }
            val byId = calls.associateBy { it.id }
            require(exchange.results.all { byId.getValue(it.callId).name == it.name }) { "Tool result name does not match its call" }
        }
    }

    private fun verifyCalls(exchange: LlmToolExchange, parsed: LlmToolTurn) {
        require(exchange.turn.calls == parsed.calls) { "Tool continuation does not match its calls" }
    }
    private fun orderedResults(exchange: LlmToolExchange): List<LlmToolResult> {
        val results = exchange.results.associateBy { it.callId }
        return exchange.turn.calls.map { results.getValue(it.id) }
    }
    private fun checkedTurn(text: String, calls: List<LlmToolCall>, continuation: JsonObject, provider: ProviderType): LlmToolTurn {
        if (text.isBlank() && calls.isEmpty()) fail("empty_turn")
        if (calls.map { it.id }.distinct().size != calls.size) fail("duplicate_call_identity")
        return LlmToolTurn(text, calls, continuation, provider)
    }
    private fun parseObject(raw: String, json: Json): JsonObject = try { json.parseToJsonElement(raw).objectValue("invalid_json_object") }
        catch (failure: SerializationException) { throw LlmToolProtocolException("invalid_json", failure) }
    private fun JsonElement?.objectValue(reason: String) = this as? JsonObject ?: fail(reason)
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.requiredString(key: String) = string(key)?.takeIf { it.isNotBlank() } ?: fail("missing_$key")
    private fun JsonObject.array(key: String) = get(key) as? JsonArray ?: fail("missing_$key")
    private fun JsonObject.optionalArray(key: String) = when (val value = get(key)) { null, JsonNull -> JsonArray(emptyList()); is JsonArray -> value; else -> fail("invalid_$key") }
    private fun JsonElement.resultText() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()
    private fun fail(reason: String): Nothing = throw LlmToolProtocolException(reason)
}
