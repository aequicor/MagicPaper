package io.aequicor.magicpaper.logging

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class AppLogPhaseTest {
    private fun last(event: String) = AppLog.history().last { it.component == "phase-test" && it.event == event }

    @Test fun aStepReportsItsDurationAndReturnsItsValue() {
        assertEquals(42, AppLog.phase("phase-test", "load", mapOf("count" to "3")) { 42 })
        val finished = last("phase.finished")
        assertEquals("load", finished.fields["phase"])
        assertEquals("3", finished.fields["count"])
        assertNotNull(finished.fields["elapsedMs"]?.toLongOrNull())
    }

    @Test fun aFailedOrCancelledStepIsTimedAndItsFailureStillPropagates() {
        val failure = IllegalStateException("broken")
        assertSame(failure, assertFailsWith<IllegalStateException> { AppLog.phase("phase-test", "failing") { throw failure } })
        assertEquals("failed", last("phase.failed").fields["result"])
        assertFailsWith<CancellationException> { AppLog.phase("phase-test", "cancelled") { throw CancellationException("stop") } }
        val cancelled = last("phase.failed")
        assertEquals("cancelled", cancelled.fields["result"])
        assertEquals("cancelled", cancelled.fields["phase"])
    }
}
