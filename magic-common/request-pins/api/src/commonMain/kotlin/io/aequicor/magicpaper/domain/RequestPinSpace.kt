package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [RequestPinMachine], declared so it can be read without running anything.
 *
 * The state has no phase field: a position is a combination of `initialized`, `removed`,
 * `persistenceUnknown`, `active` and the two failure sets, so [label] names it.
 *
 * Acceptance depends on five things only — the persistence flag, initialization, removal, whether
 * an attempt is in flight, and whether an analysis is pending. `failure` is what the interface
 * shows and no transition reads it, so the two failure positions ([UNKNOWN], [FAILED]) exist to
 * name the outcome, not to change the matrix. [label] therefore ranks them below [PENDING]: a
 * state with a settled unknown source *and* another source waiting still accepts `Analyse`, and
 * placing it at [UNKNOWN] would declare it would not. `unknown(state)` is true for that state all
 * the same, which is the one place position and predicate can disagree.
 *
 * What the declaration cannot express: an attempt id that is not the active one, a duplicate
 * message id in `Initialized`, and a blank summary are refusals of identity. They keep their own
 * tests in `RequestPinMachineTest`.
 */
object RequestPinSpace : StateSpace<RequestPinMachine.State, RequestPinMachine.Input, RequestPinMachine.Effect> {
    val NEW = PhaseId("new")
    val IDLE = PhaseId("idle")
    val PENDING = PhaseId("pending")
    val ACTIVE = PhaseId("active")
    val UNKNOWN = PhaseId("unknown-outcome")
    val FAILED = PhaseId("analysis-failed")
    val REMOVED = PhaseId("removed")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val SYNC = InputId("Sync")
    val ANALYSE = InputId("Analyse")
    val REMOVE = InputId("Remove")
    val INITIALIZED = InputId("Initialized")
    val COMPLETED = InputId("Completed")
    val FAILED_KNOWN = InputId("FailedKnown")
    val FAILED_UNKNOWN = InputId("FailedUnknown")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(NEW, IDLE, PENDING, ACTIVE, UNKNOWN, FAILED, REMOVED, PERSISTENCE_UNKNOWN)

    override val inputs = listOf(
        InputSpec(SYNC, Branch.INTENT),
        InputSpec(ANALYSE, Branch.INTENT),
        InputSpec(REMOVE, Branch.INTENT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(COMPLETED, Branch.FACT),
        InputSpec(FAILED_KNOWN, Branch.FACT),
        InputSpec(FAILED_UNKNOWN, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Analyse"), EffectId("Checkpoint"), EffectId("DeleteCheckpoint"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. Only an analysis may start from `pending`, and
    // only `active` accepts the outcome of an attempt — a stale attempt id is an identity refusal.
    override val accepts = acceptance(phases, inputs, listOf(
        //                  Sy An Rm In Co Fk Fu Rs Pu
        /* new           */ "000100001",
        /* idle          */ "101000011",
        /* pending       */ "111000011",
        /* active        */ "101011111",
        /* unknown       */ "101000011",
        /* failed        */ "101000011",
        /* removed       */ "000000011",
        /* persistence   */ "000000001",
    ))

    override fun label(state: RequestPinMachine.State): PhaseId = when {
        // Mirrors the order of the reducer's guards: the persistence flag refuses everything but
        // its own fact, and a removed conversation refuses everything but `Restored`.
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        state.removed -> REMOVED
        !state.initialized -> NEW
        state.active != null -> ACTIVE
        RequestPinMachine.pending(state) -> PENDING
        else -> when (state.failure) {
            RequestPinMachine.Failure.UNKNOWN_OUTCOME -> UNKNOWN
            RequestPinMachine.Failure.ANALYSIS -> FAILED
            RequestPinMachine.Failure.PERSISTENCE, null -> IDLE
        }
    }

    override fun name(input: RequestPinMachine.Input): InputId = when (input) {
        is RequestPinMachine.Intent.Sync -> SYNC
        is RequestPinMachine.Intent.Analyse -> ANALYSE
        RequestPinMachine.Intent.Remove -> REMOVE
        is RequestPinMachine.Fact.Initialized -> INITIALIZED
        is RequestPinMachine.Fact.Completed -> COMPLETED
        // Same branch, two inputs: a known failure may be retried on reopen, an unknown one is
        // fenced for good, and only the second can open an unknown outcome.
        is RequestPinMachine.Fact.Failed -> if (input.unknown) FAILED_UNKNOWN else FAILED_KNOWN
        RequestPinMachine.Fact.Restored -> RESTORED
        RequestPinMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: RequestPinMachine.Effect): EffectId = when (effect) {
        is RequestPinMachine.Effect.Analyse -> EffectId("Analyse")
        is RequestPinMachine.Effect.Checkpoint -> EffectId("Checkpoint")
        RequestPinMachine.Effect.DeleteCheckpoint -> EffectId("DeleteCheckpoint")
        is RequestPinMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: RequestPinMachine.State) =
        state.failure == RequestPinMachine.Failure.UNKNOWN_OUTCOME || state.failure == RequestPinMachine.Failure.PERSISTENCE

    override fun rejected(effect: RequestPinMachine.Effect) = effect is RequestPinMachine.Effect.Reject
}
