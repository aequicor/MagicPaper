package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.CodingInteractionMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Rechecks mode at invocation, including calls from stale native tool lists. */
class DefaultCustomOrchestration(private val actions: OrchestrationActions) : CustomOrchestration {
    override suspend fun execute(context: ToolExecutionContext, operationId: String, tool: String, arguments: JsonObject): JsonElement {
        require(context.mode == CodingInteractionMode.PLANNING && !context.auxiliaryExecution) {
            "Оркестрация доступна только в сессии планирования"
        }
        require(SessionToolCatalog.definitions.any { it.id == tool && it.orchestration }) { "Неизвестный инструмент оркестрации" }
        return actions.execute(context, operationId, tool, arguments)
    }
}
