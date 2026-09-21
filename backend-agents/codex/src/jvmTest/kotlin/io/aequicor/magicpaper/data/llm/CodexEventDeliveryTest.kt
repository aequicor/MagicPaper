package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexEventDeliveryTest {
    private val session = CodingSession("session", "project", "", 0, engine = CodingEngine.CODEX)
    private val run = NativeRunRef(session.id, "request")

    @Test fun realRpcClientDrainsSessionStartedBeforeTurnRequestFailure() = runBlocking {
        val home = Files.createTempDirectory("codex-event-rpc")
        val transport = IOException("turn start transport failure")
        val events = Events()
        val environment = environment(home)
        val client = CodexNativeClient(Json, home, null, environment.processes, environment.cachedAccessTokens,
            environment.questionnaires, environment.diagnostics, environment.toolPresentation)
        fun field(name: String) = client.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = field("pending").get(client) as MutableMap<Long, CompletableDeferred<JsonElement>>
        val sink = StringWriter()
        field("process").set(client, ProcessFixture())
        field("writer").set(client, object : BufferedWriter(sink) {
            override fun flush() {
                super.flush()
                val message = Json.parseToJsonElement(sink.toString().trim()).jsonObject
                sink.buffer.setLength(0)
                when (val method = message.getValue("method").jsonPrimitive.content) {
                    "thread/start" -> pending.getValue(message.getValue("id").jsonPrimitive.long).complete(
                        buildJsonObject { putJsonObject("thread") { put("id", "thread") } })
                    "turn/start" -> throw transport
                    else -> error("Unexpected RPC: $method")
                }
            }
        })
        try {
            val received = mutableListOf<CodingEvent>()
            val failure = assertFailsWith<IOException> {
                withContext(NativeAttemptContext(run, events)) {
                    client.runCoding(session, nativeRequest(), false).collect { delay(30); received += it }
                }
            }
            assertTrue(causes(failure).any { it === transport })
            assertEquals(listOf<CodingEvent>(CodingEvent.SessionStarted("thread")), received)
            assertEquals(listOf(NativeDelivery.CODEX_THREAD, NativeDelivery.CODEX_TURN), events.deliveries)
            assertTrue(events.stopping)
            assertFalse(events.stopped)
        } finally {
            // The process is a controlled RPC fixture, not an OS process or a termination proof.
            field("process").set(client, null)
            client.close()
            home.toFile().deleteRecursively()
        }
    }

    @Test fun contributionPreservesPartialOutputThroughClientFailureAndCleanup() = runBlocking {
        contributionFailure(failDuringCleanup = false)
    }

    @Test fun contributionPreservesPartialOutputBeforeShutdownFailure() = runBlocking {
        contributionFailure(failDuringCleanup = true)
    }

    private suspend fun contributionFailure(failDuringCleanup: Boolean) {
        val home = Files.createTempDirectory("codex-event-contribution")
        val failure = IOException(if (failDuringCleanup) "shutdown failed" else "transport failed")
        val partial = listOf<CodingEvent>(CodingEvent.SessionStarted("thread"), CodingEvent.FinalText("partial answer"))
        val delegates = mutableListOf<CodexNativeClient>()
        var cleaned = false
        val agent = CodexBackendAgent(environment(home), CodexNativeAdapter().descriptor) {
            val delegate = nativeTestClient(Json, home).also { delegates += it }
            object : CodexClient by delegate {
                override fun runCoding(session: CodingSession, request: CodexRunRequest, refreshResume: Boolean) = flow {
                    partial.forEach { emit(it) }
                    if (!failDuringCleanup) throw failure
                }
                override suspend fun shutdownCoding() {
                    cleaned = true
                    if (failDuringCleanup) throw failure
                }
            }
        }
        try {
            val received = mutableListOf<CodingEvent>()
            val observed = assertFailsWith<IOException> {
                agent.run(request(home)).collect { delay(30); received += it }
            }
            assertEquals(partial, received)
            assertTrue(causes(observed).any { it === failure })
            assertTrue(cleaned)
            assertEquals(2, delegates.size, "One control client and one request-owned client")
            assertTrue(agent.approvals.requests.value.isEmpty())
        } finally {
            agent.close()
            delegates.forEach { it.close() }
            home.toFile().deleteRecursively()
        }
    }

    private fun nativeRequest() = CodexRunRequest(".", "model", "openai", JsonObject(emptyMap()), JsonObject(emptyMap()),
        "", JsonArray(emptyList()), CodingInteractionMode.CODE, null, null, "fixture", null)
    private fun request(home: Path) = NativeAgentRequest(run.requestId, session, home.toString(), "prompt", "", null,
        LlmProfile("profile", "fixture", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "model"),
        JsonObject(emptyMap()), emptyList(), CodingInteractionMode.CODE,
        NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap())))
    private fun causes(failure: Throwable) = generateSequence(failure) { it.cause }.toList()
    private inner class Events : NativeAttemptEvents {
        val ref = NativeAttemptRef(run, 0)
        val deliveries = mutableListOf<NativeDelivery>()
        var stopping = false
        var stopped = false
        override suspend fun admitLaunch(run: NativeRunRef) = ref.also { assertEquals(ref.run, run) }
        override suspend fun attached(attempt: NativeAttemptRef, process: NativeProcessIdentity) { assertEquals(ref, attempt) }
        override suspend fun deliver(attempt: NativeAttemptRef, stage: NativeDelivery) { assertEquals(ref, attempt); deliveries += stage }
        override suspend fun accepted(attempt: NativeAttemptRef, nativeThreadId: String?, nativeTurnId: String?) { assertEquals(ref, attempt) }
        override suspend fun terminal(attempt: NativeAttemptRef, outcome: NativeOutcome) = error("No terminal result")
        override suspend fun stopping(attempt: NativeAttemptRef) { assertEquals(ref, attempt); stopping = true }
        override suspend fun stopped(attempt: NativeAttemptRef) { assertEquals(ref, attempt); stopped = true }
        override suspend fun unavailable(attempt: NativeAttemptRef) = error("Process was attached")
    }
    private class ProcessFixture : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
        override fun isAlive() = true
        override fun pid() = 42L
        override fun info() = object : ProcessHandle.Info {
            override fun command(): Optional<String> = Optional.empty()
            override fun commandLine(): Optional<String> = Optional.empty()
            override fun arguments(): Optional<Array<String>> = Optional.empty()
            override fun startInstant(): Optional<Instant> = Optional.of(Instant.ofEpochMilli(1))
            override fun totalCpuDuration(): Optional<Duration> = Optional.empty()
            override fun user(): Optional<String> = Optional.empty()
        }
    }
    private fun environment(home: Path) = NativeBackendEnvironment(Json, home.toString(),
        resources = NativeResources { error("No resource read") },
        processes = object : NativeProcessRecovery {
            override fun record(id: String, process: Process, attachLifetime: Boolean) { assertEquals(session.id, id); assertEquals(42L, process.pid()) }
            override fun clear(id: String) = error("No confirmed outcome")
            override fun belongsTo(id: String, process: Process?) = false
            override fun reconcile(id: String) = error("No recovery effect")
        }, cachedAccessTokens = NativeAuthTokens { error("No token read") }, refreshedAccessTokens = NativeAuthTokens { error("No token refresh") },
        questionnaires = object : NativeQuestionnaires {
            override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("No questionnaire")
            override suspend fun beginDelivery(requestId: String): String = error("No questionnaire")
            override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("No questionnaire")
        }, diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) },
        toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("No tools") },
        providerLibrary = object : NativeProviderLibrary {
            override val installation: PiInstallation get() = error("No provider installation")
            override suspend fun shutdown() = Unit
            override suspend fun prepareForReset() = Unit
            override suspend fun resumeAfterReset() = Unit
            override fun close() = Unit
            override fun prepare() = error("No provider preparation")
            override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
                exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("No provider request")
            override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("No provider bridge")
        }, lifecycleJournal = object : NativeLifecycleJournal {
            override suspend fun snapshot(): NativeJournalSnapshot = error("Client receives its scoped lifecycle")
            override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? = error("Client receives its scoped lifecycle")
        })
}
