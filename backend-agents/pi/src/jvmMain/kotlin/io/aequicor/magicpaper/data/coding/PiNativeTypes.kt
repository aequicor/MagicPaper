package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.NativeAttemptRef
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

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

interface PiProviderTurns : AutoCloseable {
    suspend fun turn(request: PiProviderTurnRequest, onUsage: (UsageCallResult) -> Unit): LlmToolTurn
}

interface PiInstallation {
    val cliPath: String
    fun aiDirectory(): String?
    suspend fun status(): io.aequicor.magicpaper.backend.NativeInstallationStatus
    fun ensureReady(): Flow<io.aequicor.magicpaper.backend.NativeInstallationStatus>
    suspend fun uninstall()
    suspend fun node(): String
    fun prepareBundledTools()
    fun toolsNotice(): String
    fun ensureFuzzySafety()
    fun bashPath(): String?
    fun homeDefaults(directory: String)
    fun environment(nodePath: String, home: String): Map<String, String>
}
