package io.aequicor.magicpaper.data.coding

import kotlin.test.*

class AgentRunResourcesTest {
    @Test fun failedAcquisitionClosesEarlierResourcesInReverseOrderWithoutMaskingCause() {
        val closed = mutableListOf<String>()
        val acquisitionFailure = IllegalStateException("next bridge failed")
        val cleanupFailure = IllegalStateException("cleanup failed")
        val failure = assertFailsWith<IllegalStateException> {
            RunResourceScope().use { scope ->
                scope.own(AutoCloseable { closed += "first" })
                scope.own(AutoCloseable { closed += "second"; throw cleanupFailure })
                throw acquisitionFailure
            }
        }
        assertSame(acquisitionFailure, failure)
        assertSame(cleanupFailure, failure.suppressed.single())
        assertEquals(listOf("second", "first"), closed)
    }

    @Test fun cleanupAttemptsEveryResourceAndCannotReturnSuccessAfterFailure() {
        val scope = RunResourceScope()
        var lastClosed = false
        scope.own(AutoCloseable { lastClosed = true; error("first") })
        scope.own(AutoCloseable { error("second") })
        val failure = assertFailsWith<IllegalStateException> { scope.close() }
        assertEquals("second", failure.message)
        assertEquals("first", failure.suppressed.single().message)
        assertTrue(lastClosed)
        scope.close() // already released; failed resources are not retried automatically
    }
}
