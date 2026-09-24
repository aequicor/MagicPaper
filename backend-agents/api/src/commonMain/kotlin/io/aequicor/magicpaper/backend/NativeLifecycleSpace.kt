package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [NativeLifecycleMachine], declared so it can be read without running anything.
 *
 * The state is a map of runs across sessions, each run holding attempts, and an attempt carries three
 * axes that look independent — termination, outcome and a recovery acknowledgement — plus a process and
 * its deliveries. Acceptance of most facts is a function of the newest run and its newest attempt, read
 * in the order the reducer evaluates its guards, so that is what names a position:
 *  1. unconfirmed persistence fences everything but its own fact: one position;
 *  2. a store that is closing or closed refuses `Begin`, `LaunchRequested` and `DeliveryRequested` and
 *     accepts `Closed` only once every attempt is stopped: four positions, see below;
 *  3. otherwise whether the newest run is still active, and the shape of its newest attempt.
 *
 * The axes are not independent, which is what keeps the shapes to fifteen rather than thirty-two. An
 * outcome other than `NOT_DISPATCHED` implies a delivery (`Accepted` needs one, and an unknown outcome
 * exists only because a delivery was requested). An acknowledgement lands only on a `STOPPED` attempt,
 * but `STOPPED` can be left: `Unavailable` moves an attempt that has a process or a delivery back to
 * `UNKNOWN` and keeps what was acknowledged. What remains:
 *  - `launched` is `NOT_STARTED`: the only shape that accepts `Attached`;
 *  - `attached` and `delivered` are `LIVE` with nothing sent and with a request sent, and differ in
 *    `Accepted`, `Terminal*` and in whether `DeliveryRequested` repeats a stage;
 *  - `outcome-known-unstopped` is a final outcome with the process not confirmed stopped, acknowledged
 *    or not. `LIVE` and `UNKNOWN` termination accept identically once the outcome is final, so they
 *    share the position;
 *  - `stop-unconfirmed-*` is `UNKNOWN` termination — a stop was requested, or a restart found the
 *    process in flight — and differs from `LIVE` in refusing `DeliveryRequested`. With an outcome that
 *    was sent and never confirmed it splits once more, into one that still accepts `Terminal*` and one
 *    whose outcome was acknowledged and no longer does (`...-delivered-acknowledged`);
 *  - `stopped-*` is `STOPPED` and unacknowledged. `SUCCEEDED` and `FAILED` are told apart only here,
 *    because only a succeeded predecessor admits `LaunchRequested`; `stopped-outcome-unknown` is the
 *    one that still accepts `Terminal*` and blocks the next `Begin`;
 *  - `acknowledged-*` is the same with the acknowledgement recorded, which removes `Acknowledge`
 *    and `Terminal*`. An acknowledgement over an outcome nobody can know holds the session until
 *    exactly one `Begin` carries it forward; one over an outcome the engine itself reported holds
 *    nothing, so a plain `Begin` is admitted beside it too.
 *
 * Each shape exists twice, active and `ended-`: `Cancel`, `RunFinished`, `NeighbourMissing` and a
 * restart end a run, and an ended run refuses `LaunchRequested` and `DeliveryRequested`, while the next
 * `Begin` of the session may be admitted. `Attached` does not look at the run, so an ended run still
 * accepts it. A run that never got an attempt is four positions: `admitted` while active,
 * `ended-unlaunched`, and the two stations of a no-dispatch decision, `no-dispatch-proved` and
 * `no-dispatch-acknowledged`.
 *
 * `Intent.Begin` is a decision tree over the whole *session's* history — unresolved attempts, pending
 * recovery acknowledgements, undispatched runs, pending no-dispatch acknowledgements, closing, a
 * repeated request, an active run — so one name cannot stand for it, yet [name] sees only the input and
 * so can split it only by payload. `Begin` is a plain request; `BeginRecovering` carries a recovery
 * acknowledgement and `BeginAfterNoDispatch` a no-dispatch one, each accepted only where exactly that
 * decision is pending; `BeginBothDecisions` and `BeginBlank` are refused whatever the store holds. The
 * guard against both decisions is the deciding one only in a store where both are pending at once, which
 * needs several runs, so a targeted test builds one; everywhere else another guard refuses first.
 * `Terminal` is three names: `TerminalSucceeded` and `TerminalFailed` accept alike and land apart, which
 * a succeeded and a failed attempt need once stopped; `TerminalUnproven` carries an outcome that is not
 * final and is refused everywhere.
 * `DeliveryRequestedOtherStage` exists because the representatives have sent `PI_STDIN`: a stage
 * already sent is refused and another is not.
 *
 * The four closing positions isolate one fence each, since a fence shows only where nothing else
 * would refuse. `closing-unstopped` is a live attempt that would otherwise take a delivery;
 * `closing-drained-run-active` an active run with a succeeded, stopped attempt that would otherwise take
 * `LaunchRequested`; `closing-drained` a store with nothing in it that would otherwise take a `Begin`;
 * `closed` is the same store once `Closed` was accepted. The last two accept identically, and only the
 * flag tells them apart. A closing store that holds a run is named by whether an attempt remains
 * unstopped and whether a run is still active, and by nothing about that attempt's shape.
 *
 * [label] names the newest run — the last in the map — and its newest attempt.
 *
 * What the declaration cannot express, and leaves to `NativeLifecycleMachineTest` and to the targeted
 * tests in `NativeLifecycleSpaceTest`:
 *  - which of several runs, attempts or sessions an input names. The matrix speaks about stores holding
 *    one run with one attempt; `Begin` reads every run of its session, and an older run's unresolved
 *    attempt, pending acknowledgement or undispatched proof changes it without changing the position;
 *  - whether a `Begin` repeats a request the store holds, or names a session other than the store's.
 *    Both depend on the store, which [name] cannot read, so the matrix's `Begin` is a fresh request in
 *    the representatives' own session and the two guards are covered by targeted tests only;
 *  - an identity that does not match: the run, attempt ordinal or process of a fact, the id or contents
 *    of an acknowledgement or a proof, a proof id already owned by another run, and a blank id on any
 *    input but `Begin`;
 *  - the payload of an input beyond those names: the thread and turn ids of `Accepted`, and the
 *    stages other than `PI_STDIN`, are accepted the same way whatever they hold;
 *  - a closing store's other inputs. Whether `Accepted`, `Acknowledge` or `Terminal*` are accepted there
 *    follows the attempt's shape, which the four closing positions do not name;
 *  - `unknown(state)` and the position can disagree. It is true when any attempt of any run has an
 *    unknown outcome or an unknown termination, so a clean newest run beside an older unresolved one
 *    reports unknown at a position that is not; `acknowledged-outcome-unknown` still reports unknown,
 *    since acknowledging leaves the outcome unconfirmed; and `stop-unconfirmed-undelivered` does, since
 *    nobody knows whether the process is gone, though nothing was sent.
 */
