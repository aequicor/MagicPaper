package io.aequicor.magicpaper.domain.browser

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [BrowserMachine], declared so it can be read without running anything.
 *
 * The machine has a [BrowserMachine.Stage] enum and a derived `stage` in which an unknown outcome
 * outranks the lifecycle, but neither is the position. Acceptance depends on the lifecycle *and* on
 * whether an outcome is unresolved: while one is, a browser that is still `READY` refuses every
 * action that is not a pure observation. So `ready` and `closed` each split in two, and the
 * persistence flag splits by whether a close was already under way, because `Closed` is judged
 * before that flag and is the one input it does not fence.
 *
 * What the declaration cannot express, and leaves to `BrowserMachineTest`: an operation id that is
 * not the pending one or was already used, a fingerprint that is not 64 hex digits, a tab id that is
 * not `tab-N`, and a blank owner. `executing` can also carry an unresolved outcome beside its
 * pending observation; it is labelled `executing`, and `unknown(state)` still reports it.
 */
object BrowserSpace : StateSpace<BrowserMachine.State, BrowserMachine.Input, BrowserMachine.Effect> {
    val NEW = PhaseId("new")
    val READY = PhaseId("ready")
    val EXECUTING = PhaseId("executing")
    val CLOSING = PhaseId("closing")
    val CLOSED = PhaseId("closed")
    val READY_UNKNOWN = PhaseId("ready-unknown-outcome")
    val CLOSED_UNKNOWN = PhaseId("closed-unknown-outcome")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")
    val PERSISTENCE_UNKNOWN_CLOSING = PhaseId("persistence-unknown-closing")

    val START = InputId("Start")
    val PERFORM_ACTION = InputId("PerformAction")
    val PERFORM_OBSERVATION = InputId("PerformObservation")
    val CLOSE = InputId("Close")
    val COMPLETED = InputId("Completed")
    val FAILED_BEFORE_EFFECT = InputId("FailedBeforeEffect")
    val FAILED_AFTER_EFFECT = InputId("FailedAfterEffect")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val CLOSED_FACT = InputId("Closed")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(
        NEW, READY, EXECUTING, CLOSING, CLOSED, READY_UNKNOWN, CLOSED_UNKNOWN, PERSISTENCE_UNKNOWN, PERSISTENCE_UNKNOWN_CLOSING,
    )

    override val inputs = listOf(
        InputSpec(START, Branch.INTENT),
        InputSpec(PERFORM_ACTION, Branch.INTENT),
        InputSpec(PERFORM_OBSERVATION, Branch.INTENT),
        InputSpec(CLOSE, Branch.INTENT),
        InputSpec(COMPLETED, Branch.FACT),
        InputSpec(FAILED_BEFORE_EFFECT, Branch.FACT),
        InputSpec(FAILED_AFTER_EFFECT, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(CLOSED_FACT, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Execute"), EffectId("Release"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. `Close`, `Restored` and `PersistenceUnknown` are
    // judged before the persistence fence and are accepted everywhere; `Closed` is accepted only
    // while a close is under way, fenced or not.
    override val accepts = acceptance(phases, inputs, listOf(
        //                              St Pa Po Cl Co Fb Fa Nm Cd Rs Pu
        /* new                       */ "10010000011",
        /* ready                     */ "01110000011",
        /* executing                 */ "00011111011",
        /* closing                   */ "00010000111",
        /* closed                    */ "00010000011",
        /* ready-unknown-outcome     */ "00110000011",
        /* closed-unknown-outcome    */ "00010000011",
        /* persistence-unknown       */ "00010000011",
        /* persistence-unknown-close */ "00010000111",
    ))

    override fun label(state: BrowserMachine.State): PhaseId? {
        val unresolved = state.unknown.isNotEmpty() || state.cleanupUnknown
        if (state.persistenceUnknown) return if (state.lifecycle == BrowserMachine.Stage.CLOSING) PERSISTENCE_UNKNOWN_CLOSING else PERSISTENCE_UNKNOWN
        return when (state.lifecycle) {
            BrowserMachine.Stage.NEW -> NEW
            BrowserMachine.Stage.READY -> if (unresolved) READY_UNKNOWN else READY
            BrowserMachine.Stage.EXECUTING -> EXECUTING
            BrowserMachine.Stage.CLOSING -> CLOSING
            BrowserMachine.Stage.CLOSED -> if (unresolved) CLOSED_UNKNOWN else CLOSED
            // The machine never stores UNKNOWN as a lifecycle — it is the derived `stage` that holds it.
            BrowserMachine.Stage.UNKNOWN -> null
        }
    }

    override fun name(input: BrowserMachine.Input): InputId = when (input) {
        is BrowserMachine.Intent.Start -> START
        // One intent, two inputs: an unresolved outcome refuses every action except a pure observation.
        is BrowserMachine.Intent.Perform -> if (input.operation.action.observesOnly) PERFORM_OBSERVATION else PERFORM_ACTION
        BrowserMachine.Intent.Close -> CLOSE
        is BrowserMachine.Fact.Completed -> COMPLETED
        // Only a failure after the effect was dispatched can leave an outcome unresolved.
        is BrowserMachine.Fact.Failed -> if (input.beforeEffect) FAILED_BEFORE_EFFECT else FAILED_AFTER_EFFECT
        is BrowserMachine.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        BrowserMachine.Fact.Closed -> CLOSED_FACT
        BrowserMachine.Fact.Restored -> RESTORED
        BrowserMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: BrowserMachine.Effect): EffectId = when (effect) {
        is BrowserMachine.Effect.Execute -> EffectId("Execute")
        BrowserMachine.Effect.Release -> EffectId("Release")
        is BrowserMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: BrowserMachine.State) = state.stage == BrowserMachine.Stage.UNKNOWN

    override fun rejected(effect: BrowserMachine.Effect) = effect is BrowserMachine.Effect.Reject
}
