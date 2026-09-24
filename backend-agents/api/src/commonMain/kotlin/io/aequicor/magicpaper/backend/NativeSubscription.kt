package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** One plain completion through the engine's own account: no project, no application tools. */
data class NativeCompletionRequest(
    val modelId: String,
    val systemInstructions: String,
    val baseInstructions: String,
    val input: JsonArray,
    val effort: String?,
    /** The profile's wait for an answer; 0 means no limit, as for every other transport. */
    val timeoutSeconds: Int,
)

/**
 * Account-backed model access that an engine offers beside its coding runs. The host sees this port only;
 * the connection, login flow and model listing stay behind it. Closing the value stops what it started.
 */
interface NativeSubscriptionAccess : AutoCloseable {
    suspend fun account(refreshToken: Boolean = false): OpenAiSubscriptionAccount
    suspend fun startLogin(): OpenAiSubscriptionLogin
    suspend fun awaitLogin(loginId: String): OpenAiSubscriptionAccount
    suspend fun cancelLogin(loginId: String)
    suspend fun logout()
    suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel>
    suspend fun subscriptionAccessToken(): String
    fun withCachedContextWindow(profile: LlmProfile): LlmProfile
    suspend fun complete(request: NativeCompletionRequest, onActivity: (CodingStep) -> Unit, onUsage: (UsageCallResult) -> Unit): String
}

/** Only what account access needs; a coding run's resources and lifecycle journal are not part of it. */
data class NativeSubscriptionEnvironment(
    val json: Json,
    val home: String,
    val commandOverride: String? = null,
    val processes: NativeProcessRecovery,
    val accessTokens: NativeAuthTokens,
    val questionnaires: NativeQuestionnaires,
    val diagnostics: NativeDiagnostics,
    val toolPresentation: NativeToolPresentationResolver,
)
