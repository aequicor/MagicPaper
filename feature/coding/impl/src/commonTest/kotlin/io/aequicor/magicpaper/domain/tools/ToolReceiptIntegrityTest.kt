package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class ToolReceiptIntegrityTest {
    private val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.CHAT, CodingInteractionMode.CODE)
    private val empty = JsonObject(emptyMap())
    private val definition = ToolDefinition("effect", "Effect", buildJsonObject { put("type", "object") }, mutating = true)
    private fun tools(store: ToolReceiptStore, definitions: List<ToolDefinition> = listOf(definition),
        ctx: ToolExecutionContext = context, check: suspend (ToolExecutionContext) -> Unit = {},
        secrets: () -> Set<String> = { emptySet() },
        reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null },
        action: suspend (ToolExecutionContext, String, JsonObject) -> JsonElement,
    ): ToolSession {
        val registry = ToolRegistry(definitions.map { JsonToolCommand(it, action) })
        return ToolSession(ctx, registry, ToolExecutor(registry, store, check, check, reconcile, secrets), secrets)
    }

    @Test fun canonicalFingerprintMatchesSha256AndIgnoresObjectOrderOnly() = runTest {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", toolArgumentsFingerprint(empty))
        val one = Json.parseToJsonElement("""{"b":[1,2],"a":{"z":3,"x":4}}""")
        val two = Json.parseToJsonElement("""{"a":{"x":4,"z":3},"b":[1,2]}""")
        assertEquals(toolArgumentsFingerprint(one), toolArgumentsFingerprint(two))
        assertNotEquals(toolArgumentsFingerprint(one), toolArgumentsFingerprint(Json.parseToJsonElement("""{"b":[2,1],"a":{"z":3,"x":4}}""")))
    }

    @Test fun redactedArgumentsStillRejectDifferentSecretOnRetryAndReturnSafeResult() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = StoredToolReceipts(kv)
        var effects = 0
        val arguments = buildJsonObject { put("password", "first-password"); put("text", "api_key=raw-key") }
        val session = tools(store) { _, _, actual ->
            effects++
            assertEquals(arguments, actual)
            buildJsonObject { put("token", "response-secret"); put("message", "Authorization: Bearer echoed-secret") }
        }
        val result = session.call("id", "effect", arguments)
        val raw = kv.keys("agent-tools-").joinToString { kv.read(it).orEmpty() }
        listOf("first-password", "raw-key", "response-secret", "echoed-secret").forEach { assertFalse(raw.contains(it)) }
        assertFalse(result.toString().contains("response-secret"))
        assertEquals(result, session.call("id", "effect", arguments))
        assertFailsWith<IllegalArgumentException> {
            session.call("id", "effect", JsonObject(arguments + ("password" to JsonPrimitive("other-password"))))
        }
        assertEquals(1, effects)
    }

    @Test fun credentialRedactionPreservesQuestionnaireCapabilityFlags() = runTest {
        val arguments = Json.parseToJsonElement("""{"questions":[{"id":"q","title":"Answer?","kind":"TEXT","secret":true}]}""").jsonObject
        val receipt = ToolReceipt("p/s/request/question", "questionnaire", arguments).forPersistence()
        assertEquals(JsonPrimitive(true), receipt.arguments["questions"]!!.jsonArray.single().jsonObject["secret"])
        validateToolArguments(ToolCatalog.get("questionnaire").schema, receipt.arguments)
    }

    @Test fun knownCredentialsAreRemovedFromResultsEventsAndErrorMessages() = runTest {
        val store = MemoryToolReceiptStore()
        val secret = "opaque-configured-credential"
        val events = mutableListOf<ToolEvent>()
        val session = tools(store, secrets = { setOf(secret) }) { _, _, _ -> error("Failure $secret") }
        session.events.observe { events += it }
        val thrown = assertFailsWith<IllegalStateException> { session.call("id", "effect", buildJsonObject { put("text", secret) }) }
        assertFalse(thrown.message.orEmpty().contains(secret))
        assertTrue(events.none { (it.summary + it.result).contains(secret) })
        assertFalse(store.forRequest("p/s/request").single().toString().contains(secret))
    }

    @Test fun successfulReadCannotBeReusedAsMutationAndReadHistorySurvivesRestart() = runTest {
        val kv = InMemoryKeyValueStore()
        val read = definition.copy(id = "read", mutating = false)
        var effects = 0
        val first = tools(StoredToolReceipts(kv), listOf(read, definition)) { _, _, _ -> effects++; JsonPrimitive("read result") }
        first.call("id", "read", empty)
        val restarted = tools(StoredToolReceipts(kv), listOf(read, definition)) { _, _, _ -> effects++; JsonNull }
        assertEquals(JsonPrimitive("read result"), restarted.call("id", "read", empty))
        assertFailsWith<IllegalArgumentException> { restarted.call("id", "effect", empty) }
        assertEquals(1, effects)
        assertEquals("read", StoredToolReceipts(kv).forRequest("p/s/request").single().toolId)
    }

    @Test fun intentPersistenceFailurePreventsAnyEffect() = runTest {
        val backing = InMemoryKeyValueStore()
        val broken = object : KeyValueStore by backing {
            override fun write(key: String, value: String) { error("Injected intent write failure") }
        }
        var effects = 0
        val session = tools(StoredToolReceipts(broken)) { _, _, _ -> effects++; JsonNull }
        assertFailsWith<IllegalStateException> { session.call("id", "effect", empty) }
        assertEquals(0, effects)
    }

    @Test fun rejectedInvocationPersistsSanitizedArgumentsAndCannotReuseItsIdentity() = runTest {
        val store = MemoryToolReceiptStore()
        var permitted = false
        var effects = 0
        val session = tools(store, check = { require(permitted) { "Permission denied" } }) { _, _, _ -> effects++; JsonNull }
        val arguments = buildJsonObject { put("password", "rejected-secret") }
        assertFailsWith<IllegalArgumentException> { session.call("rejected", "effect", arguments) }
        val rejected = store.forRequest("p/s/request").single()
        assertEquals(ToolPhase.FAILED, rejected.phase)
        assertFalse(rejected.toString().contains("rejected-secret"))
        assertEquals(toolArgumentsFingerprint(arguments), rejected.argumentFingerprint)
        permitted = true
        assertFailsWith<IllegalArgumentException> { session.call("rejected", "effect", empty) }
        assertEquals(0, effects)
        assertEquals(rejected, store.get(rejected.id))
    }

    @Test fun lostCompletionAcknowledgementReturnsUnknownThenReconcilesWithoutRepeating() = runTest {
        val backing = MemoryToolReceiptStore()
        var fail = true
        val broken = object : ToolReceiptStore by backing {
            override suspend fun save(receipt: ToolReceipt) {
                if (receipt.phase == ToolPhase.SUCCEEDED && fail) { fail = false; error("Injected acknowledgement loss") }
                backing.save(receipt)
            }
        }
        var effects = 0
        val externalResults = mutableMapOf<String, JsonElement>()
        val session = tools(broken) { _, operation, _ ->
            effects++
            JsonPrimitive("committed").also { externalResults[operation] = it }
        }
        val phases = mutableListOf<ToolPhase>()
        session.events.observe { phases += it.phase }
        assertFailsWith<IllegalStateException> { session.call("id", "effect", empty) }
        assertEquals(ToolPhase.UNKNOWN, phases.last())
        assertEquals(ToolPhase.UNKNOWN, backing.forRequest("p/s/request").single().phase)
        val uncertain = tools(backing) { _, _, _ -> effects++; JsonNull }
        assertFailsWith<IllegalStateException> { uncertain.call("id", "effect", empty) }
        val recovered = tools(backing, reconcile = { _, receipt -> externalResults[receipt.operationId] }) { _, _, _ -> effects++; JsonNull }
        assertEquals(JsonPrimitive("committed"), recovered.call("id", "effect", empty))
        assertEquals(1, effects)
    }

    @Test fun committedReceiptWhoseWriteThrowsIsNotDowngradedOrRepeated() = runTest {
        val backing = MemoryToolReceiptStore()
        val ambiguous = object : ToolReceiptStore by backing {
            override suspend fun save(receipt: ToolReceipt) {
                backing.save(receipt)
                if (receipt.phase == ToolPhase.SUCCEEDED) error("Injected post-write failure")
            }
        }
        var effects = 0
        val session = tools(ambiguous) { _, _, _ -> effects++; JsonPrimitive("done") }
        assertFailsWith<IllegalStateException> { session.call("id", "effect", empty) }
        assertEquals(ToolPhase.SUCCEEDED, backing.forRequest("p/s/request").single().phase)
        assertEquals(JsonPrimitive("done"), session.call("id", "effect", empty))
        assertEquals(1, effects)
    }

    @Test fun staleGenerationResultCannotPublishSuccess() = runTest {
        val store = MemoryToolReceiptStore()
        var generation = 4L
        val session = tools(store, ctx = context.copy(runtimeGeneration = generation), check = {
            require(it.runtimeGeneration == generation) { "Generation revoked" }
        }) { _, _, _ -> generation++; JsonPrimitive("late") }
        val events = mutableListOf<ToolEvent>()
        session.events.observe { events += it }
        assertFailsWith<IllegalArgumentException> { session.call("id", "effect", empty) }
        assertTrue(events.none { it.phase == ToolPhase.SUCCEEDED })
        assertTrue(session.calls.value.isEmpty())
        assertEquals(ToolPhase.UNKNOWN, store.forRequest("p/s/request").single().phase)
    }

    @Test fun hostDefaultsFreezeGenerationOncePerToolSession() = runTest {
        var generation = 1L
        var resolutions = 0
        val host = ToolHost(MemoryToolReceiptStore())
        host.contextDefaults = { resolutions++; it.copy(runtimeGeneration = generation, organismId = "organism") }
        host.checkScope = { require(it.runtimeGeneration == generation) { "Generation revoked" } }
        host.receiver = { _, _, _, _ -> JsonPrimitive("current") }
        val old = host.session(context)
        generation++
        assertFailsWith<IllegalArgumentException> { old.call("stale", "context.get", empty) }
        assertEquals(1, resolutions)
        assertEquals(1L, old.context.runtimeGeneration)
        assertEquals(JsonPrimitive("current"), host.session(context).call("current", "context.get", empty))
        assertEquals(2, resolutions)
    }

    @Test fun newCallIdCannotBypassUncertainMutationButReadsRemainAvailable() = runTest {
        val store = MemoryToolReceiptStore()
        var effects = 0
        val session = tools(store) { _, _, _ -> effects++; error("External outcome lost") }
        assertFailsWith<IllegalStateException> { session.call("first", "effect", empty) }
        assertFailsWith<IllegalStateException> { session.call("new-id", "effect", empty) }
        assertEquals(1, effects)
        val reader = tools(store, listOf(definition.copy(mutating = false, id = "read"))) { _, _, _ -> JsonPrimitive("diagnostics") }
        assertEquals(JsonPrimitive("diagnostics"), reader.call("inspect", "read", empty))
    }

    @Test fun explicitPreEffectRejectionDoesNotQuarantineLaterCorrectedCommand() = runTest {
        val store = MemoryToolReceiptStore()
        var effects = 0
        val session = tools(store) { _, _, arguments ->
            requireTool(arguments["accepted"] == JsonPrimitive(true)) { "Choose a valid recipient" }
            effects++
            JsonPrimitive("done")
        }
        assertFailsWith<ToolArgumentRejection> { session.call("rejected", "effect", empty) }
        assertEquals(ToolPhase.FAILED, store.get("p/s/request/rejected")!!.phase)
        assertEquals(JsonPrimitive("done"), session.call("corrected", "effect", buildJsonObject { put("accepted", true) }))
        assertEquals(1, effects)
    }

    @Test fun uncertainEffectsNotifyTheApplicationAndKeepImmunityChannelAvailable() = runTest {
        val store = MemoryToolReceiptStore()
        val reports = mutableListOf<ToolReceipt>()
        var effects = 0
        val host = ToolHost(store).also { it.unknownOutcome = { _, receipt -> reports += receipt } }
        val session = host.session(context, mapOf(
            "session.send" to { _, _, _ -> effects++; error("Outcome lost") },
            "immunity.signal" to { _, _, _ -> JsonPrimitive("recorded") },
        ))
        val send = buildJsonObject {
            put("sessionId", "parent")
            putJsonObject("context") { put("text", "context") }
        }
        assertFailsWith<IllegalStateException> { session.call("send", "session.send", send) }
        assertEquals(1, effects)
        assertEquals(ToolPhase.UNKNOWN, reports.single().phase)
        val signal = buildJsonObject { put("sessionId", "s"); put("diagnostic", "Uncertain send") }
        assertEquals(JsonPrimitive("recorded"), session.call("signal", "immunity.signal", signal))
    }

    @Test fun receiptStoreRejectsIdentityChangesAndSuccessRegression() = runTest {
        val store = StoredToolReceipts(InMemoryKeyValueStore())
        val receipt = ToolReceipt("p/s/request/id", "effect", empty, operationId = "stable")
        assertNull(store.claim(receipt))
        assertFailsWith<IllegalArgumentException> { store.save(receipt.copy(arguments = buildJsonObject { put("changed", true) })) }
        assertFailsWith<IllegalArgumentException> { store.save(receipt.copy(operationId = "changed")) }
        val succeeded = receipt.copy(phase = ToolPhase.SUCCEEDED, result = JsonPrimitive("done"))
        store.save(succeeded)
        assertFailsWith<IllegalArgumentException> { store.save(receipt) }
        assertEquals(ToolPhase.SUCCEEDED, store.get(receipt.id)!!.phase)
    }

    @Test fun oldReceiptsMigrateWithoutLosingIdentityOrPersistingRawCredentials() = runTest {
        val kv = InMemoryKeyValueStore()
        val legacy = ToolReceipt("p/s/request/legacy", "effect", buildJsonObject { put("password", "legacy-secret") },
            ToolPhase.SUCCEEDED, JsonPrimitive("api_key=old-secret"), "old-operation")
        val key = "agent-tools-" + "p/s/request".hashCode().toUInt().toString(16)
        kv.write(key, Json.encodeToString(listOf(legacy)))
        val store = StoredToolReceipts(kv)
        var effects = 0
        val session = tools(store) { _, _, _ -> effects++; JsonNull }
        session.call("legacy", "effect", legacy.arguments)
        assertEquals(0, effects)
        val migrated = store.get(legacy.id)!!
        assertEquals("old-operation", migrated.operationId)
        assertEquals(toolArgumentsFingerprint(legacy.arguments), migrated.argumentFingerprint)
        assertFalse(kv.read(key)!!.contains("legacy-secret"))
        assertFalse(kv.read(key)!!.contains("old-secret"))
    }

    @Test fun concurrentStoresClaimOneIntent() = runTest {
        val kv = InMemoryKeyValueStore()
        val first = StoredToolReceipts(kv)
        val second = StoredToolReceipts(kv)
        val receipt = ToolReceipt("p/s/request/id", "effect", empty, operationId = "operation")
        val claimed = awaitAll(async(Dispatchers.Default) { first.claim(receipt) }, async(Dispatchers.Default) { second.claim(receipt) })
        assertEquals(1, claimed.count { it == null })
        assertEquals(1, first.forRequest("p/s/request").size)
    }

    @Test fun largeResultIsRetainedAndNativeArgumentGapsAreExplicit() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = StoredToolReceipts(kv)
        val full = "x".repeat(80_000)
        val session = tools(store) { _, _, _ -> JsonPrimitive(full) }
        val events = mutableListOf<ToolEvent>()
        session.events.observe { events += it }
        session.call("large", "effect", empty)
        assertContains(events.last().result, "полный результат сохранён: p/s/request/large")
        assertEquals(full, store.get("p/s/request/large")!!.result.jsonPrimitive.content)
        flow {
            emit(CodingEvent.ToolStarted("read", "source.kt", "native"))
            emit(CodingEvent.ToolFinished("read", false, "native", "runtime preview", title = "File source.kt"))
        }.withTools(session).toList()
        val native = store.forRequest("p/s/request").single { it.native }
        assertFalse(native.argumentsComplete)
        assertFalse(native.resultComplete)
        assertEquals("source.kt", native.summary)
        assertEquals("File source.kt", native.title)
        assertEquals(ToolPhase.SUCCEEDED, native.phase)
    }

    @Test fun missingNativeCompletionIsUnknownInDurableHistoryAndTimeline() = runTest {
        val store = StoredToolReceipts(InMemoryKeyValueStore())
        val session = tools(store) { _, _, _ -> JsonNull }
        val timeline = flow {
            emit(CodingEvent.ToolStarted("exec_command", "build", "unfinished"))
            emit(CodingEvent.ToolProgress("exec_command", "unfinished", "started"))
            emit(CodingEvent.Finished)
        }.withTools(session).toList()
        assertEquals(ToolPhase.UNKNOWN, timeline.filterIsInstance<CodingEvent.ToolFinished>().single().phase)
        assertEquals(CodingEvent.Finished, timeline.last())
        val receipt = store.forRequest("p/s/request").single()
        assertEquals(ToolPhase.UNKNOWN, receipt.phase)
        assertContains(receipt.result.jsonPrimitive.content, "без подтверждённого результата")
    }

    @Test fun nativeEventsReachRecorderHistoryOnlyAfterCredentialMasking() = runTest {
        val configured = "opaque-configured-credential"
        val store = MemoryToolReceiptStore()
        val session = tools(store, secrets = { setOf(configured) }) { _, _, _ -> JsonNull }
        val emitted = flow {
            emit(CodingEvent.ToolStarted("read", "Authorization: Bearer native-secret $configured", "native", title = "Read $configured"))
            emit(CodingEvent.ToolProgress("read", "native", "Partial $configured"))
            emit(CodingEvent.ToolFinished("read", false, "native", "Result $configured", title = "Finished $configured"))
            emit(CodingEvent.Finished)
        }.withTools(session).toList()
        val recorder = CodingRunRecorder()
        emitted.forEach { recorder.apply(it) }
        val history = recorder.message("message", 1)
        val persisted = store.forRequest("p/s/request").single()
        for (secret in listOf(configured, "native-secret")) {
            assertFalse(emitted.toString().contains(secret))
            assertFalse(history.toString().contains(secret))
            assertFalse(persisted.toString().contains(secret))
        }
        assertTrue(history.steps.isNotEmpty())
        assertEquals(ToolPhase.SUCCEEDED, persisted.phase)
    }
}
