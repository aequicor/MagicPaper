package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeAgentRequest
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/** Files the adapter wrote for one attempt; the command line only ever names them, never embeds their content. */
internal class ClaudeLaunchFiles(val systemPrompt: String, val mcpConfig: String?, val extraDirectories: List<String>)

internal class ClaudeLaunch(val arguments: List<String>, val environment: Map<String, String>, val removedEnvironment: Set<String>)

/** Application MCP endpoints in Claude's `--mcp-config` shape. Bearer tokens travel in a private file, not in argv. */
internal class ClaudeMcpServers(val document: JsonObject, val names: List<String>, val toolTimeoutMillis: Long?, val startupTimeoutMillis: Long?) {
    companion object {
        val None = ClaudeMcpServers(JsonObject(emptyMap()), emptyList(), null, null)

        /** The host describes endpoints as `mcp_servers` (one object or dotted keys); disabled ones are dropped. */
        fun from(configuration: JsonObject): ClaudeMcpServers {
            val declared = buildMap<String, JsonObject> {
                (configuration["mcp_servers"] as? JsonObject)?.forEach { (name, value) -> (value as? JsonObject)?.let { put(name, it) } }
                configuration.filterKeys { it.startsWith("mcp_servers.") }.forEach { (key, value) ->
                    (value as? JsonObject)?.let { put(key.removePrefix("mcp_servers."), it) }
                }
            }
            val active = declared.filter { (_, server) ->
                (server["enabled"] as? JsonPrimitive)?.booleanOrNull != false && (server["url"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            }
            if (active.isEmpty()) return None
            val document = buildJsonObject {
                putJsonObject("mcpServers") {
                    active.forEach { (name, server) ->
                        putJsonObject(name) {
                            put("type", "http")
                            put("url", (server["url"] as JsonPrimitive).content)
                            (server["http_headers"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put("headers", it) }
                        }
                    }
                }
            }
            fun seconds(key: String) = active.values.mapNotNull { (it[key] as? JsonPrimitive)?.longOrNull }.maxOrNull()?.times(1000)
            return ClaudeMcpServers(document, active.keys.toList(), seconds("tool_timeout_sec"), seconds("startup_timeout_sec"))
        }
    }
}

internal object ClaudeCommand {
    /** Built-in tools of a read-only run. Command execution is deliberately absent: Claude has no read-only shell. */
    val READ_ONLY_TOOLS = listOf("Read", "Grep", "Glob")

    /** Claude's own web tools: a plain answer may search, but it reads and changes nothing on the computer. */
    val WEB_TOOLS = listOf("WebSearch", "WebFetch")

    /**
     * The CLI's ultracode level: xhigh effort plus standing dynamic-workflow orchestration. `--effort` does not take
     * it; the CLI enables it per session through the `ultracode` settings key.
     */
    const val ULTRACODE = "ultracode"
    private const val ULTRACODE_SETTINGS = """{"ultracode":true}"""

    /**
     * Inherited settings that would route a subscription request elsewhere: a key or token bills the API, a base URL
     * or a cloud switch leaves Anthropic. A subscription run drops them, so the CLI answers on its own sign-in.
     */
    val SUBSCRIPTION_OVERRIDES = setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
        "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")

    /**
     * One plain answer on the subscription: the conversation arrives as a stream-json user message on stdin, only the
     * web tools exist, and neither the user's settings, MCP servers, commands nor a saved session take part.
     */
    fun completion(executable: String, modelId: String, effort: String?, systemPrompt: String): ClaudeLaunch = ClaudeLaunch(buildList {
        add(executable)
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
        addAll(listOf("--model", modelId))
        effort?.let(ReasoningEffort::fromWire)?.let(::effortName)?.let { addAll(listOf("--effort", it)) }
        addAll(listOf("--system-prompt-file", systemPrompt))
        addAll(listOf("--tools", WEB_TOOLS.joinToString(","), "--allowedTools", WEB_TOOLS.joinToString(",")))
        addAll(listOf("--permission-mode", "dontAsk", "--strict-mcp-config", "--setting-sources", ""))
        addAll(listOf("--no-session-persistence", "--disable-slash-commands"))
    }, emptyMap(), SUBSCRIPTION_OVERRIDES)

    fun build(executable: String, request: NativeAgentRequest, files: ClaudeLaunchFiles, servers: ClaudeMcpServers, resume: String?): ClaudeLaunch {
        val restricted = request.mode != CodingInteractionMode.CODE
        val arguments = buildList {
            add(executable)
            addAll(listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
            addAll(listOf("--model", request.profile.modelId))
            val ultracode = request.session.codingModel?.takeIf { it.engine == CodingEngine.CLAUDE_CODE }?.level == ULTRACODE
            val effort = if (ultracode) effortName(ReasoningEffort.XHIGH) else request.profile.effort.level?.let(::effortName)
            effort?.let { addAll(listOf("--effort", it)) }
            // A read-only run is not offered the Workflow tool, so there ultracode can only mean its xhigh effort.
            if (ultracode && !restricted) addAll(listOf("--settings", ULTRACODE_SETTINGS))
            addAll(listOf("--append-system-prompt-file", files.systemPrompt))
            if (restricted) {
                addAll(listOf("--tools", READ_ONLY_TOOLS.joinToString(",")))
                addAll(listOf("--permission-mode", "dontAsk"))
                addAll(listOf("--allowedTools", (READ_ONLY_TOOLS + servers.names.map { "mcp__$it" }).joinToString(",")))
                // The user's own MCP servers and their permission rules must not widen a read-only run.
                add("--strict-mcp-config")
            } else addAll(listOf("--permission-mode", "bypassPermissions"))
            files.mcpConfig?.let { addAll(listOf("--mcp-config", it)) }
            files.extraDirectories.forEach { addAll(listOf("--add-dir", it)) }
            resume?.let { addAll(listOf("--resume", it)) }
        }
        val subscription = if (request.profile.provider == ProviderType.ANTHROPIC_SUBSCRIPTION) SUBSCRIPTION_OVERRIDES else emptySet()
        return ClaudeLaunch(arguments, environment(request.profile, servers) + request.tools.environment,
            (request.tools.removedEnvironment + subscription) - request.tools.environment.keys)
    }

    /** A blank key leaves authentication to the CLI's own sign-in; a stored key overrides it for this run only. */
    private fun environment(profile: LlmProfile, servers: ClaudeMcpServers): Map<String, String> = buildMap {
        val base = profile.baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')
        val official = base.isEmpty() || base.substringAfter("://").substringBefore('/').equals(OFFICIAL_HOST, ignoreCase = true)
        if (!official) put("ANTHROPIC_BASE_URL", base)
        if (profile.apiKey.isNotBlank()) {
            val direct = official || profile.authType == LlmAuthType.X_API_KEY
            put(if (direct) "ANTHROPIC_API_KEY" else "ANTHROPIC_AUTH_TOKEN", profile.apiKey)
        }
        servers.toolTimeoutMillis?.let { put("MCP_TOOL_TIMEOUT", it.toString()) }
        servers.startupTimeoutMillis?.let { put("MCP_TIMEOUT", it.toString()) }
    }

    private fun effortName(effort: ReasoningEffort): String? = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH -> "xhigh"
        ReasoningEffort.MAX -> "max"
        ReasoningEffort.AUTO -> null
    }

    private const val OFFICIAL_HOST = "api.anthropic.com"
}
