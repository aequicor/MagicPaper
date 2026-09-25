package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Stateless ServiceLoader entry. Construction must not launch processes or call supplied ports. */
interface BackendAgentContribution {
    val descriptor: BackendAgentDescriptor
    val paths: NativeBackendPaths
    fun create(environment: NativeBackendEnvironment): NativeAgentAdapter
    /** Account access is optional: an engine without its own account offers none. The caller closes the result. */
    fun createSubscription(environment: NativeSubscriptionEnvironment): NativeSubscriptionAccess? = null
}

/** Relative to the existing application home. These are migration-sensitive, not display names. */
data class NativeBackendPaths(val home: String, val processes: String, val questionnaires: String, val questionnaireScope: String)

/** All stateful dependencies are owned by one explicit runtime construction, never by the catalog. */
data class NativeBackendEnvironment(
    val json: Json,
    val home: String,
    val commandOverride: String? = null,
    /** The executable the person selected in the settings; read per lookup, so a fresh choice applies without a restart. */
    val commandSelection: () -> String? = { null },
    val resources: NativeResources,
    val processes: NativeProcessRecovery,
    val cachedAccessTokens: NativeAuthTokens,
    val refreshedAccessTokens: NativeAuthTokens,
    val questionnaires: NativeQuestionnaires,
    val diagnostics: NativeDiagnostics,
    val toolPresentation: NativeToolPresentationResolver,
    val providerLibrary: NativeProviderLibrary,
    val lifecycleJournal: NativeLifecycleJournal,
)

enum class NativeModelConnectionKind { DIRECT, RESPONSES_PROXY }

/** Host-created tool resources are inert values. A backend never receives their executor or authority. */
data class NativeAgentTools(
    val names: List<String>,
    val extensionSources: Map<String, String>,
    val environment: Map<String, String>,
    val removedEnvironment: Set<String>,
    val mcpConfiguration: JsonObject,
) {
    override fun toString() = "NativeAgentTools(names=$names, extensions=${extensionSources.keys})"
}

data class NativeModelConnection(val providerId: String, val baseUrl: String, val bearerToken: String) {
    override fun toString() = "NativeModelConnection(providerId=$providerId)"
}

/** Created after host policy/enrichment; every native attempt for this run receives the same values. */
data class NativeAgentRequest(
    val requestId: String,
    val session: CodingSession,
    val workingDirectory: String,
    val prompt: String,
    val instructions: String,
    val baseInstructions: String?,
    val profile: LlmProfile,
    val providerParameters: JsonObject,
    val attachments: List<Attachment>,
    val mode: CodingInteractionMode,
    val tools: NativeAgentTools,
    val modelConnection: NativeModelConnection? = null,
    val imageInput: Boolean = false,
    val freshResume: Boolean = false,
    val maxOutputContinuations: Int = 2,
    val recovery: NativeRecoveryAcknowledgement? = null,
    val noDispatchRecovery: NativeNoDispatchAcknowledgement? = null,
) {
    override fun toString() = "NativeAgentRequest(requestId=$requestId, sessionId=${session.id}, mode=$mode)"
}

interface NativeApprovalRequests {
    val requests: StateFlow<List<CodingApproval>>
    suspend fun respond(id: String, decision: CodingApprovalDecision)
}

fun interface NativeToolHistory {
    suspend fun results(nativeSessionId: String, callIds: Set<String>): List<CodingEvent.ToolFinished>
}

fun interface NativeRemoval { suspend fun remove() }

/** The engine's own sign-in: it opens the vendor's page in the browser and returns once the flow ended. Cancellation stops it. */
fun interface NativeSignIn { suspend fun signIn(): EngineSignInResult }

/** Forgets the engine's own stored login, so that the next [NativeSignIn] replaces a dead token instead of keeping it. */
fun interface NativeSignOut { suspend fun signOut(): EngineSignOutResult }

/**
 * One plain answer through the engine's own account: no project and no application tools. A failure is thrown as
 * [NativeCompletionFailure] whose message is safe to show; cancellation stops the engine.
 */
