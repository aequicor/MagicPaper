package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingInteractionMode
import kotlinx.serialization.json.JsonArray
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.TokenUsage
import kotlinx.serialization.json.JsonObject

/** Native execution capabilities belong to adapters; persisted identities remain in core:model. */
enum class BackendAgentCapability {
    MANAGED_INSTALLATION,
    EXTERNAL_INSTALLATION,
    NATIVE_APPROVALS,
    NATIVE_TOOL_HISTORY,
    DISABLE_NATIVE_SKILL_LOADING,
}

data class BackendAgentDescriptor(
    val engine: CodingEngine,
    val adapterName: String,
    val capabilities: Set<BackendAgentCapability>,
    val summary: String,
    val providerSummary: String,
    val skillLoadingSummary: String,
    val fileToolInstructions: String,
    val codingInstructions: String = "",
)

/** Fully prepared native model configuration; the host only writes it and starts the process. */
data class PiModelConfiguration(
    val root: JsonObject,
    val environment: Map<String, String>,
    val thinkingLevel: String?,
    val contextWindow: Int,
    val maxTokens: Int,
)

/** All application enrichment is resolved before this value crosses the process boundary. */
data class PiLaunchRequest(
    val nodePath: String,
    val cliPath: String,
    val workingDirectory: String,
    val modelId: String,
    val sessionsDirectory: String,
    val systemPromptFile: String,
    val replaceSystemPrompt: Boolean,
    val extensions: List<String>,
    val tools: List<String>?,
    val thinkingLevel: String?,
    val nativeSessionId: String?,
    val environment: Map<String, String>,
    val removedEnvironment: Set<String>,
    val stderrFile: String? = null,
)

/** Pi native installation and protocols; application tools remain with the host. */
interface PiNativeProtocol {
    val descriptor: BackendAgentDescriptor
    val providerId: String
    val apiKeyEnvironment: String
    val apiKeyReference: String
    fun installation(root: String, resources: NativeResources, diagnostics: NativeDiagnostics): PiInstallation
    fun providerTurns(ownership: NativeProcessOwnership, diagnostics: NativeDiagnostics): PiProviderTurns
    fun modelConfiguration(profile: LlmProfile, imageInput: Boolean = false): PiModelConfiguration
    fun supportsImageInput(modelId: String): Boolean
    fun tokenUsage(usage: JsonObject): TokenUsage
    fun parseEvents(line: String, summaryOnly: Boolean = false): List<CodingEvent>
    fun parse(line: String): CodingEvent? = parseEvents(line).let { events ->
        events.firstOrNull { it !is CodingEvent.UsageObserved } ?: events.firstOrNull()
    }
    fun truncationAdvice(profile: LlmProfile, outputTokens: Int? = null, reasoningTokens: Int? = null): String
    /** Each execution handle owns one attempt and cannot be restarted after completion or abort. */
    fun execution(ownership: NativeProcessOwnership, events: NativeAttemptEvents, attempt: NativeAttemptRef): PiNativeExecution
}

data class CodexAccessPolicy(
    val approvalConfiguration: JsonObject,
    val sandboxPolicy: JsonObject,
    val threadConfiguration: JsonObject,
)

/** Port to Codex app-server policy and terminal-evidence formats. */
interface CodexNativeProtocol {
    val descriptor: BackendAgentDescriptor
    fun codingAccessPolicy(projectPath: String): CodexAccessPolicy
    fun readOnlyAccessPolicy(): CodexAccessPolicy
    fun prepareRun(request: CodexRunRequest): CodexRunPayloads
    fun client(json: kotlinx.serialization.json.Json, home: String, command: String?, ownership: NativeProcessRecovery,
        tokens: NativeAuthTokens, questionnaires: NativeQuestionnaires, diagnostics: NativeDiagnostics,
        toolPresentation: NativeToolPresentationResolver): CodexClient
    fun questionnaireBroker(scope: kotlinx.coroutines.CoroutineScope, questionnaires: NativeQuestionnaires,
        notice: (String, String) -> Unit, failed: (String, Throwable) -> Unit): NativeQuestionnaireBroker
    fun webTitle(item: JsonObject): String
    fun terminalToolResult(item: JsonObject): CodingEvent.ToolFinished?
    fun readToolResults(response: JsonObject, threadId: String, callIds: Set<String>): List<CodingEvent.ToolFinished>
}

/** The factory exposes contracts only. Engine implementation types never reach its consumers. */
class BackendAgentProtocols(
    val pi: PiNativeProtocol,
    val codex: CodexNativeProtocol,
) {
    val descriptors: List<BackendAgentDescriptor> = listOf(pi.descriptor, codex.descriptor)
    fun descriptor(engine: CodingEngine): BackendAgentDescriptor =
        descriptors.single { it.engine == engine }
}

/** Durable identity is recorded before stdin can deliver an instruction to the native process. */
interface NativeProcessOwnership {
    fun record(id: String, process: Process, attachLifetime: Boolean = true)
    fun clear(id: String)
}

data class PiExecutionRequest(val sessionId: String, val prompt: String, val launch: PiLaunchRequest)
data class PiExecutionResult(
    val exitCode: Int? = null,
    val aborted: Boolean = false,
    val stderr: String = "",
    val streamBroken: String? = null,
    val launchError: String? = null,
    val cause: Throwable? = null,
)

/** One attempt owns the child, its streams and shutdown. Application resources stay with its caller. */
interface PiNativeExecution {
    suspend fun run(request: PiExecutionRequest, onLine: suspend (String) -> Unit): PiExecutionResult
    fun abort()
}

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
