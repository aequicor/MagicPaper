package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Actual production runtime + installed engines + local scripted model. No account or paid requests. */
class ResearchRuntimeIntegrationTest {
    @Test fun bothEnginesReadCheckRejectWritesAndResumeResearchConversation() = runBlocking {
        if (System.getProperty("magicpaper.pi.it") != "true" || System.getProperty("magicpaper.codex.it") != "true" || System.getProperty("magicpaper.research.native") != "true") return@runBlocking
        val root = Files.createTempDirectory("magicpaper-research-it").toFile()
        try {
            for (engine in CodingEngine.entries) {
                println("Research integration: $engine")
                verifyEngine(engine, root.resolve(engine.name).apply { mkdirs() })
                println("Research integration passed: $engine")
            }
        } finally { root.deleteRecursively() }
    }

    private suspend fun verifyEngine(engine: CodingEngine, root: File) {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val dir = root.resolve("project").apply { mkdirs() }
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { output }
        }
        git("init", "-q"); git("config", "user.name", "Fixture"); git("config", "user.email", "fixture@example.test")
        dir.resolve("build.gradle").writeText("// fixture")
        dir.resolve(".gitignore").writeText("build/\n.gradle/\n")
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
                            put(key, JsonArray((if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command) else listOf("sh", "-c", command)).map(::JsonPrimitive))) else put(key, command)
                        if (windows && "shell" in properties) put("shell", "powershell.exe")
                        if ("login" in properties) put("login", false)
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
                    ) else listOf(shell(if (windows) "\$env:GIT_OPTIONAL_LOCKS='0'; Get-Content source.kt,new.kt; git status --short; git diff --no-ext-diff --no-textconv --cached; git diff --no-ext-diff --no-textconv" else
                        "cat source.kt new.kt; GIT_OPTIONAL_LOCKS=0 git status --short; GIT_OPTIONAL_LOCKS=0 git diff --no-ext-diff --no-textconv --cached; GIT_OPTIONAL_LOCKS=0 git diff --no-ext-diff --no-textconv"))
                    2 -> if (engine == CodingEngine.PI) listOf(tool("write", buildJsonObject {
                        put("path", "source.kt"); put("content", "FORBIDDEN") }, "forbidden-write"))
                    else listOf(shell(if (windows) "Set-Content source.kt FORBIDDEN; Set-Content forbidden.txt FORBIDDEN" else "printf FORBIDDEN > source.kt; printf FORBIDDEN > forbidden.txt"))
                    3, 5 -> {
                        val name = tools.first { it["name"]!!.jsonPrimitive.content.endsWith("research_check") }["name"]!!.jsonPrimitive.content
                        val command = if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                            "Get-Content source.kt; Set-Content source.kt forbidden -ErrorAction SilentlyContinue; Set-Content build/check.txt BUILT; Write-Output CHECK_OK")
                            else listOf("/bin/sh", "-c", "cat source.kt; printf forbidden > source.kt; printf BUILT > build/check.txt; printf CHECK_OK")
                        listOf(tool(name, buildJsonObject { put("command", JsonArray(command.map(::JsonPrimitive))) }, "research-check-$call"))
                    }
                    else -> emptyList()
                }
                val observed = body["messages"]!!.jsonArray.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.joinToString()
                val answer = if (call >= 7) "FRESH_CONTEXT" else if (observed.contains("CURRENT-CODE") && observed.contains("CHECK_OK")) "Исследован CURRENT-CODE; CHECK_OK" else "READ_FAILED"
                val delta = buildJsonObject {
                    put("role", "assistant")
                    if (calls.isEmpty()) put("content", answer) else put("tool_calls", JsonArray(calls.mapIndexed { i, c -> JsonObject(c + ("index" to JsonPrimitive(i))) }))
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
                modelId = "planning-fixture", codingModelId = "planning-fixture", favoriteModels = listOf("planning-fixture"))
            val project = CodingProject("project", "Fixture", dir.path, 1)
            var session = CodingSession("research-${engine.name}", project.id, "Research", 1, engine = engine, researchMode = true)
            repeat(2) { turn ->
                val events = withTimeout(120000) { runtime.run(project, session, if (turn == 0) "Изучи код и проверь его" else "Объясни результаты предыдущего исследования", profile).toList() }
                assertTrue(serverErrors.isEmpty(), serverErrors.joinToString { it.stackTraceToString() } + "\nTool inventories: " + requests.map { it["tools"] }.toString())
                assertFalse(events.any { it is CodingEvent.Failed }, events.toString())
                assertTrue(events.filterIsInstance<CodingEvent.FinalText>().any { it.text.contains("CURRENT-CODE") && it.text.contains("CHECK_OK") }, events.toString())
                assertTrue(events.any { it is CodingEvent.Finished })
                session = session.copy(piSessionId = events.filterIsInstance<CodingEvent.SessionStarted>().last().sessionId)
            }
            for (mode in listOf(CodingInteractionMode.CODE, CodingInteractionMode.RESEARCH)) {
                val previousNative = session.piSessionId
                session = session.changeInteractionMode(mode)
                val events = withTimeout(120000) { runtime.run(project, session, "Новый режим: объясни ограничения", profile).toList() }
                assertFalse(events.any { it is CodingEvent.Failed }, events.toString())
                assertTrue(events.filterIsInstance<CodingEvent.FinalText>().any { it.text.contains("FRESH_CONTEXT") }, events.toString())
                val nextNative = events.filterIsInstance<CodingEvent.SessionStarted>().last().sessionId
                assertNotEquals(previousNative, nextNative)
                session = session.copy(piSessionId = nextNative)
            }
            assertTrue(serverErrors.isEmpty(), serverErrors.toString())
            assertEquals("BUILT", dir.resolve("build/check.txt").readText().trim())
            assertTrue(requests.size >= 3)
            assertTrue(requests.all { it["model"]?.jsonPrimitive?.content == "planning-fixture" })
            if (engine == CodingEngine.PI) {
                val names = requests.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                assertEquals(setOf("read", "grep", "find", "ls", "planning_git", "questionnaire", "research_check"), names)
            }
            assertEquals("CURRENT-CODE\n", source.readText(), "$engine wrote source")
            assertEquals("NEW-CODE\n", dir.resolve("new.kt").readText())
            assertFalse(dir.resolve("forbidden.txt").exists(), "$engine created a file")
            assertContentEquals(index, dir.resolve(".git/index").readBytes(), "$engine wrote Git index")
        } finally { runtime.abortAll(); codex.close(); server.stop(0) }
    }
}
