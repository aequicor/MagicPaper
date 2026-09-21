package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ToolEvent(
    val projectId: String, val ownerSessionId: String, val requestId: String, val callId: String,
    val toolId: String, val category: ToolCategory, val phase: ToolPhase,
    val summary: String, val result: String = "",
    val title: String? = null,
    val sources: List<SearchHit> = emptyList(),
    val media: GeneratedMedia? = null,
) {
    fun codingEvent(): CodingEvent = when (phase) {
        ToolPhase.STARTED -> CodingEvent.ToolStarted(toolId, summary, callId, category == ToolCategory.EXEC, category = category, title = title, media = media)
        ToolPhase.PROGRESS, ToolPhase.WAITING -> CodingEvent.ToolProgress(toolId, callId, result, phase, media = media)
        else -> CodingEvent.ToolFinished(toolId, phase != ToolPhase.SUCCEEDED, callId, result, phase, title = title, sources = sources, media = media)
    }
}


@Serializable
data class CompletedToolCall(val id: String, val tool: String, val arguments: JsonObject, val result: JsonElement)
