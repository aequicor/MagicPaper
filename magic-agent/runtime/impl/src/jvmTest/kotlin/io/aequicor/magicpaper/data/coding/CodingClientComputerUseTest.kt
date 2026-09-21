package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.llm.CodexAppServerOpenAiSubscription
import io.aequicor.magicpaper.domain.*
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class CodingClientComputerUseTest {
    @Test fun closingTurnClientPreservesAnotherSessionsComputerGrant() = runBlocking {
        val root = Files.createTempDirectory("magicpaper-client-computer")
        val computer = LeaseComputer()
        val owner = CodexAppServerOpenAiSubscription(Json, root, computerUse = computer, browser = io.aequicor.magicpaper.data.coding.testBrowserSessions, checks = io.aequicor.magicpaper.data.coding.testCommandChecks, journal = io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), questionnaireFactory = io.aequicor.magicpaper.domain.testQuestionnaireFactory())
        try {
            computer.enable("other", ComputerAccess.SCREEN, computer.capturePolicy())
            val grant = computer.grant("other")!!
            val client = owner.newCodingClient()
            assertSame(computer, client.computerUse)
            client.close()
            assertEquals(grant, computer.grant("other"))
            owner.close()
            assertNull(computer.grant("other"))
        } finally { owner.close(); root.toFile().deleteRecursively() }
    }

    @Test fun failedPreflightReleasesOnlyItsOwnGrant() = runBlocking {
        val root = Files.createTempDirectory("magicpaper-runtime-computer")
        val computer = LeaseComputer()
        val owner = CodexAppServerOpenAiSubscription(Json, root, computerUse = computer, browser = io.aequicor.magicpaper.data.coding.testBrowserSessions, checks = io.aequicor.magicpaper.data.coding.testCommandChecks, journal = io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), questionnaireFactory = io.aequicor.magicpaper.domain.testQuestionnaireFactory())
        val runtime = DesktopCodingRuntime(PiCodingRuntime(root.resolve("pi").toFile(), computerUse = computer, browser = io.aequicor.magicpaper.data.coding.testBrowserSessions, checks = io.aequicor.magicpaper.data.coding.testCommandChecks, journal = io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), questionnaireFactory = io.aequicor.magicpaper.domain.testQuestionnaireFactory()), owner)
        try {
            assertSame(computer, runtime.computerUse)
            computer.enable("owner", ComputerAccess.SCREEN, computer.capturePolicy())
            val grant = computer.grant("owner")!!
            val project = CodingProject("p", "Project", root.toString(), 1)
            val unconfigured = LlmProfile("invalid", "Missing model")
            suspend fun run(id: String) = runtime.run(project, CodingSession(id, "p", id, 1, engine = CodingEngine.CODEX), "test", unconfigured).toList()
            assertTrue(run("other").any { it is CodingEvent.Failed })
            assertEquals(grant, computer.grant("owner"))
            assertTrue(run("owner").any { it is CodingEvent.Failed })
            assertNull(computer.grant("owner"))
        } finally { owner.close(); root.toFile().deleteRecursively() }
    }
}

private class LeaseComputer : NativeComputerUse {
    override val supported = true
    override val state = kotlinx.coroutines.flow.MutableStateFlow(ComputerUseState())
    private val leases = mutableMapOf<String, ComputerLease>()
    override fun capturePolicy() = ComputerPolicyRef("fixture", 0)
    override fun configure(computer: ComputerAccess, application: ComputerAccess) { disable() }
    override fun invalidatePolicy() { disable() }
    override suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean {
        check(expectedPolicy == capturePolicy())
        leases[sessionId] = ComputerLease(sessionId, java.util.UUID.randomUUID().toString())
        return true
    }
    override suspend fun begin(sessionId: String, requestId: String): ComputerLease? = grant(sessionId)
    override fun grant(sessionId: String) = leases[sessionId]
    override fun release(lease: ComputerLease) { leases.remove(lease.sessionId, lease) }
    override fun disable(sessionId: String?) { if (sessionId == null) leases.clear() else leases.remove(sessionId) }
    override fun endpoint(lease: ComputerLease, requestId: String): ComputerEndpoint = error("No endpoint expected in a failed preflight")
    override suspend fun preview(sessionId: String) = Unit
    override fun openSystemSettings() = Unit
    override suspend fun prepareForReset() { disable() }
    override suspend fun resumeAfterReset() = Unit
    override fun close() { disable() }
}
