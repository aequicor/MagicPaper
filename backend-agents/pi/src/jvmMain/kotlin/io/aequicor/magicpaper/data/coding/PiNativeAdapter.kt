package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.LlmProfile
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Pi wire protocol: installation, model configuration, event parsing and process execution. */
class PiNativeAdapter {
    fun installation(root: String, resources: NativeResources, diagnostics: NativeDiagnostics): PiInstallation =
        PiNativeInstallation(File(root), resources, diagnostics)
    fun providerTurns(ownership: NativeProcessOwnership, diagnostics: NativeDiagnostics): PiProviderTurns =
        PiProviderTurnExecution(ownership, diagnostics)
    val descriptor = BackendAgentDescriptor(
        CodingEngine.PI,
        "Pi",
        setOf(BackendAgentCapability.MANAGED_INSTALLATION, BackendAgentCapability.DISABLE_NATIVE_SKILL_LOADING),
        "pi · работа с файлами и командами проекта",
        "Подписка ChatGPT, OpenAI-совместимые серверы, OpenRouter, Anthropic и Google.",
        "Нативная автозагрузка навыков Pi отключена (--no-skills).",
        PI_CODING_INSTRUCTIONS,
    )
    val providerId = PiModelsConfig.PROVIDER_ID
    val apiKeyEnvironment = PiModelsConfig.API_KEY_ENV
    val apiKeyReference = PiModelsConfig.API_KEY_REFERENCE
    fun modelConfiguration(profile: LlmProfile, imageInput: Boolean = false) = PiModelConfiguration(
        PiModelsConfig.root(profile, imageInput = imageInput), PiModelsConfig.environment(profile),
        PiModelsConfig.thinkingLevel(profile), PiModelsConfig.contextWindow(profile), PiModelsConfig.maxTokens(profile),
    )
    fun supportsImageInput(modelId: String) = PiModelsConfig.supportsImageInput(modelId)
    fun tokenUsage(usage: JsonObject) = PiUsageParsing.tokens(usage)
    fun parseEvents(line: String, summaryOnly: Boolean = false) = PiEventParser.parseEvents(line, summaryOnly)
    /** The first event of a line that is not a usage observation, else the usage itself. */
    fun parse(line: String): CodingEvent? = parseEvents(line).let { events ->
        events.firstOrNull { it !is CodingEvent.UsageObserved } ?: events.firstOrNull()
    }
    fun truncationAdvice(profile: LlmProfile, outputTokens: Int? = null, reasoningTokens: Int? = null) =
        PiModelsConfig.truncationAdvice(profile, outputTokens, reasoningTokens)

    fun execution(ownership: NativeProcessOwnership, events: NativeAttemptEvents, attempt: NativeAttemptRef): PiNativeExecution = PiProcessExecution(this, ownership, events, attempt)

    internal fun launch(request: PiLaunchRequest): Process {
        val command = buildList {
            addAll(listOf(request.nodePath, request.cliPath, "--mode", "json", "--provider", providerId,
                "--model", request.modelId, "--session-dir", request.sessionsDirectory,
                "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes", "--no-approve"))
            addAll(listOf(if (request.replaceSystemPrompt) "--system-prompt" else "--append-system-prompt", request.systemPromptFile))
            request.tools?.let { addAll(listOf("--tools", it.joinToString(","))) }
            request.extensions.forEach { addAll(listOf("--extension", it)) }
            request.thinkingLevel?.let { addAll(listOf("--thinking", it)) }
            request.nativeSessionId?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--session-id", it)) }
        }
        return ProcessBuilder(command).directory(File(request.workingDirectory))
            .apply {
                request.stderrFile?.let { redirectError(File(it)) }
                request.removedEnvironment.forEach(environment()::remove)
                environment().putAll(request.environment)
            }.start()
    }
}
