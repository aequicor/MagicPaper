package io.aequicor.magicpaper.logging

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Runs one step of a longer operation and writes how long it took: `phase.finished`, or `phase.failed` with `result`
 * `failed` or `cancelled` when it threw, so a slow start or load names the step that took the time. The failure itself
 * stays with the owner that handles it; this line only times it.
 */
inline fun <T> AppLog.phase(component: String, name: String, fields: Map<String, String> = emptyMap(), block: () -> T): T {
    val mark = TimeSource.Monotonic.markNow()
    val value = try { block() } catch (failure: Throwable) {
        info(component, "phase.failed", fields + mapOf("phase" to name, "elapsedMs" to mark.elapsedNow().inWholeMilliseconds.toString(),
            "result" to if (failure is CancellationException) "cancelled" else "failed"))
        throw failure
    }
    info(component, "phase.finished", fields + mapOf("phase" to name, "elapsedMs" to mark.elapsedNow().inWholeMilliseconds.toString()))
    return value
}
