package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [SkillMachine], declared so it can be read without running anything.
 *
 * Almost all of what this machine refuses is optimistic concurrency: every intent carries the
 * generation, revision, reference or name version it saw, and is refused when the catalog has moved.
 * That is identity, not position, and it stays with `SkillMachineTest`. The positions are only what
 * the reducer gates on before it looks at an identity: whether the library was loaded, whether
 * persistence is unconfirmed (which fences everything but its own fact), and whether the catalog
 * holds a skill, which decides `SetEnabled` and `Delete`, since each needs a reference to one.
 *
 * What the declaration cannot express, and leaves to `SkillMachineTest`: a stale generation, revision
 * or reference, a skill whose source differs from the one holding its name, an identifier that was
 * retired, a blank name or instruction, a negative timestamp, several skills under one name, and the
 * ceiling on catalog revisions (`Long.MAX_VALUE` changes are not reachable from a representative).
 * The representatives all live at generation `g1` and revision 1, so the one `Install`, `Import` and
 * `Clear` below carry expectations that fit both positions that accept them.
 */
object SkillSpace : StateSpace<SkillMachine.State, SkillMachine.Input, SkillMachine.Effect> {
    val NEW = PhaseId("new")
    val EMPTY = PhaseId("empty")
    val POPULATED = PhaseId("populated")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val INSTALL = InputId("Install")
    val SET_ENABLED = InputId("SetEnabled")
    val DELETE = InputId("Delete")
    val IMPORT = InputId("Import")
    val CLEAR = InputId("Clear")
    val INITIALIZED = InputId("Initialized")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(NEW, EMPTY, POPULATED, PERSISTENCE_UNKNOWN)

    override val inputs = listOf(
        InputSpec(INSTALL, Branch.INTENT),
        InputSpec(SET_ENABLED, Branch.INTENT),
        InputSpec(DELETE, Branch.INTENT),
        InputSpec(IMPORT, Branch.INTENT),
        InputSpec(CLEAR, Branch.INTENT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`. `SetEnabled` and `Delete` name a skill, so an
    // empty catalog refuses them; `Initialized` is accepted once.
    override val accepts = acceptance(phases, inputs, listOf(
        //                              In Se De Im Cl Ini Pu
        /* new                      */ "0000011",
        /* empty                    */ "1001101",
        /* populated                */ "1111101",
        /* persistence-unknown      */ "0000001",
    ))

    override fun label(state: SkillMachine.State): PhaseId = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        !state.initialized -> NEW
        state.skills.isEmpty() -> EMPTY
        else -> POPULATED
    }

    override fun name(input: SkillMachine.Input): InputId = when (input) {
        is SkillMachine.Intent.Install -> INSTALL
        is SkillMachine.Intent.SetEnabled -> SET_ENABLED
        is SkillMachine.Intent.Delete -> DELETE
        is SkillMachine.Intent.Import -> IMPORT
        is SkillMachine.Intent.Clear -> CLEAR
        is SkillMachine.Fact.Initialized -> INITIALIZED
        SkillMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: SkillMachine.Effect): EffectId = when (effect) {
        is SkillMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: SkillMachine.State) = state.persistenceUnknown

    override fun rejected(effect: SkillMachine.Effect) = effect is SkillMachine.Effect.Reject
}
