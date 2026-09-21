package io.aequicor.magicpaper.machine

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives every declared position against every declared input and holds the declaration to it.
 *
 * This replaces the table test each owner grew by hand, and adds the two properties those tables
 * could not state. A hand-written table proves the cells it lists; it cannot prove that no input
 * was forgotten, and it cannot prove that a transition never leaves the set of states the api
 * names. Both are checked here.
 *
 * [states] and [inputs] are the representatives. They live in the owner's test source set rather
 * than in the api, so a shipped binary — including the browser bundle a shared module has to
 * carry — holds no fixtures. The api still names every position; only the example values are here.
 *
 * Nothing is started and nothing is stored: the machine is a pure function, so this needs no impl,
 * no journal and no process.
 */
fun <S : Any, I : Any, E : Any> verifyStateSpace(
    machine: Machine<S, I, E>,
    states: Map<PhaseId, S>,
    inputs: Map<InputId, I>,
) {
    val space = machine.space
    val owner = machine.id.name
    val phases = space.phases
    val specs = space.inputs

    assertTrue(phases.isNotEmpty(), "$owner: пространство состояний пусто")
    assertTrue(specs.isNotEmpty(), "$owner: не объявлен ни один вход")
    assertEquals(phases.size, phases.toSet().size, "$owner: повторяющиеся имена позиций")
    assertEquals(specs.size, specs.map { it.id }.toSet().size, "$owner: повторяющиеся имена входов")
    assertEquals(space.effects.size, space.effects.toSet().size, "$owner: повторяющиеся имена эффектов")

    assertEquals(specs.map { it.id }.toSet(), space.accepts.keys,
        "$owner: матрица принятия описывает не тот набор входов, что объявлен")
    space.accepts.forEach { (input, accepted) ->
        val stray = accepted - phases.toSet()
        assertTrue(stray.isEmpty(), "$owner: матрица для ${input.name} называет неизвестные позиции $stray")
    }

    // The representatives must cover the declaration exactly: a position nobody can build is a
    // name without a state, and a state nobody declared is a position the matrix never sees.
    assertEquals(phases.toSet(), states.keys, "$owner: представители не покрывают объявленные позиции")
    assertEquals(specs.map { it.id }.toSet(), inputs.keys, "$owner: представители не покрывают объявленные входы")

    // Injectivity. This is the first guard against a label so coarse it proves nothing: collapse
    // two positions onto one name and this fails immediately.
    phases.forEach { phase ->
        assertEquals(phase, space.label(states.getValue(phase)),
            "$owner: представитель позиции ${phase.name} не опознаётся собственной label")
    }
    specs.forEach { spec ->
        assertEquals(spec.id, space.name(inputs.getValue(spec.id)),
            "$owner: представитель входа ${spec.id.name} называет себя иначе")
    }

    val reached = mutableSetOf<PhaseId>()
    phases.forEach { phase ->
        val state = states.getValue(phase)
        specs.forEach { spec ->
            val input = inputs.getValue(spec.id)
            val accepted = phase in space.accepts.getValue(spec.id)
            val step = machine.step(state, input)
            val rejected = step.effects.any { space.rejected(it) }

            assertEquals(!accepted, rejected,
                "$owner: ${phase.name} × ${spec.id.name} — объявлено accepted=$accepted, получено rejected=$rejected")

            step.effects.forEach { effect ->
                assertTrue(space.name(effect) in space.effects,
                    "$owner: эффект ${space.name(effect).name} не объявлен в space.effects")
            }

            if (rejected) {
                assertEquals(state, step.state,
                    "$owner: отказ на ${phase.name} × ${spec.id.name} изменил состояние")
                return@forEach
            }

            // Closure: an accepted transition must land on a position the api names. This is the
            // property that makes "read the api and you have the whole state space" checkable.
            val target = space.label(step.state)
                ?: fail("$owner: ${phase.name} × ${spec.id.name} привёл в состояние, которого нет в " +
                    "объявленном пространстве. Либо позиция не объявлена, либо label слишком тонкая.")
            assertTrue(target in phases, "$owner: label вернула необъявленное имя ${target.name}")
            reached += target
        }
    }

    // Decision 17: a machine with no unknown outcome will answer confidently after a crash about
    // an effect whose result nobody observed.
    assertTrue(phases.any { space.unknown(states.getValue(it)) },
        "$owner: не объявлено ни одной позиции неизвестного исхода")

    val unreachable = phases.drop(1).filterNot { it in reached }
    assertTrue(unreachable.isEmpty(),
        "$owner: объявленные, но недостижимые позиции: ${unreachable.map { it.name }}")
}
