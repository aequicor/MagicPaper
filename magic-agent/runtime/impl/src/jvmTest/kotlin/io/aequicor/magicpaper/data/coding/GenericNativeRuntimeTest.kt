package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.domain.browser.*
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.test.*

class GenericNativeRuntimeTest {
    @Test fun unknownNativeOutcomeRetainsOutputBeforeRecoveryAfterResourceCleanup() = runBlocking {
        val root = Files.createTempDirectory("generic-native-output").toFile()
        try {
            val computer = Computer()
            val unknown = NativeRecoveryRequired(NativeRecoverySummary(emptyList(), true))
            val output: List<CodingEvent> = List(12) { CodingEvent.Notice("partial-$it") }
            val agent = object : BackendAgent by Agent() {
                override fun run(request: NativeAgentRequest) = flow<CodingEvent> {
                    output.forEach { emit(it) }
                    throw unknown
                }
            }
            val runtime = GenericNativeRuntime(agent, Library, {}, { it }, computer,
                testQuestionnaireFactory().create("fixture", null), testBrowserSessions, testCommandChecks)
            val received = mutableListOf<CodingEvent>()
            val failure = assertFailsWith<NativeRunRecoveryRequired> {
                runtime.run(project(root.path), session, "Prompt", profile).onEach { delay(5) }.toList(received)
            }
            assertSame(unknown, failure.cause)
            assertTrue(failure.recovery.persistenceUnknown)
            assertEquals(output, received)
            assertTrue(computer.closed)
        } finally { root.deleteRecursively() }
    }

    @Test fun runtimeOffersTheNativeModelCatalogUnderTheAgentsOwnEngineOnlyWhenItHasOne() = runBlocking<Unit> {
        val listed = listOf(CodingModel("openai", "gpt-fixture", levels = listOf("low", "ultra"), defaultLevel = "low"))
        val withCatalog = object : BackendAgent by Agent() { override val models = NativeModelCatalog { listed } }
        fun runtime(agent: BackendAgent) = GenericNativeRuntime(agent, Library, {}, { it }, null,
            testQuestionnaireFactory().create("fixture", null), testBrowserSessions, testCommandChecks)
        val sources = runtime(withCatalog).modelSources
        assertEquals(setOf(withCatalog.descriptor.engine), sources.keys)
        assertEquals(listed, sources.getValue(withCatalog.descriptor.engine).fetch())
        assertTrue(runtime(Agent()).modelSources.isEmpty())
    }

