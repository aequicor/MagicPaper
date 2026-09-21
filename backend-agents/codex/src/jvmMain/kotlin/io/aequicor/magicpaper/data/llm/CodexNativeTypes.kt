package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.NativeSubscriptionAccess
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class CodexAccessPolicy(
    val approvalConfiguration: JsonObject,
    val sandboxPolicy: JsonObject,
    val threadConfiguration: JsonObject,
)

/** Values resolved by the host; native permissions are still enforced by the adapter. */
data class CodexRunRequest(
    val workingDirectory: String,
    val modelId: String,
    val modelProvider: String,
    val providerConfiguration: JsonObject,
    val toolConfiguration: JsonObject,
    val instructions: String,
    val input: JsonArray,
    val mode: CodingInteractionMode,
    val effort: String?,
    val nativeSessionId: String?,
    val serviceName: String,
    val baseInstructions: String?,
)

data class CodexRunPayloads(val threadStart: JsonObject, val threadResume: JsonObject?, val turnStart: JsonObject)

data class NativeRuntimeStatus(val ready: Boolean, val detail: String)

/** One long-lived app-server connection. A coding run uses its own instance. */
interface CodexClient : NativeSubscriptionAccess {
    val codingApprovals: StateFlow<List<CodingApproval>>
    suspend fun respondCodingApproval(id: String, decision: CodingApprovalDecision)
    /** Каталог `model/list` без эвристик [ModelDefaults]: уровни и умолчание — как объявил Codex. */
    suspend fun codingModels(): List<CodingModel>
    suspend fun runtimeStatus(): NativeRuntimeStatus
    fun runCoding(session: CodingSession, request: CodexRunRequest, refreshResume: Boolean): Flow<CodingEvent>
    suspend fun reconcileCoding(sessionId: String)
    suspend fun readCodingToolResults(threadId: String, callIds: Set<String>): List<CodingEvent.ToolFinished>
    fun abortCoding(sessionId: String)
    suspend fun shutdownCoding()
    fun abortAllCoding()
}

/** Native request/delivery protocol; the host remains the sole questionnaire state owner. */
interface NativeQuestionnaireBroker {
    fun receive(id: JsonPrimitive, method: String, params: JsonObject, session: CodingSession, reply: suspend (JsonObject) -> Unit): Boolean
    fun contains(threadId: String, id: JsonPrimitive): Boolean
    fun resolved(threadId: String, id: JsonElement)
    fun clearTurn(threadId: String, turnId: String? = null)
    fun completeItem(threadId: String, itemId: String)
    fun clear()
}
