package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Composition root binds receivers; every invocation gets a fresh, application-owned scope. */
class ToolHost(val receipts: ToolReceiptStore) {
    val questions = RuntimeQuestionnaires()
    var checkScope: suspend (ToolExecutionContext) -> Unit = {}
    var checkReplayScope: suspend (ToolExecutionContext) -> Unit = { checkScope(it) }
    var reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null }
    var receiver: suspend (ToolExecutionContext, String, String, JsonObject) -> JsonElement = { _, _, _, _ -> error("Инструменты приложения не подключены") }
    var prepareWorker: suspend (CodingSession) -> ToolExecutionContext = { ToolExecutionContext.worker(it) }
    var search: (suspend (ToolExecutionContext, String) -> JsonElement)? = null
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    fun session(context: ToolExecutionContext, overrides: Map<String, suspend (ToolExecutionContext, String, JsonObject) -> JsonElement> = emptyMap()): ToolSession {
        val commands = ToolCatalog.definitions.filter { !it.native && (it.id != "web.search" || search != null) }.map { definition ->
            JsonToolCommand(definition) { ctx, id, args ->
                val override = overrides[definition.id]
                if (override != null) override(ctx, id, args)
                else when (definition.id) {
                    "questionnaire" -> {
                        val request = json.decodeFromJsonElement<ToolQuestions>(args)
                        require(request.questions.validQuestions() && request.questions.isNotEmpty()) { "Некорректные вопросы" }
                        val safe = request.questions.map { it.copy(secret = false, allowCustomInput = true, canSkip = true,
                            options = it.options.map { o -> o.copy(enabled = true) }) }
                        val answers = questions.ask(UserInteractionRequest("tool:$id", ctx.projectId, ctx.ownerSessionId,
                            InteractionKind.RUNTIME, safe, ownerSessionId = ctx.ownerSessionId, createdAt = Id.now()))
                        json.encodeToJsonElement(answers)
                    }
                    "web.search" -> search!!(ctx, json.decodeFromJsonElement<ToolSearch>(args).query.also { require(it.isNotBlank()) })
                    else -> receiver(ctx, id, definition.id, args)
                }
            }
        }
        val registry = ToolRegistry(commands)
        val executionContext = if (context.role in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) && context.sessionId == context.ownerSessionId)
            context.copy(sessionId = "planning-${context.requestId}-${Id.new()}") else context
        return ToolSession(executionContext, registry, ToolExecutor(registry, receipts, checkScope, checkReplayScope, reconcile))
    }
}
