package io.aequicor.magicpaper.data.tools

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.coding.PiCodingRuntime
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Production transports, real installed binaries, local scripted models; no account or paid request. */
class AgentToolEngineIntegrationTest {
    private class Fixture(val responses: Boolean) : AutoCloseable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}/v1"
        init {
            server.createContext("/") { exchange ->
                try {
                    val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    check(requests.size < 12) { "Unexpected model loop: ${requests.map { it["input"]?.jsonArray?.map { item -> (item as? JsonObject)?.get("type") } }}" }
                    requests += request
                    val output = if (responses) responses(request) else chat(request)
                    val bytes = output.toByteArray()
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (error: Throwable) { errors += error; exchange.sendResponseHeaders(400, -1); exchange.close() }
            }
            server.start()
        }
        private fun args(index: Int) = when (index) {
            0 -> "{}"
            1 -> """{"stageId":"foreign","message":"FORBIDDEN"}"""
            else -> """{"reply":"PLAN_RECEIVED","tree":[],"milestones":[]}"""
        }
        private fun name(index: Int) = listOf("magicpaper_context_get", "magicpaper_stage_send", "magicpaper_plan_propose")[index]
        private fun chat(request: JsonObject): String {
            val results = request["messages"]!!.jsonArray.count { it.jsonObject["role"] == JsonPrimitive("tool") }
            val delta = if (results < 3) buildJsonObject {
                put("role", "assistant"); putJsonArray("tool_calls") { add(buildJsonObject {
                    put("index", 0); put("id", "call_$results"); put("type", "function")
                    putJsonObject("function") { put("name", name(results)); put("arguments", args(results)) }
                }) }
            } else buildJsonObject { put("role", "assistant"); put("content", "TOOLS_DONE") }
            val first = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("index", 0); put("delta", delta) }) } }
            return "data: $first\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"${if (results < 3) "tool_calls" else "stop"}\"}]}\n\ndata: [DONE]\n\n"
        }
        private fun responses(request: JsonObject): String {
            val input = request["input"]!!.jsonArray.mapNotNull { it as? JsonObject }
            val outputs = input.filter { it["type"] == JsonPrimitive("function_call_output") }
            val completed = outputs.mapNotNull { it["call_id"]?.jsonPrimitive?.content }.toSet()
            val index = (0..2).firstOrNull { "call_$it" !in completed } ?: 3
            val discovered = request["tools"].toString().contains("magicpaper_context_get") ||
                input.any { it["type"] == JsonPrimitive("tool_search_output") && it.toString().contains("magicpaper_context_get") }
            val item = if (!discovered && completed.isEmpty()) buildJsonObject {
                put("id", "search_${requests.size}"); put("type", "tool_search_call"); put("call_id", "search_${requests.size}")
                put("execution", "client"); put("status", "completed")
                putJsonObject("arguments") { put("query", "magicpaper_agent_tools magicpaper_context_get magicpaper_plan_propose") }
            } else if (index < 3) buildJsonObject {
                put("id", "fc_${requests.size}"); put("type", "function_call"); put("call_id", "call_$index")
                put("name", name(index)); put("namespace", "mcp__magicpaper_agent_tools"); put("arguments", args(index)); put("status", "completed")
            } else Json.parseToJsonElement("""{"id":"msg_${requests.size}","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"TOOLS_DONE","annotations":[]}]}""").jsonObject
            return listOf(
                "response.created" to """{"type":"response.created","response":{"id":"resp_${requests.size}"}}""",
                "response.output_item.done" to """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                "response.completed" to """{"type":"response.completed","response":{"id":"resp_${requests.size}","status":"completed","output":[$item],"usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}"""
            ).joinToString("\n\n", postfix = "\n\n") { (event, data) -> "event: $event\ndata: $data" }
        }
        override fun close() { server.stop(0) }
    }
    private fun tools(): ToolSession {
        val host = ToolHost(MemoryToolReceiptStore())
        host.receiver = { _, _, name, _ -> check(name == "context.get") { "Forbidden receiver reached: $name" }; JsonPrimitive("CONTEXT_OK") }
        return host.session(ToolExecutionContext("p", "s", "s", "request", ToolRole.PLANNER, CodingInteractionMode.PLANNING, "plan"),
            mapOf("plan.propose" to { _, _, args -> args }))
    }
    private fun verify(events: List<CodingEvent>, tools: ToolSession, fixture: Fixture) {
        assertTrue(fixture.errors.isEmpty(), fixture.errors.toString() + "\nSearch outputs: " + fixture.requests.last()["input"]?.jsonArray
            ?.filter { (it as? JsonObject)?.get("type") == JsonPrimitive("tool_search_output") }?.take(1)?.toString()?.take(6000) + "\nSearch definition: " + fixture.requests.first()["tools"]?.jsonArray
            ?.filter { it.jsonObject["type"] == JsonPrimitive("tool_search") }?.toString()?.take(6000))
        assertTrue(events.none { it is CodingEvent.Failed }, events.toString())
        assertTrue(events.filterIsInstance<CodingEvent.FinalText>().any { it.text.contains("TOOLS_DONE") }, events.toString())
        assertEquals(JsonPrimitive("CONTEXT_OK"), tools.results.value["context.get"], fixture.requests.last()["input"]?.jsonArray
            ?.filter { (it as? JsonObject)?.get("type") != JsonPrimitive("message") }?.toString()?.take(12000))
        assertTrue("plan.propose" in tools.results.value)
        assertFalse("stage.send" in tools.results.value)
        assertContains(fixture.requests.last().toString(), "CONTEXT_OK")
        assertEquals(1, events.filterIsInstance<CodingEvent.ToolStarted>().count { it.tool == "context.get" })
        assertEquals(1, events.filterIsInstance<CodingEvent.ToolFinished>().count { it.tool == "plan.propose" && !it.isError })
        assertEquals(CodingEvent.Finished, events.last())
    }
    @Test fun piPlanningInvokesToolsReceivesResultsAndRejectsForbiddenRole() = runBlocking {
        if (System.getProperty("magicpaper.pi.it") != "true") return@runBlocking
        val root = Files.createTempDirectory("magicpaper-agent-tools-pi-")
        val runtime = PiCodingRuntime()
        Fixture(false).use { fixture ->
            try {
                val tools = tools()
                val profile = LlmProfile("local", "Local", modelId = "mock-model", baseUrl = fixture.url, apiKey = "local-test")
                val events = withTimeout(60_000) { runtime.runPlanning(CodingProject("p", "Project", root.toString(), 0),
                    CodingSession("tool-it-${System.nanoTime()}", "p", "Planner", 0, engine = CodingEngine.PI, planningMode = true),
                    "Read application context, submit a plan, then finish", profile).withTools(tools).toList() }
                verify(events, tools, fixture)
            } finally { runtime.abortAll(); root.toFile().deleteRecursively() }
        }
    }
    @Test fun codexPlanningInvokesMcpToolsReceivesResultsAndRejectsForbiddenRole() = runBlocking {
        if (System.getProperty("magicpaper.codex.it") != "true") return@runBlocking
        val root = Files.createTempDirectory("magicpaper-agent-tools-codex-")
        Fixture(true).use { fixture ->
            val runtime = CodexAppServerOpenAiSubscription(Json { ignoreUnknownKeys = true }, root.resolve("home"))
            try {
                val tools = tools()
                val profile = LlmProfile("local", "Local", modelId = "gpt-5.4", baseUrl = fixture.url)
                val config = buildJsonObject { putJsonObject("model_providers.agent_tools_test") {
                    put("name", "Local test"); put("base_url", fixture.url); put("wire_api", "responses")
                } }
                val observed = CopyOnWriteArrayList<CodingEvent>()
                val events = withTimeout(60_000) { runtime.runCoding(CodingProject("p", "Project", root.toString(), 0),
                    CodingSession("s", "p", "Planner", 0, engine = CodingEngine.CODEX, planningMode = true), "Use application tools", profile,
                    emptyList(), "agent_tools_test", config, planning = true).withTools(tools).onEach { observed += it }.toList() }
                verify(events, tools, fixture)
            } finally { runtime.close(); root.toFile().deleteRecursively() }
        }
    }
}
