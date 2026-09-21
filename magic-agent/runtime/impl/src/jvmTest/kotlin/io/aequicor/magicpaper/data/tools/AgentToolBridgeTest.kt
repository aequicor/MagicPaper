package io.aequicor.magicpaper.data.tools

import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.MediaKind
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.serialization.json.*
import kotlin.test.*
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking

class AgentToolBridgeTest {
    @Test fun generatedAssetsReachBothAgentSurfacesAsReusablePathsWithoutUntrustedImageBlocks() = runBlocking {
        val path = "/tmp/immutable-media/asset.png"
        val result = buildJsonObject {
            put("path", path)
            putJsonObject("media") { put("id", "asset"); put("kind", "IMAGE"); put("phase", "READY") }
            putJsonObject("image") { put("type", "image"); put("data", "untrusted-inline-data"); put("mimeType", "image/png") }
        }
        val host = testToolSessions(MemoryToolReceiptStore())
        for (mode in listOf(CodingInteractionMode.CODE, CodingInteractionMode.RESEARCH)) {
            val tools = host.session(ToolExecutionContext("p", "s", "s", "r-$mode", ToolRole.CHAT, mode,
                mediaCapabilities = setOf(MediaKind.IMAGE)), overrides = mapOf("image.generate" to { _, _, _ -> result }))
            assertTrue("magicpaper_image_generate" in PiAgentToolExtension.source(tools))
            AgentToolBridge(tools, io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
                assertTrue("magicpaper_image_generate" in bridge.list())
                val response = bridge.call(JsonPrimitive(2), "magicpaper_image_generate", buildJsonObject { put("prompt", "A diagram") })
                assertEquals(JsonPrimitive(false), response["isError"])
                val content = response["content"]!!.jsonArray
                assertEquals(1, content.size)
                assertEquals(JsonPrimitive("text"), content.single().jsonObject["type"])
                val decoded = Json.parseToJsonElement(content.single().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
                assertEquals(JsonPrimitive(path), decoded["path"])
                assertEquals(result, decoded)
            }
        }
    }

    @Test fun resumedWorkerCanHandOffWhenTransportRequestNumbersRestart() = runBlocking {
        val store = MemoryToolReceiptStore()
        val executed = mutableListOf<String>()
        val host = testToolSessions(store, receiver = { _, _, tool, _ -> executed.add(tool); buildJsonObject { put("status", "applied") } })
        val context = ToolExecutionContext("p", "worker", "worker", "attempt-turn-0",
            ToolRole.WORKER, CodingInteractionMode.CODE, "plan")
        // The earlier transport used request 2 to read context; the resumed worker's
        // same request number now carries its finished report under the same durable turn.
        AgentToolBridge(host.session(context), io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
            assertFalse(bridge.call(JsonPrimitive(2), "context.get")["isError"]!!.jsonPrimitive.boolean)
        }
        AgentToolBridge(host.session(context), io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
            val report = buildJsonObject { put("kind", "RESULT"); put("text", "Verified report") }
            val response = bridge.call(JsonPrimitive(2), "stage.handoff", report)
            assertFalse(response["isError"]!!.jsonPrimitive.boolean, response.toString())
            assertEquals(response, bridge.call(JsonPrimitive(2), "stage.handoff", report))
            // JSON-RPC string and number IDs are distinct even in a single transport.
            assertFalse(bridge.call(JsonPrimitive("2"), "context.get")["isError"]!!.jsonPrimitive.boolean)
            assertFailsWith<java.io.IOException> {
                bridge.call(JsonPrimitive(2), "stage.handoff", buildJsonObject { put("kind", "RESULT"); put("text", "Changed") })
            }
        }
        assertEquals(listOf("context.get", "stage.handoff", "context.get"), executed)
        val receipts = store.forRequest("p/worker/attempt-turn-0")
        assertEquals(3, receipts.size)
        assertEquals(3, receipts.map { it.operationId }.distinct().size)
        assertTrue(receipts.all { it.phase == ToolPhase.SUCCEEDED })
    }

    @Test fun orchestrationToolsStayHiddenFromOrdinaryAndResearchRunsOnBothAgentSurfaces() = runBlocking {
        val host = testToolSessions(MemoryToolReceiptStore())
        val orchestration = SessionToolCatalog.definitions.filter { it.orchestration }.map { it.wireName }
        assertTrue(orchestration.isNotEmpty())
        val create = buildJsonObject {
            put("name", "Child"); put("task", "Inspect"); put("acceptance", "Verified findings")
        }
        for (mode in CodingInteractionMode.entries) {
            val session = host.session(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, mode))
            val embedded = PiAgentToolExtension.source(session)
            AgentToolBridge(session, io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
                val listed = bridge.list()
                for (wire in orchestration) {
                    assertEquals(mode == CodingInteractionMode.PLANNING, wire in embedded, "$wire in pi extension for $mode")
                    assertEquals(mode == CodingInteractionMode.PLANNING, wire in listed, "$wire in tools/list for $mode")
                }
            }
            if (mode != CodingInteractionMode.PLANNING)
                assertFailsWith<IllegalArgumentException> { session.call("denied", "session.create", create) }
        }
    }

    private fun AgentToolBridge.list(): List<String> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", JsonPrimitive(1)); put("method", "tools/list") }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            return Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        } finally { connection.disconnect() }
    }

    private fun AgentToolBridge.call(id: JsonPrimitive, tool: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            val body = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
                put("params", buildJsonObject { put("name", tool); put("arguments", args) })
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            return Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                .jsonObject["result"]!!.jsonObject
        } finally { connection.disconnect() }
    }

    @Test fun streamProbesAreDeclinedWithAllowAndUnauthenticatedOnesStillForbidden() {
        val session = testToolSessions(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r",
            ToolRole.PLANNER, CodingInteractionMode.PLANNING, "plan"))
        AgentToolBridge(session, io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
            fun status(method: String, authorized: Boolean): Pair<Int, String?> {
                val connection = URI(bridge.url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method
                    if (authorized) connection.setRequestProperty("Authorization", "Bearer ${bridge.token}")
                    connection.connectTimeout = 5_000; connection.readTimeout = 5_000
                    return connection.responseCode to connection.getHeaderField("Allow")
                } finally { connection.disconnect() }
            }
            assertEquals(405 to "POST", status("GET", authorized = true))
            assertEquals(405 to "POST", status("DELETE", authorized = true))
            assertEquals(403, status("GET", authorized = false).first)
        }
    }

    @Test fun restrictedMcpAllowlistCannotOverwriteTheBridgeAndOrdinarySessionsKeepInheritedServers() {
        val session = testToolSessions(MemoryToolReceiptStore()).session(ToolExecutionContext("p", "s", "s", "r",
            ToolRole.PLANNER, CodingInteractionMode.PLANNING, "plan"))
        AgentToolBridge(session, io.aequicor.magicpaper.data.browser.fakeBrowserSession()).use { bridge ->
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
