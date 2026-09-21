package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real native chat adapters, isolated histories, localhost scripted model; no account requests. */
class ChatRuntimeIntegrationTest {
    @Test fun bothEnginesReuseChatHistorySwitchModelAndLoadAgentsInstructions() = runBlocking {
        if (System.getProperty("magicpaper.pi.it") != "true" || System.getProperty("magicpaper.codex.it") != "true") return@runBlocking
        val root = Files.createTempDirectory("magicpaper-chat-native-").toFile()
        try {
            for (engine in CodingEngine.entries) {
                val directory = root.resolve(engine.name).apply { mkdirs() }
                val requests = CopyOnWriteArrayList<JsonObject>()
                val failures = CopyOnWriteArrayList<Throwable>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/") { exchange ->
                    try {
                        val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                        requests += body
                        fun chunk(content: Boolean) = buildJsonObject {
                            put("id", "reply-${requests.size}"); put("object", "chat.completion.chunk")
                            put("model", body.getValue("model"))
                            putJsonArray("choices") { add(buildJsonObject {
                                put("index", 0)
                                putJsonObject("delta") { if (content) { put("role", "assistant"); put("content", "REPLY-${requests.size}") } }
                                put("finish_reason", if (content) JsonNull else JsonPrimitive("stop"))
                            }) }
                        }
                        val payload = "data: ${chunk(true)}\n\ndata: ${chunk(false)}\n\ndata: [DONE]\n\n"
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { it.write(payload.toByteArray()) }
                    } catch (error: Throwable) { failures += error; exchange.close() }
                }
                server.start()
                val codex = CodexAppServerOpenAiSubscription(Json { ignoreUnknownKeys = true }, directory.resolve("codex").toPath(), browser = io.aequicor.magicpaper.data.coding.testBrowserSessions, checks = io.aequicor.magicpaper.data.coding.testCommandChecks, journal = io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), questionnaireFactory = io.aequicor.magicpaper.domain.testQuestionnaireFactory())
                val runtime = DesktopCodingRuntime(isolatedPiRuntime(directory), codex)
                try {
                    val session = ChatSession("chat-${engine.name}", "Fixture", 1, 1, engine = engine,
                        messages = listOf(ChatMessage("first", ChatRole.USER, "First", 1)))
                    val workspace = java.io.File(runtime.rootPath, "chat-workspaces/${session.id}").apply { mkdirs() }
                    workspace.resolve("AGENTS.md").writeText("Instruction marker: CHAT_AGENTS_FIXTURE. Answer briefly.")
                    fun profile(model: String) = LlmProfile("fixture", "Fixture", modelId = model,
                        baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "fixture")
                    val first = withTimeout(60_000) { runtime.runChat(session, "First", profile("model-one")).toList() }
                    assertTrue(first.none { it is CodingEvent.Failed }, "$engine: $first")
                    val nativeId = first.filterIsInstance<CodingEvent.SessionStarted>().first().sessionId
                    val reply = first.filterIsInstance<CodingEvent.FinalText>().last().text
                    val next = session.copy(nativeSessionId = nativeId, messages = session.messages +
                        ChatMessage("reply", ChatRole.AGENT, reply, 2) + ChatMessage("second", ChatRole.USER, "Second", 3))
                    val second = withTimeout(60_000) { runtime.runChat(next, "Second", profile("model-two")).toList() }
                    assertTrue(second.none { it is CodingEvent.Failed }, "$engine: $second")
                    assertEquals(nativeId, second.filterIsInstance<CodingEvent.SessionStarted>().first().sessionId)
                    assertEquals(listOf("model-one", "model-two"), requests.map { it.getValue("model").jsonPrimitive.content })
                    assertContains(requests.first().toString(), "CHAT_AGENTS_FIXTURE")
                    assertContains(requests.last().toString(), "REPLY-1")
                    assertTrue(failures.isEmpty(), failures.toString())
                    runtime.deleteChatSession(next)
                    assertFalse(workspace.exists())
                } finally { runtime.abortAll(); codex.close(); server.stop(0) }
            }
        } finally { root.deleteRecursively() }
    }
}
