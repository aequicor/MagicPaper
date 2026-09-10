package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PlanRetryNativeRecoveryTest {
    private val session = CodingSession("worker", "project", "Worker", 0, piSessionId = "native-thread", engine = CodingEngine.CODEX, runtimeGeneration = 1)
    private val binding = SessionLegacyAttempt("plan", "run", "stage", "attempt", 0, 1)
    private val receipt = ToolReceipt("project/worker/attempt-turn-0/native/exec-check", "shell.exec", JsonObject(emptyMap()),
        phase = ToolPhase.UNKNOWN, runtimeGeneration = 1, native = true, argumentsComplete = false, resultComplete = false)
    private fun request() = PlanRetryRecoveryRequest(session, binding, 3, listOf(
        SessionAuditEvent("quarantine-${receipt.id}", "APPLICATION", "QUARANTINE", setOf("worker"), "Unknown tool outcome", 0)))

    @Test fun exactTerminalReceiptSettlesQuarantineOnlyAfterProcessReconciliation() = runTest {
        val receipts = MemoryToolReceiptStore().apply { save(receipt) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("command", false, "exec-check", "completed", ToolPhase.SUCCEEDED)) }
        val proof = PlanRetryNativeRecovery(runtime, receipts).reconcile(request())
        assertEquals(listOf("reconcile", "results"), runtime.calls)
        assertEquals(setOf("quarantine-${receipt.id}"), proof?.quarantineOperationIds)
        assertEquals(ToolPhase.SUCCEEDED, receipts.get(receipt.id)?.phase)
    }
    @Test fun unknownOutcomeAndPartialOrWrongCallEvidenceCannotAuthorizeRetry() = runTest {
        for (result in listOf(emptyList(), listOf(CodingEvent.ToolFinished("command", false, "other-call", "success", ToolPhase.SUCCEEDED)),
            listOf(CodingEvent.ToolFinished("command", false, "exec-check", "BUILD SUCCESSFUL")))) {
            val receipts = MemoryToolReceiptStore().apply { save(receipt) }
            assertNull(PlanRetryNativeRecovery(Runtime().apply { results = result }, receipts).reconcile(request()))
            assertEquals(ToolPhase.UNKNOWN, receipts.get(receipt.id)?.phase)
        }
    }
    @Test fun failedCommandIsKnownOutcomeWithoutBeingMarkedSuccessful() = runTest {
        val receipts = MemoryToolReceiptStore().apply { save(receipt) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("command", true, "exec-check", "exit 1", ToolPhase.FAILED)) }
        assertNotNull(PlanRetryNativeRecovery(runtime, receipts).reconcile(request()))
        assertEquals(ToolPhase.FAILED, receipts.get(receipt.id)?.phase)
    }
    @Test fun otherGenerationAndNonNativeEffectsCannotUseNativeProof() = runTest {
        for (old in listOf(receipt.copy(runtimeGeneration = 2), receipt.copy(native = false), receipt.copy(id = receipt.id.replace("turn-0", "turn-1")))) {
            val receipts = MemoryToolReceiptStore().apply { save(old) }
            val runtime = Runtime()
            assertNull(PlanRetryNativeRecovery(runtime, receipts).reconcile(request()))
            assertEquals(listOf("reconcile"), runtime.calls)
        }
    }
    @Test fun failedProcessReconciliationPreservesReceipt() = runTest {
        val receipts = MemoryToolReceiptStore().apply { save(receipt) }
        assertFailsWith<IllegalStateException> { PlanRetryNativeRecovery(Runtime().apply { failReconcile = true }, receipts).reconcile(request()) }
        assertEquals(ToolPhase.UNKNOWN, receipts.get(receipt.id)?.phase)
    }

    private class Runtime : CodingRuntime {
        var results = emptyList<CodingEvent.ToolFinished>()
        var failReconcile = false
        val calls = mutableListOf<String>()
        override suspend fun reconcile(sessionId: String) { calls += "reconcile"; check(!failReconcile) }
        override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> { calls += "results"; return results }
        override val supported = true
        override val rootPath = ""
        override suspend fun status(): RuntimeStatus = error("unused")
        override fun ensureReady(): Flow<RuntimeStatus> = emptyFlow()
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = error("must not execute worker")
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }
}
