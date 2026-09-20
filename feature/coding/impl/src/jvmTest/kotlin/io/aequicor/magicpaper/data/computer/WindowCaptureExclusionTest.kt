package io.aequicor.magicpaper.data.computer

import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class WindowCaptureExclusionTest {
    @Test fun unavailableAffinityRestoresPartiallyPreparedWindowsBeforeFallback() {
        val events = mutableListOf<String>()
        val result = captureWithWindowExclusion(
            prepare = { events += "exclude first"; throw ExecutionException(WindowCaptureExclusionUnavailable("read_affinity", 87)) },
            restore = { events += "restore first" },
            capture = { fail("Unprepared capture must not run") },
            hiddenCapture = { events += "hidden capture"; "image" },
        )
        assertEquals("image", result)
        assertEquals(listOf("exclude first", "restore first", "hidden capture"), events)
    }

    @Test fun failedRestorationPreventsFallbackCapture() {
        val preparation = WindowCaptureExclusionUnavailable("set_affinity", 5)
        val cleanup = IllegalStateException("restore")
        val thrown = assertFailsWith<WindowCaptureExclusionUnavailable> {
            captureWithWindowExclusion(
                prepare = { throw preparation }, restore = { throw cleanup },
                capture = { fail("Capture must not run") }, hiddenCapture = { fail("Fallback must not run") },
            )
        }
        assertSame(preparation, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test fun captureFailureIsNotRetriedAndKeepsCleanupFailure() {
        val captureFailure = IllegalStateException("capture")
        val cleanup = IllegalStateException("restore")
        val thrown = assertFailsWith<IllegalStateException> {
            captureWithWindowExclusion(
                prepare = {}, restore = { throw cleanup }, capture = { throw captureFailure },
                hiddenCapture = { fail("Capture must never be repeated automatically") },
            )
        }
        assertSame(captureFailure, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test fun cancellationRestoresWindowsAndNeverStartsFallback() {
        val cancelled = CancellationException("stop")
        var restored = false
        val thrown = assertFailsWith<CancellationException> {
            captureWithWindowExclusion(
                prepare = { throw ExecutionException(cancelled) }, restore = { restored = true },
                capture = { fail("Cancelled capture") }, hiddenCapture = { fail("Cancelled fallback") },
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(restored)
    }

    @Test fun successfulCaptureRestoresBeforeReturning() {
        val events = mutableListOf<String>()
        assertEquals("image", captureWithWindowExclusion(
            prepare = { events += "prepare" }, restore = { events += "restore" },
            capture = { events += "capture"; "image" }, hiddenCapture = { fail("Unexpected fallback") },
        ))
        assertEquals(listOf("prepare", "capture", "restore"), events)
    }
}
