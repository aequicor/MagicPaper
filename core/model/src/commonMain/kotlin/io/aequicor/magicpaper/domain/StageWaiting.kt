package io.aequicor.magicpaper.domain

/**
 * What a stage attempt is waiting for after the planner has decided its next turn.
 *
 * The attempt stores this as three fields — `awaitingPlanner`, `waitingForUser`,
 * `waitingForEvent` — because that is the shape already on disk. They are not independent:
 * every legal combination comes from one [StageTurnAction], so producing them separately
 * invites a combination the planner never chose. This type is the only producer.
 */
sealed interface StageWaiting {
    /** The attempt continues on its own. */
    data object Nothing : StageWaiting

    /** A person must answer before the attempt continues. */
    data class User(val requestId: String) : StageWaiting

    /** An external event must arrive; a null request id means the event was not identified. */
    data class Event(val requestId: String?) : StageWaiting

    companion object {
        /**
         * An older plan recorded a wait without a request id. It still blocks the attempt, so
         * it keeps a stable placeholder rather than degrading into "waiting for nothing".
         */
        const val LEGACY_REQUEST = "legacy"

        fun of(action: StageTurnAction, requestId: String?): StageWaiting = when (action) {
            StageTurnAction.WAIT -> User(requestId ?: LEGACY_REQUEST)
            StageTurnAction.WAIT_EVENT -> Event(requestId)
            StageTurnAction.VERIFY, StageTurnAction.CONTINUE -> Nothing
        }
    }
}

/** True while the planner, not the worker, owns the next decision. */
val StageWaiting.awaitsPlanner: Boolean get() = this is StageWaiting.User

val StageWaiting.userRequestId: String? get() = (this as? StageWaiting.User)?.requestId

val StageWaiting.eventRequestId: String? get() = (this as? StageWaiting.Event)?.requestId

/** Reads back what an attempt on disk is waiting for, including plans written earlier. */
val StageAttempt.waiting: StageWaiting
    get() = waitingForUser?.let(StageWaiting::User)
        ?: waitingForEvent?.let { StageWaiting.Event(it) }
        ?: StageWaiting.Nothing

/**
 * Applies one planner decision. The four fields move together so the attempt cannot record,
 * for example, a wait for a person and a wait for an event at the same time.
 */
fun StageAttempt.awaiting(waiting: StageWaiting): StageAttempt = copy(
    awaitingPlanner = waiting.awaitsPlanner,
    coordinationPending = false,
    waitingForUser = waiting.userRequestId,
    waitingForEvent = waiting.eventRequestId,
)

/** The reply arrived: the attempt stops waiting without changing whose turn it is. */
fun StageAttempt.replied(): StageAttempt = copy(error = null, waitingForUser = null, waitingForEvent = null)

/** The worker returned its turn and the planner has not answered yet. */
fun StageAttempt.handedToPlanner(): StageAttempt = copy(awaitingPlanner = true, coordinationPending = true)
