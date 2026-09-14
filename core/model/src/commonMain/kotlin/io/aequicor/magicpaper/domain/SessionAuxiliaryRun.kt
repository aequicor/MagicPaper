package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** An app-authenticated native alias borrows its owner's existing authority and resources. */
@Serializable data class SessionAuxiliaryRun(
    val id: String, val sessionId: String, val ownerSessionId: String, val generation: Long,
    val runId: String, val requestId: String, val mode: CodingInteractionMode,
    val observed: SessionObservedState = SessionObservedState.RUNNING,
    val usage: Map<String, Long> = emptyMap(), val startedAt: Long = 0, val endedAt: Long = 0,
) {
    val settled: Boolean get() = observed in setOf(SessionObservedState.COMPLETED, SessionObservedState.FAILED, SessionObservedState.STOPPED)
}
