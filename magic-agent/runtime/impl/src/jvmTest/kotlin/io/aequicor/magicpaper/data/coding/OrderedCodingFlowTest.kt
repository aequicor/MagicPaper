package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.NativeRunRecoveryRequired
import io.aequicor.magicpaper.domain.NativeRunRecoverySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedCodingFlowTest {
    @Test fun slowConsumerReceivesAllOutputBeforeExactOperationalFailure() = runTest {
        val failure = NativeRunRecoveryRequired(NativeRunRecoverySnapshot(emptyList(), true))
        val output: List<CodingEvent> = List(12) { CodingEvent.Notice("part-$it") }
        val received = mutableListOf<CodingEvent>()
        val actual = assertFailsWith<NativeRunRecoveryRequired> {
            flow<CodingEvent> { output.forEach { emit(it) }; throw failure }
                .flowOnPreservingOutput(StandardTestDispatcher(testScheduler, "producer"))
                .onEach { delay(100) }.toList(received)
        }
        assertSame(failure, actual)
        assertEquals(output, received)
    }

    @Test fun consumerCancellationStillStopsAndCleansUpProducer() = runTest {
        var cleaned = false
        val output = flow<CodingEvent> {
            try { emit(CodingEvent.Notice("first")); awaitCancellation() }
            finally { cleaned = true }
        }.flowOnPreservingOutput(StandardTestDispatcher(testScheduler, "producer"))
        assertEquals(listOf(CodingEvent.Notice("first")), output.take(1).toList())
        assertTrue(cleaned)
    }
}
