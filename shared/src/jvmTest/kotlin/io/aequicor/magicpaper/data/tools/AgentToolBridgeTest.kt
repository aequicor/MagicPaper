package io.aequicor.magicpaper.data.tools

import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentToolBridgeTest {
    @Test fun restrictedMcpAllowlistCannotOverwriteTheBridgeAndOrdinarySessionsKeepInheritedServers() {
        val session = ToolHost(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r",
            ToolRole.PLANNER, CodingInteractionMode.PLANNING, "plan"))
        AgentToolBridge(session).use { bridge ->
            val disabled = buildJsonObject { put("enabled", false) }
            val base = buildJsonObject {
                put("sandbox", "read-only")
                put("mcp_servers", buildJsonObject { put("magicpaper_research", buildJsonObject { put("enabled", true) }) })
                put("mcp_servers.magicpaper_computer", disabled)
            }
            for (ordered in listOf(base, JsonObject(base.entries.reversed().associate { it.toPair() }))) {
                val config = bridge.codexConfig(ordered)
                assertTrue(config.keys.none { it.startsWith("mcp_servers.") })
                val servers = config["mcp_servers"]!!.jsonObject
                assertEquals(setOf("magicpaper_agent_tools", "magicpaper_research", "magicpaper_computer"), servers.keys)
                assertEquals(JsonPrimitive(true), servers["magicpaper_agent_tools"]!!.jsonObject["required"])
                assertEquals(disabled, servers["magicpaper_computer"])
                assertEquals(JsonPrimitive("read-only"), config["sandbox"])
            }
            val ordinary = bridge.codexConfig(JsonObject(base - "mcp_servers"))
            assertFalse("mcp_servers" in ordinary, "Working sessions must retain native inherited MCP settings")
            assertTrue("mcp_servers.magicpaper_agent_tools" in ordinary)
        }
    }
}
