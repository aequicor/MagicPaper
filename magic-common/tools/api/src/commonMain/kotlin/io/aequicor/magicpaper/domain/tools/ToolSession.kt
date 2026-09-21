package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.GeneratedMedia
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/** A scoped model/transport capability. Its executor and durable receipts belong to the common tool owner. */
interface ToolSession : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ToolSession>
    val context: ToolExecutionContext
    val events: ToolEventHub
    val results: StateFlow<Map<String, JsonElement>>
    val calls: StateFlow<List<CompletedToolCall>>
    val definitions: List<ToolDefinition>
    suspend fun call(id: String, name: String, args: JsonObject): JsonElement
    suspend fun receipt(id: String): ToolReceipt?
    fun attachNativeCommands(commands: List<ToolCommand<*, *>>): () -> Unit
    suspend fun recordNative(event: ToolEvent): Boolean
    suspend fun publishNative(event: ToolEvent)

    fun interface Factory {
        fun create(context: ToolExecutionContext, commands: List<ToolCommand<*, *>>, receipts: ToolReceiptStore,
            scope: ToolExecutionScope, authorization: ToolExecutionAuthorization, recovery: ToolExecutionRecovery,
            mediaReceiptLock: Mutex): ToolSession
    }
}

interface ToolEventHub {
    val events: Flow<ToolEvent>
    fun observe(observer: suspend (ToolEvent) -> Unit): () -> Unit
}

/** Scope checks apply to both new calls and historical receipt recovery. */
interface ToolExecutionScope {
    fun knownSecrets(): Set<String>
    suspend fun check(context: ToolExecutionContext, historical: Boolean = false)
}

interface ToolExecutionAuthorization {
    suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition)
    suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject)
    suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition)
}

/** The owner of an external effect is the only party that can reconcile its result. */
interface ToolExecutionRecovery {
    suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement?
    suspend fun questionnaire(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement?
    suspend fun unknown(context: ToolExecutionContext, receipt: ToolReceipt)
}
