package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionReceiptRecoveryTest {
    private val json = Json { encodeDefaults = true }
    private fun create() = json.encodeToJsonElement(SessionCreateArgs("Child", "Investigate", "Verified findings", 1_000)).jsonObject
    private fun rename() = json.encodeToJsonElement(SessionControlArgs("session-child", OrganismAction.RENAME, "Clarify", name = "Renamed")).jsonObject
    private suspend fun receipt(context: ToolExecutionContext, operation: String, tool: String, args: JsonObject) =
        ToolReceipt("${context.projectId}/${context.ownerSessionId}/${context.requestId}/$operation", tool, args,
            phase = ToolPhase.UNKNOWN, operationId = operation, runtimeGeneration = context.runtimeGeneration).forPersistence()
    private fun restarted(f: SessionOrganismTestFixture) = SessionOrganismService(SessionOrganismStore(f.storage) { 1_000 }, f.projects, f.settings)

    @Test fun acceptedCreationIsNotSuccessUntilDurableRuntimeObservationProvesOwnership() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        val context = f.context(); val args = create()
        f.service.execute(context, "child", "session.create", args)
        val intent = receipt(context, "child", "session.create", args)
        StoredToolReceipts(f.storage).save(intent)
        assertNull(restarted(f).reconcile(context, StoredToolReceipts(f.storage).get(intent.id)!!))
        f.store.beginRun(f.root.organismId!!, "session-child")
        val observed = f.store.get(f.root.organismId!!)
        assertNotNull(restarted(f).reconcile(context, intent))
        assertEquals(observed, f.store.get(observed.id)) // Proof is read-only.
        f.store.observe(observed.id, "session-child", observed.sessions.getValue("session-child").generation, SessionObservedState.UNKNOWN)
        assertNull(restarted(f).reconcile(context, intent))
    }

    @Test fun acceptedSendRequiresDeliveryAcknowledgementAndNeverSendsDuringRecovery() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }; f.create("child")
        val context = f.context()
        val packet = SessionContextPacket("Verified context")
        val args = json.encodeToJsonElement(SessionSendArgs("session-child", packet)).jsonObject
        val before = f.store.get(f.root.organismId!!)
        f.store.command(f.service.authority(context, before), "message", OrganismCommand(OrganismAction.SEND, "session-child", packet = packet))
        val intent = receipt(context, "message", "session.send", args)
        assertNull(restarted(f).reconcile(context, intent))
        assertTrue(f.projects.messages(f.project.id, "session-child").isEmpty())
        f.service.deliver(before.id)
        val delivered = f.store.get(before.id)
        assertNotNull(restarted(f).reconcile(context, intent))
        assertEquals(1, f.projects.messages(f.project.id, "session-child").size)
        assertEquals(delivered, f.store.get(before.id))
    }

    @Test fun stopAndWaitRequireObservedTerminalChildrenBeforeTheyCanBeReconciled() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }; f.create("child")
        val context = f.context()
        val waitArgs = json.encodeToJsonElement(SessionWaitArgs(setOf("session-child"))).jsonObject
        val wait = receipt(context, "wait", "session.wait", waitArgs)
        assertFailsWith<IllegalStateException> { f.service.execute(context, "wait", "session.wait", waitArgs) }
        assertNull(restarted(f).reconcile(context, wait))
        val stopArgs = json.encodeToJsonElement(SessionControlArgs("session-child", OrganismAction.STOP, "Done")).jsonObject
        f.service.stopSubtree = { }
        assertFailsWith<IllegalArgumentException> { f.service.execute(context, "stop", "session.control", stopArgs) }
        val stop = receipt(context, "stop", "session.control", stopArgs)
        assertNull(restarted(f).reconcile(context, stop))
        val before = f.store.get(f.root.organismId!!)
        f.store.observe(before.id, "session-child", before.sessions.getValue("session-child").generation, SessionObservedState.STOPPED)
        f.store.finishStop(before.id, setOf("session-child"))
        assertNotNull(restarted(f).reconcile(context, stop))
        assertNotNull(restarted(f).reconcile(context, wait))
    }

    @Test fun fingerprintAndGenerationChecksRejectUnrelatedHistoricalProof() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }; f.create("child")
        val context = f.context(); val args = rename()
        f.service.execute(context, "rename", "session.control", args)
        val intent = receipt(context, "rename", "session.control", args)
        assertNotNull(restarted(f).reconcile(context, intent))
        val altered = intent.copy(arguments = JsonObject(args + ("name" to JsonPrimitive("Different"))))
        assertNull(restarted(f).reconcile(context, altered))
        assertNull(restarted(f).reconcile(context, intent.copy(native = true)))
        assertNull(restarted(f).reconcile(context, intent.copy(argumentsComplete = false)))
        val next = f.store.beginRun(f.root.organismId!!, context.ownerSessionId)
        assertFailsWith<ToolArgumentRejection> { restarted(f).reconcile(context, intent) }
        assertFailsWith<ToolArgumentRejection> { restarted(f).reconcile(context.copy(runtimeGeneration = next.generation), intent) }
    }

    @Test fun redactedArgumentsCannotBeGuessedBackToProveTheOriginalCommand() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }; f.create("child")
        val context = f.context()
        val args = json.encodeToJsonElement(SessionControlArgs("session-child", OrganismAction.RENAME,
            "Authorization: Bearer original-secret", name = "Renamed")).jsonObject
        f.service.execute(context, "rename", "session.control", args)
        val intent = receipt(context, "rename", "session.control", args)
        assertFalse(intent.arguments.toString().contains("original-secret"))
        assertNull(restarted(f).reconcile(context, intent))
    }

    @Test fun routeReviewAndSignalRequireTheirOwnCommittedAuditAndPayload() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        f.create("a"); f.create("b")
        val context = f.context()
        val routeArgs = json.encodeToJsonElement(SessionRouteArgs("session-a", "session-b", "Share findings")).jsonObject
        f.service.execute(context, "route", "session.route", routeArgs)
        assertNotNull(restarted(f).reconcile(context, receipt(context, "route", "session.route", routeArgs)))
        val id = f.root.organismId!!
        f.store.recordResult(id, SessionResult("result", "session-a", 1, "root", "Preliminary result", sourceVersion = "source"))
        f.store.observe(id, "session-a", 1, SessionObservedState.COMPLETED)
        val reviewArgs = json.encodeToJsonElement(SessionReviewResultArgs("result", false, "Missing verification", "source", emptyList())).jsonObject
        f.service.execute(context, "review", "session.result.review", reviewArgs)
        assertNotNull(restarted(f).reconcile(context, receipt(context, "review", "session.result.review", reviewArgs)))
        val signalArgs = json.encodeToJsonElement(ImmunitySignalArgs("session-b", "Observed timeout")).jsonObject
        f.store.command(f.service.authority(context, f.store.get(id)), "signal",
            OrganismCommand(OrganismAction.SIGNAL, "session-b", reason = "Observed timeout"))
        assertNotNull(restarted(f).reconcile(context, receipt(context, "signal", "immunity.signal", signalArgs)))
        assertNull(restarted(f).reconcile(context, receipt(context, "route", "immunity.signal", signalArgs)))
    }

    @Test fun unknownReceiptCanBeProvedWhileQuarantinedWithoutRestoringMutationAuthority() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }; f.create("child")
        val backing = StoredToolReceipts(f.storage)
        var dropCompletion = true
        val receipts = object : ToolReceiptStore by backing {
            override suspend fun save(receipt: ToolReceipt) {
                if (receipt.phase == ToolPhase.SUCCEEDED && dropCompletion) { dropCompletion = false; error("Lost completion write") }
                backing.save(receipt)
            }
        }
        val host = ToolHost(receipts)
        var executions = 0
        host.receiver = { context, id, tool, args -> executions++; f.service.execute(context, id, tool, args) }
        host.authorizeReceipt = { context, _ ->
            val actor = f.store.get(f.root.organismId!!).sessions.getValue(context.ownerSessionId)
            requireTool(actor.generation == context.runtimeGeneration && actor.mode == context.mode) { "Revoked authority" }
        }
        host.authorizeTool = { context, definition ->
            host.authorizeReceipt(context, definition)
            f.store.check(f.service.authority(context, f.store.get(f.root.organismId!!)))
        }
        host.unknownOutcome = { context, intent ->
            f.service.project(f.store.quarantine(f.root.organismId!!, context.ownerSessionId, context.runtimeGeneration, intent.operationId, "Unknown outcome"))
        }
        host.reconcile = { context, intent -> restarted(f).reconcile(context, intent) }
        val context = f.context()
        val tools = host.session(context)
        assertFailsWith<IllegalStateException> { tools.call("rename", "session.control", rename()) }
        assertEquals(SessionDesiredState.QUARANTINE, f.store.get(f.root.organismId!!).sessions.getValue("root").desired)
        assertEquals(ToolPhase.UNKNOWN, backing.forRequest("${context.projectId}/${context.ownerSessionId}/${context.requestId}").single().phase)
        val result = tools.call("rename", "session.control", rename()).jsonObject
        assertEquals(JsonPrimitive(true), result["reconciled"])
        assertEquals(JsonPrimitive("QUARANTINE"), result["ownerDesiredState"])
        assertEquals(1, executions)
        assertEquals(ToolPhase.SUCCEEDED, backing.forRequest("${context.projectId}/${context.ownerSessionId}/${context.requestId}").single().phase)
        assertFailsWith<ToolArgumentRejection> { tools.call("new-change", "session.control", rename()) }
        assertEquals(1, executions)
        assertEquals(SessionDesiredState.QUARANTINE, f.store.get(f.root.organismId!!).sessions.getValue("root").desired)
    }
}
