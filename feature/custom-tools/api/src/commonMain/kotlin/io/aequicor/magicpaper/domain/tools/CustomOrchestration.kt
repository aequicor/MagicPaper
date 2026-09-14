package io.aequicor.magicpaper.domain.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Session ownership, durable effects and deletion remain with the session feature. */
fun interface OrchestrationActions {
    suspend fun execute(context: ToolExecutionContext, operationId: String, tool: String, arguments: JsonObject): JsonElement
}

interface CustomOrchestration {
    suspend fun execute(context: ToolExecutionContext, operationId: String, tool: String, arguments: JsonObject): JsonElement
}
