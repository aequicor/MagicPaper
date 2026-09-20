package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.LogLevel
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owner of tool-call diagnostics for both engines and both call kinds.
 *
 * Application calls are reported by [ToolExecutor.execute], which holds the real cause; engine-native
 * calls are reported by [ToolExecutor.recordNative], where only the phase reported by the engine is
 * known. Arguments, results and error texts never enter `INFO`/`DEBUG`/`ERROR`: they stay in the
 * receipt for the UI and reach diagnostics only as a redacted, size-bounded `TRACE` payload. The call
 * identity is a storage key, so it is logged as the opaque `entityId` that `AppLog` derives from it.
 */
internal object ToolCallDiagnostics {
    private const val COMPONENT = "coding.tool"

    fun log(context: ToolExecutionContext, event: ToolEvent, native: Boolean, durationMs: Long?,
            cause: Throwable?, secrets: Set<String>) {
        val fields = buildMap {
            put("projectId", event.projectId)
            put("sessionId", event.ownerSessionId)
            put("requestId", event.requestId)
            if (event.callId.isNotBlank()) put("entityId", event.callId)
            put("tool", event.toolId)
            put("category", event.category.name)
            put("kind", if (native) "native" else "application")
            put("mode", context.mode.name)
            put("phase", event.phase.name)
            durationMs?.let { put("durationMs", it.coerceAtLeast(0).toString()) }
            if (event.phase != ToolPhase.SUCCEEDED) put("failure", failureCode(event.phase, cause))
        }
        when (event.phase) {
            ToolPhase.STARTED -> AppLog.info(COMPONENT, "call.started", fields)
            ToolPhase.PROGRESS, ToolPhase.WAITING -> AppLog.debug(COMPONENT, "call.progress", fields)
            ToolPhase.SUCCEEDED -> AppLog.info(COMPONENT, "call.completed", fields)
            // Cancellation is control flow: an observable outcome, not an unexpected failure.
            ToolPhase.CANCELLED -> AppLog.info(COMPONENT, "call.cancelled", fields)
            ToolPhase.FAILED -> if (cause != null) AppLog.error(COMPONENT, "call.failed", cause, fields)
                else AppLog.error(COMPONENT, "call.failed", fields)
            ToolPhase.UNKNOWN -> AppLog.error(COMPONENT, "call.outcome_unknown", fields)
        }
        if (AppLog.isEnabled(LogLevel.TRACE)) AppLog.trace(COMPONENT, "call.payload", fields, secrets) {
            listOf(event.summary, event.result).filter { it.isNotBlank() }.joinToString("\n")
        }
    }

    /** Machine code for the log; the human-readable text stays with the receipt and the UI. */
    private fun failureCode(phase: ToolPhase, cause: Throwable?): String = when {
        cause is CancellationException -> "cancelled"
        phase == ToolPhase.UNKNOWN -> "outcome_uncertain"
        cause is ToolArgumentRejection -> "validation"
        cause is ToolStateRejection -> "state"
        cause is RejectedToolCall -> "rejected"
        cause is IllegalArgumentException -> "validation"
        cause is IllegalStateException -> "state"
        cause != null -> "runtime"
        phase == ToolPhase.FAILED -> "engine_reported"
        else -> "reported"
    }
}
