package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.coding.testCommandChecks
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ResearchCheckBridgeTest {
    private val project = CodingProject("project", "Project", "/project", 1)
    private val session = CodingSession("session", project.id, "Research", 1, runtimeGeneration = 7, researchMode = true)
    private fun request(id: JsonPrimitive, command: List<String> = listOf("tool", "arg")) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
        putJsonObject("params") {
            put("name", "research_check")
            putJsonObject("arguments") { put("command", JsonArray(command.map(::JsonPrimitive))); put("cwd", "module") }
        }
    }
    private fun call(bridge: ResearchCheckBridge, body: JsonObject): JsonObject {
        val connection = URI(bridge.url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer ${bridge.token}")
        connection.setRequestProperty("Content-Type", "application/json")
        return try {
            connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        } finally { connection.disconnect() }
    }

    @Test fun exactWireIdentityScopeAndPolicyReachTheOwnerAndDuplicatesReuseTheSameReply() {
        val commands = CopyOnWriteArrayList<CheckCommand>()
        val checks = object : CommandChecks by testCommandChecks {
            override suspend fun run(command: CheckCommand): CheckResult { commands += command; return CheckResult("verified", 0) }
        }
        ResearchCheckBridge(session, project, checks, "native-request").use { bridge ->
            val body = request(JsonPrimitive("call-1"))
            val first = call(bridge, body)
            assertEquals(first, call(bridge, body))
            assertContains(first.toString(), "verified")
            assertEquals(CheckCommand(CheckRef(CheckScope(project.id, session.id, "native-request", 7),
                JsonPrimitive("call-1").toString()), project.path, listOf("tool", "arg"), "module", CheckPolicy.PROTECTED_PROJECT), commands.single())
            call(bridge, request(JsonPrimitive(1)))
            call(bridge, request(JsonPrimitive("1")))
            assertNotEquals(commands[1].ref, commands[2].ref, "Numeric and string JSON-RPC IDs are distinct")
        }
    }

    @Test fun changedArgumentsCannotJoinAnInFlightCallOrReadItsCachedResult() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val commands = CopyOnWriteArrayList<CheckCommand>()
        val checks = object : CommandChecks by testCommandChecks {
            override suspend fun run(command: CheckCommand): CheckResult {
                commands += command; entered.complete(Unit); finish.await(); return CheckResult("original", 0)
            }
        }
        ResearchCheckBridge(session, project, checks, "request").use { bridge ->
            val original = async(Dispatchers.IO) { call(bridge, request(JsonPrimitive("same"))) }
            withTimeout(5_000) { entered.await() }
            val changed = withContext(Dispatchers.IO) { call(bridge, request(JsonPrimitive("same"), listOf("other"))) }
            assertTrue("error" in changed); assertFalse(original.isCompleted)
            finish.complete(Unit); assertContains(original.await().toString(), "original")
            assertEquals(changed, withContext(Dispatchers.IO) { call(bridge, request(JsonPrimitive("same"), listOf("other"))) })
            assertEquals(1, commands.size)
        }
    }

    @Test fun unknownAndOperationalFailuresNeverExposeTheUnderlyingPayload() {
        for (failure in listOf(CheckOutcomeUnknown(IllegalStateException("secret-command-output")), IllegalStateException("secret-command-output"))) {
            var calls = 0
            val checks = object : CommandChecks by testCommandChecks {
                override suspend fun run(command: CheckCommand): CheckResult { calls++; throw failure }
            }
            ResearchCheckBridge(session, project, checks, "request").use { bridge ->
                val body = request(JsonPrimitive("id"))
                val result = call(bridge, body)
                assertTrue("error" in result); assertFalse(result.toString().contains("secret-command-output"))
                if (failure is CheckOutcomeUnknown) assertContains(result.toString(), "Повтор заблокирован")
                assertEquals(result, call(bridge, body)); assertEquals(1, calls)
            }
        }
    }

    @Test fun closingTheEndpointCancelsItsCallAndAbortsTheExactSession() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val aborted = CopyOnWriteArrayList<String>()
        val checks = object : CommandChecks by testCommandChecks {
            override suspend fun run(command: CheckCommand): CheckResult {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            override fun abort(sessionId: String) { aborted += sessionId }
        }
        val bridge = ResearchCheckBridge(session, project, checks, "request")
        val pending = async(Dispatchers.IO) { runCatching { call(bridge, request(JsonPrimitive("id"))) } }
        try {
            withTimeout(5_000) { entered.await() }; bridge.close()
            withTimeout(5_000) { cancelled.await() }
            assertEquals(listOf(session.id), aborted)
            assertFalse(pending.await().getOrNull()?.containsKey("result") == true)
        } finally { bridge.close() }
    }

    @Test fun piProtocolReusesTheNativeCallIdAcrossWireRetries() {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
        org.junit.Assume.assumeTrue("Protocol fixture uses installed Node without network", node != null)
        val script = """
            process.env.MAGICPAPER_RESEARCH_URL='http://fixture'; process.env.MAGICPAPER_RESEARCH_TOKEN='fixture';
            let tool; const calls=[];
            globalThis.fetch=async (_,request)=>{calls.push(JSON.parse(request.body));return {ok:true,json:async()=>({result:{content:[],isError:false}})}};
            const module=await import('data:text/javascript;base64,'+Buffer.from(${JsonPrimitive(PiResearchExtension.source)}).toString('base64'));
            module.default({registerTool:value=>tool=value});
            await tool.execute('native-call',{command:['test']}); await tool.execute('native-call',{command:['test']});
            if(calls.length!==2 || calls.some(call=>call.id!=='native-call' || call.method!=='tools/call')) throw new Error('Call identity changed');
        """.trimIndent()
        val process = ProcessBuilder(checkNotNull(node).absolutePath, "--input-type=module", "-e", script).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
