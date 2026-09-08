package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual production runtime + installed engines + local scripted model. No account or paid requests. */
class PlanningRuntimeIntegrationTest {
    @Test fun bothEnginesReadCurrentCodeRejectWritesAndReturnAValidatedPlan() = runBlocking {
        if (System.getProperty("magicpaper.pi.it") != "true" || System.getProperty("magicpaper.codex.it") != "true") return@runBlocking
        val root = Files.createTempDirectory("magicpaper-planning-it").toFile()
        try {
            for (engine in CodingEngine.entries) {
                println("Planning integration: $engine")
                verifyEngine(engine, root.resolve(engine.name).apply { mkdirs() })
                println("Planning integration passed: $engine")
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun verifyEngine(engine: CodingEngine, root: File) {
        val dir = root.resolve("project").apply { mkdirs() }
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { output }
        }
        git("init", "-q"); git("config", "user.name", "Fixture"); git("config", "user.email", "fixture@example.test")
        val source = dir.resolve("source.kt")
        source.writeText("BASE\n"); git("add", "."); git("commit", "-qm", "base")
        source.writeText("STAGED-CODE\n"); git("add", ".")
        source.writeText("CURRENT-CODE\n")
        dir.resolve("new.kt").writeText("NEW-CODE\n")
        val index = dir.resolve(".git/index").readBytes()
        val requests = CopyOnWriteArrayList<JsonObject>()
        val serverErrors = CopyOnWriteArrayList<Throwable>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                requests += body
                val tools = body["tools"]?.jsonArray.orEmpty().map { it.jsonObject["function"]!!.jsonObject }
                val call = requests.size
                fun tool(name: String, args: JsonObject, id: String) = buildJsonObject {
                    put("id", id); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
                }
                fun shell(command: String): JsonObject {
                    val spec = tools.first { it["name"]!!.jsonPrimitive.content.let { n -> n.contains("exec_command") || n.contains("shell") } }
                    val properties = spec["parameters"]!!.jsonObject["properties"]!!.jsonObject
                    val key = if ("cmd" in properties) "cmd" else "command"
                    val args = buildJsonObject {
                        if (properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content == "array")
                            put(key, JsonArray(listOf("sh", "-c", command).map(::JsonPrimitive))) else put(key, command)
                    }
                    return tool(spec["name"]!!.jsonPrimitive.content, args, "shell-$call")
                }
                val calls = when (call) {
                    1 -> if (engine == CodingEngine.PI) listOf(
                        tool("read", buildJsonObject { put("path", "source.kt") }, "read-source"),
                        tool("read", buildJsonObject { put("path", "new.kt") }, "read-new"),
                        tool("planning_git", buildJsonObject { put("action", "status") }, "status"),
                        tool("planning_git", buildJsonObject { put("action", "diff"); put("staged", true) }, "staged"),
                        tool("planning_git", buildJsonObject { put("action", "diff") }, "unstaged"),
                    ) else listOf(shell("cat source.kt new.kt; GIT_OPTIONAL_LOCKS=0 git status --short; GIT_OPTIONAL_LOCKS=0 git diff --no-ext-diff --no-textconv --cached; GIT_OPTIONAL_LOCKS=0 git diff --no-ext-diff --no-textconv"))
                    2 -> if (engine == CodingEngine.PI) listOf(tool("write", buildJsonObject {
                        put("path", "source.kt"); put("content", "FORBIDDEN") }, "forbidden-write"))
                    else listOf(shell("printf FORBIDDEN > source.kt; printf FORBIDDEN > forbidden.txt"))
                    else -> emptyList()
                }
                val observed = body["messages"]!!.jsonArray.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.joinToString()
                val reply = if (observed.contains("CURRENT-CODE") && observed.contains("NEW-CODE") && observed.contains("STAGED-CODE"))
                    "Изучены source.kt и new.kt: CURRENT-CODE, NEW-CODE, STAGED-CODE" else "READ_FAILED"
                val plan = buildJsonObject {
                    put("reply", reply)
                    putJsonArray("tree") {
                        add(buildJsonObject { put("id", "root"); put("title", "Fix"); put("kind", "GOAL"); putJsonArray("children") { add(JsonPrimitive("stage")) } })
                        add(buildJsonObject { put("id", "stage"); put("title", "Update"); put("kind", "STAGE"); put("stageId", "stage") })
                    }
                    putJsonArray("milestones") { add(buildJsonObject { put("id", "stage"); put("title", "Update"); put("description", reply); put("acceptance", "Checks pass") }) }
                }
                val delta = buildJsonObject {
                    put("role", "assistant")
                    if (calls.isEmpty()) put("content", plan.toString()) else put("tool_calls", JsonArray(calls.mapIndexed { i, c -> JsonObject(c + ("index" to JsonPrimitive(i))) }))
                }
                fun chunk(d: JsonObject, finish: JsonElement) = buildJsonObject {
                    put("id", "fixture-$call"); put("object", "chat.completion.chunk"); put("model", "planning-fixture")
                    putJsonArray("choices") { add(buildJsonObject { put("index", 0); put("delta", d); put("finish_reason", finish) }) }
                }
                val response = "data: ${chunk(delta, JsonNull)}\n\ndata: ${chunk(buildJsonObject {}, JsonPrimitive(if (calls.isEmpty()) "stop" else "tool_calls"))}\n\ndata: [DONE]\n\n"
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } catch (e: Throwable) { serverErrors += e; exchange.close() }
        }
        server.start()
        val codex = CodexAppServerOpenAiSubscription(Json { ignoreUnknownKeys = true }, root.resolve("codex").toPath())
        val runtime = DesktopCodingRuntime(PiCodingRuntime(), codex)
        try {
            val profile = LlmProfile("fixture", "Fixture", baseUrl = "http://127.0.0.1:${server.address.port}/v1", apiKey = "fixture",
                modelId = "planning-fixture", codingModelId = "wrong-executor-model", favoriteModels = listOf("planning-fixture"))
            val project = CodingProject("project", "Fixture", dir.path, 1)
            val text = object : LlmGateway { override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Text-only fallback") }
            val composer = PlanComposer(text, planningGateway = RuntimePlanningGateway(runtime), projectLookup = { project })
            val activity = mutableListOf<CodingStep>()
            val result = withTimeout(90000) { composer.refine(Plan("plan", project.id, "Изучи код и подготовь фикс", engine = engine),
                "Составь план по текущему коду", profile, listOf(profile), emptyList(), onActivity = activity::add) }
            assertTrue(serverErrors.isEmpty(), serverErrors.toString())
            assertTrue(result.dialogue.last().text.contains("CURRENT-CODE"), "$engine: ${result.dialogue.last().text}\n$activity")
            assertTrue(result.milestones.isNotEmpty())
            assertTrue(result.dialogue.last().questions.isEmpty(), "First turn can return a plan without questions")
            assertTrue(activity.any { it.kind == CodingStepKind.EXEC || it.kind == CodingStepKind.TOOL })
            assertTrue(requests.size >= 3)
            assertTrue(requests.all { it["model"]?.jsonPrimitive?.content == "planning-fixture" })
            if (engine == CodingEngine.PI) {
                val names = requests.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                assertEquals(setOf("read", "grep", "find", "ls", "planning_git"), names)
            }
            assertEquals("CURRENT-CODE\n", source.readText(), "$engine wrote source")
            assertEquals("NEW-CODE\n", dir.resolve("new.kt").readText())
            assertFalse(dir.resolve("forbidden.txt").exists(), "$engine created a file")
            assertContentEquals(index, dir.resolve(".git/index").readBytes(), "$engine wrote Git index")
        } finally { runtime.abortAll(); codex.close(); server.stop(0) }
    }
}
