package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AgentToolsTest {
    private val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.ORCHESTRATOR, CodingInteractionMode.PLANNING, "plan", "run")
    private val empty = JsonObject(emptyMap())
    private fun session(receipts: ToolReceiptStore = MemoryToolReceiptStore(), ctx: ToolExecutionContext = context,
        check: suspend (ToolExecutionContext) -> Unit = {}, action: suspend (ToolExecutionContext, String, JsonObject) -> JsonElement): ToolSession {
        val registry = ToolRegistry(listOf(JsonToolCommand(ToolCatalog.get("stage.send"), action)))
        return ToolSession(ctx, registry, ToolExecutor(registry, receipts, check))
    }
    private val args = buildJsonObject { put("stageId", "one"); put("message", "Do it") }

    @Test fun rolesModesAndBoundPlansDetermineCatalog() {
        val host = ToolHost(MemoryToolReceiptStore())
        fun available(ctx: ToolExecutionContext) = host.session(ctx).definitions.map { it.id }.toSet()
        val planner = context.copy(role = ToolRole.PLANNER)
        assertTrue("plan.propose" in available(planner))
        assertFalse("stage.send" in available(planner))
        assertFalse("plan.propose" in available(context))
        assertTrue("session.manage" in available(context))
        assertFalse("web.search" in available(context))
        assertTrue("stage.handoff" in available(context.copy(role = ToolRole.WORKER, mode = CodingInteractionMode.CODE)))
        assertFalse("stage.handoff" in available(context.copy(role = ToolRole.CHAT, planId = null)))
        for (role in ToolRole.entries) {
            assertFalse(ToolCatalog.get("file.write").allowed(context.copy(role = role, mode = CodingInteractionMode.PLANNING)))
            assertFalse(ToolCatalog.get("computer").allowed(context.copy(role = role, mode = CodingInteractionMode.RESEARCH)))
        }
        assertTrue(ToolCatalog.get("file.write").allowed(context.copy(role = ToolRole.WORKER, mode = CodingInteractionMode.CODE)))
        assertTrue(ToolCatalog.get("research_check").allowed(context.copy(role = ToolRole.CHAT, mode = CodingInteractionMode.RESEARCH)))
    }

    @Test fun applicationReceiversUseTheTrustedContextAndUsageOwnerAcrossTheBridge() = runTest {
        val ctx = context.copy(parentSessionId = "parent")
        val tools = session(ctx = ctx) { actual, _, _ ->
            assertEquals(ctx, actual)
            assertEquals(ctx, currentCoroutineContext()[ToolSession]!!.context)
            assertEquals(UsageScope("coding:s", "coding:parent", "p", "plan"), currentCoroutineContext()[UsageOwner]!!.scope)
            JsonPrimitive("ok")
        }
        withContext(UsageOwner(UsageScope("wrong"))) { tools.call("scope", "stage.send", args) }
    }

    @Test fun invalidArgumentsAndForeignScopeCannotInvokeReceiver() = runTest {
        var executed = 0
        val tools = session { _, _, _ -> executed++; JsonPrimitive("ok") }
        val events = mutableListOf<ToolEvent>()
        tools.events.observe { events += it }
        for (bad in listOf(empty, JsonObject(args + ("ownerSessionId" to JsonPrimitive("other"))), JsonObject(args + ("stageId" to JsonPrimitive(12)))))
            assertFailsWith<IllegalArgumentException> { tools.call("bad-${events.size}", "stage.send", bad) }
        assertFailsWith<IllegalArgumentException> { tools.call("unknown", "does_not_exist", empty) }
        val denied = session(ctx = context.copy(role = ToolRole.PLANNER)) { _, _, _ -> executed++; JsonNull }
        assertFailsWith<IllegalArgumentException> { denied.call("denied", "stage.send", args) }
        val foreign = session(check = { require(it.projectId == "other") }) { _, _, _ -> executed++; JsonNull }
        assertFailsWith<IllegalArgumentException> { foreign.call("foreign", "stage.send", args) }
        assertEquals(0, executed)
        assertEquals(4, events.count { it.phase == ToolPhase.FAILED })
    }

    @Test fun concurrentDuplicateAndRestartReturnReceiptWithoutRepeatingSideEffect() = runTest {
        val receipts = StoredToolReceipts(InMemoryKeyValueStore())
        var executed = 0
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val tools = session(receipts) { _, operation, _ -> assertFalse(operation.contains('/')); executed++; started.complete(Unit); gate.await(); JsonPrimitive("delivery") }
        val first = async { tools.call("call", "stage.send", args) }
        started.await()
        val repeated = async { tools.call("call", "magicpaper_stage_send", args) }
        runCurrent()
        assertEquals(1, executed)
        gate.complete(Unit)
        assertEquals(first.await(), repeated.await())
        val restored = session(receipts) { _, _, _ -> executed++; JsonPrimitive("wrong") }
        assertEquals(JsonPrimitive("delivery"), restored.call("call", "stage.send", args))
        assertEquals(1, executed)
        assertFailsWith<IllegalArgumentException> { restored.call("call", "stage.send", JsonObject(args + ("message" to JsonPrimitive("Different")))) }
    }

    @Test fun interruptedMutationIsNeverRepeatedAndCancellationIsObservable() = runTest {
        val receipts = MemoryToolReceiptStore()
        var effects = 0
        val tools = session(receipts) { _, _, _ -> effects++; awaitCancellation() }
        val phases = mutableListOf<ToolPhase>()
        tools.events.observe { phases += it.phase }
        val job = launch { tools.call("interrupted", "stage.send", args) }
        runCurrent(); job.cancelAndJoin()
        assertEquals(listOf(ToolPhase.STARTED, ToolPhase.CANCELLED), phases)
        val restored = session(receipts) { _, _, _ -> effects++; JsonNull }
        assertFailsWith<IllegalStateException> { restored.call("interrupted", "stage.send", args) }
        assertEquals(1, effects)
    }

    @Test fun nativeAndApplicationEventsHaveOneOrderedTimelineAndStableIdentity() = runTest {
        val tools = session { _, _, _ -> JsonPrimitive("queued") }
        val events = flow {
            emit(CodingEvent.ToolStarted("read", "file.kt", "native"))
            emit(CodingEvent.ToolFinished("read", false, "native", "content"))
            emit(CodingEvent.ToolStarted("magicpaper_stage_send", "raw", "wire"))
            currentCoroutineContext()[ToolSession]!!.call("wire", "stage.send", args)
            emit(CodingEvent.ToolFinished("magicpaper_stage_send", false, "wire"))
            emit(CodingEvent.Finished)
        }.withTools(tools).toList()
        assertEquals(2, events.filterIsInstance<CodingEvent.ToolStarted>().size)
        assertEquals(2, events.filterIsInstance<CodingEvent.ToolFinished>().size)
        assertEquals(CodingEvent.Finished, events.last())
        val recorder = CodingRunRecorder()
        events.forEach(recorder::apply)
        val saved = recorder.message("reply", 0)
        assertEquals(listOf("file.read", "stage.send"), saved.steps.map { it.tool })
        assertTrue(saved.steps.all { !it.running && it.ok })
        assertEquals(ToolCategory.READ, saved.steps.first().toolCategory)
        assertEquals(ToolPhase.SUCCEEDED, saved.steps.last().toolPhase)
        assertEquals(saved.steps.map { it.id }, recorder.message("reply", 0).steps.map { it.id })
    }

    @Test fun parallelCallsAndRepeatedEventsKeepOneCardPerRequestAndCall() = runTest {
        val tools = session { _, _, _ -> JsonPrimitive("queued") }
        val recorder = CodingRunRecorder()
        tools.events.observe { recorder.apply(it.codingEvent()) }
        awaitAll(async { tools.call("first", "stage.send", args) }, async { tools.call("second", "stage.send", args) })
        val initial = recorder.message("reply", 0).steps
        tools.call("first", "stage.send", args)
        val repeated = recorder.message("reply", 0).steps
        assertEquals(2, repeated.size)
        assertEquals(initial.map { it.id }, repeated.map { it.id })
        assertTrue(repeated.all { it.ok && !it.running })

        suspend fun native(ctx: ToolExecutionContext): List<CodingEvent> = flow {
            emit(CodingEvent.ToolStarted("engine_custom", "cat source.kt", "1", isExec = true))
            emit(CodingEvent.ToolProgress("engine_custom", "1", "source"))
            emit(CodingEvent.ToolFinished("engine_custom", false, "1", "source"))
        }.withTools(session(ctx = ctx) { _, _, _ -> JsonNull }).toList()
        val first = native(context)
        val second = native(context.copy(requestId = "another"))
        assertNotEquals(first.filterIsInstance<CodingEvent.ToolStarted>().single().callId,
            second.filterIsInstance<CodingEvent.ToolStarted>().single().callId)
        val nativeRecorder = CodingRunRecorder()
        (first + second + second.last()).forEach(nativeRecorder::apply)
        assertEquals(2, nativeRecorder.message("native", 0).steps.size)
        assertTrue(nativeRecorder.message("native", 0).steps.all { it.kind == CodingStepKind.EXEC && it.ok && !it.running })
    }

    @Test fun questionnaireWaitsAndCannotGrantPermissions() = runTest {
        val host = ToolHost(MemoryToolReceiptStore())
        val tools = host.session(context)
        val phases = mutableListOf<ToolPhase>()
        tools.events.observe { phases += it.phase }
        val job = async { tools.call("ask", "questionnaire", Json.parseToJsonElement("""{"questions":[{"id":"q","title":"Format?","kind":"TEXT","secret":true,"canSkip":false}]}""").jsonObject) }
        runCurrent()
        val request = host.questions.requests.value.single()
        assertFalse(request.questions.single().secret)
        assertTrue(request.questions.single().canSkip)
        assertEquals(InteractionKind.RUNTIME, request.kind)
        assertFalse(job.isCompleted)
        assertEquals(listOf(ToolPhase.STARTED, ToolPhase.WAITING), phases)
        host.questions.respond(request.id, listOf(PlanningAnswer("q", text = "pdf")))
        assertContains(job.await().toString(), "pdf")
        assertEquals(ToolPhase.SUCCEEDED, phases.last())
    }

    @Test fun generatedSchemasAcceptNullableDefaultsAndRejectUnknownNestedFields() {
        val schema = ToolCatalog.get("plan.propose").schema
        validateToolArguments(schema, Json.parseToJsonElement("""{"reply":"Ready","isolatedWorkspace":null,"tree":[],"milestones":[]} """))
        assertFailsWith<IllegalArgumentException> { validateToolArguments(schema,
            Json.parseToJsonElement("""{"reply":"Ready","milestones":[{"id":"x","title":"x","forged":true}]}""")) }
    }

    @Test fun observationsRedactCredentialsWithoutChangingCommandArguments() = runTest {
        var actual = ""
        val tools = session { _, _, a -> actual = a["message"]!!.jsonPrimitive.content; JsonPrimitive("Authorization: Bearer secret-value") }
        val events = mutableListOf<ToolEvent>(); tools.events.observe { events += it }
        tools.call("redact", "stage.send", JsonObject(args + ("message" to JsonPrimitive("api_key=secret-value"))))
        assertEquals("api_key=secret-value", actual)
        assertTrue(events.none { it.summary.contains("secret-value") || it.result.contains("secret-value") })
    }
}
