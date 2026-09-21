package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [UsageMachine], declared so it can be read without running anything.
 *
 * Acceptance depends on two flags: whether the archive was loaded, and whether persistence is
 * unconfirmed, which fences everything but its own fact. A loaded archive is one position however
 * many records it holds. `Import` and `Clear` are judged before initialization is checked, so they
 * are accepted from a store that was never loaded and start a new generation there too.
 *
 * The three observations — `Recorded`, `ContextObserved` and `CumulativeObserved` — are accepted only
 * at a loaded archive, and only when the observation names the current generation. A stale
 * generation is identity, not position: `Clear` and `Import` each start a new one, and an observation
 * captured before that is refused. That refusal, the identity of a record or a counter, the
 * replacement of a pending record, and the validity of an archive, a record, a token count or a
 * context size all stay in `UsageMachineTest`. The representatives are loaded at generation `g1`.
 *
 * This owner has no effects of its own. The one effect here, `Reject`, is [UsageMachine.step]'s
 * view of the message `Transition.rejection` already carried.
 */
object UsageSpace : StateSpace<UsageMachine.State, UsageMachine.Input, UsageMachine.Effect> {
    val NEW = PhaseId("new")
    val LOADED = PhaseId("loaded")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val IMPORT = InputId("Import")
    val CLEAR = InputId("Clear")
    val INITIALIZED = InputId("Initialized")
    val RECORDED = InputId("Recorded")
    val CONTEXT_OBSERVED = InputId("ContextObserved")
    val CUMULATIVE_OBSERVED = InputId("CumulativeObserved")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")

    override val phases = listOf(NEW, LOADED, PERSISTENCE_UNKNOWN)

    override val inputs = listOf(
        InputSpec(IMPORT, Branch.INTENT),
        InputSpec(CLEAR, Branch.INTENT),
        InputSpec(INITIALIZED, Branch.FACT),
        InputSpec(RECORDED, Branch.FACT),
        InputSpec(CONTEXT_OBSERVED, Branch.FACT),
        InputSpec(CUMULATIVE_OBSERVED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
    )

    override val effects = listOf(EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs`.
    override val accepts = acceptance(phases, inputs, listOf(
        //                              Im Cl In Rc Cx Cu Pu
        /* new                      */ "1110001",
        /* loaded                   */ "1101111",
        /* persistence-unknown      */ "0000001",
    ))

    override fun label(state: UsageMachine.State): PhaseId = when {
        state.persistenceUnknown -> PERSISTENCE_UNKNOWN
        !state.initialized -> NEW
        else -> LOADED
    }

    override fun name(input: UsageMachine.Input): InputId = when (input) {
        is UsageMachine.Intent.Import -> IMPORT
        is UsageMachine.Intent.Clear -> CLEAR
        is UsageMachine.Fact.Initialized -> INITIALIZED
        is UsageMachine.Fact.Recorded -> RECORDED
        is UsageMachine.Fact.ContextObserved -> CONTEXT_OBSERVED
        is UsageMachine.Fact.CumulativeObserved -> CUMULATIVE_OBSERVED
        is UsageMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
    }

    override fun name(effect: UsageMachine.Effect): EffectId = when (effect) {
        is UsageMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: UsageMachine.State) = state.persistenceUnknown

    override fun rejected(effect: UsageMachine.Effect) = effect is UsageMachine.Effect.Reject
}
