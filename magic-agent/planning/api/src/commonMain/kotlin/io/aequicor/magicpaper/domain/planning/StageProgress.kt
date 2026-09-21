package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

/** Coalesced native output; it carries no phase, acceptance, retry or execution authority. */
@Serializable data class StageProgress(
    val engineSessionId: String, val mergeEngineSessionId: String, val report: String, val mergeReport: String,
    val steps: List<CodingStep>, val pendingTool: String, val pendingToolExternal: Boolean, val activity: String,
) {
    fun applyTo(attempt: StageAttempt) = attempt.copy(engineSessionId = engineSessionId, mergeEngineSessionId = mergeEngineSessionId,
        report = report, mergeReport = mergeReport, steps = steps, pendingTool = pendingTool,
        pendingToolExternal = pendingToolExternal, activity = activity)
    companion object {
        fun from(attempt: StageAttempt) = StageProgress(attempt.engineSessionId, attempt.mergeEngineSessionId, attempt.report,
            attempt.mergeReport, attempt.steps, attempt.pendingTool, attempt.pendingToolExternal, attempt.activity)
    }
}
