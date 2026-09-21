package io.aequicor.magicpaper.machine.render

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.PhaseId

/** One cell of a machine's table: what this input does from this position. */
data class Cell(
    val from: PhaseId,
    val input: InputId,
    val to: PhaseId?,
    val effects: List<EffectId>,
    val rejected: Boolean,
)

/**
 * A machine's whole table with its type parameters erased.
 *
 * Erasure is not tidiness, it is the only way to hold several machines at once: `Machine<S, I, E>`
 * is invariant because `S` stands in both a parameter and a return, so a `List<Machine<*, *, *>>`
 * cannot be stepped. [project] resolves each machine while its types are still known and hands
 * back something a catalogue can keep.
 */
data class MachineProjection(
    val id: MachineId,
    val phases: List<PhaseId>,
    val inputs: List<InputSpec>,
    val effects: List<EffectId>,
    val cells: List<Cell>,
    val unknownPhases: Set<PhaseId>,
)

/**
 * Resolves a declared space into a table by running the machine over the representatives.
 *
 * Nothing here is declared twice: the api names the positions and says which inputs they accept,
 * and every target and every effect below is computed from the pure reducer. This is where the
 * approach differs from a lambda-based statechart, whose own exporter cannot see through a guard.
 */
fun <S : Any, I : Any, E : Any> project(
    machine: Machine<S, I, E>,
    states: Map<PhaseId, S>,
    inputs: Map<InputId, I>,
): MachineProjection {
    val space = machine.space
    val cells = space.phases.flatMap { phase ->
        val state = states.getValue(phase)
        space.inputs.map { spec ->
            val step = machine.step(state, inputs.getValue(spec.id))
            val rejected = step.effects.any { space.rejected(it) }
            Cell(
                from = phase,
                input = spec.id,
                to = if (rejected) null else space.label(step.state),
                effects = step.effects.map { space.name(it) },
                rejected = rejected,
            )
        }
    }
    return MachineProjection(
        id = machine.id,
        phases = space.phases,
        inputs = space.inputs,
        effects = space.effects,
        cells = cells,
        unknownPhases = space.phases.filter { space.unknown(states.getValue(it)) }.toSet(),
    )
}

/** Inputs an executor may send, separated from those only the outside world sends. */
fun MachineProjection.inputs(branch: Branch) = inputs.filter { it.branch == branch }.map { it.id }
