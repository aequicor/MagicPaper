package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.LlmProfile
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Public only for the group's factory; Gradle prevents consumers from seeing this module. */
class PiNativeAdapter : PiNativeProtocol {
    override fun installation(root: String, resources: NativeResources, diagnostics: NativeDiagnostics): PiInstallation =
        PiNativeInstallation(File(root), resources, diagnostics)
    override fun providerTurns(ownership: NativeProcessOwnership, diagnostics: NativeDiagnostics): PiProviderTurns =
        PiProviderTurnExecution(ownership, diagnostics)
    override val descriptor = BackendAgentDescriptor(
        CodingEngine.PI,
        "Pi",
        setOf(BackendAgentCapability.MANAGED_INSTALLATION, BackendAgentCapability.DISABLE_NATIVE_SKILL_LOADING),
        "pi · работа с файлами и командами проекта",
        "Подписка ChatGPT, OpenAI-совместимые серверы, OpenRouter, Anthropic и Google.",
        "Нативная автозагрузка навыков Pi отключена (--no-skills).",
        PI_CODING_INSTRUCTIONS,
    )
    override val providerId = PiModelsConfig.PROVIDER_ID
    override val apiKeyEnvironment = PiModelsConfig.API_KEY_ENV
    override val apiKeyReference = PiModelsConfig.API_KEY_REFERENCE
    override fun modelConfiguration(profile: LlmProfile, imageInput: Boolean) = PiModelConfiguration(
        PiModelsConfig.root(profile, imageInput = imageInput), PiModelsConfig.environment(profile),
        PiModelsConfig.thinkingLevel(profile), PiModelsConfig.contextWindow(profile), PiModelsConfig.maxTokens(profile),
    )
    override fun supportsImageInput(modelId: String) = PiModelsConfig.supportsImageInput(modelId)
    override fun tokenUsage(usage: JsonObject) = PiUsageParsing.tokens(usage)
    override fun parseEvents(line: String, summaryOnly: Boolean) = PiEventParser.parseEvents(line, summaryOnly)
    override fun truncationAdvice(profile: LlmProfile, outputTokens: Int?, reasoningTokens: Int?) =
        PiModelsConfig.truncationAdvice(profile, outputTokens, reasoningTokens)

    override fun execution(ownership: NativeProcessOwnership, events: NativeAttemptEvents, attempt: NativeAttemptRef): PiNativeExecution = PiProcessExecution(this, ownership, events, attempt)

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