    @Test fun noDispatchProofDecisionAndConsumptionKeepExactNativeIdentityAcrossDesktopTransport() = runBlocking<Unit> {
        val root = Files.createTempDirectory("generic-native-no-dispatch").toFile()
        try {
            val proof = NativeNoDispatchProof(NativeRunRef(session.id, "previous"), "proof", "journal-epoch")
            val nativeAck = NativeNoDispatchAcknowledgement("ack", proof, "parent-decision")
            val base = Agent()
            var acknowledgements = 0
            val agent = object : BackendAgent by base {
                override suspend fun inspectRecovery(sessionId: String) = NativeRecoverySummary(emptyList(), false,
                    listOf(NativeNoDispatchItem(proof, nativeAck)), base.request?.let {
                        listOf(NativeRecoveryConsumption(nativeAck.id, NativeRunRef(sessionId, checkNotNull(it.session.pendingRun).runId)))
                    }.orEmpty())
                override suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String): NativeNoDispatchAcknowledgement {
                    assertEquals(nativeAck.proof, proof)
                    assertEquals(nativeAck.parentDecisionId, parentDecisionId)
                    acknowledgements++
                    return nativeAck
                }
            }
            val generic = GenericNativeRuntime(agent, Library, {}, { it }, null,
                testQuestionnaireFactory().create("fixture", null), testBrowserSessions, testCommandChecks)
            val desktop = DesktopCodingRuntime(listOf(NativeRuntimeBinding(agent.descriptor, generic)), null,
                { CodingSkillSelection(emptyList()) }, testCommandChecks)
            val snapshot = desktop.recovery.inspect(session.id)
            assertTrue(snapshot.items.isEmpty())
            val hostProof = snapshot.noDispatch.single().proof
            assertEquals(NativeRunNoDispatchProof(CodingEngine.PI, session.id, "previous", "proof", "journal-epoch"), hostProof)
            val ack = desktop.recovery.acknowledgeNoDispatch(hostProof, "parent-decision")
            assertEquals(1, acknowledgements)
            assertFailsWith<IllegalArgumentException> {
                generic.recovery.acknowledgeNoDispatch(hostProof.copy(engine = CodingEngine.CODEX), "parent-decision")
            }
            assertEquals(1, acknowledgements)
            val next = session.copy(pendingRun = CodingRunCheckpoint("message", "Prompt", runId = "fresh"))
            val events = withContext(NativeRunRecoveryBinding(noDispatchAcknowledgement = ack)) {
                desktop.run(project(root.path), next, "Prompt", profile).toList()
            }
            assertTrue(events.none { it is CodingEvent.Failed })
            assertEquals(nativeAck, base.request?.noDispatchRecovery)
            assertNull(base.request?.recovery)
            assertEquals(listOf(NativeRunRecoveryConsumption("ack", CodingEngine.PI, session.id, "fresh")),
                desktop.recovery.inspect(session.id).consumptions)
            assertFailsWith<IllegalArgumentException> { NativeRunRecoveryBinding() }
            assertFailsWith<IllegalArgumentException> { NativeRunRecoveryBinding(
                NativeRunRecoveryAcknowledgement("attempt", NativeRunRecoveryRef(CodingEngine.PI, session.id, "previous", 0), "decision"), ack) }
        } finally { root.deleteRecursively() }
    }

    @Test fun desktopBundleCompletesEveryNativeCleanupAndPreservesCancellation() = runBlocking {
        val calls = mutableListOf<String>()
        val ordinary = IllegalStateException("first native owner")
        val cancelled = CancellationException("second native owner")
        val first = object : BackendAgent by Agent() {
            override suspend fun prepareForReset() { calls += "first.pause" }
            override suspend fun resumeAfterReset() { calls += "first.resume"; throw ordinary }
            override suspend fun shutdown() { calls += "first.shutdown"; throw ordinary }
        }
        val second = object : BackendAgent by Agent() {
            override suspend fun prepareForReset() { calls += "second.pause" }
            override suspend fun resumeAfterReset() { calls += "second.resume"; throw cancelled }
            override suspend fun shutdown() { calls += "second.shutdown"; throw cancelled }
        }
        val library = object : NativeProviderLibrary by Library {
            override suspend fun prepareForReset() { calls += "library.pause" }
            override suspend fun resumeAfterReset() { calls += "library.resume" }
            override suspend fun shutdown() { calls += "library.shutdown" }
        }
        val subscription = object : OpenAiSubscriptionService {
            override suspend fun account(refreshToken: Boolean): OpenAiSubscriptionAccount = error("Unexpected account")
            override suspend fun startLogin(): OpenAiSubscriptionLogin = error("Unexpected login")
            override suspend fun awaitLogin(loginId: String): OpenAiSubscriptionAccount = error("Unexpected login")
            override suspend fun cancelLogin(loginId: String) = error("Unexpected login")
            override suspend fun logout() = error("Unexpected logout")
            override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> = error("Unexpected models")
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Unexpected model")
            override fun close() { calls += "subscription.close" }
        }
        val checks = object : io.aequicor.magicpaper.domain.checks.CommandChecks by testCommandChecks {
            override suspend fun prepareForReset() { calls += "checks.pause" }
            override suspend fun resumeAfterReset() { calls += "checks.resume" }
            override suspend fun close() { calls += "checks.close" }
        }
        val runtime = io.aequicor.magicpaper.di.DesktopNativeRuntime(NoopCodingRuntime, subscription, null,
            listOf(AutoCloseable { calls += "resource.close" }), listOf(first, second), library, checks)
        runtime.prepareForReset()
        assertSame(cancelled, assertFailsWith<CancellationException> { runtime.resumeAfterReset() })
        assertTrue(ordinary in cancelled.suppressed)
        assertEquals(listOf("first.pause", "second.pause", "checks.pause", "library.pause", "library.resume", "checks.resume", "first.resume", "second.resume"), calls)
        calls.clear()
        assertSame(cancelled, assertFailsWith<CancellationException> { runtime.shutdown() })
        assertEquals(listOf("first.shutdown", "second.shutdown", "checks.close", "library.shutdown", "resource.close", "subscription.close"), calls)
    }

    @Test fun nativeReceivesOnlyPreparedValuesAndFinishedFollowsResourceClose() = runBlocking {
        val root = Files.createTempDirectory("generic-native").toFile()
        try {
            val computer = Computer()
            val agent = Agent()
            val runtime = runtime(agent, computer)
            val observed = withContext(NativeRunContext("durable-run")) {
                runtime.run(project(root.path), session, "Prompt", profile, emptyList()).onEach {
                    if (it is CodingEvent.Finished) assertTrue(computer.closed)
                }.toList()
            }
            assertEquals(1, observed.count { it is CodingEvent.Finished })
            val request = checkNotNull(agent.request)
            assertEquals("durable-run", request.requestId)
            assertEquals("durable-run", computer.request)
            assertEquals("fixture-extension", request.tools.extensionSources["computer-use.mjs"])
            assertEquals("fixture-secret", request.tools.environment["MAGICPAPER_COMPUTER_TOKEN"])
            assertFalse(request.tools.toString().contains("fixture-secret"))
            assertFalse(request.toString().contains("Prompt"))
            assertEquals("durable-run", computer.request)
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationClosesResourcesAndRemainsCancellation() = runBlocking {
        val root = Files.createTempDirectory("generic-native-cancel").toFile()
        try {
            val computer = Computer()
            val cleanup = IllegalStateException("Abort failed")
            val agent = Agent(cancel = true, abortFailure = cleanup)
            val runtime = runtime(agent, computer)
            val cancelled = assertFailsWith<CancellationException> {
                withContext(NativeRunContext("cancelled-run")) { runtime.run(project(root.path), session, "Prompt", profile, emptyList()).toList() }
            }
            assertTrue(computer.closed)
            val cleanupEvidence = generateSequence<Throwable>(cancelled) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()
            assertTrue(cleanup in cleanupEvidence, cancelled.stackTraceToString())
            assertEquals(listOf("session"), agent.aborted)
        } finally { root.deleteRecursively() }
    }
    @Test fun failedResourceCloseCannotPublishSuccessfulTerminalEvent() = runBlocking {
        val root = Files.createTempDirectory("generic-native-cleanup").toFile()
        try {
            val computer = Computer(failClose = true)
            val agent = Agent()
            val events = withContext(NativeRunContext("cleanup-run")) {
                runtime(agent, computer).run(project(root.path), session, "Prompt", profile, emptyList()).toList()
            }
            assertNotNull(agent.request)
            assertTrue(computer.closed)
            assertIs<CodingEvent.Failed>(events[events.lastIndex - 1])
            assertIs<CodingEvent.Finished>(events.last())
            assertEquals(1, events.count { it is CodingEvent.Finished })
        } finally { root.deleteRecursively() }
    }
    @Test fun browserUsesToolReceiptIdentityWhileNativeProcessUsesItsSeparateRunIdentity() = runBlocking {
        val root = Files.createTempDirectory("generic-browser-identity").toFile()
        try {
            val context = ToolExecutionContext("project", "receipt-owner", "session", "pending-run", ToolRole.CHAT, CodingInteractionMode.CODE)
            val tools = testToolSessions(MemoryToolReceiptStore()).session(context)
            val realBrowser = io.aequicor.magicpaper.data.browser.createDesktopBrowserSessions(InMemoryEventJournal())
            var owner: Pair<String, String>? = null
            val browser = object : BrowserSessions by realBrowser {
                override fun create(ownerSessionId: String, requestId: String): BrowserSession {
                    owner = ownerSessionId to requestId
                    return realBrowser.create(ownerSessionId, requestId)
                }
            }
            var validated = false
            val agent = Agent(onRun = {
                val result = tools.call("native-call", "browser.validate_html", buildJsonObject {
                    put("html", "<!doctype html><html lang='en'><head><title>Fixture</title></head><body><p>Fixture</p></body></html>")
                }).jsonObject
                assertEquals("provided_html", result.getValue("source").jsonPrimitive.content)
                validated = true
            })
            val runtime = GenericNativeRuntime(agent, Library, {}, { it }, null,
                testQuestionnaireFactory().create("fixture", null), browser, testCommandChecks)
            val events = withContext(tools + NativeRunContext("hashed-native-run")) {
                runtime.run(project(root.path), session, "Prompt", profile, emptyList()).toList()
            }
            assertEquals(context.ownerSessionId to context.requestId, owner)
            assertEquals("hashed-native-run", agent.request?.requestId)
            assertTrue(validated, events.toString())
            assertTrue(events.none { it is CodingEvent.Failed }, events.toString())
        } finally { root.deleteRecursively() }
    }
    private val profile = LlmProfile("local", "Local", modelId = "fixture", baseUrl = "http://127.0.0.1:1", apiKey = "fixture")
    private val session = CodingSession("session", "project", "Session", 0, engine = CodingEngine.PI)
    private fun project(path: String) = CodingProject("project", "Project", path, 0)
    private fun runtime(agent: Agent, computer: Computer) = GenericNativeRuntime(agent, Library, {}, { it }, computer,
        testQuestionnaireFactory().create("fixture", null), testBrowserSessions, testCommandChecks)
    private class Agent(private val cancel: Boolean = false, private val abortFailure: Throwable? = null, private val onRun: suspend () -> Unit = {}) : BackendAgent {
        var request: NativeAgentRequest? = null
        val aborted = mutableListOf<String>()
        override val descriptor = backendCatalog.descriptor(CodingEngine.PI)
        override val rootPath = "fixture"
        override val approvals: NativeApprovalRequests? = null
        override val history: NativeToolHistory? = null
        override val removal: NativeRemoval? = null
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun prepare() = flow { emit(status()) }
        override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
        override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
        override fun run(request: NativeAgentRequest): Flow<CodingEvent> = flow {
            this@Agent.request = request
            onRun()
            if (cancel) throw CancellationException("Cancelled native attempt")
            emit(CodingEvent.FinalText("Result")); emit(CodingEvent.Finished)
        }
        override suspend fun inspectRecovery(sessionId: String) = NativeRecoverySummary(emptyList(), false)
        override suspend fun stopRecovery(attempt: NativeAttemptRef) = NativeRecoverySummary(emptyList(), false)
        override suspend fun acknowledgeRecovery(attempt: NativeAttemptRef, parentDecisionId: String): NativeRecoveryAcknowledgement = error("Unexpected recovery")
        override suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String): NativeNoDispatchAcknowledgement = error("Unexpected no-dispatch recovery")
        override suspend fun shutdown() = close()
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override suspend fun reconcile(sessionId: String) = false
        override fun abort(sessionId: String) { aborted += sessionId; abortFailure?.let { throw it } }
        override fun abortAll() = Unit
        override fun close() = Unit
    }
    private object Library : NativeProviderLibrary {
        override suspend fun shutdown() = close()
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override fun prepare(): Flow<NativeInstallationStatus> = error("Direct adapter does not need a proxy")
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn = error("Unexpected provider turn")
        override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = error("Unexpected proxy")
        override fun close() = Unit
    }
    private class Computer(private val failClose: Boolean = false) : NativeComputerUse {
        var closed = false
        var request: String? = null
        val lease = ComputerLease("session", "lease")
        override val supported = true
        override val state = MutableStateFlow(ComputerUseState())
        override suspend fun begin(sessionId: String, requestId: String) = lease
        override fun grant(sessionId: String) = lease
        override fun release(lease: ComputerLease) = Unit
        override fun endpoint(lease: ComputerLease, requestId: String): ComputerEndpoint {
            request = requestId
            return object : ComputerEndpoint {
                override val descriptor = ComputerEndpointDescriptor("http://127.0.0.1:1", "fixture-secret", "fixture-extension")
                override fun close() { closed = true; if (failClose) error("private cleanup detail") }
            }
        }
        override fun capturePolicy() = ComputerPolicyRef("fixture", 0)
        override fun configure(computer: ComputerAccess, application: ComputerAccess) = Unit
        override fun invalidatePolicy() = Unit
        override suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean = error("Unexpected permission")
        override fun disable(sessionId: String?) = Unit
        override suspend fun preview(sessionId: String) = Unit
        override fun openSystemSettings() = Unit
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override fun close() = Unit
    }
}
