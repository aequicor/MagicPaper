package io.aequicor.magicpaper.domain

/**
 * Whether the planner still owes an answer for a turn the worker handed back.
 *
 * Stored as `coordinationPending: Boolean?`. The third value is not a third answer: it is an
 * attempt written before the field existed, about which nothing was recorded. Five places
 * read it, and they did not agree on what to do with that — three treated an unrecorded
 * attempt as settled, one as pending, and one inferred an answer from the attempt itself.
 *
 * None of them was wrong: each leans the way that is safe where it stands, and a plan that
 * predates the field has no better evidence to offer. But the disagreement was invisible,
 * spelled as `== true` in three places and `!= false` in a fourth. Here it is deliberate.
 *
 * The producers are [handedToPlanner], [awaiting] and [coordinationSettled]; none of them
 * writes the unrecorded value back, because an attempt that has said what it owes never
 * returns to silence.
 */
sealed interface CoordinationState {
    /** The worker returned its turn and the planner has not answered it. */
    data object Pending : CoordinationState

    /** Nothing is owed: the answer arrived, or the turn never went to the planner. */
    data object Settled : CoordinationState

    /** Written before an attempt recorded this. Only the attempt's own evidence can say. */
    data object Unrecorded : CoordinationState
}

val StageAttempt.coordinationState: CoordinationState
    get() = when (coordinationPending) {
        true -> CoordinationState.Pending
        false -> CoordinationState.Settled
        null -> CoordinationState.Unrecorded
    }

/**
 * True only for an attempt that says so itself.
 *
 * The reading for a side that must not act on a guess: emitting a durable handoff event, or
 * refusing an attempt a restored verification. Inventing either for an old plan does damage
 * that leaving it alone does not.
 */
val CoordinationState.certainlyPending: Boolean get() = this == CoordinationState.Pending

/**
 * True for anything but an attempt that says it is settled.
 *
 * The reading for a side where closing a handoff too early loses a planner's answer: an
 * unrecorded attempt keeps its record queued rather than being marked resolved.
 */
val CoordinationState.possiblyPending: Boolean get() = this != CoordinationState.Settled

/**
 * The resolved answer, where one is needed before running the turn: an unrecorded attempt is
 * judged by what it carries — it was handed over, it produced a report, nobody is waiting on
 * a person, and its turn is accounted for in the activity log.
 */
val StageAttempt.coordinationOwed: Boolean
    get() = when (coordinationState) {
        CoordinationState.Pending -> true
        CoordinationState.Settled -> false
        CoordinationState.Unrecorded -> awaitingPlanner && report.isNotBlank() && waitingForUser == null &&
            (turnIndex == 0 || chatTurns.size > turnIndex)
    }

/** The planner owes nothing and the worker holds the turn. */
fun StageAttempt.coordinationSettled(): StageAttempt = copy(awaitingPlanner = false, coordinationPending = false)
