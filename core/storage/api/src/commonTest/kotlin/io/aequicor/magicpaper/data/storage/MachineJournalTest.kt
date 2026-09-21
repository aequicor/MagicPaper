package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.Step
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives [MachineJournal] over a machine small enough that the journal is the only thing under
 * test. The owners' own journal tests keep proving their machines; this proves the runner.
 */
class MachineJournalTest {
    private object Counter : Machine<Counter.State, Counter.Input, Counter.Effect> {
        data class State(val total: Int = 0)
        sealed interface Input {
            data class Add(val amount: Int) : Input
            data object Noop : Input
        }
        sealed interface Effect { data class Reject(val reason: String) : Effect }

        override val id = MachineId("counter")
        override val space = object : StateSpace<State, Input, Effect> {
            override val phases = listOf(PhaseId("counting"))
            override val inputs = listOf(InputSpec(InputId("Add"), Branch.INTENT), InputSpec(InputId("Noop"), Branch.INTENT))
            override val effects = listOf(EffectId("Reject"))
            override val accepts = emptyMap<InputId, Set<PhaseId>>()
            override fun label(state: State) = PhaseId("counting")
            override fun name(input: Input) = when (input) { is Input.Add -> InputId("Add"); Input.Noop -> InputId("Noop") }
            override fun name(effect: Effect) = EffectId("Reject")
            override fun unknown(state: State) = false
            override fun rejected(effect: Effect) = true
        }
        override fun step(state: State, input: Input): Step<State, Effect> = when (input) {
            is Input.Add -> if (input.amount <= 0) Step(state, listOf(Effect.Reject("Только положительные"))) else Step(State(state.total + input.amount))
            Input.Noop -> Step(state)
        }
    }

    private val codec = object : InputCodec<Counter.Input> {
        override fun encode(input: Counter.Input, revision: JournalRevision) = when (input) {
            is Counter.Input.Add -> "add:${input.amount}:${revision.resetEpoch}"
            Counter.Input.Noop -> "noop:${revision.resetEpoch}"
        }
        override fun decode(record: JournalRecord, revision: JournalRevision): Counter.Input {
            val parts = record.detail.split(":")
            check(parts.last() == revision.resetEpoch.toString()) { "Запись другого поколения" }
            return if (parts[0] == "add") Counter.Input.Add(parts[1].toInt()) else Counter.Input.Noop
        }
    }

    private val stream = "counter:1"
    private fun journalOver(backend: EventJournal, elideNoOps: Boolean = false) =
        MachineJournal(backend, Counter, Counter.State(), "counter.input.v1", codec, elideNoOps)

