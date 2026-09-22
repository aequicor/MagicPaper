package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mapping is what every owner relies on to reconstruct a machine's phase and transitions from
 * logs alone, so it is checked as a pure function rather than through the process-wide [io.aequicor.magicpaper.logging.AppLog] sink.
 */
class MachineTransitionLogTest {
    private data class State(val phase: String)
    private sealed interface Input { data object Go : Input; data object Stay : Input }
    private sealed interface Effect { data object Reject : Effect; data object Notify : Effect }

    private val space = object : StateSpace<State, Input, Effect> {
        override val phases = listOf(PhaseId("idle"), PhaseId("running"))
        override val inputs = listOf(InputSpec(InputId("Go"), Branch.INTENT), InputSpec(InputId("Stay"), Branch.INTENT))
        override val effects = listOf(EffectId("Reject"), EffectId("Notify"))
        override val accepts = emptyMap<InputId, Set<PhaseId>>()
        override fun label(state: State) = if (state.phase == "unknown") null else PhaseId(state.phase)
        override fun name(input: Input) = when (input) { Input.Go -> InputId("Go"); Input.Stay -> InputId("Stay") }
        override fun name(effect: Effect) = when (effect) { Effect.Reject -> EffectId("Reject"); Effect.Notify -> EffectId("Notify") }
        override fun unknown(state: State) = false
        override fun rejected(effect: Effect) = effect == Effect.Reject
    }

    @Test fun anAcceptedTransitionNamesBothPhasesTheInputAndEveryEffect() {
        val fields = MachineTransitionLog.fields(space, State("idle"), Input.Go, State("running"), listOf(Effect.Notify))
        assertEquals(mapOf("from" to "idle", "to" to "running", "action" to "Go", "result" to "Notify", "outcome" to "accepted"), fields)
    }

    @Test fun aRejectedTransitionIsMarkedRejectedEvenWhenOtherEffectsAlsoFired() {
        val fields = MachineTransitionLog.fields(space, State("idle"), Input.Go, State("idle"), listOf(Effect.Notify, Effect.Reject))
        assertEquals("Notify+Reject", fields.getValue("result"))
        assertEquals("rejected", fields.getValue("outcome"))
    }

    @Test fun noEffectsIsRecordedExplicitlyRatherThanAsAnEmptyValue() {
        val fields = MachineTransitionLog.fields(space, State("idle"), Input.Stay, State("idle"), emptyList())
        assertEquals("none", fields.getValue("result"))
        assertEquals("accepted", fields.getValue("outcome"))
    }

    @Test fun aStateOutsideTheDeclaredSpaceIsLoggedAsUnknownRatherThanThrowing() {
        val fields = MachineTransitionLog.fields(space, State("unknown"), Input.Go, State("running"), emptyList())
        assertEquals("unknown", fields.getValue("from"))
        assertEquals("running", fields.getValue("to"))
    }
}
