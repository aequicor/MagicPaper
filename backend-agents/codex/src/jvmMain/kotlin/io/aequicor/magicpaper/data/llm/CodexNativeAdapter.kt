package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEngine
import java.nio.file.Paths
import java.io.File
import kotlinx.serialization.json.*

/** Public only for the group's factory; native extensions are composed by the host. */
class CodexNativeAdapter : CodexNativeProtocol {
    override fun client(json: Json, home: String, command: String?, ownership: NativeProcessRecovery,
        tokens: NativeAuthTokens, questionnaires: NativeQuestionnaires, diagnostics: NativeDiagnostics,
        toolPresentation: NativeToolPresentationResolver): CodexClient =
        CodexNativeClient(json, Paths.get(home), command, ownership, tokens, questionnaires, diagnostics, toolPresentation)

    override fun questionnaireBroker(scope: kotlinx.coroutines.CoroutineScope, questionnaires: NativeQuestionnaires,
        notice: (String, String) -> Unit, failed: (String, Throwable) -> Unit): NativeQuestionnaireBroker =
        CodexQuestionnaireBroker(scope, questionnaires, notice, failed)

    override val descriptor = BackendAgentDescriptor(
        CodingEngine.CODEX,
        "Codex",
        setOf(BackendAgentCapability.EXTERNAL_INSTALLATION, BackendAgentCapability.NATIVE_APPROVALS,
            BackendAgentCapability.NATIVE_TOOL_HISTORY),
        "Codex · работа с файлами, командами и подтверждениями доступа",
        "Подписка ChatGPT и API-провайдеры.",
        "Нативные навыки и плагины Codex определяются его конфигурацией при запуске; список ниже относится к пакетам MagicPaper.",
        CODEX_FILE_TOOL_INSTRUCTIONS,
        CODEX_CODING_INSTRUCTIONS,
    )
    override fun codingAccessPolicy(projectPath: String): CodexAccessPolicy {
        val permissions = CodexCodingPermissions(Paths.get(projectPath))
        return CodexAccessPolicy(buildJsonObject { with(permissions) { approvals() } },
            permissions.sandboxPolicy(), permissions.threadConfig())
    }
    override fun readOnlyAccessPolicy() = CodexAccessPolicy(
        buildJsonObject { with(CodexPlanningPermissions) { approvals() } },
        CodexPlanningPermissions.sandboxPolicy(), CodexPlanningPermissions.threadConfig(),
    )
    override fun prepareRun(request: CodexRunRequest): CodexRunPayloads {
        val restricted = request.mode != io.aequicor.magicpaper.domain.CodingInteractionMode.CODE
        val permissions = if (restricted) readOnlyAccessPolicy() else codingAccessPolicy(request.workingDirectory)
        // Read-only restrictions win even when the prepared provider configuration names conflicting settings.
        val nativeConfiguration = if (restricted) JsonObject(request.providerConfiguration + permissions.threadConfiguration)
            else JsonObject(permissions.threadConfiguration + request.providerConfiguration)
        val configuration = mergeTools(nativeConfiguration, request.toolConfiguration, restricted)
        fun JsonObjectBuilder.approvals() = permissions.approvalConfiguration.forEach { (key, value) -> put(key, value) }
        fun thread(resume: Boolean) = buildJsonObject {
            if (resume) put("threadId", checkNotNull(request.nativeSessionId))
            put("cwd", request.workingDirectory)
            put("model", request.modelId)
            approvals()
            put("sandbox", if (restricted) "read-only" else "workspace-write")
            put("modelProvider", request.modelProvider)
            put("config", configuration)
            put("developerInstructions", request.instructions)
            if (!resume) {
                put("serviceName", request.serviceName)
                request.baseInstructions?.let { put("baseInstructions", it) }
            }
        }
        return CodexRunPayloads(thread(false), request.nativeSessionId?.let { thread(true) }, buildJsonObject {
            put("input", request.input)
            put("model", request.modelId)
            request.effort?.let { put("effort", it) }
            put("summary", "auto")
            approvals()
            put("cwd", request.workingDirectory)
            put("sandboxPolicy", permissions.sandboxPolicy)
        })
    }
    private fun mergeTools(base: JsonObject, tools: JsonObject, restricted: Boolean): JsonObject {
        fun isMcp(key: String) = key == "mcp_servers" || key.startsWith("mcp_servers.")
        require(tools.keys.all(::isMcp)) { "Tool configuration may only contain MCP endpoints" }
        if (!restricted && base["mcp_servers"] == null && tools["mcp_servers"] == null) return JsonObject(base + tools)
        fun JsonObjectBuilder.servers(config: JsonObject) {
            (config["mcp_servers"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            config.filterKeys { it.startsWith("mcp_servers.") }.forEach { (key, value) -> put(key.removePrefix("mcp_servers."), value) }
        }
        return buildJsonObject {
            base.filterKeys { !isMcp(it) }.forEach { (key, value) -> put(key, value) }
            put("mcp_servers", buildJsonObject {
                if (!restricted) servers(base)
                servers(tools)
            })
        }
    }
    override fun webTitle(item: JsonObject) = codexWebTitle(item)
    override fun terminalToolResult(item: JsonObject) = CodexNativeToolResults.terminal(item)
    override fun readToolResults(response: JsonObject, threadId: String, callIds: Set<String>) =
        CodexNativeToolResults.read(response, threadId, callIds)
    internal fun launch(commandPath: String, homeDirectory: String): Process =
        ProcessBuilder(commandPath, "app-server").directory(File(homeDirectory))
            .apply { environment()["CODEX_HOME"] = File(homeDirectory).absolutePath }.start()
}
