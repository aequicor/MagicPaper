package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine
import kotlin.test.*

class BackendAgentFactoryTest {
    @Test fun factoryConnectsOnlyImplementedEnginesAndTheirCapabilities() {
        val catalog = createBackendAgentCatalog()
        assertEquals(setOf(CodingEngine.PI, CodingEngine.CODEX, CodingEngine.CLAUDE_CODE), catalog.descriptors.map { it.engine }.toSet())
        assertEquals(catalog.descriptors.size, catalog.descriptors.map { it.engine }.distinct().size)
        val pi = catalog.descriptor(CodingEngine.PI)
        assertEquals("Pi", pi.adapterName)
        assertContains(pi.capabilities, BackendAgentCapability.MANAGED_INSTALLATION)
        assertFalse(BackendAgentCapability.EXTERNAL_INSTALLATION in pi.capabilities)
        val codex = catalog.descriptor(CodingEngine.CODEX)
        assertContains(codex.capabilities, BackendAgentCapability.EXTERNAL_INSTALLATION)
        assertContains(codex.capabilities, BackendAgentCapability.NATIVE_TOOL_HISTORY)
        assertFalse(BackendAgentCapability.MANAGED_INSTALLATION in codex.capabilities)
        val claude = catalog.descriptor(CodingEngine.CLAUDE_CODE)
        assertEquals("Claude Code", claude.adapterName)
        assertEquals(setOf(BackendAgentCapability.EXTERNAL_INSTALLATION), claude.capabilities)
    }

    @Test fun onlyAnEngineWithItsOwnAccountOffersSubscriptionAccess() {
        assertNotNull(subscriptionAccess(CodingEngine.CODEX))
        assertNull(subscriptionAccess(CodingEngine.PI))
        assertNull(subscriptionAccess(CodingEngine.CLAUDE_CODE))
    }

    private fun subscriptionAccess(engine: CodingEngine): NativeSubscriptionAccess? {
        val home = java.nio.file.Files.createTempDirectory("subscription-access-")
        try {
            return createNativeSubscriptionAccess(engine, NativeSubscriptionEnvironment(kotlinx.serialization.json.Json, home.toString(),
                processes = object : NativeProcessRecovery {
                    override fun record(id: String, process: Process, attachLifetime: Boolean) = error("No process launch")
                    override fun clear(id: String) = error("No process cleanup")
                    override fun belongsTo(id: String, process: Process?) = false
                    override fun reconcile(id: String) = error("No process recovery")
                },
                accessTokens = NativeAuthTokens { error("No tokens") },
                questionnaires = object : NativeQuestionnaires {
                    override suspend fun ask(request: io.aequicor.magicpaper.domain.UserInteractionRequest) = error("No questionnaire")
                    override suspend fun beginDelivery(requestId: String) = error("No questionnaire")
                    override suspend fun finishDelivery(requestId: String, attemptId: String,
                        outcome: io.aequicor.magicpaper.domain.QuestionnaireDeliveryOutcome) = error("No questionnaire")
                },
                diagnostics = NativeDiagnostics { _, _, cause, _ -> throw AssertionError(cause) },
                toolPresentation = NativeToolPresentationResolver { _, _, _ -> error("No tools") }))
                ?.also { it.close() }
        } finally { home.toFile().deleteRecursively() }
    }
}
