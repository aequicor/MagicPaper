package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ToolReceipt(val id: String, val toolId: String, val arguments: JsonObject,
    val phase: ToolPhase = ToolPhase.STARTED, val result: JsonElement = JsonNull, val operationId: String = "",
    val argumentFingerprint: String = "", val error: String = "", val recordedAt: Long = 0, val updatedAt: Long = 0,
    val runtimeGeneration: Long = 0,
    val argumentsComplete: Boolean = true, val resultComplete: Boolean = true, val native: Boolean = false,
    val summary: String = "", val mutating: Boolean = true, val title: String? = null,
)
