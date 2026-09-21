package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeAgentRequest
import io.aequicor.magicpaper.backend.NativeAgentTools
import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ClaudeLaunchTest {
    private val files = ClaudeLaunchFiles("/h/system.md", null, emptyList())
    private fun request(mode: CodingInteractionMode = CodingInteractionMode.CODE, profile: LlmProfile = profile(), resume: String = "",
        tools: NativeAgentTools = NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap()))) =
        NativeAgentRequest("r", CodingSession("s", "p", "", 0, engine = CodingEngine.CLAUDE_CODE, piSessionId = resume), "/work", "prompt",
            "", null, profile, JsonObject(emptyMap()), emptyList(), mode, tools)
    private fun profile(baseUrl: String = "https://api.anthropic.com", key: String = "", auth: LlmAuthType? = LlmAuthType.X_API_KEY,
        effort: EffortSelection = EffortSelection.Default) =
        LlmProfile("p", "Anthropic", baseUrl, key, ProviderType.ANTHROPIC, auth, modelId = "claude-sonnet-4-5", effort = effort)
    private fun ClaudeLaunch.value(flag: String) = arguments[arguments.indexOf(flag) + 1]

    @Test fun codingRunSkipsPermissionPromptsBecauseNobodyCanAnswerThem() {
        val launch = ClaudeCommand.build("/bin/claude", request(), files, ClaudeMcpServers.None, null)
        assertEquals("/bin/claude", launch.arguments.first())
        assertEquals("bypassPermissions", launch.value("--permission-mode"))
        assertEquals("claude-sonnet-4-5", launch.value("--model"))
        assertEquals("/h/system.md", launch.value("--append-system-prompt-file"))
        assertTrue("--tools" !in launch.arguments && "--strict-mcp-config" !in launch.arguments)
        assertTrue(launch.arguments.none { it == "prompt" }, "the prompt goes through stdin, never argv")
    }

    @Test fun readOnlyRunsHaveNoShellNoWritesAndNoUserServers() {
        val servers = ClaudeMcpServers.from(buildJsonObject {
            put("mcp_servers.bridge", buildJsonObject { put("url", "http://127.0.0.1:1/mcp"); put("enabled", true) })
        })
        listOf(CodingInteractionMode.PLANNING, CodingInteractionMode.RESEARCH).forEach { mode ->
            val launch = ClaudeCommand.build("c", request(mode), files, servers, null)
            assertEquals("Read,Grep,Glob", launch.value("--tools"))
            assertEquals("dontAsk", launch.value("--permission-mode"))
            assertEquals("Read,Grep,Glob,mcp__bridge", launch.value("--allowedTools"))
            assertContains(launch.arguments, "--strict-mcp-config")
            assertTrue("bypassPermissions" !in launch.arguments)
        }
    }

    @Test fun resumeContinuesTheStoredConversation() {
        assertEquals("abc", ClaudeCommand.build("c", request(), files, ClaudeMcpServers.None, "abc").value("--resume"))
        assertTrue("--resume" !in ClaudeCommand.build("c", request(), files, ClaudeMcpServers.None, null).arguments)
    }

    @Test fun effortIsPassedOnlyWhenTheUserChoseOne() {
        fun args(effort: EffortSelection) = ClaudeCommand.build("c", request(profile = profile(effort = effort)), files, ClaudeMcpServers.None, null).arguments
        assertTrue("--effort" !in args(EffortSelection.Default))
        assertTrue("--effort" !in args(EffortSelection.of(ReasoningEffort.AUTO)))
        assertEquals("high", args(EffortSelection.of(ReasoningEffort.HIGH)).let { it[it.indexOf("--effort") + 1] })
    }

    @Test fun ownSignInIsUsedUnlessTheProfileHoldsAKey() {
        assertTrue(ClaudeCommand.build("c", request(), files, ClaudeMcpServers.None, null).environment.isEmpty())
        val keyed = ClaudeCommand.build("c", request(profile = profile(key = "sk-1")), files, ClaudeMcpServers.None, null).environment
        assertEquals(mapOf("ANTHROPIC_API_KEY" to "sk-1"), keyed)
    }

    @Test fun gatewayGetsItsAddressAndTokenWithoutTheVersionSuffix() {
        val gateway = ClaudeCommand.build("c", request(profile = profile("https://gw.example/anthropic/v1/", "tok", LlmAuthType.BEARER)),
            files, ClaudeMcpServers.None, null).environment
        assertEquals(mapOf("ANTHROPIC_BASE_URL" to "https://gw.example/anthropic", "ANTHROPIC_AUTH_TOKEN" to "tok"), gateway)
    }

    @Test fun applicationEnvironmentIsAppliedAndStaleEndpointsAreRemoved() {
        val tools = NativeAgentTools(emptyList(), emptyMap(), mapOf("MAGICPAPER_RESEARCH_URL" to "u"), setOf("MAGICPAPER_RESEARCH_URL", "MAGICPAPER_TOKEN_KEY"),
            JsonObject(emptyMap()))
        val launch = ClaudeCommand.build("c", request(tools = tools), files, ClaudeMcpServers.None, null)
        assertEquals("u", launch.environment["MAGICPAPER_RESEARCH_URL"])
        assertEquals(setOf("MAGICPAPER_TOKEN_KEY"), launch.removedEnvironment)
    }

    @Test fun mcpEndpointsBecomeClaudeServersAndDisabledOnesAreDropped() {
        val servers = ClaudeMcpServers.from(buildJsonObject {
            put("mcp_servers.tools", buildJsonObject {
                put("url", "http://127.0.0.1:9/mcp"); put("enabled", true); put("tool_timeout_sec", 604800); put("startup_timeout_sec", 10)
                put("http_headers", buildJsonObject { put("Authorization", "Bearer t") })
            })
            put("mcp_servers.computer", buildJsonObject { put("url", "http://127.0.0.1:1/mcp"); put("enabled", false) })
        })
        assertEquals(listOf("tools"), servers.names)
        val entry = servers.document.getValue("mcpServers").jsonObject.getValue("tools").jsonObject
        assertEquals("http", entry.getValue("type").jsonPrimitive.content)
        assertEquals("Bearer t", entry.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        assertEquals(604_800_000L, servers.toolTimeoutMillis)
        assertEquals(10_000L, servers.startupTimeoutMillis)
    }

    @Test fun singleRootObjectFormIsUnderstoodToo() {
        val servers = ClaudeMcpServers.from(buildJsonObject {
            put("mcp_servers", buildJsonObject { put("bridge", buildJsonObject { put("url", "http://x/mcp") }) })
        })
        assertEquals(listOf("bridge"), servers.names)
        assertTrue(ClaudeMcpServers.from(JsonObject(emptyMap())).names.isEmpty())
    }
}
