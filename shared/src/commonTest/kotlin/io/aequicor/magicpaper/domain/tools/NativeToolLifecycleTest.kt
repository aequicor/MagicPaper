package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.CodingRunRecorder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class NativeToolLifecycleTest {
    private val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.CHAT,
        CodingInteractionMode.CODE, runtimeGeneration = 3)
    private val identity = "p/s/request/native/exec"
    private val terminalPhases = listOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED)
    private fun started() = CodingEvent.ToolStarted("exec_command", "./gradlew :shared:check", "exec", isExec = true)
    private fun completed(phase: ToolPhase) = CodingEvent.ToolFinished("exec_command", phase != ToolPhase.SUCCEEDED,
        "exec", "native terminal evidence: $phase", phase)

    @Test fun lateEventsCannotReopenConfirmedNativeCommandOrChangeItsTimelineResult() = runTest {
        terminalPhases.forEach { terminal ->
            val store = MemoryToolReceiptStore()
            val uncertain = mutableListOf<ToolReceipt>()
            val host = ToolHost(store).also { it.unknownOutcome = { _, receipt -> uncertain += receipt } }
            val session = host.session(context)
            val timeline = flowOf(
                started(), completed(terminal), started(),
                CodingEvent.ToolProgress("exec_command", "exec", "late output"),
                CodingEvent.ToolProgress("exec_command", "exec", "late wait", ToolPhase.WAITING),
                completed(if (terminal == ToolPhase.SUCCEEDED) ToolPhase.FAILED else ToolPhase.SUCCEEDED),
                CodingEvent.Finished,
            ).withTools(session).toList()

            assertEquals(1, timeline.filterIsInstance<CodingEvent.ToolStarted>().size)
            assertTrue(timeline.none { it is CodingEvent.ToolProgress })
            assertEquals(terminal, timeline.filterIsInstance<CodingEvent.ToolFinished>().single().phase)
            val receipt = assertNotNull(store.get(identity))
            assertEquals(terminal, receipt.phase)
            assertEquals(JsonPrimitive(completed(terminal).resultPreview), receipt.result)
            assertTrue(session.pendingNativeEvents.isEmpty())
            assertTrue(uncertain.isEmpty())

            val recorder = CodingRunRecorder()
            timeline.forEach { recorder.apply(it) }
            val step = recorder.message("message", 1).steps.single { it.callId == identity }
            assertFalse(step.running)
            assertEquals(terminal, step.toolPhase)
            assertEquals(terminal == ToolPhase.SUCCEEDED, step.ok)
            assertEquals(completed(terminal).resultPreview, step.result)
        }
    }

    @Test fun restartedSessionDiscardsLateEventsAgainstPersistedNativeCompletion() = runTest {
        terminalPhases.forEach { terminal ->
            val storage = InMemoryKeyValueStore()
            val first = ToolHost(StoredToolReceipts(storage)).session(context)
            flowOf(started(), completed(terminal), CodingEvent.Finished).withTools(first).toList()
            val original = StoredToolReceipts(storage).get(identity)
            val restarted = ToolHost(StoredToolReceipts(storage)).session(context)

            val timeline = flowOf(started(), CodingEvent.ToolProgress("exec_command", "exec", "late output"),
                CodingEvent.Finished).withTools(restarted).toList()

            assertEquals(listOf(CodingEvent.Finished), timeline)
            assertEquals(original, StoredToolReceipts(storage).get(identity))
            assertTrue(restarted.pendingNativeEvents.isEmpty())
        }
    }

    @Test fun uncertainCommandNeedsNativeTerminalEvidenceAndAllowsHistoricalScopeRecovery() = runTest {
        terminalPhases.forEach { terminal ->
            val store = MemoryToolReceiptStore()
            var live = true
            var historicalChecks = 0
            val uncertain = mutableListOf<ToolReceipt>()
            val host = ToolHost(store).also {
                it.checkScope = { require(live) { "Execution revoked" } }
                it.checkReplayScope = { scope -> assertEquals(context, scope); historicalChecks++ }
                it.unknownOutcome = { _, receipt -> uncertain += receipt }
            }
            val session = host.session(context)
            val incomplete = flowOf(started(), CodingEvent.TextDelta("BUILD SUCCESSFUL"), CodingEvent.Finished)
                .withTools(session).toList()
            assertEquals(ToolPhase.UNKNOWN, incomplete.filterIsInstance<CodingEvent.ToolFinished>().single().phase)
            val original = assertNotNull(store.get(identity))
            assertEquals(ToolPhase.UNKNOWN, original.phase)
            live = false

            val late = flowOf(started(), CodingEvent.ToolProgress("exec_command", "exec", "still waiting"),
                CodingEvent.TextDelta("The command succeeded"), CodingEvent.Finished).withTools(session).toList()
            assertTrue(late.none { it is CodingEvent.ToolStarted || it is CodingEvent.ToolProgress || it is CodingEvent.ToolFinished })
            assertEquals(original, store.get(identity))
            assertEquals(1, uncertain.size)
            assertEquals(0, historicalChecks)

            val recovered = flowOf(completed(terminal), CodingEvent.Finished).withTools(session).toList()
            assertEquals(terminal, recovered.filterIsInstance<CodingEvent.ToolFinished>().single().phase)
            assertEquals(terminal, store.get(identity)?.phase)
            assertEquals(JsonPrimitive(completed(terminal).resultPreview), store.get(identity)?.result)
            assertEquals(1, historicalChecks)
            assertEquals(1, uncertain.size)
        }
    }

    @Test fun separatelyReconciledTerminalReceiptPreventsSyntheticUnknownAtStreamEnd() = runTest {
        terminalPhases.forEach { terminal ->
            val store = MemoryToolReceiptStore()
            val uncertain = mutableListOf<ToolReceipt>()
            val session = ToolHost(store).also { it.unknownOutcome = { _, receipt -> uncertain += receipt } }.session(context)
            val timeline = flow {
                emit(started())
                // Observe STARTED through the hub so reconciliation runs after its durable intent.
                emit(CodingEvent.Finished)
            }
            session.events.observe { event ->
                if (event.phase == ToolPhase.STARTED) {
                    val pending = assertNotNull(store.get(identity))
                    store.save(pending.copy(phase = terminal, result = JsonPrimitive("reconciled native evidence")))
                }
            }
            val events = timeline.withTools(session).toList()

            assertTrue(events.none { it is CodingEvent.ToolFinished && it.phase == ToolPhase.UNKNOWN })
            assertEquals(terminal, store.get(identity)?.phase)
            assertEquals(JsonPrimitive("reconciled native evidence"), store.get(identity)?.result)
            assertTrue(session.pendingNativeEvents.isEmpty())
            assertTrue(uncertain.isEmpty())
        }
    }

    @Test fun oldGenerationCannotResolveNativeUncertainty() = runTest {
        val store = MemoryToolReceiptStore()
        val host = ToolHost(store)
        val first = host.session(context)
        flowOf(started(), CodingEvent.Finished).withTools(first).toList()
        val original = store.get(identity)
        val wrongGeneration = host.session(context.copy(runtimeGeneration = context.runtimeGeneration + 1))

        assertFailsWith<IllegalArgumentException> {
            flowOf(completed(ToolPhase.SUCCEEDED), CodingEvent.Finished).withTools(wrongGeneration).toList()
        }
        assertEquals(original, store.get(identity))

        host.checkReplayScope = { error("Historical generation revoked") }
        val revoked = host.session(context)
        assertFailsWith<IllegalStateException> {
            flowOf(completed(ToolPhase.SUCCEEDED), CodingEvent.Finished).withTools(revoked).toList()
        }
        assertEquals(original, store.get(identity))
    }

    @Test fun durableNativeFailuresAndCancellationCannotBeReplacedWhileUnknownCanBeResolved() = runTest {
        listOf<ToolReceiptStore>(MemoryToolReceiptStore(), StoredToolReceipts(InMemoryKeyValueStore())).forEach { store ->
            listOf(ToolPhase.FAILED, ToolPhase.CANCELLED).forEach { terminal ->
                val receipt = ToolReceipt("p/s/request/native/$terminal", "shell.exec", JsonObject(emptyMap()),
                    phase = ToolPhase.UNKNOWN, runtimeGeneration = 3, native = true, argumentsComplete = false,
                    resultComplete = false)
                store.save(receipt)
                store.save(receipt.copy(phase = terminal, result = JsonPrimitive("confirmed result"), error = "confirmed error"))
                val confirmed = assertNotNull(store.get(receipt.id))
                (listOf(ToolPhase.STARTED, ToolPhase.PROGRESS, ToolPhase.WAITING, ToolPhase.UNKNOWN) + terminalPhases)
                    .filter { it != terminal }.forEach { replacement ->
                        assertFailsWith<IllegalArgumentException> { store.save(confirmed.copy(phase = replacement)) }
                    }
                assertFailsWith<IllegalArgumentException> { store.save(confirmed.copy(result = JsonPrimitive("replacement"))) }
                assertFailsWith<IllegalArgumentException> { store.save(confirmed.copy(error = "replacement")) }
                assertFailsWith<IllegalArgumentException> { store.save(confirmed.copy(native = false)) }
                assertEquals(confirmed, store.get(receipt.id))
            }
        }
    }
}
