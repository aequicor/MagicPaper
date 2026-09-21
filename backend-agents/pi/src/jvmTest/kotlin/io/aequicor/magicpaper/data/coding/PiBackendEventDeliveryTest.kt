package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PiBackendEventDeliveryTest {
    @Test fun installationNoticeReachesSlowCollectorBeforePreflightFailure() = runBlocking {
        val home = Files.createTempDirectory("pi-event-delivery").toFile()
        val cli = home.resolve("fixture-cli").apply { writeText("unused") }
        val failure = IOException("fuzzy configuration unavailable")
        val installation = Installation(cli.absolutePath, failure)
        val agent = PiBackendContribution().create(environment(home.absolutePath, installation))
        try {
            // Repeating a rejected preflight also verifies its live session registration was cleared.
            repeat(2) {
                val received = mutableListOf<CodingEvent>()
                val observed = assertFailsWith<IOException> {
                    agent.run(request(home.absolutePath)).collect { delay(30); received += it }
                }
                assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === failure })
                assertEquals(listOf<CodingEvent>(CodingEvent.Notice("Fixture tools notice")), received)
            }
        } finally { agent.close(); home.deleteRecursively() }
    }

    @Test fun preflightCancellationIsNotConvertedIntoFailureOrFinished() = runBlocking {
        val home = Files.createTempDirectory("pi-event-cancellation").toFile()
        val cli = home.resolve("fixture-cli").apply { writeText("unused") }
        val cancellation = CancellationException("fixture cancellation")
        val agent = PiBackendContribution().create(environment(home.absolutePath, Installation(cli.absolutePath, cancellation)))
        try {
            val received = mutableListOf<CodingEvent>()
            val observed = assertFailsWith<CancellationException> { agent.run(request(home.absolutePath)).toList(received) }
            assertTrue(generateSequence<Throwable>(observed) { it.cause }.any { it === cancellation })
            assertTrue(received.none { it is CodingEvent.Failed || it is CodingEvent.Finished })
        } finally { agent.close(); home.deleteRecursively() }
    }

    private fun request(path: String) = NativeAgentRequest("request", CodingSession("session", "project", "", 0, engine = CodingEngine.PI),
        path, "prompt", "", null, LlmProfile("profile", "fixture", modelId = "model"), JsonObject(emptyMap()), emptyList(),
        CodingInteractionMode.CODE, NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap())))
    private class Installation(override val cliPath: String, private val failure: Throwable) : PiInstallation {
        override fun aiDirectory() = error("No model library access")
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun ensureReady() = flow { emit(status()) }
        override suspend fun uninstall() = error("No installation change")
        override suspend fun node() = "unused-node"
        override fun prepareBundledTools() = Unit
        override fun toolsNotice() = "Fixture tools notice"
        override fun ensureFuzzySafety(): Unit = throw failure
        override fun bashPath(): String? = error("No dispatch")
        override fun homeDefaults(directory: String): Unit = error("No dispatch")
        override fun environment(nodePath: String, home: String): Map<String, String> = error("No dispatch")
    }
    private fun environment(path: String, installation: PiInstallation) = NativeBackendEnvironment(
        Json, path, resources = NativeResources { error("No resource read") },
        processes = object : NativeProcessRecovery {
            override fun record(id: String, process: Process, attachLifetime: Boolean) = error("No process launch")
            override fun clear(id: String) = error("No process cleanup")
            override fun belongsTo(id: String, process: Process?) = false
            override fun reconcile(id: String) = error("No process recovery")
        }, cachedAccessTokens = NativeAuthTokens { error("No tokens") }, refreshedAccessTokens = NativeAuthTokens { error("No tokens") },
        questionnaires = object : NativeQuestionnaires {
            override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("No questionnaire")
            override suspend fun beginDelivery(requestId: String): String = error("No questionnaire")
            override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome): Unit = error("No questionnaire")
        }, diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) },
        toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("No tools") },
        providerLibrary = object : NativeProviderLibrary {
            override val installation = installation
            override suspend fun shutdown() = Unit
            override suspend fun prepareForReset() = Unit
            override suspend fun resumeAfterReset() = Unit
            override fun close() = Unit
            override fun prepare() = error("No provider preparation")
            override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
                exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("No provider request")
            override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("No provider bridge")
        }, lifecycleJournal = object : NativeLifecycleJournal {
            override suspend fun snapshot(): NativeJournalSnapshot = error("Preflight cannot admit a task")
            override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? = error("Preflight cannot admit a task")
        })
}
