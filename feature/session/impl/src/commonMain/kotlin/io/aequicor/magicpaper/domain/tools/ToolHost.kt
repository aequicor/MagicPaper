package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Composition root binds receivers; every invocation gets a fresh, application-owned scope. */
class ToolHost(val receipts: ToolReceiptStore, val questions: RuntimeQuestionnaires = RuntimeQuestionnaires()) {
    var contextDefaults: (ToolExecutionContext) -> ToolExecutionContext = { it }
    var knownSecrets: () -> Set<String> = { emptySet() }
    var checkScope: suspend (ToolExecutionContext) -> Unit = {}
    var checkReplayScope: suspend (ToolExecutionContext) -> Unit = { checkScope(it) }
    var reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null }
    var unknownOutcome: suspend (ToolExecutionContext, ToolReceipt) -> Unit = { _, _ -> }
    var authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { _, _ -> }
    var authorizeCommand: suspend (ToolExecutionContext, ToolDefinition, JsonObject) -> Unit = { _, _, _ -> }
    var authorizeReceipt: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { context, definition -> authorizeTool(context, definition) }
    var orchestration: CustomOrchestration? = null
    var receiver: suspend (ToolExecutionContext, String, String, JsonObject) -> JsonElement = { _, _, _, _ -> error("Инструменты приложения не подключены") }
    var prepareWorker: suspend (CodingSession) -> ToolExecutionContext = { ToolExecutionContext.worker(it) }
    var search: (suspend (ToolExecutionContext, String) -> JsonElement)? = null
    var taskHandoff: (suspend (ToolExecutionContext, TaskHandoff) -> Unit)? = null
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    internal suspend fun askQuestionnaire(ctx: ToolExecutionContext, id: String, args: JsonObject): JsonElement {
        val request = json.decodeFromJsonElement<ToolQuestions>(args)
        val safe = QuestionnaireContract.ready(request.questions)
        val answers = questions.ask(UserInteractionRequest("tool:$id", ctx.projectId, ctx.ownerSessionId,
            InteractionKind.RUNTIME, safe, ownerSessionId = ctx.ownerSessionId, createdAt = Id.now(),
            runtimeGeneration = ctx.runtimeGeneration, runId = ctx.runId ?: ctx.requestId))
        return json.encodeToJsonElement(answers)
    }

    fun session(context: ToolExecutionContext, overrides: Map<String, suspend (ToolExecutionContext, String, JsonObject) -> JsonElement> = emptyMap()): ToolSession {
        val scopedContext = contextDefaults(context)
        val commands = ToolCatalog.definitions.filter { !it.native && (it.id != "web.search" || search != null) }.map { definition ->
            JsonToolCommand(definition) { ctx, id, args ->
                val override = overrides[definition.id]
                if (override != null) override(ctx, id, args)
                else when (definition.id) {
                    "questionnaire" -> askQuestionnaire(ctx, id, args)
                    "task.handoff" -> {
                        checkNotNull(taskHandoff) { "Worktree недоступен" }(ctx, json.decodeFromJsonElement<TaskHandoff>(args))
                        buildJsonObject { put("accepted", true) }
                    }
                    "web.search" -> search!!(ctx, json.decodeFromJsonElement<ToolSearch>(args).query.also { require(it.isNotBlank()) })
                    else -> if (definition.orchestration && orchestration != null) orchestration!!.execute(ctx, id, definition.id, args)
                        else receiver(ctx, id, definition.id, args)
                }
            }
        }
        val registry = ToolRegistry(commands)
        val executionContext = if (scopedContext.role in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) && scopedContext.sessionId == scopedContext.ownerSessionId)
            scopedContext.copy(sessionId = "planning-${scopedContext.requestId}-${Id.new()}") else scopedContext
        return ToolSession(executionContext, registry, ToolExecutor(registry, receipts, checkScope, checkReplayScope, reconcile,
            knownSecrets = { knownSecrets() }, recoverQuestionnaire = { ctx, receipt ->
                askQuestionnaire(ctx, receipt.operationId, receipt.arguments)
            }, unknownOutcome = { ctx, receipt -> unknownOutcome(ctx, receipt) },
            authorizeTool = { ctx, definition -> authorizeTool(ctx, definition) },
            authorizeCommand = { ctx, definition, args -> authorizeCommand(ctx, definition, args) },
            authorizeReceipt = { ctx, definition -> authorizeReceipt(ctx, definition) }), knownSecrets = { knownSecrets() })
    }
}
