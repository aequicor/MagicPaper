package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingWakeGuardTest {
    @Test fun applicationQuestionnaireSurvivesWakeUntilAnswered() = runTest {
        var offset = 0L
        val registry = ToolRegistry(emptyList())
        val tools = ToolSession(ToolExecutionContext("p", "s", "s", "r", ToolRole.CHAT, CodingInteractionMode.CODE),
            registry, ToolExecutor(registry, MemoryToolReceiptStore()))
        val received = mutableListOf<CodingEvent>()
        val job = launch(tools) {
            flow<CodingEvent> { awaitCancellation() }
                .withWakeGuard(clock = { testScheduler.currentTime + offset }).toList(received)
        }
        runCurrent()
        tools.events.publish(ToolEvent("p", "s", "r", "q", "questionnaire", ToolCategory.ACTION, ToolPhase.WAITING, ""))
        offset += 60_000
        advanceTimeBy(60_000)
        assertTrue(job.isActive)
        tools.events.publish(ToolEvent("p", "s", "r", "q", "questionnaire", ToolCategory.ACTION, ToolPhase.SUCCEEDED, ""))
        advanceTimeBy(2_000)
        offset += 60_000
        advanceTimeBy(32_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertIs<CodingEvent.Failed>(received.first())
    }

    @Test fun suspendedSilentStreamIsCleanedUpBeforeFailure() = runTest {
        var offset = 0L
        var cleaned = false
        var interrupted = false
        val received = mutableListOf<CodingEvent>()
        val job = launch {
            flow<CodingEvent> {
                try { awaitCancellation() } finally { assertTrue(interrupted); cleaned = true }
            }.withWakeGuard(clock = { testScheduler.currentTime + offset }, onStalled = { interrupted = true }).collect {
                assertTrue(cleaned)
                received += it
            }
        }
        runCurrent()
        offset += 60_000
        advanceTimeBy(32_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertIs<CodingEvent.Failed>(received.first())
        assertEquals(CodingEvent.Finished, received.last())
        assertEquals(2, received.size)
    }

    @Test fun eventsAfterWakeKeepStreamAlive() = runTest {
        var offset = 0L
        val events = Channel<CodingEvent>(Channel.UNLIMITED)
        val received = mutableListOf<CodingEvent>()
        val job = launch {
            events.receiveAsFlow().withWakeGuard(clock = { testScheduler.currentTime + offset }).toList(received)
        }
        runCurrent()
        offset += 60_000
        advanceTimeBy(2_000)
        events.send(CodingEvent.Notice("resumed"))
        runCurrent()
        advanceTimeBy(60_000)
        assertTrue(job.isActive)
        events.send(CodingEvent.Finished)
        events.close()
        job.join()
        assertEquals(2, received.size)
        assertFalse(received.any { it is CodingEvent.Failed })
    }

    @Test fun ordinarySilenceAndCancellationDoNotProduceFailure() = runTest {
        val received = mutableListOf<CodingEvent>()
        val job = launch {
            flow<CodingEvent> { awaitCancellation() }
                .withWakeGuard(clock = { testScheduler.currentTime }).toList(received)
        }
        advanceTimeBy(180_000)
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(received.isEmpty())
    }

    @Test fun pendingUserDecisionSurvivesWake() = runTest {
        var offset = 0L
        val received = mutableListOf<CodingEvent>()
        val job = launch {
            flow<CodingEvent> { awaitCancellation() }
                .withWakeGuard(clock = { testScheduler.currentTime + offset }, awaitingUser = { true })
                .toList(received)
        }
        runCurrent()
        offset += 60_000
        advanceTimeBy(60_000)
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(received.isEmpty())
    }
}
