package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.tools.ToolPhase
import kotlinx.serialization.json.*

/** Persisted protocol items are evidence; agent messages and partial output deliberately are not. */
internal object CodexNativeToolResults {
    fun read(response: JsonObject, threadId: String, callIds: Set<String>): List<CodingEvent.ToolFinished> {
        val thread = response["thread"] as? JsonObject ?: return emptyList()
        if (thread.text("id") != threadId) return emptyList()
        return (thread["turns"] as? JsonArray).orEmpty().flatMap { turn ->
            ((turn as? JsonObject)?.get("items") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }.filter { it.text("id") in callIds }.mapNotNull(::terminal)
        }.groupBy { it.callId }.values.mapNotNull { results -> results.distinct().singleOrNull() }
    }

    fun terminal(item: JsonObject): CodingEvent.ToolFinished? {
        val id = item.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val status = item.text("status")
        val type = item.text("type")
        if (type !in setOf("commandExecution", "fileChange", "mcpToolCall")) return null
        val exit = (item["exitCode"] as? JsonPrimitive)?.intOrNull
        val result = item["result"] as? JsonObject
        val phase = when {
            status in setOf("failed", "declined") -> ToolPhase.FAILED
            status in setOf("cancelled", "interrupted") -> ToolPhase.CANCELLED
            status != "completed" -> return null
            type == "commandExecution" && exit == null -> return null
            exit != null && exit != 0 || result?.get("isError") == JsonPrimitive(true) -> ToolPhase.FAILED
            else -> ToolPhase.SUCCEEDED
        }
        val output = when (type) {
            "commandExecution" -> item.text("aggregatedOutput").orEmpty()
            "fileChange" -> item["changes"]?.toString().orEmpty()
            else -> result?.toString() ?: item["error"]?.toString().orEmpty()
        }
        return CodingEvent.ToolFinished(when (type) {
            "commandExecution" -> "command"
            "fileChange" -> "edit"
            else -> "${item.text("server")}:${item.text("tool")}"
        }, phase != ToolPhase.SUCCEEDED, id, output, phase)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
