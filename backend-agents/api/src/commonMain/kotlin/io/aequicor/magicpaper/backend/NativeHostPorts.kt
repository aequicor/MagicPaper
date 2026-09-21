package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.JsonElement

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
