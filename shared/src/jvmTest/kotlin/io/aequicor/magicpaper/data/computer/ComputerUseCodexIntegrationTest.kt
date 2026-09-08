package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.ComputerAccess
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Local protocol smoke test with a fake model and screen; no account or remote API is used. */
class ComputerUseCodexIntegrationTest {
    @Test fun installedAppServerDiscoversAndCallsComputerMcp(): Unit = runBlocking {
        val binary = System.getenv("MAGICPAPER_COMPUTER_CODEX") ?: return@runBlocking
        val dir = Files.createTempDirectory("computer-codex-").toFile()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            val item = """{"id":"msg_test","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Ready","annotations":[]}]}"""
            val events = listOf(
                "response.created" to """{"type":"response.created","response":{"id":"resp_test"}}""",
                "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                "response.completed" to """{"type":"response.completed","response":{"id":"resp_test","status":"completed","output":[$item],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
            ).joinToString("\n\n", postfix = "\n\n") { (event, data) -> "event: $event\ndata: $data" }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, events.size.toLong())
            exchange.responseBody.use { it.write(events) }
        }
        model.start()
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
        try {
            computer.bridge("a")!!.use { bridge ->
                val process = ProcessBuilder(binary,
                    "-c", "model_provider=\"computer_test\"", "-c", "model=\"test-model\"",
                    "-c", "model_providers.computer_test={name=\"Computer test\",base_url=\"http://127.0.0.1:${model.address.port}/v1\",wire_api=\"responses\"}",
                    "app-server",
                ).apply {
                    environment()["CODEX_HOME"] = dir.resolve("codex").apply { mkdirs() }.absolutePath
                    environment().remove("OPENAI_API_KEY")
                }.directory(dir).redirectError(dir.resolve("stderr.log")).start()
                val lines = LinkedBlockingQueue<JsonObject>()
                val reader = thread(isDaemon = true, name = "computer-test-codex-reader") {
                    process.inputStream.bufferedReader().useLines { input -> input.forEach { line ->
                        runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.let(lines::offer)
                    } }
                }
                val writer = process.outputStream.bufferedWriter()
                var nextId = 0
                val notifications = mutableListOf<JsonObject>()
                fun call(method: String, params: JsonObject): JsonObject {
                    val id = ++nextId
                    writer.write(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
                    writer.newLine(); writer.flush()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                    while (System.nanoTime() < deadline) {
                        val message = lines.poll(1, TimeUnit.SECONDS) ?: continue
                        if (message["id"] == JsonPrimitive(id)) {
                            assertNull(message["error"], "Codex method $method failed: ${message["error"]}")
                            return message["result"]!!.jsonObject
                        }
                        notifications += message
                    }
                    error("Codex method $method timed out")
                }
                try {
                    call("initialize", buildJsonObject {
                        put("clientInfo", buildJsonObject { put("name", "magicpaper_test"); put("version", "1") })
                        put("capabilities", buildJsonObject { put("experimentalApi", true) })
                    })
                    writer.write("{\"method\":\"initialized\"}\n"); writer.flush()
                    val result = call("thread/start", buildJsonObject {
                        put("cwd", dir.absolutePath); put("model", "test-model"); put("approvalPolicy", "never")
                        put("config", ComputerUseBridge.codexConfig(buildJsonObject {}, bridge))
                    })
                    val threadId = result["thread"]!!.jsonObject["id"]!!.jsonPrimitive.content
                    val screenshot = call("mcpServer/tool/call", buildJsonObject {
                        put("threadId", threadId); put("server", "magicpaper_computer"); put("tool", "computer")
                        put("arguments", request("screenshot"))
                    })
                    assertFalse(screenshot.failed())
                    assertEquals("image", screenshot["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
                    assertNotNull(computer.state.value.preview)
                    call("turn/start", buildJsonObject {
                        put("threadId", threadId)
                        put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "Test persistence") }) })
                    })
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (notifications.none { it["method"] == JsonPrimitive("turn/completed") } && System.nanoTime() < deadline) {
                        lines.poll(1, TimeUnit.SECONDS)?.let(notifications::add)
                    }
                    assertTrue(notifications.any { it["method"] == JsonPrimitive("turn/completed") }, "Fake model turn did not finish")
                    bridge.close()
                    computer.enable("a", ComputerAccess.SCREEN)
                    computer.bridge("a")!!.use { renewed ->
                        call("thread/unsubscribe", buildJsonObject { put("threadId", threadId) })
                        call("thread/resume", buildJsonObject {
                            put("threadId", threadId)
                            put("config", ComputerUseBridge.codexConfig(buildJsonObject {}, renewed))
                        })
                        val resumed = call("mcpServer/tool/call", buildJsonObject {
                            put("threadId", threadId); put("server", "magicpaper_computer"); put("tool", "computer")
                            put("arguments", request("screenshot"))
                        })
                        assertFalse(resumed.failed())
                        assertEquals("image", resumed["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
                    }
                } finally {
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                    reader.join(1000)
                }
            }
        } finally { model.stop(0); dir.deleteRecursively() }
    }
}
