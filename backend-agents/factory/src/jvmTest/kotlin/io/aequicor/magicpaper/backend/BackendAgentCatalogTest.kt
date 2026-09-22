package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class BackendAgentCatalogTest {
    private fun catalog(entries: List<BackendAgentContribution>) = BackendAgentCatalog(entries, BackendAgentConstructor { contribution, env -> contribution.create(env) as BackendAgent })
    private val descriptor = createBackendAgentCatalog().descriptor(CodingEngine.PI).copy(capabilities = emptySet())
    private fun contribution(value: BackendAgentDescriptor = descriptor, create: () -> BackendAgent = { FakeAgent(value) }) =
        object : BackendAgentContribution {
            override val descriptor = value
            override val paths = NativeBackendPaths("coding", "owned-processes", "coding-questionnaires", "native-pi")
            override fun create(environment: NativeBackendEnvironment) = create()
        }

    @Test fun serviceLoaderFindsEveryInstalledEngineWithoutHostRegistration() {
        val catalog = createBackendAgentCatalog()
        assertEquals(setOf(CodingEngine.PI, CodingEngine.CODEX, CodingEngine.CLAUDE_CODE), catalog.descriptors.map { it.engine }.toSet())
        assertEquals(catalog.descriptors.size, catalog.descriptors.distinctBy { it.engine }.size)
    }
    @Test fun duplicateIdentityIsRejectedBeforeAnEnvironmentCanBeRequested() {
        var created = false
        val entry = contribution { created = true; FakeAgent(descriptor) }
        assertFailsWith<IllegalArgumentException> { catalog(listOf(entry, entry)) }
        assertFalse(created)
    }
    @Test fun missingDeclaredCapabilityClosesTheRejectedInstance() = environment { env ->
        val invalid = FakeAgent(descriptor.copy(capabilities = setOf(BackendAgentCapability.NATIVE_APPROVALS)))
        val failure = assertFailsWith<IllegalArgumentException> {
            catalog(listOf(contribution(invalid.descriptor) { invalid })).create { _, _ -> env }
        }
        assertContains(failure.message.orEmpty(), "approval")
        assertTrue(invalid.closed)
    }
    @Test fun declaredModelCatalogWithoutImplementationIsRejected() = environment { env ->
        val invalid = FakeAgent(descriptor.copy(capabilities = setOf(BackendAgentCapability.NATIVE_MODEL_CATALOG)))
        val failure = assertFailsWith<IllegalArgumentException> {
            catalog(listOf(contribution(invalid.descriptor) { invalid })).create { _, _ -> env }
        }
        assertContains(failure.message.orEmpty(), "model catalog")
        assertTrue(invalid.closed)
    }
    @Test fun modelCatalogWithoutDeclaredCapabilityIsRejected() = environment { env ->
        val invalid = FakeAgent(descriptor, models = NativeModelCatalog { emptyList() })
        val failure = assertFailsWith<IllegalArgumentException> {
            catalog(listOf(contribution(invalid.descriptor) { invalid })).create { _, _ -> env }
        }
        assertContains(failure.message.orEmpty(), "model catalog")
        assertTrue(invalid.closed)
    }
    @Test fun onlyEnginesWithTheirOwnModelListsDeclareANativeModelCatalog() {
        val declared = createBackendAgentCatalog().descriptors
            .filter { BackendAgentCapability.NATIVE_MODEL_CATALOG in it.capabilities }.map { it.engine }
        assertEquals(setOf(CodingEngine.CODEX, CodingEngine.CLAUDE_CODE), declared.toSet())
    }
    @Test fun constructionFailureClosesEarlierInstancesAndPreservesCleanupCause() = environment { env ->
        val primary = IllegalStateException("construction")
        val cleanup = IllegalStateException("cleanup")
        val first = FakeAgent(descriptor, cleanup)
        val second = contribution(descriptor.copy(engine = CodingEngine.CODEX)) { throw primary }
        val failure = assertFailsWith<IllegalStateException> {
            catalog(listOf(contribution { first }, second)).create { _, _ -> env }
        }
        assertSame(primary, failure)
        assertSame(cleanup, failure.suppressed.single())
        assertTrue(first.closed)
    }
    @Test fun eachFactoryCallOwnsItsOwnMutableExecutionState() = environment { env ->
        val catalog = catalog(listOf(contribution()))
        val first = catalog.create { _, _ -> env }.single() as FakeAgent
        val second = catalog.create { _, _ -> env }.single() as FakeAgent
        assertNotSame(first, second)
        first.abort("a")
        first.close()
        assertEquals(listOf("a"), first.aborted)
        assertTrue(first.closed)
        assertTrue(second.aborted.isEmpty())
        assertFalse(second.closed)
        second.close()
    }

    private class FakeAgent(
        override val descriptor: BackendAgentDescriptor,
        val closeFailure: Throwable? = null,
        override val models: NativeModelCatalog? = null,
    ) : BackendAgent {
        val aborted = mutableListOf<String>()
        var closed = false
        override val rootPath = "fixture"
        override val approvals: NativeApprovalRequests? = null
        override val history: NativeToolHistory? = null
        override val removal: NativeRemoval? = null
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun prepare() = flow { emit(status()) }
        override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
        override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
        override fun run(request: NativeAgentRequest) = flowOf<CodingEvent>(CodingEvent.Finished)
        override suspend fun inspectRecovery(sessionId: String) = NativeRecoverySummary(emptyList(), false)
        override suspend fun stopRecovery(attempt: NativeAttemptRef) = NativeRecoverySummary(emptyList(), false)
        override suspend fun acknowledgeRecovery(attempt: NativeAttemptRef, parentDecisionId: String): NativeRecoveryAcknowledgement = error("Unexpected recovery")
        override suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String): NativeNoDispatchAcknowledgement = error("Unexpected no-dispatch recovery")
        override suspend fun shutdown() = close()
        override suspend fun prepareForReset() = Unit
        override suspend fun resumeAfterReset() = Unit
        override suspend fun reconcile(sessionId: String) = false
        override fun abort(sessionId: String) { aborted += sessionId }
        override fun abortAll() = Unit
        override fun close() { closed = true; closeFailure?.let { throw it } }
    }
    private fun environment(test: (NativeBackendEnvironment) -> Unit) {
        val root = Files.createTempDirectory("native-catalog").toFile()
        val processes = object : NativeProcessRecovery, NativeProviderProcesses {
            override fun reconcileOrphans() = error("Construction must not restore external processes")
            override fun belongsTo(id: String, process: Process?) = false
            override fun reconcile(id: String) = error("Unexpected process access")
            override fun record(id: String, process: Process, attachLifetime: Boolean) = error("Unexpected process access")
            override fun clear(id: String) = error("Unexpected process access")
        }
        val diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) }
        val resources = NativeResources { error("Construction must not read native resources") }
        val library = createNativeProviderLibrary(root.absolutePath, resources, processes, diagnostics, MemoryNativeJournal())
        val questionnaires = object : NativeQuestionnaires {
            override suspend fun ask(request: UserInteractionRequest): List<PlanningAnswer> = error("Unexpected questionnaire")
            override suspend fun beginDelivery(requestId: String): String = error("Unexpected questionnaire")
            override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome) = error("Unexpected questionnaire")
        }
        try { library.use {
            test(NativeBackendEnvironment(Json, root.absolutePath, resources = resources, processes = processes,
                cachedAccessTokens = NativeAuthTokens { error("Unexpected credentials") },
                refreshedAccessTokens = NativeAuthTokens { error("Unexpected credentials") }, questionnaires = questionnaires,
                diagnostics = diagnostics, toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("Unexpected tool") },
                providerLibrary = library, lifecycleJournal = object : NativeLifecycleJournal {
                    override suspend fun snapshot(): NativeJournalSnapshot = error("Construction must not load state")
                    override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? = error("Construction must not persist")
                }))
        } } finally { root.deleteRecursively() }
    }
}