    /** Writes, then reports failure: the acknowledgement was lost, the record was not. */
    private class LostAcknowledgement(private val inner: EventJournal) : EventJournal by inner {
        var lose = false
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val record = inner.append(expected, operation, at, detail)
            if (lose) throw IllegalStateException("acknowledgement lost")
            return record
        }
    }

    /** Fails before writing anything. */
    private class RefusesToWrite(private val inner: EventJournal) : EventJournal by inner {
        var readable = true
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? =
            throw IllegalStateException("disk full")
        override suspend fun snapshot(stream: String): JournalSnapshot =
            if (readable) inner.snapshot(stream) else throw IllegalStateException("unreadable")
    }

    private suspend fun accepted(runner: MachineJournal<Counter.State, Counter.Input, Counter.Effect>,
        revision: JournalRevision, state: Counter.State, input: Counter.Input) =
        assertIs<Appended.Accepted<Counter.State, Counter.Effect>>(runner.append(revision, state, input, at = 1))

    @Test fun appendedInputsReplayToTheStateTheyProduced() = runTest {
        val backend = InMemoryEventJournal()
        val runner = journalOver(backend)
        var revision = backend.snapshot(stream).revision
        var state = Counter.State()
        for (amount in listOf(2, 3, 5)) {
            val done = accepted(runner, revision, state, Counter.Input.Add(amount))
            revision = done.revision; state = done.step.state
        }
        assertEquals(10, state.total)
        val snapshot = backend.snapshot(stream)
        assertEquals(revision, snapshot.revision)
        assertEquals(state, runner.replay(snapshot))
    }

    @Test fun refusedInputWritesNothing() = runTest {
        val backend = InMemoryEventJournal()
        val runner = journalOver(backend)
        val revision = backend.snapshot(stream).revision
        val refused = assertIs<Appended.Refused<Counter.State, Counter.Effect>>(
            runner.append(revision, Counter.State(), Counter.Input.Add(-1), at = 1))
        assertEquals(Counter.State(), refused.step.state)
        assertEquals(emptyList(), backend.read(stream))
    }

    @Test fun staleRevisionIsAConflictNotAnError() = runTest {
        val backend = InMemoryEventJournal()
        val runner = journalOver(backend)
        val stale = backend.snapshot(stream).revision
        accepted(runner, stale, Counter.State(), Counter.Input.Add(1))
        assertEquals(Appended.Conflict, runner.append(stale, Counter.State(), Counter.Input.Add(2), at = 2))
        assertEquals(1, backend.read(stream).size)
    }

    @Test fun lostAcknowledgementIsSettledByReadingTheStreamBack() = runTest {
        val backend = LostAcknowledgement(InMemoryEventJournal())
        val runner = journalOver(backend)
        val revision = backend.snapshot(stream).revision
        backend.lose = true
        val done = accepted(runner, revision, Counter.State(), Counter.Input.Add(4))
        assertEquals(4, done.step.state.total)
        assertEquals(1, backend.read(stream).size, "Settling must not append a second copy")
        assertEquals(backend.snapshot(stream).revision, done.revision)
    }

    @Test fun writeThatNeverLandedIsUnknownNotRetried() = runTest {
        val backend = RefusesToWrite(InMemoryEventJournal())
        val runner = journalOver(backend)
        val revision = backend.snapshot(stream).revision
        assertFailsWith<JournalOutcomeUnknown> { runner.append(revision, Counter.State(), Counter.Input.Add(1), at = 1) }
        assertEquals(emptyList(), backend.read(stream))
    }

    @Test fun unreadableStreamAfterAFailedWriteIsUnknown() = runTest {
        val backend = RefusesToWrite(InMemoryEventJournal())
        val runner = journalOver(backend)
        val revision = backend.snapshot(stream).revision
        backend.readable = false
        val failure = assertFailsWith<JournalOutcomeUnknown> { runner.append(revision, Counter.State(), Counter.Input.Add(1), at = 1) }
        assertTrue(failure.cause?.suppressedExceptions?.isNotEmpty() == true, "The read failure must not be lost")
    }

    @Test fun anotherWritersRecordIsNotMistakenForOurs() = runTest {
        val inner = InMemoryEventJournal()
        val runner = journalOver(inner)
        val revision = inner.snapshot(stream).revision
        // The backend throws for us, but the record that is last belongs to someone else.
        val journal = object : EventJournal by inner {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                inner.append(stream, "counter.input.v1", at, "add:9:0")
                throw IllegalStateException("lost")
            }
        }
        assertFailsWith<JournalOutcomeUnknown> { journalOver(journal).append(revision, Counter.State(), Counter.Input.Add(1), at = 1) }
        assertEquals(9, runner.replay(inner.snapshot(stream)).total)
    }

    @Test fun replayRefusesForeignRecordsAndTransitionsTheReducerWouldRefuse() = runTest {
        val backend = InMemoryEventJournal()
        val runner = journalOver(backend)
        backend.append(stream, "somebody.else.v1", 1, "add:1:0")
        assertFailsWith<IllegalStateException> { runner.replay(backend.snapshot(stream)) }
        backend.drop(stream)
        backend.append(stream, "counter.input.v1", 1, "add:-3:0")
        assertFailsWith<IllegalStateException> { runner.replay(backend.snapshot(stream)) }
    }

    @Test fun elidingNoOpsSkipsTheWriteOnlyWhenAsked() = runTest {
        val backend = InMemoryEventJournal()
        val revision = backend.snapshot(stream).revision
        val eliding = assertIs<Appended.Accepted<Counter.State, Counter.Effect>>(
            journalOver(backend, elideNoOps = true).append(revision, Counter.State(), Counter.Input.Noop, at = 1))
        assertEquals(revision, eliding.revision)
        assertEquals(emptyList(), backend.read(stream))
        accepted(journalOver(backend), revision, Counter.State(), Counter.Input.Noop)
        assertEquals(1, backend.read(stream).size)
    }
}
