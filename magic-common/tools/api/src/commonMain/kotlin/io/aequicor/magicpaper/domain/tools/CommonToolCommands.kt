package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface QuestionnaireToolCommands {
    val questions: RuntimeQuestionnaireService
    suspend fun ask(ctx: ToolExecutionContext, id: String, args: JsonObject): JsonElement
    fun interface Factory { fun create(questions: RuntimeQuestionnaireService): QuestionnaireToolCommands }
}

/** One application owner settles native and provider media receipts under the same transaction lock. */
interface MediaToolReceiptOwner {
    val lock: Mutex
    suspend fun reconcileCompletion(owner: MediaGenerationOwner, operationId: String, media: GeneratedMedia)
}

interface MediaToolCommands {
    /** Shared with receipt execution so cancellation cannot overwrite a settled media result. */
    val receiptLock: Mutex
    suspend fun prepareContext(context: ToolExecutionContext, policy: SessionMediaTools): ToolExecutionContext
    fun kind(id: String): MediaKind?
    suspend fun generate(context: ToolExecutionContext, operationId: String, tool: String, arguments: JsonObject,
        researchChat: Boolean = false): JsonElement
    suspend fun reconcile(receipt: ToolReceipt): JsonElement?
    suspend fun reconcileCompletion(owner: MediaGenerationOwner, operationId: String, media: GeneratedMedia)
    fun interface Factory {
        fun create(receipts: ToolReceiptStore, service: MediaGenerationService?,
            allowed: suspend (ToolExecutionContext, MediaKind) -> Boolean,
            checkScope: suspend (ToolExecutionContext) -> Unit,
            authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit,
            receiptOwner: MediaToolReceiptOwner): MediaToolCommands
    }
}
