package io.aequicor.magicpaper.data.computer

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.coding.PiModelsConfig
import io.aequicor.magicpaper.domain.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real installed pi, generated extension, and a local fake vision model. */
class ComputerUsePiIntegrationTest {
    @Test fun installedPiCarriesScreenshotBackToVisionModel() = screenshotRoundTrip("computer")
    @Test fun installedPiCarriesBackgroundWindowBackToVisionModel() = screenshotRoundTrip("application")

    private fun screenshotRoundTrip(tool: String): Unit = runBlocking {
        val node = System.getenv("MAGICPAPER_COMPUTER_NODE")
        val cli = System.getenv("MAGICPAPER_COMPUTER_PI")
        org.junit.Assume.assumeTrue("Installed engine smoke test is opt-in", node != null && cli != null)
        val arguments = request("screenshot") { if (tool == "application") put("window_id", "w") }
        val dir = Files.createTempDirectory("computer-pi-").toFile()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            fun screenshotId(value: JsonElement): String? = when (value) {
                is JsonObject -> value.values.firstNotNullOfOrNull(::screenshotId)
                is JsonArray -> value.firstNotNullOfOrNull(::screenshotId)
                is JsonPrimitive -> if (value.isString) Regex("\"screenshot_id\"\\s*:\\s*\"([^\"]+)\"").find(value.content)?.groupValues?.get(1) else null
            }
            val next = if (tool == "computer" && requests.size == 2) request("screenshot") {
                put("screenshot_id", checkNotNull(screenshotId(requests.last()["messages"]!!)))
                put("region", buildJsonObject { put("x", 400); put("y", 200); put("width", 100); put("height", 50) })
            } else arguments
            val encodedArguments = JsonPrimitive(next.toString())
            val chunks = if (requests.size <= 2) listOf(
                """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"shot-${requests.size}","type":"function","function":{"name":"$tool","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":$encodedArguments}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            ) else listOf(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":"SCREEN_RECEIVED"}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            )
            val bytes = (chunks.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        model.start()
        val desktop = FakeComputerDesktop()
        val computer = DesktopComputerUse(desktop, applicationFactory = { FakeApplicationDesktop() })
        if (tool == "computer") computer.enable("a", ComputerAccess.SCREEN)
        else { computer.configure(ComputerAccess.OFF, ComputerAccess.SCREEN); computer.begin("a") }
        try {
            val home = dir.resolve("home").apply { mkdirs() }
            home.resolve("models.json").writeText(PiModelsConfig.json(LlmProfile("test", "Local test",
                modelId = "mock-model", baseUrl = "http://127.0.0.1:${model.address.port}/v1", apiKey = "local-test"), imageInput = true))
            home.resolve("settings.json").writeText("""{"defaultProjectTrust":"never","telemetry":false}""")
            val extension = home.resolve("computer.mjs").apply { writeText(PiComputerExtension.source) }
            computer.bridge("a")!!.use { bridge ->
                val output = dir.resolve("output.txt")
                val process = ProcessBuilder(node, cli, "--mode", "json", "--provider", PiModelsConfig.PROVIDER_ID,
                    "--model", "mock-model", "--thinking", "off", "--no-extensions", "--no-skills", "--no-prompt-templates",
                    "--no-themes", "--no-approve", "--extension", extension.absolutePath,
                ).directory(dir).apply {
                    environment()["PI_CODING_AGENT_DIR"] = home.absolutePath
                    environment()["PI_OFFLINE"] = "1"
                    environment()["PI_SKIP_VERSION_CHECK"] = "1"
                    environment()["PI_TELEMETRY"] = "0"
                    environment()[PiModelsConfig.API_KEY_ENV] = "local-test"
                    environment()["MAGICPAPER_COMPUTER_URL"] = bridge.url
                    environment()["MAGICPAPER_COMPUTER_TOKEN"] = bridge.token
                }.redirectErrorStream(true).redirectOutput(output).start()
                try {
                    process.outputStream.use { it.write("Describe the test screen".toByteArray()) }
                    assertTrue(process.waitFor(25, TimeUnit.SECONDS), "pi did not finish")
                    assertEquals(0, process.exitValue(), output.readText().take(4000))
                    assertEquals(3, requests.size, output.readText().take(4000))
                    val definitions = requests.first()["tools"]!!.jsonArray
                    assertTrue(definitions.any { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive(tool) })
                    val sent = requests.last()["messages"].toString()
                    assertEquals(1, Regex("data:image/(jpeg|png);base64,").findAll(sent).count(), "Only the latest screenshot should reach the model")
                    assertContains(sent, if (tool == "computer") "data:image/png;base64," else "data:image/jpeg;base64,")
                    assertContains(sent, "Earlier screenshot image omitted")
                    assertContains(output.readText(), "SCREEN_RECEIVED")
                    if (tool == "computer") {
                        assertNotNull(computer.state.value.preview)
                        assertEquals(DesktopCaptureRequest(DesktopRegion(640, 320, 160, 80), ScreenshotResolution.NATIVE), desktop.captureRequests.last())
                    }
                    else { assertNull(computer.state.value.preview); assertEquals(0, desktop.captures) }
                } finally {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
        } finally { computer.disable(); model.stop(0); dir.deleteRecursively() }
    }
}
