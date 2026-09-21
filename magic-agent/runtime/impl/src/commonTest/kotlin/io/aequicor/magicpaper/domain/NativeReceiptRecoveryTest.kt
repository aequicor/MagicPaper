package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Recovery of an ordinary session: no plan binding, so any request of this generation is in scope. */
class NativeReceiptRecoveryTest {
    private val session = CodingSession("root", "project", "Root", 0, piSessionId = "native-thread", engine = CodingEngine.PI, runtimeGeneration = 1)
    private val receipt = ToolReceipt("project/root/request-1/native/call-1", "powershell", JsonObject(emptyMap()),
        phase = ToolPhase.UNKNOWN, runtimeGeneration = 1, native = true, argumentsComplete = false, resultComplete = false)
    private fun request(vararg receipts: ToolReceipt) = SessionQuarantineRecoveryRequest(session, 1, receipts.map { receipt ->
        SessionAuditEvent("quarantine-${receipt.operationId.ifBlank { receipt.id }}", "APPLICATION", QUARANTINE_ACTION,
            setOf("root"), "Неизвестный исход", 0)
    })

    @Test fun terminalEngineItemResolvesAReceiptOfAnyRequest() = runTest {
        val receipts = MemoryToolReceiptStore().apply { save(receipt) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("powershell", false, "call-1", "BUILD SUCCESSFUL", ToolPhase.SUCCEEDED)) }
        val proof = SessionQuarantineRecovery(runtime, receipts).reconcile(request(receipt))
        assertEquals(listOf("reconcile", "results"), runtime.calls)
        assertEquals(setOf("quarantine-${receipt.id}"), proof?.quarantineOperationIds)
        assertEquals(ToolPhase.SUCCEEDED, receipts.get(receipt.id)?.phase)
        assertTrue(proof!!.evidence.any { it.contains(receipt.id) && it.contains("SUCCEEDED") })
    }

    @Test fun aFailedCommandIsAProvenOutcomeAndNotASuccess() = runTest {
        val receipts = MemoryToolReceiptStore().apply { save(receipt) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("powershell", true, "call-1", "exit 1", ToolPhase.FAILED)) }
        assertNotNull(SessionQuarantineRecovery(runtime, receipts).reconcile(request(receipt)))
        assertEquals(ToolPhase.FAILED, receipts.get(receipt.id)?.phase)
    }

    @Test fun missingOrForeignOrNonTerminalEvidenceKeepsTheQuarantine() = runTest {
        for (result in listOf(emptyList(),
            listOf(CodingEvent.ToolFinished("powershell", false, "call-2", "ok", ToolPhase.SUCCEEDED)),
            listOf(CodingEvent.ToolFinished("powershell", false, "call-1", "выполняется")))) {
            val receipts = MemoryToolReceiptStore().apply { save(receipt) }
            assertNull(SessionQuarantineRecovery(Runtime().apply { results = result }, receipts).reconcile(request(receipt)))
            assertEquals(ToolPhase.UNKNOWN, receipts.get(receipt.id)?.phase)
        }
    }

    @Test fun anotherGenerationOrNonNativeEffectIsOutsideThisReconciler() = runTest {
        for (old in listOf(receipt.copy(runtimeGeneration = 2), receipt.copy(native = false))) {
            val receipts = MemoryToolReceiptStore().apply { save(old) }
            val runtime = Runtime()
            assertNull(SessionQuarantineRecovery(runtime, receipts).reconcile(request(old)))
            assertEquals(listOf("reconcile"), runtime.calls, "Без доказуемой области журнал движка не читается")
        }
    }

    @Test fun oneUnresolvedReceiptBlocksProofForTheWholeSession() = runTest {
        val second = receipt.copy(id = "project/root/request-2/native/call-2")
        val receipts = MemoryToolReceiptStore().apply { save(receipt); save(second) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("powershell", false, "call-1", "ok", ToolPhase.SUCCEEDED)) }
        assertNull(SessionQuarantineRecovery(runtime, receipts).reconcile(request(receipt, second)))
        assertEquals(ToolPhase.UNKNOWN, receipts.get(second.id)?.phase)
    }

    @Test fun readOnlyUnknownCallsDoNotBlockRecovery() = runTest {
        val read = receipt.copy(id = "project/root/request-1/native/call-read", mutating = false)
        val receipts = MemoryToolReceiptStore().apply { save(receipt); save(read) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("powershell", false, "call-1", "ok", ToolPhase.SUCCEEDED)) }
        assertNotNull(SessionQuarantineRecovery(runtime, receipts).reconcile(request(receipt)))
    }

    @Test fun aQuarantineNamedByItsOperationIdIsMatchedByThatId() = runTest {
        val named = receipt.copy(operationId = "operation-1")
        val receipts = MemoryToolReceiptStore().apply { save(named) }
        val runtime = Runtime().apply { results = listOf(CodingEvent.ToolFinished("powershell", false, "call-1", "ok", ToolPhase.SUCCEEDED)) }
        val proof = SessionQuarantineRecovery(runtime, receipts).reconcile(request(named))
        assertEquals(setOf("quarantine-operation-1"), proof?.quarantineOperationIds)
    }

    private class Runtime : CodingRuntime {
        var results = emptyList<CodingEvent.ToolFinished>()
        val calls = mutableListOf<String>()
        override suspend fun reconcile(sessionId: String) { calls += "reconcile" }
        override suspend fun nativeToolResults(session: CodingSession, callIds: Set<String>): List<CodingEvent.ToolFinished> { calls += "results"; return results }
        override val supported = true
        override val rootPath = ""
        override suspend fun status(): RuntimeStatus = error("unused")
        override fun ensureReady(): Flow<RuntimeStatus> = emptyFlow()
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = error("must not execute")
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
    }
}
