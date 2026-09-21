package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LlmToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

@Serializable
data class LlmToolCall(val id: String, val name: String, val arguments: JsonObject)

@Serializable
data class LlmToolResult(val callId: String, val name: String, val content: JsonElement, val isError: Boolean = false)

/** Provider-owned continuation is opaque: preserve signed/thinking blocks byte-for-value across tool turns. */
@Serializable
data class LlmToolTurn(
    val text: String = "",
    val calls: List<LlmToolCall> = emptyList(),
    val continuation: JsonObject = JsonObject(emptyMap()),
    val provider: ProviderType? = null,
)

@Serializable
data class LlmToolExchange(val turn: LlmToolTurn, val results: List<LlmToolResult>)
