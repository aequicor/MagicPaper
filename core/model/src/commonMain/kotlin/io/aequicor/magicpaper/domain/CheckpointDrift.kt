package io.aequicor.magicpaper.domain

/**
 * Whether the durable plan still describes the turn a coroutine is holding.
 *
 * Executing a stage means suspending several times — loading profiles, compiling the
 * dependency context, asking the planner for instructions — and anything may rewrite the plan
 * meanwhile: an inbox migration restoring a finished turn, a user's retry starting a new run,
 * a recovery admitting a fresh generation. The values the coroutine holds are a snapshot, and
 * the next thing it does is start a native turn, which is expensive and has consequences.
 *
 * So the question is asked once more, immediately before that turn, against what is on disk.
 */
sealed interface CheckpointDrift {
    /** Nothing moved. The turn in hand is the turn on disk. */
    data object None : CheckpointDrift

    /** The plan, the stage or its attempt is no longer there; there is nothing to continue. */
    data object Gone : CheckpointDrift

    /** Another run, another attempt or another turn owns this stage now. */
    data object TakenOver : CheckpointDrift

    /** The same turn, recorded in a different phase. Its durable values are authoritative. */
    data class Advanced(val stage: Milestone, val attempt: StageAttempt) : CheckpointDrift
}

/**
 * Identity is compared before phase, and that order is part of the contract: a plan that has
 * moved on to another run or another attempt has *not* advanced this one, and adopting its
 * values would attribute a native turn to work it never belonged to. Only when the turn is
 * demonstrably the same does a differing phase mean progress worth picking up.
 *
 * Identity is the run, the attempt, the turn within it and the generation admitted for it —
 * each of those distinguishes work that must never be confused. A phase never takes part:
 * telling progress from substitution is the whole purpose.
 */
fun CheckpointDrift(held: Plan, holding: StageAttempt, durable: Plan?, stageId: String): CheckpointDrift {
    val stage = durable?.milestones?.firstOrNull { it.id == stageId }
    val recorded = stage?.attempts?.lastOrNull()
    return when {
        durable == null || stage == null || recorded == null -> CheckpointDrift.Gone
        durable.runId != held.runId || recorded.id != holding.id ||
            recorded.turnIndex != holding.turnIndex ||
            recorded.sessionGeneration != holding.sessionGeneration -> CheckpointDrift.TakenOver
        recorded.phase != holding.phase -> CheckpointDrift.Advanced(stage, recorded)
        else -> CheckpointDrift.None
    }
}
