package io.aequicor.magicpaper.domain

/**
 * Where the resolution of a merge conflict stands.
 *
 * On disk it is `mergePhase: AttemptPhase?` — the work attempt's own phase enum, reused, with
 * `null` carrying a sixth meaning of its own. Two loops, the stage merge and the delivery
 * conflict, each spelled the same three questions against that field, and four more places in
 * the model spelled a fourth. This type is the only reader and the only producer, so the
 * combinations that never occur stay impossible.
 *
 * The stored form does not change: the same six values are written, under their own names.
 */
sealed interface MergeProgress {
    /** Nothing recorded: no conflict has been resolved on this attempt. */
    data object Untouched : MergeProgress

    /** A resolver is admitted and owes a turn. */
    data object Admitted : MergeProgress

    /** The resolver is running — or stopped mid-run, which reads the same and runs again. */
    data object Running : MergeProgress

    /** The resolver reported. Only a verdict moves the conflict on from here. */
    data object AwaitingVerdict : MergeProgress

    /** The verdict accepted the resolution. */
    data object Settled : MergeProgress

    /** The verdict rejected it; another resolver may be admitted. */
    data object Rejected : MergeProgress
}

/**
 * Reads the stored phase. A value no producer writes any more reads as
 * [MergeProgress.Running], because that is what it already behaves as, and nothing writes it
 * back: an unfinished resolution runs its resolver again.
 */
val StageAttempt.mergeProgress: MergeProgress
    get() = when (mergePhase) {
        null -> MergeProgress.Untouched
        AttemptPhase.PREPARED -> MergeProgress.Admitted
        AttemptPhase.VERIFYING -> MergeProgress.AwaitingVerdict
        AttemptPhase.COMPLETE -> MergeProgress.Settled
        AttemptPhase.FAILED -> MergeProgress.Rejected
        AttemptPhase.EXECUTING, AttemptPhase.INTEGRATING -> MergeProgress.Running
    }

/** Records where the resolution now stands. */
fun StageAttempt.merging(progress: MergeProgress): StageAttempt = copy(
    mergePhase = when (progress) {
        MergeProgress.Untouched -> null
        MergeProgress.Admitted -> AttemptPhase.PREPARED
        MergeProgress.Running -> AttemptPhase.EXECUTING
        MergeProgress.AwaitingVerdict -> AttemptPhase.VERIFYING
        MergeProgress.Settled -> AttemptPhase.COMPLETE
        MergeProgress.Rejected -> AttemptPhase.FAILED
    },
)

/** True when a fresh resolver must be admitted: none ever was, or the last one was rejected. */
val MergeProgress.needsResolver: Boolean
    get() = this == MergeProgress.Untouched || this == MergeProgress.Rejected

/**
 * True while the resolver still owes its turn.
 *
 * Only the resolver's own report ends this — not a verdict, not a settled conflict. A merge
 * that is settled yet still fails to integrate admits a resolver again rather than asking a
 * verifier to re-read a report nobody produced this time round.
 */
val MergeProgress.needsTurn: Boolean get() = this != MergeProgress.AwaitingVerdict

/** True while a conflict is being resolved: something is recorded, and it is not accepted. */
val MergeProgress.unresolved: Boolean
    get() = this != MergeProgress.Untouched && this != MergeProgress.Settled

/** True once any resolution is recorded — from then on its assignment governs the run. */
val MergeProgress.started: Boolean get() = this != MergeProgress.Untouched
