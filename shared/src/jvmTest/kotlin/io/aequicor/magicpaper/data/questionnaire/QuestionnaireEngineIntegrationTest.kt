package io.aequicor.magicpaper.data.questionnaire

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.coding.PiModelsConfig
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real engine binaries, isolated homes and local deterministic models. No account/API credential is used. */
class QuestionnaireEngineIntegrationTest {
    private val arguments = """{"questions":[{"id":"format","title":"Формат?","kind":"SINGLE","options":[{"id":"pdf","label":"PDF"}]},{"id":"extra","title":"Дополнения?","kind":"TEXT","options":[]}]}"""
    private val answers = listOf(PlanningAnswer("format", listOf("pdf"), "Комментарий"), PlanningAnswer("extra", skipped = true))
    private fun model(requests: MutableList<JsonObject>, responses: Boolean): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            requests += body
            val ask = requests.size % 2 == 1
            val payload = if (!responses) {
                val chunks = if (ask) listOf(
                    """{"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"question","type":"function","function":{"name":"questionnaire","arguments":""}}]}}]}""",
                    buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray { add(buildJsonObject { put("index", 0); put("function", buildJsonObject { put("arguments", arguments) }) }) })
                    }) }) }) }.toString(),
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")
                else listOf("""{"choices":[{"index":0,"delta":{"role":"assistant","content":"ANSWERS_RECEIVED"}}]}""", """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
                chunks.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") { "data: $it" }
            } else {
                val name = (body["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
                    .firstOrNull { it.endsWith("questionnaire") } ?: "mcp__magicpaper_questionnaire__questionnaire"
                val last = (body["input"] as? JsonArray)?.lastOrNull() as? JsonObject
                val answered = last?.get("type") == JsonPrimitive("function_call_output") && last["call_id"]?.jsonPrimitive?.content?.startsWith("question_") == true
                val discovered = body.toString().contains("\"name\":\"questionnaire\"")
                val item = if (!answered && !discovered) buildJsonObject {
                    put("id", "search_${requests.size}"); put("type", "tool_search_call"); put("call_id", "search_${requests.size}")
                    put("execution", "client"); put("status", "completed"); put("arguments", buildJsonObject { put("query", "magicpaper_questionnaire questionnaire") })
                } else if (!answered) buildJsonObject {
                    put("id", "fc_${requests.size}"); put("type", "function_call"); put("call_id", "question_${requests.size}")
                    put("name", "questionnaire"); put("namespace", "mcp__magicpaper_questionnaire"); put("arguments", arguments); put("status", "completed")
                } else Json.parseToJsonElement("""{"id":"msg_${requests.size}","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ANSWERS_RECEIVED","annotations":[]}]}""").jsonObject
                listOf(
                    "response.created" to """{"type":"response.created","response":{"id":"resp_${requests.size}"}}""",
                    "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                    "response.completed" to """{"type":"response.completed","response":{"id":"resp_${requests.size}","status":"completed","output":[$item],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}"""
                ).joinToString("\n\n", postfix = "\n\n") { (event, data) -> "event: $event\ndata: $data" }
            }
            val bytes = payload.toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start(); return server
    }

    @Test fun installedPiExtensionReturnsConfirmedChoicesTextAndSkippedQuestions() = runBlocking {
        val node = System.getenv("MAGICPAPER_QUESTIONNAIRE_NODE") ?: return@runBlocking
        val cli = System.getenv("MAGICPAPER_QUESTIONNAIRE_PI") ?: return@runBlocking
        val root = Files.createTempDirectory("questionnaire-pi-").toFile()
        val requests = CopyOnWriteArrayList<JsonObject>(); val model = model(requests, false)
        val registry = RuntimeQuestionnaires()
        try {
            val home = root.resolve("home").apply { mkdirs() }
            home.resolve("models.json").writeText(PiModelsConfig.json(LlmProfile("local", "Local", modelId = "mock-model", baseUrl = "http://127.0.0.1:${model.address.port}/v1", apiKey = "local-test")))
            home.resolve("settings.json").writeText("""{"defaultProjectTrust":"never","telemetry":false}""")
            val extension = home.resolve("questionnaire.mjs").apply { writeText(PiQuestionnaireExtension.source) }
            QuestionnaireBridge(registry, CodingSession("s", "p", "Session", 0)).use { bridge ->
                val output = root.resolve("output.txt")
                val process = ProcessBuilder(node, cli, "--mode", "json", "--provider", PiModelsConfig.PROVIDER_ID,
                    "--model", "mock-model", "--thinking", "off", "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes", "--no-approve",
                    "--extension", extension.absolutePath).directory(root).apply {
                    environment()["PI_CODING_AGENT_DIR"] = home.absolutePath; environment()["PI_OFFLINE"] = "1"
                    environment()["PI_SKIP_VERSION_CHECK"] = "1"; environment()["PI_TELEMETRY"] = "0"
                    environment()["MAGICPAPER_QUESTIONNAIRE_URL"] = bridge.url; environment()["MAGICPAPER_QUESTIONNAIRE_TOKEN"] = bridge.token
                }.redirectErrorStream(true).redirectOutput(output).start()
                try {
                    process.outputStream.use { it.write("Ask for a format".toByteArray()) }
                    val question = withTimeout(25_000) { registry.requests.first { it.isNotEmpty() }.single() }
                    assertEquals(1, requests.size); assertTrue(process.isAlive)
                    registry.respond(question.id, answers)
                    assertTrue(withContext(Dispatchers.IO) { process.waitFor(25, TimeUnit.SECONDS) }, output.readText())
                    assertEquals(0, process.exitValue(), output.readText())
                    assertEquals(2, requests.size, output.readText())
                    assertContains(requests.last().toString(), "Комментарий"); assertContains(requests.last().toString(), "skipped")
                    assertContains(output.readText(), "ANSWERS_RECEIVED")
                } finally { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
        } finally { model.stop(0); root.deleteRecursively() }
    }

    @Test fun installedCodexCallsQuestionnaireOnNewAndResumedSessions() = runBlocking {
        val binary = System.getenv("MAGICPAPER_QUESTIONNAIRE_CODEX") ?: return@runBlocking
        val root = Files.createTempDirectory("questionnaire-codex-")
        val requests = CopyOnWriteArrayList<JsonObject>(); val model = model(requests, true)
        val profile = LlmProfile("local", "Local", modelId = "gpt-5.4", baseUrl = "http://127.0.0.1:${model.address.port}/v1")
        val config = buildJsonObject { put("model_providers.questionnaire_test", buildJsonObject {
            put("name", "Local test"); put("base_url", profile.baseUrl); put("wire_api", "responses")
        }) }
        var native = ""
        try {
            repeat(2) {
                val service = CodexAppServerOpenAiSubscription(Json { ignoreUnknownKeys = true }, root.resolve("home"), binary)
                try {
                    val observed = CopyOnWriteArrayList<CodingEvent>()
                    val events = async { service.runCoding(CodingProject("p", "Project", root.toString(), 0),
                        CodingSession("s", "p", "Session", 0, piSessionId = native, engine = CodingEngine.CODEX), "Ask a question", profile, emptyList(),
                        "questionnaire_test", config).onEach { observed += it }.toList() }
                    val question = withTimeoutOrNull(15_000) { service.codingQuestionnaires.first { it.isNotEmpty() }.single() }
                        ?: error("No Codex questionnaire: events=$observed, modelRequests=${requests.size}, input=${requests.lastOrNull()?.get("input")?.jsonArray?.takeLast(3)}, tools=${requests.lastOrNull()?.get("tools")}")
                    assertFalse(events.isCompleted)
                    service.respondCodingQuestionnaire(question.id, answers)
                    val result = withTimeout(30_000) { events.await() }
                    assertTrue(result.none { it is CodingEvent.Failed }, result.toString())
                    val thread = result.filterIsInstance<CodingEvent.SessionStarted>().single().sessionId
                    if (native.isNotBlank()) assertEquals(native, thread)
                    native = thread
                    assertContains(requests.last().toString(), "Комментарий")
                } finally { service.close() }
            }
            assertTrue(requests.size >= 4)
        } finally { model.stop(0); root.toFile().deleteRecursively() }
    }
}
