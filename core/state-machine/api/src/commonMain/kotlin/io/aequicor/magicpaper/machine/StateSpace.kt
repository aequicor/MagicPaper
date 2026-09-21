package io.aequicor.magicpaper.machine

/**
 * What a machine declares about its own state space, so that reading the api is enough to recover
 * every position a feature can stand in.
 *
 * Only the alphabet is declared here — the names of the positions, the inputs and the effects, the
 * acceptance matrix, and the three predicates that give those names meaning. The representative
 * values that stand at each position live in the owner's test source set, because a fixture is not
 * something a shipped binary should carry into the browser bundle. Everything else — the target of
 * a transition, the effects it emits, the edges of a diagram — is derived by running the machine
 * over those representatives: a declared target column would be a second source of truth and would
 * drift from the code it claims to describe.
 *
 * [label] is the one genuinely new thing an owner writes, and it is the declaration that matters.
 */
interface StateSpace<S : Any, I : Any, E : Any> {
    /** Every named position. The first is conventionally where the machine starts. */
    val phases: List<PhaseId>

    /** Every input kind the machine accepts, with the branch that is allowed to send it. */
    val inputs: List<InputSpec>

    /** Every effect the machine can emit, rejection included. */
    val effects: List<EffectId>

    /**
     * Which positions do not refuse this input.
     *
     * This is the acceptance matrix owners already write in their table tests, moved to where the
     * api can be read without a test runner. It is keyed by position, so each position must have
     * exactly one representative: a machine therefore declares fewer positions than its old test
     * listed states, because those tests also listed states that differ only in payload.
     */
    val accepts: Map<InputId, Set<PhaseId>>

    /**
     * Which declared position this state stands at, or null when the space does not name it.
     *
     * A declared abstraction, not a bisimulation, and the difference bounds what can be proved. A
     * state carrying a counter has an infinite space, and an input that compares itself against
     * that counter — `Intent.Clear(generation, expectedVersion)` against `state.version` — cannot
     * have acceptance be a function of any finite abstraction. So the harness proves the space is
     * *closed*: no accepted transition leaves the set of positions the api names. That is the
     * property hand-written tables cannot check at all, and it is what makes the api a description
     * of the whole state space rather than a sample of it.
     *
     * It must be written from the fields of [S] alone. A `label` that consults anything else stops
     * being a statement about the machine.
     */
    fun label(state: S): PhaseId?

    /** Total by construction: an exhaustive `when`, so a new input branch fails to compile. */
    fun name(input: I): InputId

    /** Total for the same reason. */
    fun name(effect: E): EffectId

    /** An outcome that was requested and never confirmed. It outranks every other resume check. */
    fun unknown(state: S): Boolean

    /**
     * Owners spell refusal differently — `Reject(reason: String)` in eight machines,
     * `Reject(reason: Reason)` in another, and a `Reject` outside the effect hierarchy entirely in
     * `SessionOrganismMachine` — so it is a predicate on the value, never a shared type.
     */
    fun rejected(effect: E): Boolean
}

/**
 * Builds an acceptance matrix from one row of flags per position, in the order owners already
 * write it: `"10011101111"` reads as position row against input column, `'1'` meaning accepted.
 */
fun acceptance(
    phases: List<PhaseId>,
    inputs: List<InputSpec>,
    rows: List<String>,
): Map<InputId, Set<PhaseId>> {
    require(rows.size == phases.size) { "Строк в матрице ${rows.size}, а позиций ${phases.size}" }
    rows.forEachIndexed { index, row ->
        require(row.length == inputs.size) { "Строка $index длиной ${row.length}, а входов ${inputs.size}" }
        require(row.all { it == '0' || it == '1' }) { "Строка $index содержит не только 0 и 1" }
    }
    return inputs.mapIndexed { column, input ->
        input.id to phases.filterIndexed { row, _ -> rows[row][column] == '1' }.toSet()
    }.toMap()
}
