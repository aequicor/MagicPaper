package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [PluginMachine], declared so it can be read without running anything.
 *
 * Acceptance depends on two flags only: whether the preferences were loaded, and whether persistence
 * is unconfirmed, which fences everything but its own fact. `missingPlugin` is what the interface
 * shows when a plugin's owner is gone; no transition reads it, and every intent clears it. It is a
 * position only so that the fact that sets it has somewhere to land, and it accepts exactly what
 * `ready` accepts.
 *
 * What the declaration cannot express, and leaves to `PluginMachineTest`: an import with a blank or
 * repeated plugin id, and a toggle of a blank id. Those are refusals of payload, not of position.
 */
object PluginSpace : StateSpace<PluginMachine.State, PluginMachine.Input, PluginMachine.Effect> {
    val NEW = PhaseId("new")
    val READY = PhaseId("ready")
    val READY_PLUGIN_MISSING = PhaseId("ready-plugin-missing")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val TOGGLE = InputId("Toggle")
    val IMPORT = InputId("Import")
    val CLEAR = InputId("Clear")
    val INITIALIZED = InputId("Initialized")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(NEW, READY, READY_PLUGIN_MISSING, PERSISTENCE_UNKNOWN)

    override val inputs = listOf(
        InputSpec(TOGGLE, Branch.INTENT),
        InputSpec(IMPORT, Branch.INTENT),
        InputSpec(CLEAR, Branch.INTENT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. Nothing but `Initialized` and its own fact is
    // accepted before the preferences are loaded, and `Initialized` only once.
    override val accepts = acceptance(phases, inputs, listOf(
        //                              To Im Cl In Nm Pu
        /* new                      */ "000101",
        /* ready                    */ "111011",
        /* ready-plugin-missing     */ "111011",
        /* persistence-unknown      */ "000001",
    ))

    override fun label(state: PluginMachine.State): PhaseId = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        !state.initialized -> NEW
        state.missingPlugin != null -> READY_PLUGIN_MISSING
        else -> READY
    }

    override fun name(input: PluginMachine.Input): InputId = when (input) {
        is PluginMachine.Intent.Toggle -> TOGGLE
        is PluginMachine.Intent.Import -> IMPORT
        PluginMachine.Intent.Clear -> CLEAR
        is PluginMachine.Fact.Initialized -> INITIALIZED
        is PluginMachine.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        PluginMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: PluginMachine.Effect): EffectId = when (effect) {
        is PluginMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: PluginMachine.State) = state.persistenceUnknown

    override fun rejected(effect: PluginMachine.Effect) = effect is PluginMachine.Effect.Reject
}
