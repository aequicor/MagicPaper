package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/** Application services are adapted here; native implementations have no application dependencies. */
fun interface NativeDiagnostics {
    fun error(component: String, event: String, cause: Throwable, fields: Map<String, String>)
}
/** Only the current short-lived access token crosses this boundary; refresh tokens stay with the host. */
fun interface NativeAuthTokens { suspend fun readAccessToken(): String? }
interface NativeQuestionnaires {
    suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer>
    suspend fun beginDelivery(requestId: String): String
    suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome)
}
interface NativeProcessRecovery : NativeProcessOwnership {
    fun belongsTo(id: String, process: Process?): Boolean
    fun reconcile(id: String)
}
data class NativeToolPresentation(val name: String, val summary: String)
fun interface NativeToolPresentationResolver {
    fun resolve(server: String, tool: String, arguments: JsonElement?): NativeToolPresentation
}
data class CodexCompletionRequest(
    val modelId: String,
    val systemInstructions: String,
    val baseInstructions: String,
    val input: JsonArray,
    val effort: String?,
    val timeoutSeconds: Int,
)

data class NativeRuntimeStatus(val ready: Boolean, val detail: String)

/** One long-lived app-server connection. A coding run uses its own instance. */
interface CodexClient : AutoCloseable {
    val codingApprovals: StateFlow<List<CodingApproval>>
    suspend fun respondCodingApproval(id: String, decision: CodingApprovalDecision)
    suspend fun account(refreshToken: Boolean = false): OpenAiSubscriptionAccount
    suspend fun startLogin(): OpenAiSubscriptionLogin
    suspend fun awaitLogin(loginId: String): OpenAiSubscriptionAccount
    suspend fun cancelLogin(loginId: String)
    suspend fun logout()
    suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel>
    /** Каталог `model/list` без эвристик [ModelDefaults]: уровни и умолчание — как объявил Codex. */
    suspend fun codingModels(): List<CodingModel>
    suspend fun runtimeStatus(): NativeRuntimeStatus
    suspend fun subscriptionAccessToken(): String
    fun withCachedContextWindow(profile: LlmProfile): LlmProfile
    suspend fun complete(request: CodexCompletionRequest, onActivity: (CodingStep) -> Unit, onUsage: (UsageCallResult) -> Unit): String
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
