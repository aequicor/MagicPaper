package io.aequicor.magicpaper.machine.render

import io.aequicor.magicpaper.machine.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A two-position machine, small enough that the whole rendered page can be read in the test. */
private object Gate : Machine<Gate.State, Gate.Input, Gate.Effect> {
    data class State internal constructor(val open: Boolean = false, val stuck: Boolean = false)
    sealed interface Input
    sealed interface Intent : Input { data object Open : Intent; data object Close : Intent }
    sealed interface Fact : Input { data object Jammed : Fact }
    sealed interface Effect {
        data class Reject(val reason: String) : Effect
        data object Swing : Effect
    }

    override val id = MachineId("gate")
    override val space get() = GateSpace
    override fun step(state: State, input: Input): Step<State, Effect> = when {
        state.stuck -> Step(state, listOf(Effect.Reject("Заклинило")))
        input is Intent.Open && !state.open -> Step(state.copy(open = true), listOf(Effect.Swing))
        input is Intent.Close && state.open -> Step(state.copy(open = false), listOf(Effect.Swing))
        input is Fact.Jammed -> Step(state.copy(stuck = true))
        else -> Step(state, listOf(Effect.Reject("Уже в этом положении")))
    }
}

private object GateSpace : StateSpace<Gate.State, Gate.Input, Gate.Effect> {
    val SHUT = PhaseId("shut")
    val OPEN = PhaseId("open")
    val STUCK = PhaseId("stuck")
    val OPEN_IN = InputId("Open")
    val CLOSE_IN = InputId("Close")
    val JAMMED = InputId("Jammed")

    override val phases = listOf(SHUT, OPEN, STUCK)
    override val inputs = listOf(
        InputSpec(OPEN_IN, Branch.INTENT), InputSpec(CLOSE_IN, Branch.INTENT), InputSpec(JAMMED, Branch.FACT),
    )
    override val effects = listOf(EffectId("Reject"), EffectId("Swing"))
    override val accepts = acceptance(phases, inputs, listOf("101", "011", "000"))
    override fun label(state: Gate.State) = when {
        state.stuck -> STUCK
        state.open -> OPEN
        else -> SHUT
    }
    override fun name(input: Gate.Input) = when (input) {
        Gate.Intent.Open -> OPEN_IN
        Gate.Intent.Close -> CLOSE_IN
        Gate.Fact.Jammed -> JAMMED
    }
    override fun name(effect: Gate.Effect) = when (effect) {
        is Gate.Effect.Reject -> EffectId("Reject")
        Gate.Effect.Swing -> EffectId("Swing")
    }
    override fun unknown(state: Gate.State) = state.stuck
    override fun rejected(effect: Gate.Effect) = effect is Gate.Effect.Reject
}

class RenderTest {
    private val shut = Gate.State()
    private val states = mapOf(
        GateSpace.SHUT to shut,
        GateSpace.OPEN to Gate.step(shut, Gate.Intent.Open).state,
        GateSpace.STUCK to Gate.step(shut, Gate.Fact.Jammed).state,
    )
    private val inputs = mapOf<InputId, Gate.Input>(
        GateSpace.OPEN_IN to Gate.Intent.Open,
        GateSpace.CLOSE_IN to Gate.Intent.Close,
        GateSpace.JAMMED to Gate.Fact.Jammed,
    )
    private val projection = project(Gate, states, inputs)

    @Test fun projectionResolvesEveryCellByRunningTheReducer() {
        assertEquals(9, projection.cells.size)
        val opened = projection.cells.single { it.from == GateSpace.SHUT && it.input == GateSpace.OPEN_IN }
        assertEquals(GateSpace.OPEN, opened.to)
        assertEquals(listOf(EffectId("Swing")), opened.effects)
        // A refused cell names no target: the machine did not move, so the diagram draws no edge.
        val refused = projection.cells.single { it.from == GateSpace.OPEN && it.input == GateSpace.OPEN_IN }
        assertTrue(refused.rejected && refused.to == null)
        assertEquals(setOf(GateSpace.STUCK), projection.unknownPhases)
        assertEquals(listOf(GateSpace.JAMMED), projection.inputs(Branch.FACT))
    }

    @Test fun mermaidDrawsOnlyTransitionsTheReducerActuallyMade() {
        val diagram = projection.toMermaid()
        assertEquals(
            """
            stateDiagram-v2
                %% gate
                [*] --> shut
                shut --> open : Open / Swing
                shut --> stuck : Jammed
                open --> shut : Close / Swing
                open --> stuck : Jammed
                stuck : неизвестный исход
            """.trimIndent() + "\n",
            diagram,
        )
        assertTrue(projection.toMermaid(showRefusals = true).contains("open --> open : Open ✗"))
    }

    @Test fun markdownCarriesTheDeclaredMatrixBesideTheDiagram() {
        val page = projection.toMarkdown()
        assertTrue(page.contains("| позиция | Open | Close | Jammed |"), page)
        assertTrue(page.contains("| shut | open | · | stuck |"), page)
        assertTrue(page.contains("| stuck ⚠ | · | · | · |"), page)
    }
}
