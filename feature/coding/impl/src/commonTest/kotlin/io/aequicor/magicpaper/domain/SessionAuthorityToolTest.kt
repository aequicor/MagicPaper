package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SessionAuthorityToolTest {
    private val json = Json { encodeDefaults = true }
    private fun create(version: Long? = null) = json.encodeToJsonElement(
        SessionCreateArgs("Child", "Investigate", "Verified findings", 1_000, expectedVersion = version)).jsonObject
    private fun rename(child: String, version: Long? = null) = json.encodeToJsonElement(
        SessionControlArgs(child, OrganismAction.RENAME, "Clarify ownership", name = "Reviewed child", expectedVersion = version)).jsonObject
    private fun host(f: SessionOrganismTestFixture): ToolHost = ToolHost(StoredToolReceipts(f.storage)).also { host ->
        host.receiver = { context, operation, name, args -> f.service.execute(context, operation, name, args) }
        host.authorizeTool = { context, definition ->
            val organism = f.store.get(f.root.organismId!!)
            val actor = organism.sessions.getValue(context.ownerSessionId)
            requireTool(actor.generation == context.runtimeGeneration && actor.mode == context.mode) { "Revoked authority" }
            if (definition.mutating && definition.id != "immunity.signal")
                f.store.check(f.service.authority(context.copy(stateVersion = null), organism))
        }
    }

    @Test fun oneToolSessionCanApplySequentialCommandsWithoutFreezingAnObsoleteVersion() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        val tools = host(f).session(f.context())
        val first = tools.call("first", "session.create", create()).jsonObject
        val second = tools.call("second", "session.create", create()).jsonObject
        assertNotEquals(first["sessionId"], second["sessionId"])
        val version = f.store.get(f.root.organismId!!).version
        tools.call("rename", "session.control", rename(first.getValue("sessionId").jsonPrimitive.content, version))
        assertTrue(f.store.get(f.root.organismId!!).version > version)
        assertEquals(3, f.store.get(f.root.organismId!!).operations.size)
    }

    @Test fun explicitAggregateVersionDetectsOutboxChangesEvenWhenActorNodeIsUnchanged() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        f.create("child")
        val before = f.store.get(f.root.organismId!!)
        f.send("message", "session-child")
        val after = f.store.get(before.id)
        assertEquals(before.sessions.getValue("root").version, after.sessions.getValue("root").version)
        assertTrue(after.version > before.version)
        val tools = host(f).session(f.context())
        assertFailsWith<StaleSessionVersion> { tools.call("stale", "session.control", rename("session-child", before.version)) }
        assertEquals(after.operations, f.store.get(before.id).operations)
        assertEquals(ToolPhase.FAILED, tools.receiptsForTest(f).single().phase)
        tools.call("fresh", "session.control", rename("session-child", after.version))
        assertEquals("Reviewed child", f.store.get(before.id).sessions.getValue("session-child").name)
    }

    private suspend fun ToolSession.receiptsForTest(f: SessionOrganismTestFixture) =
        StoredToolReceipts(f.storage).forRequest("${context.projectId}/${context.ownerSessionId}/${context.requestId}")

    @Test fun identicalExplicitVersionReplaysDurablyButChangingItReusesTheOperationIllegally() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        f.create("child")
        val context = f.context()
        val expected = f.store.get(f.root.organismId!!).version
        val args = rename("session-child", expected)
        f.service.execute(context, "rename", "session.control", args)
        val committed = f.store.get(f.root.organismId!!)
        val restarted = SessionOrganismService(SessionOrganismStore(f.storage) { 1_000 }, f.projects, f.settings)
        restarted.execute(context, "rename", "session.control", args)
        assertEquals(committed, restarted.store.get(committed.id))
        assertFailsWith<IllegalArgumentException> {
            restarted.execute(context, "rename", "session.control", rename("session-child", committed.version))
        }
        assertEquals(committed, restarted.store.get(committed.id))
    }

    @Test fun explicitVersionIsComparedAtomicallyAfterSuspendingSourceInspection() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        f.create("child")
        val context = f.context()
        val before = f.store.get(f.root.organismId!!)
        val service = SessionOrganismService(f.store, f.projects, f.settings, sourceSnapshot = {
            f.store.command(f.service.authority(context, before), "intervening-rename",
                OrganismCommand(OrganismAction.RENAME, "session-child", name = "Concurrent change"))
            "source-sha"
        })
        service.startChild = { _, _ -> fail("A stale create must not start a runtime") }
        assertFailsWith<StaleSessionVersion> { service.execute(context, "stale-create", "session.create", create(before.version)) }
        val after = f.store.get(before.id)
        assertEquals(before.sessions.keys, after.sessions.keys)
        assertFalse("stale-create" in after.operations)
        assertEquals("Concurrent change", after.sessions.getValue("session-child").name)
        assertEquals(before.sessions.getValue("root").remainingTokens, after.sessions.getValue("root").remainingTokens)
    }

    @Test fun omittedVersionRevalidatesOnlyAPrecommitConflictWithoutRepeatingSourceInspection() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        f.create("child")
        val context = f.context()
        val before = f.store.get(f.root.organismId!!)
        var inspected = 0
        var started = 0
        val service = SessionOrganismService(f.store, f.projects, f.settings, sourceSnapshot = {
            inspected++
            f.store.command(f.service.authority(context, before), "intervening-rename",
                OrganismCommand(OrganismAction.RENAME, "session-child", name = "Concurrent change"))
            "source-sha"
        })
        service.startChild = { _, _ -> started++ }
        service.execute(context, "new-child", "session.create", create())
        val after = f.store.get(before.id)
        assertEquals(before.sessions.size + 1, after.sessions.size)
        assertEquals(1, inspected)
        assertEquals(1, started)
        assertEquals(before.sessions.getValue("root").remainingTokens - 1_000, after.sessions.getValue("root").remainingTokens)
        assertEquals("source-sha", after.sessions.getValue("session-new-child").task?.sourceVersion)
        assertEquals(setOf("child", "intervening-rename", "new-child"), after.operations.keys)
    }

    @Test fun revokedGenerationCannotReplaySuccessEvenWithItsOriginalVersionAndArguments() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(); f.service.startChild = { _, _ -> }
        val owner = f.context()
        val tools = host(f).session(owner)
        val args = create(f.store.get(f.root.organismId!!).version)
        tools.call("create", "session.create", args)
        val next = f.store.beginRun(f.root.organismId!!, "root")
        assertNotEquals(owner.runtimeGeneration, next.generation)
        assertFailsWith<ToolArgumentRejection> { tools.call("create", "session.create", args) }
        val renewed = host(f).session(owner.copy(runtimeGeneration = next.generation))
        assertFailsWith<IllegalArgumentException> { renewed.call("create", "session.create", args) }
        assertEquals(1, f.store.get(f.root.organismId!!).operations.size)
    }

    @Test fun auxiliaryRuntimeCanInspectButCannotBorrowItsHistoryOwnersLifecycleAuthority() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize()
        var mutations = 0
        val host = ToolHost(MemoryToolReceiptStore()).also { it.receiver = { _, _, tool, _ ->
            if (ToolCatalog.get(tool).mutating) mutations++
            JsonNull
        } }
        val tools = host.session(f.context().copy(sessionId = "root-merge", auxiliaryExecution = true))
        tools.call("context", "context.get", JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> { tools.call("create", "session.create", create()) }
        assertFailsWith<IllegalArgumentException> { tools.call("rename", "session.control", rename("other")) }
        tools.call("signal", "immunity.signal", json.encodeToJsonElement(ImmunitySignalArgs("root", "Observed failure")).jsonObject)
        assertEquals(1, mutations)
    }

    @Test fun argumentAuthorityIsCheckedBeforeOverridesAndReplays() = runTest {
        var allowed = false
        var effects = 0
        val host = ToolHost(MemoryToolReceiptStore())
        host.authorizeCommand = { _, definition, args ->
            if (definition.id == "session.control") requireTool(allowed && args["sessionId"] == JsonPrimitive("child")) { "Foreign target" }
        }
        val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.CHAT, CodingInteractionMode.RESEARCH)
        val tools = host.session(context, mapOf("session.control" to { _, _, _ -> effects++; JsonPrimitive("renamed") }))
        assertFailsWith<ToolArgumentRejection> { tools.call("rejected", "session.control", rename("child")) }
        allowed = true
        tools.call("accepted", "session.control", rename("child"))
        allowed = false
        assertFailsWith<ToolArgumentRejection> { tools.call("accepted", "session.control", rename("child")) }
        assertEquals(1, effects)
    }

    @Test fun auxiliaryVerificationModeExceptionIsLimitedToDiagnosticsAndSignal() {
        val context = ToolExecutionContext("p", "owner", "final", "request", ToolRole.CHAT,
            CodingInteractionMode.CODE, planId = "plan", auxiliaryExecution = true)
        for (tool in listOf("context.get", "receipt.get", "questionnaire", "immunity.signal")) {
            assertTrue(ToolCatalog.get(tool).allowsAuthorityMode(context, CodingInteractionMode.PLANNING))
            assertFalse(ToolCatalog.get(tool).allowsAuthorityMode(context.copy(auxiliaryExecution = false), CodingInteractionMode.PLANNING))
        }
        assertFalse(ToolCatalog.get("session.create").allowsAuthorityMode(context, CodingInteractionMode.PLANNING))
        assertFalse(ToolCatalog.get("file.write").allowsAuthorityMode(context, CodingInteractionMode.PLANNING))
        assertFalse(ToolCatalog.get("context.get").allowsAuthorityMode(context.copy(planId = null), CodingInteractionMode.PLANNING))
    }
}
