package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [DraftMachine], declared so it can be read without running anything.
 *
 * The draft state has no phase field — it is a product of flags — so the positions are named here
 * and [label] says which of them a state stands at. It names nine, where the machine's table test
 * listed eleven states: `edited` and `writing` differ only by a spent revision, and a second
 * `saved` differed only by its version. A position is not a payload.
 *
 * What the declaration cannot express: acceptance of [CLEAR] depends on the version the input
 * carries matching the one the state carries, and no finite set of positions can decide that. The
 * matrix therefore speaks about the representatives; the identity refusals keep their own test,
 * `DraftMachineTest.anotherWriterOfTheSameKeyIsRefusedByGenerationAndByReset`.
 */
object DraftSpace : StateSpace<DraftMachine.State, DraftMachine.Input, DraftMachine.Effect> {
    val FRESH = PhaseId("fresh")
    val FRESH_EDITED = PhaseId("fresh-edited")
    val OPENED = PhaseId("opened")
    val LOADED = PhaseId("loaded")
    val EDITING = PhaseId("editing")
    val SAVED = PhaseId("saved")
    val FAILED = PhaseId("write-failed")
    val CLEANUP_UNKNOWN = PhaseId("cleanup-unknown")
    val REVOKED = PhaseId("revoked")

    val EDIT = InputId("Edit")
    val PERSIST = InputId("Persist")
    val CLEAR = InputId("Clear")
    val CLEANUP = InputId("Cleanup")
    val REVOKE = InputId("Revoke")
    val RESET_EPOCH = InputId("ResetEpochObserved")
    val RESTORED = InputId("Restored")
    val WRITTEN = InputId("Written")
    val CLEARED = InputId("Cleared")
    val FAILED_PLAIN = InputId("Failed")
    val FAILED_COMMITTED = InputId("FailedCommitted")
    val CLEANUP_RETRIED = InputId("CleanupRetried")

    override val phases = listOf(FRESH, FRESH_EDITED, OPENED, LOADED, EDITING, SAVED, FAILED, CLEANUP_UNKNOWN, REVOKED)

    override val inputs = listOf(
        InputSpec(EDIT, Branch.INTENT),
        InputSpec(PERSIST, Branch.INTENT),
        InputSpec(CLEAR, Branch.INTENT),
        InputSpec(CLEANUP, Branch.INTENT),
        InputSpec(REVOKE, Branch.INTENT),
        InputSpec(RESET_EPOCH, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(WRITTEN, Branch.FACT),
        InputSpec(CLEARED, Branch.FACT),
        InputSpec(FAILED_PLAIN, Branch.FACT),
        InputSpec(FAILED_COMMITTED, Branch.FACT),
        InputSpec(CLEANUP_RETRIED, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("Reject"), EffectId("Schedule"), EffectId("Write"),
        EffectId("Delete"), EffectId("RetryCleanup"),
    )

    // Rows follow `phases`, columns follow `inputs`. A stale writer, a foreign reset epoch and a
    // version that does not match are refusals of identity, not of position; they stay in tests.
    override val accepts = acceptance(phases, inputs, listOf(
        //                Ed Pe Cl Cu Rv RE Rs Wr Cd Fp Fc Cr
        /* fresh        */ "100111011111",
        /* fresh-edited */ "100111011111",
        /* opened       */ "100110111111",
        /* loaded       */ "110110111111",
        /* editing      */ "111110111111",
        /* saved        */ "111110111111",
        /* write-failed */ "111110111111",
        /* cleanup-unk  */ "111110111111",
        /* revoked      */ "010110111111",
    ))

    override fun label(state: DraftMachine.State): PhaseId = when {
        state.revoked -> REVOKED
        // The unknown outcome outranks the notice: a committed record whose cleanup was never
        // acknowledged is a position of its own, and the next keystroke must not clear it.
        state.cleanup != null -> CLEANUP_UNKNOWN
        state.failure != null -> FAILED
        state.resetEpoch == null -> if (state.version > 0) FRESH_EDITED else FRESH
        !state.loaded -> OPENED
        state.saving -> EDITING
        // A restored draft nobody has edited yet still accepts no cleanup of version one.
        state.version > 0 -> SAVED
        else -> LOADED
    }

    override fun name(input: DraftMachine.Input): InputId = when (input) {
        is DraftMachine.Intent.Edit -> EDIT
        is DraftMachine.Intent.Persist -> PERSIST
        is DraftMachine.Intent.Clear -> CLEAR
        is DraftMachine.Intent.Cleanup -> CLEANUP
        DraftMachine.Intent.Revoke -> REVOKE
        is DraftMachine.Fact.ResetEpochObserved -> RESET_EPOCH
        is DraftMachine.Fact.Restored -> RESTORED
        is DraftMachine.Fact.Written -> WRITTEN
        is DraftMachine.Fact.Cleared -> CLEARED
        is DraftMachine.Fact.CleanupRetried -> CLEANUP_RETRIED
        // Both representatives are the same branch; the committed flag is what splits them, and
        // only the committed one can open an unknown outcome.
        is DraftMachine.Fact.Failed -> if (input.failure.committed) FAILED_COMMITTED else FAILED_PLAIN
    }

    override fun name(effect: DraftMachine.Effect): EffectId = when (effect) {
        is DraftMachine.Effect.Reject -> EffectId("Reject")
        is DraftMachine.Effect.Schedule -> EffectId("Schedule")
        is DraftMachine.Effect.Write -> EffectId("Write")
        is DraftMachine.Effect.Delete -> EffectId("Delete")
        DraftMachine.Effect.RetryCleanup -> EffectId("RetryCleanup")
    }

    override fun unknown(state: DraftMachine.State) = state.unknown

    override fun rejected(effect: DraftMachine.Effect) = effect is DraftMachine.Effect.Reject
}
