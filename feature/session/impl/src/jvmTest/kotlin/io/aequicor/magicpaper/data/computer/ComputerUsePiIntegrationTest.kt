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
    @Test fun installedPiCarriesScreenshotBackToVisionModel(): Unit = runBlocking {
        val node = System.getenv("MAGICPAPER_COMPUTER_NODE") ?: return@runBlocking
        val cli = System.getenv("MAGICPAPER_COMPUTER_PI") ?: return@runBlocking
        val dir = Files.createTempDirectory("computer-pi-").toFile()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val chunks = if (requests.size == 1) listOf(
                """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"shot","type":"function","function":{"name":"computer","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"action\":\"screenshot\"}"}}]}}]}""",
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
        val computer = DesktopComputerUse(FakeComputerDesktop())
        computer.enable("a", ComputerAccess.SCREEN)
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
                    environment()["MAGICPAPER_COMPUTER_URL"] = bridge.url
                    environment()["MAGICPAPER_COMPUTER_TOKEN"] = bridge.token
                }.redirectErrorStream(true).redirectOutput(output).start()
                try {
                    process.outputStream.use { it.write("Describe the test screen".toByteArray()) }
                    assertTrue(process.waitFor(25, TimeUnit.SECONDS), "pi did not finish")
                    assertEquals(0, process.exitValue(), output.readText().take(4000))
                    assertEquals(2, requests.size, output.readText().take(4000))
                    val definitions = requests.first()["tools"]!!.jsonArray
                    assertTrue(definitions.any { it.jsonObject["function"]?.jsonObject?.get("name") == JsonPrimitive("computer") })
                    assertContains(requests.last()["messages"].toString(), "data:image/png;base64,")
                    assertContains(output.readText(), "SCREEN_RECEIVED")
                    assertNotNull(computer.state.value.preview)
                } finally {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
        } finally { model.stop(0); dir.deleteRecursively() }
    }
}
