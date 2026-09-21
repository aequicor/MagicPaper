package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.CodingSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The plan owner authenticates calls and reconciles its own durable effects. */
interface PlanningToolAccess {
    fun knownSecrets(): Set<String>
    suspend fun prepareWorker(session: CodingSession): ToolExecutionContext
    suspend fun checkScope(context: ToolExecutionContext, historical: Boolean = false)
    suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject)
    suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement?
    suspend fun execute(context: ToolExecutionContext, operation: String, tool: String, arguments: JsonObject): JsonElement
}

/** Aggregate authority stays independent of plan scope and applies to replayed receipts too. */
interface ToolRunAuthority {
    fun contextDefaults(context: ToolExecutionContext): ToolExecutionContext
    suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition)
    suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition)
    suspend fun unknownOutcome(context: ToolExecutionContext, receipt: ToolReceipt)
}
