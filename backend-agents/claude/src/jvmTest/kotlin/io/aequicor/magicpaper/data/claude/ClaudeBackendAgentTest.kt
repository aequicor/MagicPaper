package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** The child is a shell script that plays Claude Code's stream; nothing here reaches a network or an account. */
class ClaudeBackendAgentTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private class Fixture(val home: File) : AutoCloseable {
        val work = home.resolve("work").apply { mkdirs() }
        val recorded = CopyOnWriteArrayList<String>()
        val cleared = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val binary = home.resolve("claude")
        fun script(body: String) { binary.writeText("#!/bin/sh\nD=\"${home.path}\"\nprintf '%s\\n' \"\$@\" > \"\$D/args\"\n$body\n"); binary.setExecutable(true) }
        fun args() = home.resolve("args").readLines()
        fun agent(): NativeAgentAdapter = ClaudeBackendContribution().create(environment())
        private fun environment() = NativeBackendEnvironment(
            Json, home.resolve("state").path, binary.path, NativeResources { error("No resource read") },
            object : NativeProcessRecovery {
                override fun record(id: String, process: Process, attachLifetime: Boolean) { recorded += id }
                override fun clear(id: String) { cleared += id }
                override fun belongsTo(id: String, process: Process?) = false
                override fun reconcile(id: String) = false
            }, NativeAuthTokens { error("No tokens") }, NativeAuthTokens { error("No tokens") },
            object : NativeQuestionnaires {
                override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("No questionnaire")
                override suspend fun beginDelivery(requestId: String): String = error("No questionnaire")
                override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("No questionnaire")
            }, NativeDiagnostics { _, event, _, _ -> errors += event },
            NativeToolPresentationResolver { server, tool, _ -> NativeToolPresentation("$server:$tool", tool) },
            object : NativeProviderLibrary {
                override suspend fun shutdown() = Unit
                override suspend fun prepareForReset() = Unit
                override suspend fun resumeAfterReset() = Unit
                override fun close() = Unit
                override fun prepare() = error("No provider preparation")
                override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
                    exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("No provider request")
                override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("No provider bridge")
            }, MemoryNativeJournal())
        fun request(mode: CodingInteractionMode = CodingInteractionMode.CODE, resume: String = "", provider: ProviderType = ProviderType.ANTHROPIC,
            tools: NativeAgentTools = NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap()))) =
            NativeAgentRequest("request", CodingSession("session", "project", "", 0, engine = CodingEngine.CLAUDE_CODE, piSessionId = resume),
                work.path, "сделай задачу", "system text", if (mode == CodingInteractionMode.CODE) null else "base text",
                LlmProfile("profile", "Anthropic", "https://api.anthropic.com", provider = provider, modelId = "claude-sonnet-4-5"),
                JsonObject(emptyMap()), emptyList(), mode, tools)
        override fun close() { home.deleteRecursively() }
    }

    private fun fixture() = Fixture(Files.createTempDirectory("claude-agent").toFile())
    private fun run(f: Fixture, request: NativeAgentRequest) = nativeRunBlocking { f.agent().use { it.run(request).toList() } }

    private val success = """
cat > "${'$'}D/stdin"
cat <<'JSON'
{"type":"system","subtype":"init","session_id":"native-1"}
{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1","usage":{"input_tokens":10,"output_tokens":1}}}}
{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"гот"}}}
{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":3}}}
{"type":"stream_event","event":{"type":"message_stop"}}
{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"готово"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":3}}}
{"type":"result","subtype":"success","is_error":false,"result":"готово","session_id":"native-1"}
JSON
"""

    @Test fun answerIsStreamedTheSessionIsBoundAndThePromptTravelsOnStdin() {
        if (windows) return
        fixture().use { f ->
            f.script(success)
            val events = run(f, f.request(resume = "earlier"))
            assertEquals(CodingEvent.SessionStarted("native-1"), events.first { it is CodingEvent.SessionStarted })
            assertEquals("готово", events.filterIsInstance<CodingEvent.FinalText>().single().text)
            assertContains(events, CodingEvent.TextDelta("гот"))
            assertEquals(TokenUsage(10, 3), events.filterIsInstance<CodingEvent.UsageObserved>().single().tokens)
            assertTrue(events.none { it is CodingEvent.Failed })
            assertEquals(CodingEvent.Finished, events.last())
            assertEquals("сделай задачу", f.home.resolve("stdin").readText())
            val args = f.args()
            assertEquals("earlier", args[args.indexOf("--resume") + 1])
            assertContains(args, "bypassPermissions")
            assertEquals(listOf("session"), f.recorded)
            assertEquals(listOf("session"), f.cleared)
        }
    }

    @Test fun promptsAndTokensNeverReachArgvAndTemporaryFilesAreRemoved() {
        if (windows) return
        fixture().use { f ->
            f.script(success)
            val tools = NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), buildJsonObject {
                put("mcp_servers.bridge", buildJsonObject {
                    put("url", "http://127.0.0.1:9/mcp"); put("enabled", true)
                    put("http_headers", buildJsonObject { put("Authorization", "Bearer secret-token") })
                })
            })
            run(f, f.request(tools = tools))
            val joined = f.args().joinToString(" ")
            assertFalse("secret-token" in joined || "сделай задачу" in joined || "system text" in joined)
            val configs = f.home.resolve("state/session-configs/session").listFiles().orEmpty()
            assertTrue(configs.isEmpty(), "attempt files must not outlive the attempt: ${configs.toList()}")
        }
    }

    @Test fun readOnlyRunGetsBothInstructionLayersInOneSystemPrompt() {
        if (windows) return
        fixture().use { f ->
            f.script(success + "\ncp \"${'$'}(printf '%s\\n' \"${'$'}@\" | awk '/^--append-system-prompt-file\$/{getline; print}')\" \"${'$'}D/prompt\"")
            run(f, f.request(CodingInteractionMode.PLANNING))
            assertContains(f.args(), "dontAsk")
            assertEquals("base text\n\nsystem text", f.home.resolve("prompt").readText())
        }
    }

    @Test fun missingSignInBecomesAFailureWithAnActionAndStillFinishes() {
        if (windows) return
        fixture().use { f ->
            f.script("""
cat > /dev/null
cat <<'JSON'
{"type":"system","subtype":"init","session_id":"n"}
{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Not logged in · Please run /login"}]},"error":"authentication_failed"}
{"duration_api_ms":0,"is_error":true,"subtype":"success","result":"Not logged in · Please run /login","type":"result"}
JSON
exit 1""")
            val events = run(f, f.request())
            val failure = events.filterIsInstance<CodingEvent.Failed>().single()
            assertContains(failure.message, "не авторизован")
            assertEquals(CodingRecovery.SignIn(CodingEngine.CLAUDE_CODE), failure.recovery,
                "The failure offers the engine's own sign-in instead of a command to type")
            assertTrue(events.none { it is CodingEvent.FinalText })
            assertEquals(CodingEvent.Finished, events.last())
        }
    }

    @Test fun exitWithoutAResultIsNotPassedOffAsAnAnswer() {
        if (windows) return
        fixture().use { f ->
            f.script("cat > /dev/null\nexit 7")
            val events = run(f, f.request())
            assertContains(events.filterIsInstance<CodingEvent.Failed>().single().message, "кодом 7")
            assertEquals(CodingEvent.Finished, events.last())
        }
    }

    @Test fun connectionOfAnotherProviderIsRefusedBeforeAnythingStarts() {
        fixture().use { f ->
            val events = run(f, f.request(provider = ProviderType.OPENAI_COMPATIBLE))
            assertContains(events.filterIsInstance<CodingEvent.Failed>().single().message, "Anthropic")
            assertTrue(f.recorded.isEmpty())
        }
    }

    @Test fun catalogDeclaresTheModelFamiliesWithTheLevelsTheCliAccepts() = kotlinx.coroutines.runBlocking {
        fixture().use { f ->
            val models = checkNotNull(f.agent().use { it.models }).models()
            assertEquals(listOf("fable", "opus", "sonnet", "haiku"), models.map { it.id })
            assertTrue(models.all { it.provider == "anthropic" })
            assertEquals(listOf("low", "medium", "high"), models.single { it.id == "sonnet" }.levels)
            assertFalse(models.single { it.id == "haiku" }.supportsLevels)
        }
    }

    @Test fun missingInstallationIsReportedAsAFailure() {
        fixture().use { f ->
            val events = run(f, f.request())
            assertContains(events.filterIsInstance<CodingEvent.Failed>().single().message, "Claude Code")
            assertEquals(CodingEvent.Finished, events.last())
        }
    }

    @Test fun cancellationStopsTheWholeProcessTreeBeforeOwnershipIsCleared() {
        if (windows) return
        fixture().use { f ->
            f.script("echo \$\$ > \"\$D/pid\"\ncat > /dev/null\necho '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"n\"}'\nsleep 60 &\nwait")
            nativeRunBlocking {
                f.agent().use { agent ->
                    val started = CompletableDeferred<Unit>()
                    val job = launch(Dispatchers.IO) { agent.run(f.request()).collect { if (it is CodingEvent.SessionStarted) started.complete(Unit) } }
                    withTimeout(20_000) { started.await() }
                    job.cancelAndJoin()
                }
            }
            val pid = f.home.resolve("pid").readText().trim().toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the child must be gone")
            assertEquals(listOf("session"), f.cleared)
        }
    }

    @Test fun aSecondRunOfTheSameSessionIsRejectedWhileOneIsActive() {
        if (windows) return
        fixture().use { f ->
            f.script("cat > /dev/null\necho '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"n\"}'\nsleep 60 &\nwait")
            nativeRunBlocking {
                f.agent().use { agent ->
                    val started = CompletableDeferred<Unit>()
                    val first = launch(Dispatchers.IO) { agent.run(f.request()).collect { if (it is CodingEvent.SessionStarted) started.complete(Unit) } }
                    withTimeout(20_000) { started.await() }
                    assertFailsWith<IllegalStateException> { agent.run(f.request()).toList() }
                    first.cancelAndJoin()
                }
            }
        }
    }
}
