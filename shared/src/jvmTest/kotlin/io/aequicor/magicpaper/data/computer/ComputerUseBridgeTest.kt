package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class ComputerUseBridgeTest {
    private val client = HttpClient.newHttpClient()

    private fun post(bridge: ComputerUseBridge, body: String, authorization: String? = "Bearer ${bridge.token}", origin: String? = null,
        method: String = "POST", version: String = "2025-06-18"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(bridge.url)).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", version)
        authorization?.let { request.header("Authorization", it) }
        origin?.let { request.header("Origin", it) }
        request.method(method, HttpRequest.BodyPublishers.ofString(body))
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun rpc(id: Int, method: String, params: JsonObject = buildJsonObject {}): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }.toString()
    private fun tool(id: Int, args: JsonObject) = rpc(id, "tools/call", buildJsonObject { put("name", "computer"); put("arguments", args) })
    private fun HttpResponse<String>.result() = Json.parseToJsonElement(body()).jsonObject["result"]!!.jsonObject

    @Test fun endpointRejectsUnauthenticatedBrowserAndInvalidRequests(): Unit = runBlocking {
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
        computer.bridge("a")!!.use { bridge ->
            val body = rpc(1, "tools/list")
            assertEquals(401, post(bridge, body, authorization = null).statusCode())
            assertEquals(401, post(bridge, body, authorization = "Bearer invalid").statusCode())
            assertEquals(403, post(bridge, body, origin = "https://example.com").statusCode())
            assertEquals(403, post(bridge, body, origin = "null").statusCode())
            assertEquals(405, post(bridge, "", method = "GET").statusCode())
            assertEquals(400, post(bridge, body, version = "unexpected").statusCode())
            assertEquals(400, post(bridge, "{").statusCode())
            assertEquals(413, post(bridge, "x".repeat(65537)).statusCode())
        }
    }

    @Test fun mcpHandshakeDiscoveryAndPngDelivery(): Unit = runBlocking {
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
        computer.bridge("a")!!.use { bridge ->
            val init = post(bridge, rpc(1, "initialize", buildJsonObject { put("protocolVersion", "2025-06-18") })).result()
            assertEquals(JsonPrimitive("2025-06-18"), init["protocolVersion"])
            assertNotNull(init["capabilities"]!!.jsonObject["tools"])
            assertEquals(202, post(bridge, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
            val tools = post(bridge, rpc(2, "tools/list")).result()["tools"]!!.jsonArray
            assertEquals("computer", tools.single().jsonObject["name"]!!.jsonPrimitive.content)
            val result = post(bridge, tool(3, request("screenshot")))
            assertEquals(200, result.statusCode())
            assertEquals("no-store", result.headers().firstValue("cache-control").orElse(""))
            assertFalse(result.result().failed())
            assertEquals("image", result.result()["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
            computer.disable("a")
            assertEquals(403, post(bridge, tool(4, request("screenshot"))).statusCode())
        }
        assertEquals(ComputerAccess.OFF, computer.state.value.access)
    }

    @Test fun duplicateRpcCannotRepeatInputAndCloseRevokesAccess(): Unit = runBlocking {
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop)
        computer.enable("a", ComputerAccess.CONTROL)
        val bridge = computer.bridge("a")!!
        bridge.use {
            val id = post(bridge, tool(1, request("screenshot"))).result().screenshotId()
            val click = tool(2, request("click") { put("screenshot_id", id); put("x", 10); put("y", 10) })
            assertFalse(post(bridge, click).result().failed())
            assertNotNull(Json.parseToJsonElement(post(bridge, click).body()).jsonObject["error"])
            assertEquals(1, desktop.performed.size)
        }
        assertNull(computer.grant("a"))
        assertFails { post(bridge, rpc(3, "ping")) }
    }

    @Test fun codexConfigurationPreservesSandboxAndReplacesOldEndpoint(): Unit = runBlocking {
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
        val base = buildJsonObject { put("sandbox_workspace_write.network_access", false) }
        computer.bridge("a")!!.use { bridge ->
            val enabled = ComputerUseBridge.codexConfig(base, bridge)
            assertEquals(JsonPrimitive(false), enabled["sandbox_workspace_write.network_access"])
            val server = enabled["mcp_servers.magicpaper_computer"]!!.jsonObject
            assertEquals(JsonPrimitive(true), server["required"])
            assertEquals(JsonPrimitive(bridge.url), server["url"])
            val disabled = ComputerUseBridge.codexConfig(enabled, null)["mcp_servers.magicpaper_computer"]!!.jsonObject
            assertEquals(JsonPrimitive(false), disabled["enabled"])
            assertTrue(disabled["http_headers"]!!.jsonObject.isEmpty())
            assertNotEquals(server["url"], disabled["url"])
        }
    }

    /** Optional local Node smoke test: executes the actual generated extension against the bridge. */
    @Test fun piExtensionReturnsVisionContentAndThrowsForDeniedInput(): Unit = runBlocking {
        val node = System.getenv("MAGICPAPER_COMPUTER_NODE") ?: return@runBlocking
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
        val dir = Files.createTempDirectory("computer-extension-").toFile()
        try {
            dir.resolve("computer.mjs").writeText(PiComputerExtension.source)
            val test = dir.resolve("check.mjs").apply { writeText("""
                import register from './computer.mjs';
                import assert from 'node:assert/strict';
                let tool;
                register({ registerTool(t) { tool = t; } });
                assert.equal(tool.name, 'computer');
                assert.equal(tool.parameters.type, 'object');
                const shot = await tool.execute('one', { action: 'screenshot' });
                assert.equal(shot.content[1].type, 'image');
                assert.equal(shot.content[1].mimeType, 'image/png');
                const id = JSON.parse(shot.content[0].text).screenshot_id;
                await assert.rejects(() => tool.execute('two', { action: 'click', screenshot_id: id, x: 1, y: 1 }), /только просмотр/);
                // A continuation loads a new extension, with its own distinct RPC ids.
                register({ registerTool(t) { tool = t; } });
                assert.equal((await tool.execute('three', { action: 'screenshot' })).content[1].type, 'image');
                console.log('Pi extension: image delivery, denial, and continuation passed');
            """.trimIndent()) }
            computer.bridge("a")!!.use { bridge ->
                val output = dir.resolve("output.txt")
                val process = ProcessBuilder(node, test.absolutePath).apply {
                    environment()["MAGICPAPER_COMPUTER_URL"] = bridge.url
                    environment()["MAGICPAPER_COMPUTER_TOKEN"] = bridge.token
                }.redirectErrorStream(true).redirectOutput(output).start()
                try {
                    assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Node extension did not finish")
                    assertEquals(0, process.exitValue(), output.readText())
                } finally { process.destroyForcibly() }
            }
        } finally { dir.deleteRecursively() }
    }
}
