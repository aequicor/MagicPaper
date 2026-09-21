package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** Small in-memory port adapters for tests that exercise the real session/executor. */
fun testToolSessions(
    receipts: ToolReceiptStore,
    questions: RuntimeQuestionnaireService = io.aequicor.magicpaper.domain.testQuestionnaires(),
    contextDefaults: (ToolExecutionContext) -> ToolExecutionContext = { it },
    knownSecrets: () -> Set<String> = { emptySet() },
    checkScope: suspend (ToolExecutionContext) -> Unit = {},
    checkReplayScope: suspend (ToolExecutionContext) -> Unit = checkScope,
    reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null },
    unknownOutcome: suspend (ToolExecutionContext, ToolReceipt) -> Unit = { _, _ -> },
    authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { _, _ -> },
    authorizeReceipt: suspend (ToolExecutionContext, ToolDefinition) -> Unit = authorizeTool,
    authorizeCommand: suspend (ToolExecutionContext, ToolDefinition, JsonObject) -> Unit = { _, _, _ -> },
    receiver: suspend (ToolExecutionContext, String, String, JsonObject) -> JsonElement = { _, _, _, _ -> error("Unexpected command") },
    prepareWorker: suspend (CodingSession) -> ToolExecutionContext = { ToolExecutionContext.worker(it) },
    search: (suspend (ToolExecutionContext, String) -> JsonElement)? = null,
    mediaGeneration: MediaGenerationService? = null,
    mediaToolReceipts: MediaToolReceiptOwner = DefaultMediaToolReceiptOwner(receipts) { asset -> mediaGeneration?.localPath(asset) },
    mediaAllowed: suspend (ToolExecutionContext, MediaKind) -> Boolean = { _, _ -> true },
    planningAccess: (() -> PlanningToolAccess)? = null,
): ToolSessionFactory {
    val knownSecretsAction = knownSecrets
    val prepareWorkerAction = prepareWorker
    val checkScopeAction = checkScope
    val checkReplayScopeAction = checkReplayScope
    val authorizeCommandAction = authorizeCommand
    val reconcileAction = reconcile
    val contextDefaultsAction = contextDefaults
    val authorizeToolAction = authorizeTool
    val authorizeReceiptAction = authorizeReceipt
    val unknownOutcomeAction = unknownOutcome
    val access = planningAccess ?: {
        object : PlanningToolAccess {
            override fun knownSecrets() = knownSecretsAction()
            override suspend fun prepareWorker(session: CodingSession) = prepareWorkerAction(session)
            override suspend fun checkScope(context: ToolExecutionContext, historical: Boolean) =
                if (historical) checkReplayScopeAction(context) else checkScopeAction(context)
            override suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject) =
                authorizeCommandAction(context, definition, arguments)
            override suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt) = reconcileAction(context, receipt)
            override suspend fun execute(context: ToolExecutionContext, operation: String, tool: String, arguments: JsonObject) =
                receiver(context, operation, tool, arguments)
        }
    }
    val authority = object : ToolRunAuthority {
        override fun contextDefaults(context: ToolExecutionContext) = contextDefaultsAction(context)
        override suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition) = authorizeToolAction(context, definition)
        override suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition) = authorizeReceiptAction(context, definition)
        override suspend fun unknownOutcome(context: ToolExecutionContext, receipt: ToolReceipt) = unknownOutcomeAction(context, receipt)
    }
    val questionnaires = DefaultQuestionnaireToolCommands(questions)
    val media = DefaultMediaToolCommands(mediaGeneration, mediaAllowed, { access().checkScope(it) }, authority::authorizeTool,
        mediaToolReceipts)
    val commands = ApplicationToolCommands(questionnaires, media, OrchestrationActions { context, operation, tool, arguments ->
        access().execute(context, operation, tool, arguments)
    }, orchestration = null, taskHandoff = null, search = search)
    return ToolSessionFactory(receipts, questionnaires, media, access, authority, commands, DefaultToolSessionFactory())
}

fun testToolRuntime(delegate: CodingRuntime, sessions: ToolSessionFactory, tree: SessionTreeRuntime? = null): ToolEnabledCodingRuntime =
    ToolEnabledCodingRuntime(delegate, sessions, sessions.questionnaires.questions, sessions.media,
        { sessions.planningAccess().prepareWorker(it) }, tree)
