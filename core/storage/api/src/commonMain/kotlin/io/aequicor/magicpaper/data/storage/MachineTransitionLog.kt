package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.StateSpace

/**
 * The one place a machine's transition becomes a log line.
 *
 * Every owner's `reduce` keeps its own `Transition`/reject shape — that stays theirs, per
 * `Machine.step`'s own KDoc — but every owner's [StateSpace] already names positions, inputs and
 * effects for exactly this purpose. Without this, a state machine's phase and its transitions can
 * only be recovered by replaying the durable journal; this makes them visible in the log stream
 * itself, at the point an owner's write path commits a transition, whether from a live [append][
 * record] or while reconstructing state after a crash ([replay][record]).
 *
 * Lives beside [MachineJournal] rather than in `:core:state-machine:impl`, for the reason that
 * module's own build file gives: it has to be reachable from every owner's `impl`, which may not
 * depend on another `impl`. This module already carries both the machine contract and the logging
 * facade.
 */
object MachineTransitionLog {
    /** `DEBUG`, per AGENTS.md's logging table: "relevant state transitions" are a `DEBUG` record, not `INFO`. */

    /** A transition observed live, about to be (or just) written to the journal. */
    fun <S : Any, I : Any, E : Any> append(machine: MachineId, space: StateSpace<S, I, E>, before: S, input: I, after: S, effects: List<E>) =
        AppLog.debug(machine.name, "transition", fields(space, before, input, after, effects))

    /** A transition replayed from the journal while reconstructing state, e.g. after a crash. */
    fun <S : Any, I : Any, E : Any> replay(machine: MachineId, space: StateSpace<S, I, E>, before: S, input: I, after: S, effects: List<E>) =
        AppLog.debug(machine.name, "replayed_transition", fields(space, before, input, after, effects))

    /** Pure so the mapping is checked without going through the process-wide [AppLog] sink. */
    fun <S : Any, I : Any, E : Any> fields(space: StateSpace<S, I, E>, before: S, input: I, after: S, effects: List<E>): Map<String, String> = mapOf(
        "from" to (space.label(before)?.name ?: "unknown"),
        "to" to (space.label(after)?.name ?: "unknown"),
        "action" to space.name(input).name,
        "result" to if (effects.isEmpty()) "none" else effects.joinToString("+") { space.name(it).name },
        "outcome" to if (effects.any(space::rejected)) "rejected" else "accepted",
    )
}
