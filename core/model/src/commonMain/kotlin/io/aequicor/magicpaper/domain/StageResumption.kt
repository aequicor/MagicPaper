package io.aequicor.magicpaper.domain

/**
 * What picking up a saved attempt means, decided before any effect runs.
 *
 * The decision is a pure reading of the attempt and journal evidence: its executor cannot
 * reinterpret it. Order matters and is part of the contract — an unconfirmed external command
 * outranks everything, because resuming would repeat an effect whose result nobody knows.
 */
sealed interface StageResumption {
    /**
     * An external command finished without a confirmed result. The attempt must not resume:
     * only evidence from the engine or a person's confirmation can settle what happened.
     */
    data class UnknownOutcome(val tool: String) : StageResumption

    /** Execution was stopped. The recorded phase is a recovery checkpoint, not liveness. */
    data class Interrupted(val checkpoint: AttemptPhase) : StageResumption

    /** The attempt still owns its turn and may run. */
    data class Runnable(val phase: AttemptPhase) : StageResumption

    /** Execution is behind it; verification, merge or completion owns the attempt now. */
    data class Settled(val phase: AttemptPhase) : StageResumption

    companion object {
        /** The phases from which an attempt may still take a turn. */
        val RUNNABLE_PHASES = setOf(AttemptPhase.PREPARED, AttemptPhase.EXECUTING, AttemptPhase.FAILED)
    }
}

val StageAttempt.resumption: StageResumption
    get() = resumption(journalUnsettled = false)

/** Journal uncertainty survives a missing or rolled-back attempt checkpoint. */
fun StageAttempt.resumption(journalUnsettled: Boolean): StageResumption = when {
        journalUnsettled -> StageResumption.UnknownOutcome("незавершённая операция плана")
        pendingToolExternal && pendingTool.isNotBlank() -> StageResumption.UnknownOutcome(pendingTool)
        interrupted -> StageResumption.Interrupted(phase)
        phase in StageResumption.RUNNABLE_PHASES -> StageResumption.Runnable(phase)
        else -> StageResumption.Settled(phase)
    }

/** True while the attempt may take another turn, independent of why it was picked up. */
val StageAttempt.mayRun: Boolean get() = phase in StageResumption.RUNNABLE_PHASES