object NativeLifecycleSpace : StateSpace<NativeLifecycleMachine.State, NativeLifecycleMachine.Input, NativeLifecycleMachine.Effect> {
    val EMPTY = PhaseId("empty")
    val ADMITTED = PhaseId("admitted")
    val LAUNCHED = PhaseId("launched")
    val ATTACHED = PhaseId("attached")
    val DELIVERED = PhaseId("delivered")
    val OUTCOME_KNOWN_UNSTOPPED = PhaseId("outcome-known-unstopped")
    val STOP_UNCONFIRMED_UNDELIVERED = PhaseId("stop-unconfirmed-undelivered")
    val STOP_UNCONFIRMED_DELIVERED = PhaseId("stop-unconfirmed-delivered")
    val STOPPED_UNDELIVERED = PhaseId("stopped-undelivered")
    val STOPPED_OUTCOME_UNKNOWN = PhaseId("stopped-outcome-unknown")
    val STOPPED_SUCCEEDED = PhaseId("stopped-succeeded")
    val STOPPED_FAILED = PhaseId("stopped-failed")
    val ACKNOWLEDGED_UNDELIVERED = PhaseId("acknowledged-undelivered")
    val ACKNOWLEDGED_OUTCOME_UNKNOWN = PhaseId("acknowledged-outcome-unknown")
    val ACKNOWLEDGED_SUCCEEDED = PhaseId("acknowledged-succeeded")
    val ACKNOWLEDGED_FAILED = PhaseId("acknowledged-failed")
    val STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED = PhaseId("stop-unconfirmed-delivered-acknowledged")
    val ENDED_UNLAUNCHED = PhaseId("ended-unlaunched")
    val NO_DISPATCH_PROVED = PhaseId("no-dispatch-proved")
    val NO_DISPATCH_ACKNOWLEDGED = PhaseId("no-dispatch-acknowledged")
    val ENDED_LAUNCHED = PhaseId("ended-launched")
    val ENDED_ATTACHED = PhaseId("ended-attached")
    val ENDED_DELIVERED = PhaseId("ended-delivered")
    val ENDED_OUTCOME_KNOWN_UNSTOPPED = PhaseId("ended-outcome-known-unstopped")
    val ENDED_STOP_UNCONFIRMED_UNDELIVERED = PhaseId("ended-stop-unconfirmed-undelivered")
    val ENDED_STOP_UNCONFIRMED_DELIVERED = PhaseId("ended-stop-unconfirmed-delivered")
    val ENDED_STOPPED_UNDELIVERED = PhaseId("ended-stopped-undelivered")
    val ENDED_STOPPED_OUTCOME_UNKNOWN = PhaseId("ended-stopped-outcome-unknown")
    val ENDED_STOPPED_SUCCEEDED = PhaseId("ended-stopped-succeeded")
    val ENDED_STOPPED_FAILED = PhaseId("ended-stopped-failed")
    val ENDED_ACKNOWLEDGED_UNDELIVERED = PhaseId("ended-acknowledged-undelivered")
    val ENDED_ACKNOWLEDGED_OUTCOME_UNKNOWN = PhaseId("ended-acknowledged-outcome-unknown")
    val ENDED_ACKNOWLEDGED_SUCCEEDED = PhaseId("ended-acknowledged-succeeded")
    val ENDED_ACKNOWLEDGED_FAILED = PhaseId("ended-acknowledged-failed")
    val ENDED_STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED = PhaseId("ended-stop-unconfirmed-delivered-acknowledged")
    val CLOSING_UNSTOPPED = PhaseId("closing-unstopped")
    val CLOSING_DRAINED_RUN_ACTIVE = PhaseId("closing-drained-run-active")
    val CLOSING_DRAINED = PhaseId("closing-drained")
    val CLOSED = PhaseId("closed")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val BEGIN = InputId("Begin")
    val BEGIN_RECOVERING = InputId("BeginRecovering")
    val BEGIN_AFTER_NO_DISPATCH = InputId("BeginAfterNoDispatch")
    val BEGIN_BOTH_DECISIONS = InputId("BeginBothDecisions")
    val BEGIN_BLANK = InputId("BeginBlank")
    val CANCEL = InputId("Cancel")
    val STOP = InputId("Stop")
    val ACKNOWLEDGE = InputId("Acknowledge")
    val ACKNOWLEDGE_NO_DISPATCH = InputId("AcknowledgeNoDispatch")
    val CLOSE = InputId("Close")
    val LAUNCH_REQUESTED = InputId("LaunchRequested")
    val ATTACHED_FACT = InputId("Attached")
    val DELIVERY_REQUESTED = InputId("DeliveryRequested")
    val DELIVERY_REQUESTED_OTHER_STAGE = InputId("DeliveryRequestedOtherStage")
    val ACCEPTED = InputId("Accepted")
    val TERMINAL_SUCCEEDED = InputId("TerminalSucceeded")
    val TERMINAL_FAILED = InputId("TerminalFailed")
    val TERMINAL_UNPROVEN = InputId("TerminalUnproven")
    val STOPPING = InputId("Stopping")
    val STOPPED = InputId("Stopped")
    val UNAVAILABLE = InputId("Unavailable")
    val RUN_FINISHED = InputId("RunFinished")
    val NO_DISPATCH_CONFIRMED = InputId("NoDispatchConfirmed")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val CLOSED_FACT = InputId("Closed")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        EMPTY, ADMITTED,
        LAUNCHED, ATTACHED, DELIVERED, OUTCOME_KNOWN_UNSTOPPED, STOP_UNCONFIRMED_UNDELIVERED, STOP_UNCONFIRMED_DELIVERED,
        STOPPED_UNDELIVERED, STOPPED_OUTCOME_UNKNOWN, STOPPED_SUCCEEDED, STOPPED_FAILED,
        ACKNOWLEDGED_UNDELIVERED, ACKNOWLEDGED_OUTCOME_UNKNOWN, ACKNOWLEDGED_SUCCEEDED, ACKNOWLEDGED_FAILED,
        STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED,
        ENDED_UNLAUNCHED, NO_DISPATCH_PROVED, NO_DISPATCH_ACKNOWLEDGED,
        ENDED_LAUNCHED, ENDED_ATTACHED, ENDED_DELIVERED, ENDED_OUTCOME_KNOWN_UNSTOPPED,
        ENDED_STOP_UNCONFIRMED_UNDELIVERED, ENDED_STOP_UNCONFIRMED_DELIVERED,
        ENDED_STOPPED_UNDELIVERED, ENDED_STOPPED_OUTCOME_UNKNOWN, ENDED_STOPPED_SUCCEEDED, ENDED_STOPPED_FAILED,
        ENDED_ACKNOWLEDGED_UNDELIVERED, ENDED_ACKNOWLEDGED_OUTCOME_UNKNOWN,
        ENDED_ACKNOWLEDGED_SUCCEEDED, ENDED_ACKNOWLEDGED_FAILED, ENDED_STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED,
        CLOSING_UNSTOPPED, CLOSING_DRAINED_RUN_ACTIVE, CLOSING_DRAINED, CLOSED,
        PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(BEGIN, Branch.INTENT),
        InputSpec(BEGIN_RECOVERING, Branch.INTENT),
        InputSpec(BEGIN_AFTER_NO_DISPATCH, Branch.INTENT),
        InputSpec(BEGIN_BOTH_DECISIONS, Branch.INTENT),
        InputSpec(BEGIN_BLANK, Branch.INTENT),
        InputSpec(CANCEL, Branch.INTENT),
        InputSpec(STOP, Branch.INTENT),
        InputSpec(ACKNOWLEDGE, Branch.INTENT),
        InputSpec(ACKNOWLEDGE_NO_DISPATCH, Branch.INTENT),
        InputSpec(CLOSE, Branch.INTENT),
        InputSpec(LAUNCH_REQUESTED, Branch.FACT),
        InputSpec(ATTACHED_FACT, Branch.FACT),
        InputSpec(DELIVERY_REQUESTED, Branch.FACT),
        InputSpec(DELIVERY_REQUESTED_OTHER_STAGE, Branch.FACT),
        InputSpec(ACCEPTED, Branch.FACT),
        InputSpec(TERMINAL_SUCCEEDED, Branch.FACT),
        InputSpec(TERMINAL_FAILED, Branch.FACT),
        InputSpec(TERMINAL_UNPROVEN, Branch.FACT),
        InputSpec(STOPPING, Branch.FACT),
        InputSpec(STOPPED, Branch.FACT),
        InputSpec(UNAVAILABLE, Branch.FACT),
        InputSpec(RUN_FINISHED, Branch.FACT),
        InputSpec(NO_DISPATCH_CONFIRMED, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(CLOSED_FACT, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("Execute"), EffectId("Launch"), EffectId("Deliver"), EffectId("Stop"), EffectId("Publish"), EffectId("Reject"),
    )

    // Rows follow `phases`, columns follow `inputs`. Generated from per-position attributes by a
    // throwaway script and then held to the machine cell by cell by the harness, so a cell that is
    // wrong fails the Space test rather than being trusted. Reading it:
    //  - `Begin` is accepted only where the session has nothing unresolved, nothing that still needs a
    //    decision carried (an acknowledgement over an unknown outcome, or an undispatched proof) and no
    //    active run, and it is a fresh request; `BeginRecovering` and `BeginAfterNoDispatch` only where
    //    exactly their acknowledgement is pending; `BeginBothDecisions` and `BeginBlank` nowhere;
    //  - `Stop`, `Stopping`, `Stopped` and `Unavailable` are accepted wherever an attempt exists, and
    //    `Stop` and `Stopping` leave a stopped attempt's termination alone;
    //  - `Closed` is accepted where no attempt is unstopped, an empty store included;
    //  - `PersistenceUnknown` is accepted everywhere; `Close` and `Restored` everywhere but behind it.
    override val accepts = acceptance(phases, inputs, listOf(
        //                                                     Bg Bx Bn Bb Bl Ca St Ak An Cl Lr At Dr Do Ac Ts Tf Tu Sg Sd Un Rf Nc Nm Cd Rs Pu
        /* empty                                         */ "100000000100000000000000111",
        /* admitted                                      */ "000001000110000000000101111",
        /* launched                                      */ "000001100101000000111101011",
        /* attached                                      */ "000001100100110000111101011",
        /* delivered                                     */ "000001100100011110111101011",
        /* outcome-known-unstopped                       */ "000001100100001000111101011",
        /* stop-unconfirmed-undelivered                  */ "000001100100000000111101011",
        /* stop-unconfirmed-delivered                    */ "000001100100001110111101011",
        /* stopped-undelivered                           */ "000001110100000000111101111",
        /* stopped-outcome-unknown                       */ "000001110100001110111101111",
        /* stopped-succeeded                             */ "000001110110001000111101111",
        /* stopped-failed                                */ "000001110100001000111101111",
        /* acknowledged-undelivered                      */ "000001100100000000111101111",
        /* acknowledged-outcome-unknown                  */ "000001100100001000111101111",
        /* acknowledged-succeeded                        */ "000001100110001000111101111",
        /* acknowledged-failed                           */ "000001100100001000111101111",
        /* stop-unconfirmed-delivered-acknowledged       */ "000001100100001000111101011",
        /* ended-unlaunched                              */ "100001000100000000000111111",
        /* no-dispatch-proved                            */ "000001001100000000000111111",
        /* no-dispatch-acknowledged                      */ "001001000100000000000111111",
        /* ended-launched                                */ "100001100101000000111101011",
        /* ended-attached                                */ "000001100100000000111101011",
        /* ended-delivered                               */ "000001100100001110111101011",
        /* ended-outcome-known-unstopped                 */ "000001100100001000111101011",
        /* ended-stop-unconfirmed-undelivered            */ "000001100100000000111101011",
        /* ended-stop-unconfirmed-delivered              */ "000001100100001110111101011",
        /* ended-stopped-undelivered                     */ "100001110100000000111101111",
        /* ended-stopped-outcome-unknown                 */ "000001110100001110111101111",
        /* ended-stopped-succeeded                       */ "100001110100001000111101111",
        /* ended-stopped-failed                          */ "100001110100001000111101111",
        /* ended-acknowledged-undelivered                */ "110001100100000000111101111",
        /* ended-acknowledged-outcome-unknown            */ "010001100100001000111101111",
        /* ended-acknowledged-succeeded                  */ "110001100100001000111101111",
        /* ended-acknowledged-failed                     */ "110001100100001000111101111",
        /* ended-stop-unconfirmed-delivered-acknowledged */ "000001100100001000111101011",
        /* closing-unstopped                             */ "000001100100000000111101011",
        /* closing-drained-run-active                    */ "000001110100001000111101111",
        /* closing-drained                               */ "000000000100000000000000111",
        /* closed                                        */ "000000000100000000000000111",
        /* persistence-unknown                           */ "000000000000000000000000001",
    ))

    /** The two positions one attempt shape stands at: while its run is active, and once the run ended. */
    private class Shape(val active: PhaseId, val ended: PhaseId)

    private val LAUNCHED_SHAPE = Shape(LAUNCHED, ENDED_LAUNCHED)
    private val ATTACHED_SHAPE = Shape(ATTACHED, ENDED_ATTACHED)
    private val DELIVERED_SHAPE = Shape(DELIVERED, ENDED_DELIVERED)
    private val KNOWN_UNSTOPPED_SHAPE = Shape(OUTCOME_KNOWN_UNSTOPPED, ENDED_OUTCOME_KNOWN_UNSTOPPED)
    private val LOST_UNDELIVERED_SHAPE = Shape(STOP_UNCONFIRMED_UNDELIVERED, ENDED_STOP_UNCONFIRMED_UNDELIVERED)
    private val LOST_DELIVERED_SHAPE = Shape(STOP_UNCONFIRMED_DELIVERED, ENDED_STOP_UNCONFIRMED_DELIVERED)
    private val LOST_ACKNOWLEDGED_SHAPE = Shape(STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED, ENDED_STOP_UNCONFIRMED_DELIVERED_ACKNOWLEDGED)

    // Exhaustive over outcome and termination: a new value of either does not compile until it is
    // placed here, and only then is it allowed to change what a position means.
    private fun shape(attempt: NativeLifecycleMachine.Attempt): Shape = when (attempt.termination) {
        NativeTermination.NOT_STARTED -> LAUNCHED_SHAPE
        NativeTermination.LIVE -> when (attempt.outcome) {
            NativeOutcome.NOT_DISPATCHED -> ATTACHED_SHAPE
            NativeOutcome.UNKNOWN -> DELIVERED_SHAPE
            NativeOutcome.SUCCEEDED, NativeOutcome.FAILED -> KNOWN_UNSTOPPED_SHAPE
        }
        NativeTermination.UNKNOWN -> when (attempt.outcome) {
            NativeOutcome.NOT_DISPATCHED -> LOST_UNDELIVERED_SHAPE
            NativeOutcome.UNKNOWN -> if (attempt.acknowledgement == null) LOST_DELIVERED_SHAPE else LOST_ACKNOWLEDGED_SHAPE
            NativeOutcome.SUCCEEDED, NativeOutcome.FAILED -> KNOWN_UNSTOPPED_SHAPE
        }
        NativeTermination.STOPPED -> if (attempt.acknowledgement == null) when (attempt.outcome) {
            NativeOutcome.NOT_DISPATCHED -> Shape(STOPPED_UNDELIVERED, ENDED_STOPPED_UNDELIVERED)
            NativeOutcome.UNKNOWN -> Shape(STOPPED_OUTCOME_UNKNOWN, ENDED_STOPPED_OUTCOME_UNKNOWN)
            NativeOutcome.SUCCEEDED -> Shape(STOPPED_SUCCEEDED, ENDED_STOPPED_SUCCEEDED)
            NativeOutcome.FAILED -> Shape(STOPPED_FAILED, ENDED_STOPPED_FAILED)
        } else when (attempt.outcome) {
            NativeOutcome.NOT_DISPATCHED -> Shape(ACKNOWLEDGED_UNDELIVERED, ENDED_ACKNOWLEDGED_UNDELIVERED)
            NativeOutcome.UNKNOWN -> Shape(ACKNOWLEDGED_OUTCOME_UNKNOWN, ENDED_ACKNOWLEDGED_OUTCOME_UNKNOWN)
            NativeOutcome.SUCCEEDED -> Shape(ACKNOWLEDGED_SUCCEEDED, ENDED_ACKNOWLEDGED_SUCCEEDED)
            NativeOutcome.FAILED -> Shape(ACKNOWLEDGED_FAILED, ENDED_ACKNOWLEDGED_FAILED)
        }
    }

    private fun position(run: NativeLifecycleMachine.Run): PhaseId {
        val attempt = run.attempts.lastOrNull()
        return when {
            attempt != null -> shape(attempt).let { if (run.active) it.active else it.ended }
            run.active -> ADMITTED
            run.noDispatchAcknowledgement != null -> NO_DISPATCH_ACKNOWLEDGED
            run.noDispatchProof != null -> NO_DISPATCH_PROVED
            else -> ENDED_UNLAUNCHED
        }
    }

    // An attempt left unstopped outranks the flag: `Unavailable` can move a stopped attempt back to
    // `UNKNOWN` after `Closed` was accepted, and such a store no longer accepts `Closed` again.
    private fun closing(state: NativeLifecycleMachine.State): PhaseId = when {
        state.runs.values.any { run -> run.attempts.any { it.termination != NativeTermination.STOPPED } } -> CLOSING_UNSTOPPED
        state.closed -> CLOSED
        state.runs.values.any { it.active } -> CLOSING_DRAINED_RUN_ACTIVE
        else -> CLOSING_DRAINED
    }

    override fun label(state: NativeLifecycleMachine.State): PhaseId = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        state.closing || state.closed -> closing(state)
        else -> state.runs.values.lastOrNull()?.let(::position) ?: EMPTY
    }