interface NativeCompletion {
    /** The subscription provider whose requests this engine answers, so the host routes by capability. */
    val provider: ProviderType
    suspend fun complete(request: NativeCompletionRequest, onActivity: (CodingStep) -> Unit, onUsage: (UsageCallResult) -> Unit): String
    /** A provider may expose current plan allowances without sending a model request. */
    suspend fun readPlanUsage(): PlanUsage? = null
}

/** [signedOut] marks the one failure the engine's own sign-in resolves. */
class NativeCompletionFailure(message: String, val signedOut: Boolean = false, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Каталог моделей как его объявляет сам движок, без эвристик приложения. Ошибку опроса пробрасывает. */
fun interface NativeModelCatalog { suspend fun models(): List<CodingModel> }

/** One native engine instance. Native-specific connection/attempt state stays behind this boundary. */
interface NativeAgentAdapter : AutoCloseable {
    val descriptor: BackendAgentDescriptor
    val rootPath: String
    val approvals: NativeApprovalRequests?
    val history: NativeToolHistory?
    val removal: NativeRemoval?
    /** Задан ровно тогда, когда у дескриптора есть [BackendAgentCapability.NATIVE_MODEL_CATALOG]. */
    val models: NativeModelCatalog? get() = null
    /** Задан ровно тогда, когда у дескриптора есть [BackendAgentCapability.NATIVE_SIGN_IN]; a signed-out failure then carries [CodingRecovery.SignIn]. */
    val signIn: NativeSignIn? get() = null
    /** Задан вместе с [signIn]: из собственного аккаунта движка можно выйти, чтобы войти заново. */
    val signOut: NativeSignOut? get() = null
    /** Present for an engine that answers plain requests on its own account, such as a chat on a subscription. */
    val completion: NativeCompletion? get() = null
    suspend fun status(): NativeInstallationStatus
    fun prepare(): Flow<NativeInstallationStatus>
    /** Pure native model policy, resolved before the host builds provider controls and enrichment. */
    fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean): LlmProfile
    fun modelConnection(profile: LlmProfile): NativeModelConnectionKind
    fun run(request: NativeAgentRequest): Flow<CodingEvent>
    /** True only when this call itself proved the session's tracked native process fully terminated. */
    suspend fun reconcile(sessionId: String): Boolean
    fun abort(sessionId: String)
    fun abortAll()
    /**
     * Application reset, after every run stopped and no record refers to a session: deletes the engine's own
     * session files. The installation and its configuration stay.
     */
    suspend fun eraseSessionsForReset() = Unit
}

/** Public factory result. The protocol adapter cannot bypass journal admission or recovery. */
interface BackendAgent : NativeAgentAdapter {
    suspend fun inspectRecovery(sessionId: String): NativeRecoverySummary
    suspend fun stopRecovery(attempt: NativeAttemptRef): NativeRecoverySummary
    suspend fun acknowledgeRecovery(attempt: NativeAttemptRef, parentDecisionId: String): NativeRecoveryAcknowledgement
    suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String): NativeNoDispatchAcknowledgement
    suspend fun shutdown()
    suspend fun prepareForReset()
    suspend fun resumeAfterReset()
}
fun interface BackendAgentConstructor {
    fun create(contribution: BackendAgentContribution, environment: NativeBackendEnvironment): BackendAgent
}

/** Model access is a backend capability, independent of any selected coding engine. */
interface NativeProviderLibrary : AutoCloseable {
    suspend fun shutdown()
    suspend fun prepareForReset()
    suspend fun resumeAfterReset()
    fun prepare(): Flow<NativeInstallationStatus>
    suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn
    suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge
}

data class NativeProviderUsage(val id: String, val completed: Boolean, val usage: UsageCallResult)
interface NativeProviderBridge : AutoCloseable {
    suspend fun shutdown()
    val connection: NativeModelConnection
    /** A single host collector observes every metric and any read failure in its own run scope. */
    val usage: Flow<NativeProviderUsage>
    /** Idempotent; stop the process and close pipes before returning, retaining primary cleanup failures. */
    override fun close()
}

/** Model transport attempts have opaque IDs, so recovery enumerates their own receipt namespace. */
interface NativeProviderProcesses : NativeProcessOwnership {
    /** Never terminates a process whose recorded application owner is still alive. */
    fun reconcileOrphans()
}
