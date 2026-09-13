package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable enum class SessionCodingWorkspacePhase { PREPARING, READY, RUNNING, CAPTURING, CAPTURED, STOPPED, UNKNOWN }

/** An external-effect checkpoint in the session aggregate; never a claim of Git/DB atomicity. */
@Serializable data class SessionCodingWorkspace(
    val generation: Long,
    val runId: String,
    val attempt: StageAttempt,
    val phase: SessionCodingWorkspacePhase = SessionCodingWorkspacePhase.PREPARING,
    val workspace: PlanWorkspace? = null,
    val sourceSnapshot: String? = null,
    val resultSnapshot: String? = null,
    val error: String = "",
    val sourcePath: String = "",
)