    override fun name(input: NativeLifecycleMachine.Input): InputId = when (input) {
        // Only the payload can split the tree: whether a request repeats one the store holds, or names
        // another session, is a fact about the store and is left to the targeted tests.
        is NativeLifecycleMachine.Intent.Begin -> when {
            input.acknowledgement != null && input.noDispatchAcknowledgement != null -> BEGIN_BOTH_DECISIONS
            input.run.sessionId.isBlank() || input.run.requestId.isBlank() -> BEGIN_BLANK
            input.acknowledgement != null -> BEGIN_RECOVERING
            input.noDispatchAcknowledgement != null -> BEGIN_AFTER_NO_DISPATCH
            else -> BEGIN
        }
        is NativeLifecycleMachine.Intent.Cancel -> CANCEL
        is NativeLifecycleMachine.Intent.Stop -> STOP
        is NativeLifecycleMachine.Intent.Acknowledge -> ACKNOWLEDGE
        is NativeLifecycleMachine.Intent.AcknowledgeNoDispatch -> ACKNOWLEDGE_NO_DISPATCH
        NativeLifecycleMachine.Intent.Close -> CLOSE
        is NativeLifecycleMachine.Fact.LaunchRequested -> LAUNCH_REQUESTED
        is NativeLifecycleMachine.Fact.Attached -> ATTACHED_FACT
        // The representatives have sent `PI_STDIN`, so it stands for a stage already sent.
        is NativeLifecycleMachine.Fact.DeliveryRequested -> when (input.stage) {
            NativeDelivery.PI_STDIN -> DELIVERY_REQUESTED
            NativeDelivery.CODEX_THREAD, NativeDelivery.CODEX_TURN, NativeDelivery.PROVIDER_STDIN, NativeDelivery.CLAUDE_STDIN ->
                DELIVERY_REQUESTED_OTHER_STAGE
        }
        is NativeLifecycleMachine.Fact.Accepted -> ACCEPTED
        // Both final outcomes are accepted alike and land apart; an outcome that is not final is refused.
        is NativeLifecycleMachine.Fact.Terminal -> when (input.outcome) {
            NativeOutcome.SUCCEEDED -> TERMINAL_SUCCEEDED
            NativeOutcome.FAILED -> TERMINAL_FAILED
            NativeOutcome.NOT_DISPATCHED, NativeOutcome.UNKNOWN -> TERMINAL_UNPROVEN
        }
        is NativeLifecycleMachine.Fact.Stopping -> STOPPING
        is NativeLifecycleMachine.Fact.Stopped -> STOPPED
        is NativeLifecycleMachine.Fact.Unavailable -> UNAVAILABLE
        is NativeLifecycleMachine.Fact.RunFinished -> RUN_FINISHED
        is NativeLifecycleMachine.Fact.NoDispatchConfirmed -> NO_DISPATCH_CONFIRMED
        is NativeLifecycleMachine.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        NativeLifecycleMachine.Fact.Closed -> CLOSED_FACT
        NativeLifecycleMachine.Fact.Restored -> RESTORED
        NativeLifecycleMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: NativeLifecycleMachine.Effect): EffectId = when (effect) {
        is NativeLifecycleMachine.Effect.Execute -> EffectId("Execute")
        is NativeLifecycleMachine.Effect.Launch -> EffectId("Launch")
        is NativeLifecycleMachine.Effect.Deliver -> EffectId("Deliver")
        is NativeLifecycleMachine.Effect.Stop -> EffectId("Stop")
        is NativeLifecycleMachine.Effect.Publish -> EffectId("Publish")
        is NativeLifecycleMachine.Effect.Reject -> EffectId("Reject")
    }

    // A delivery that was requested and never confirmed, or a process nobody knows to be gone.
    // `LIVE` is not unknown: the reducer treats it as unresolved, but it is observed, not in doubt.
    override fun unknown(state: NativeLifecycleMachine.State) = state.persistenceUnknown ||
        state.runs.values.any { run ->
            run.attempts.any { it.outcome == NativeOutcome.UNKNOWN || it.termination == NativeTermination.UNKNOWN }
        }

    override fun rejected(effect: NativeLifecycleMachine.Effect) = effect is NativeLifecycleMachine.Effect.Reject
}
