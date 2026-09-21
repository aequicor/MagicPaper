package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

internal object PiProviderProtocol {
    private val emptyUsage = buildJsonObject {
        listOf("input", "output", "cacheRead", "cacheWrite", "totalTokens").forEach { put(it, 0) }
        putJsonObject("cost") { listOf("input", "output", "cacheRead", "cacheWrite", "total").forEach { put(it, 0) } }
    }

    fun request(request: PiProviderTurnRequest): JsonObject {
        val profile = request.profile
        require(profile.provider == ProviderType.OPENAI_SUBSCRIPTION) { "Subscription provider required" }
        require(request.tools.map { it.name }.distinct().size == request.tools.size) { "Duplicate provider tool definition" }
        val definition = PiModelsConfig.root(profile, imageInput = request.messages.any { m -> m.attachments.any { it.kind == AttachmentKind.IMAGE } })
            .getValue("providers").jsonObject.getValue(PiModelsConfig.PROVIDER_ID).jsonObject
        val model = JsonObject(definition.getValue("models").jsonArray.first().jsonObject + mapOf(
            // pi-ai preserves Responses call_id|item_id pairs only for its native provider identity.
            "api" to JsonPrimitive("openai-codex-responses"), "provider" to JsonPrimitive("openai-codex"),
            "baseUrl" to definition.getValue("baseUrl"), "maxTokens" to JsonPrimitive(profile.advanced.safeMaxTokens),
            "input" to buildJsonArray { add("text"); if (request.messages.any { m -> m.attachments.any { it.kind == AttachmentKind.IMAGE } }) add("image") },
            "cost" to emptyUsage.getValue("cost"),
        ))
        return buildJsonObject {
            put("model", model)
            put("libraryPath", request.libraryPath)
            put("accessToken", request.accessToken)
            if (!profile.effortSelectionFor().isDefault) PiModelsConfig.thinkingLevel(profile)?.let { put("reasoning", it) }
            putJsonObject("context") {
                put("systemPrompt", request.messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content })
                putJsonArray("tools") { request.tools.forEach { tool -> add(buildJsonObject {
                    put("name", tool.name); put("description", tool.description); put("parameters", tool.inputSchema)
                }) } }
                putJsonArray("messages") {
                    request.messages.filterNot { it.role == LlmChatRole.SYSTEM }.forEach { message ->
                        add(buildJsonObject {
                            put("role", if (message.role == LlmChatRole.ASSISTANT) "assistant" else "user")
                            putJsonArray("content") {
                                add(text(message.content))
                                message.attachments.forEach { attachment -> when (attachment.kind) {
                                    AttachmentKind.IMAGE -> add(buildJsonObject {
                                        put("type", "image"); put("mimeType", attachment.mimeType); put("data", attachment.dataBase64)
                                    })
                                    AttachmentKind.TEXT -> add(text("${attachment.name}\n${attachment.decodeText()}"))
                                    AttachmentKind.FILE -> Unit
                                } }
                            }
                            put("timestamp", 0)
                            if (message.role == LlmChatRole.ASSISTANT) {
                                put("api", "openai-codex-responses"); put("provider", "openai-codex"); put("model", profile.modelId)
                                put("stopReason", "stop"); put("usage", emptyUsage)
                            }
                        })
                    }
                    request.exchanges.forEach { exchange ->
                        require(exchange.turn.provider == ProviderType.OPENAI_SUBSCRIPTION) { "Wrong continuation provider" }
                        val assistant = exchange.turn.continuation["assistant"] as? JsonObject
                            ?: error("Provider continuation missing")
                        require(calls(assistant) == exchange.turn.calls) { "Provider continuation calls changed" }
                        require(exchange.results.map { it.callId }.toSet() == exchange.turn.calls.map { it.id }.toSet() &&
                            exchange.results.size == exchange.turn.calls.size) { "Provider results do not match calls" }
                        add(assistant) // Includes signed thinking and all opaque provider fields without reconstruction.
                        exchange.results.forEach { result ->
                            require(exchange.turn.calls.single { it.id == result.callId }.name == result.name) { "Provider result tool changed" }
                            add(buildJsonObject {
                                put("role", "toolResult"); put("toolCallId", result.callId); put("toolName", result.name)
                                put("isError", result.isError); put("timestamp", 0)
                                putJsonArray("content") { add(text((result.content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: result.content.toString())) }
                            })
                        }
                    }
                }
            }
        }
    }

    fun result(assistant: JsonObject, allowed: Set<String>): LlmToolTurn {
        check(assistant["role"]?.jsonPrimitive?.content == "assistant") { "Provider assistant missing" }
        val calls = calls(assistant)
        check(calls.map { it.id }.distinct().size == calls.size && calls.all { it.id.isNotBlank() && it.name in allowed }) { "Provider tool identity invalid" }
        val stop = assistant["stopReason"]?.jsonPrimitive?.content
        check(stop in setOf("stop", "toolUse")) { "Provider turn incomplete" }
        check(stop != "toolUse" || calls.isNotEmpty()) { "Provider tool calls missing" }
        val content = assistant.getValue("content").jsonArray.map { it.jsonObject }
        val text = content.filter { it["type"]?.jsonPrimitive?.content == "text" }.joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        check(text.isNotBlank() || calls.isNotEmpty()) { "Provider answer empty" }
        return LlmToolTurn(text, calls, buildJsonObject { put("assistant", assistant) }, ProviderType.OPENAI_SUBSCRIPTION)
    }

    private fun calls(assistant: JsonObject): List<LlmToolCall> = assistant.getValue("content").jsonArray.map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "toolCall" }.map {
            LlmToolCall(it.getValue("id").jsonPrimitive.content, it.getValue("name").jsonPrimitive.content,
                it["arguments"] as? JsonObject ?: error("Provider tool arguments incomplete"))
        }

    private fun text(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
}
