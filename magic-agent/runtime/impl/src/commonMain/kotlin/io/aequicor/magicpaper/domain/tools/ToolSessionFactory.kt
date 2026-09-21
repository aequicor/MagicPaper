package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Adds project authority to the common executor; it never owns another tool interpreter. */
class ToolSessionFactory(
    val receipts: ToolReceiptStore,
    val questionnaires: QuestionnaireToolCommands,
    val media: MediaToolCommands,
    val planningAccess: () -> PlanningToolAccess,
    private val authority: ToolRunAuthority,
    private val commands: ApplicationToolCommands,
    private val factory: ToolSession.Factory,
) {
    private val scope = object : ToolExecutionScope {
        override fun knownSecrets() = planningAccess().knownSecrets()
        override suspend fun check(context: ToolExecutionContext, historical: Boolean) = planningAccess().checkScope(context, historical)
    }
    private val authorization = object : ToolExecutionAuthorization {
        override suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition) = authority.authorizeTool(context, definition)
        override suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject) =
            planningAccess().authorizeCommand(context, definition, arguments)
        override suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition) = authority.authorizeReceipt(context, definition)
    }
    private val recovery = object : ToolExecutionRecovery {
        override suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement? =
            if (media.kind(receipt.toolId) != null) media.reconcile(receipt) else planningAccess().reconcile(context, receipt)
        override suspend fun questionnaire(context: ToolExecutionContext, receipt: ToolReceipt) =
            questionnaires.ask(context, receipt.operationId, receipt.arguments)
        override suspend fun unknown(context: ToolExecutionContext, receipt: ToolReceipt) = authority.unknownOutcome(context, receipt)
    }

    /** Chat owns this scope; cancellation revokes it without consulting the project registry. */
    internal fun researchChatSession(context: ToolExecutionContext, allowSearch: Boolean = true): ToolSession {
        require(context.role == ToolRole.CHAT && context.mode == CodingInteractionMode.RESEARCH && context.planId == null)
        val chatScope = object : ToolExecutionScope {
            override fun knownSecrets() = scope.knownSecrets()
            override suspend fun check(context: ToolExecutionContext, historical: Boolean) { currentCoroutineContext().ensureActive() }
        }
        val chatAuthorization = object : ToolExecutionAuthorization {
            override suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition) = Unit
            override suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject) = Unit
            override suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition) = Unit
        }
        return factory.create(context, commands.research(context, allowSearch), receipts, chatScope, chatAuthorization, recovery, media.receiptLock)
    }

    fun session(context: ToolExecutionContext,
        overrides: Map<String, suspend (ToolExecutionContext, String, JsonObject) -> JsonElement> = emptyMap()): ToolSession {
        val scopedContext = authority.contextDefaults(context)
        val executionContext = if (scopedContext.role in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) && scopedContext.sessionId == scopedContext.ownerSessionId)
            scopedContext.copy(sessionId = "planning-${scopedContext.requestId}-${Id.new()}") else scopedContext
        return factory.create(executionContext, commands.project(scopedContext, overrides), receipts, scope, authorization, recovery, media.receiptLock)
    }
}
