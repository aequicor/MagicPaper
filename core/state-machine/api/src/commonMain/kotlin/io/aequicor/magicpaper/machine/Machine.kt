package io.aequicor.magicpaper.machine

import kotlinx.serialization.Serializable

/**
 * The shared shape of a state-owning module's machine.
 *
 * Every owner already wrote this by hand: an immutable state built only by the reducer, one input
 * hierarchy split into intents and facts, effects that are values, and a pure reducer. The type
 * exists so the declaration can be read by something other than the owner — the transition
 * harness, the state-space document — not to hand owners a base class to inherit behaviour from.
 *
 * Adopting it costs three lines and touches nothing else. [step] bridges to the owner's own
 * `reduce`, whose nested `Transition` type, name and every call site stay exactly as they are:
 *
 * ```
 * object CommandCheckMachine : Machine<CommandCheckMachine.State, CommandCheckMachine.Input, CommandCheckMachine.Effect> {
 *     override val id = MachineId("command-check")
 *     override val space get() = CommandCheckSpace
 *     override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }
 *     // State, Input, Effect, Transition and reduce are untouched.
 * }
 * ```
 *
 * `initial` is deliberately absent: owners parameterize it differently — `initial()`,
 * `initial(generation)`, `initial(journalId, visitId, route, welcomeRequired)` — and it creates a
 * value rather than deciding a transition.
 */
interface Machine<S : Any, I : Any, E : Any> {
    val id: MachineId

    /**
     * Pure: ids, time and randomness arrive as input values and are never read here.
     *
     * Named [step] rather than `reduce` because `Transition` and `reduce` are already taken inside
     * every owner, and reusing the names would force nineteen machines to spell out qualified
     * types in their overrides for no gain.
     */
    fun step(state: S, input: I): Step<S, E>

    /** What this machine declares about its own state space. */
    val space: StateSpace<S, I, E>
}

/** The state after a transition and the effects the executor owes. Rejection is one of them. */
data class Step<out S : Any, out E : Any>(val state: S, val effects: List<E> = emptyList())

/**
 * Which machine. One per state-owning module; a module without state declares none.
 *
 * Always a literal. Wasm omits an enclosing interface from a nested type's name, so deriving an
 * identifier from `this::class.simpleName` would name nineteen machines `Input`, `State` and
 * `Effect` — the same trap `app/.../di/FeatureFactoryQualifiers.kt` documents for Koin qualifiers.
 */
@Serializable
data class MachineId(val name: String)

/**
 * A named position in the declared state space.
 *
 * Seven machines have a `Phase` enum and can map one to one; the other twelve distinguish a
 * position by a combination of fields, so the name is declared rather than derived from a type.
 */
@Serializable
data class PhaseId(val name: String)

/** The name of one input branch, as the owner's sealed hierarchy spells it. Literal, as above. */
@Serializable
data class InputId(val name: String)

/** The name of one effect branch. Literal, as above. */
@Serializable
data class EffectId(val name: String)

/**
 * Who may produce an input.
 *
 * The split carries ownership: without it a neighbour could send a fact — "the turn finished" —
 * that only the executor has the right to produce.
 */
enum class Branch { INTENT, FACT }

/** One declared input: its name and who is allowed to send it. The value lives in the tests. */
@Serializable
data class InputSpec(val id: InputId, val branch: Branch)
