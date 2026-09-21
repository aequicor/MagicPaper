package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.Step

/**
 * How one input of a machine travels through a journal record.
 *
 * The wire format stays with the owner: the `@SerialName`s of its inputs are stored data, and what
 * else a record carries — a fresh id, the reset epoch it was written under — differs between
 * owners. [revision] is the one the append is expected to advance, so an owner can stamp the epoch
 * it observed and, on replay, refuse a record written under another one.
 */
interface InputCodec<I : Any> {
    fun encode(input: I, revision: JournalRevision): String

    /** May throw: a record that cannot be read is a corrupt journal, and replay fails on it. */
    fun decode(record: JournalRecord, revision: JournalRevision): I
}

/** What became of one input offered to [MachineJournal.append]. */
sealed interface Appended<out S : Any, out E : Any> {
    /** Written and confirmed. [revision] is where the next append expects to find the stream. */
    data class Accepted<S : Any, E : Any>(val step: Step<S, E>, val revision: JournalRevision) : Appended<S, E>

    /** The reducer refused the input and nothing was written; the owner's `Reject` carries its reason. */
    data class Refused<S : Any, E : Any>(val step: Step<S, E>) : Appended<S, E>

    /** Another writer moved the stream or the application was reset; nothing was written. */
    data object Conflict : Appended<Nothing, Nothing>
}

/**
 * The write may or may not be durable and nothing observed afterwards settles it.
 *
 * This is the persistence-unknown outcome every owner turns into `Fact.PersistenceUnknown`, so the
 * state stops accepting work instead of guessing.
 */
class JournalOutcomeUnknown(cause: Throwable) : IllegalStateException("Не удалось подтвердить запись журнала", cause)

/**
 * Replay and append of one machine's inputs over one journal stream.
 *
 * This is the part every journal-backed owner repeats line for line: a snapshot is replayed through
 * the pure reducer with the stream, order and refusals checked, and an append that failed after the
 * backend may have accepted it is settled by reading the stream back. It is deliberately no more
 * than that. Which stream an owner uses, how it caches state, what it does on a conflict, how it
 * imports a legacy snapshot and what it publishes afterwards all stay with the owner, because they
 * are what differs between them.
 *
 * Call [append] where cancellation cannot interrupt it — `withContext(NonCancellable)` — as the
 * owners already do: an admitted write must reach its reconcile step.
 *
 * @param initial the state a stream with no records starts from; the machine has no `initial` of
 * its own because owners parameterize it differently.
 * @param operation the record kind this stream carries; any other kind fails replay.
 * @param elideNoOps skip the write when an input changes nothing and asks for nothing. An input
 * that is journaled at all is authoritative, so an owner that replays a no-op needs this off.
 */
class MachineJournal<S : Any, I : Any, E : Any>(
    private val journal: EventJournal,
    private val machine: Machine<S, I, E>,
    private val initial: S,
    private val operation: String,
    private val codec: InputCodec<I>,
    private val elideNoOps: Boolean = false,
) {
    /** Throws on a corrupt journal; a state the reducer would have refused is never restored. */
    fun replay(snapshot: JournalSnapshot): S {
        val revision = snapshot.revision
        check(revision.seq >= 0 && revision.resetEpoch >= 0) { "Повреждена ревизия журнала ${machine.id.name}" }
        check(snapshot.records.isEmpty() || snapshot.records.last().seq == revision.seq) {
            "Неполная ревизия журнала ${machine.id.name}"
        }
        var state = initial
        var previous = 0L
        for (record in snapshot.records) {
            check(record.stream == revision.stream && record.seq > previous && record.seq <= revision.seq) {
                "Нарушен порядок журнала ${machine.id.name}"
            }
            previous = record.seq
            check(record.operation == operation) { "Неизвестная запись журнала ${machine.id.name}" }
            val step = machine.step(state, codec.decode(record, revision))
            check(step.effects.none { machine.space.rejected(it) }) { "Недопустимый переход в журнале ${machine.id.name}" }
            state = step.state
        }
        return state
    }

    /**
     * Steps [state] by [input] and, when the reducer accepts, appends it under [revision].
     *
     * The record is written before the caller runs any effect, so after a crash the only evidence of
     * a request is that it was written down first. When the write throws, the stream is read back:
     * the write counts as done only if the last record is exactly this input under the same epoch and
     * the stream still replays. Anything else is [JournalOutcomeUnknown].
     */
    suspend fun append(revision: JournalRevision, state: S, input: I, at: Long): Appended<S, E> {
        val step = machine.step(state, input)
        if (step.effects.any { machine.space.rejected(it) }) return Appended.Refused(step)
        if (elideNoOps && step.state == state && step.effects.isEmpty()) return Appended.Accepted(step, revision)
        val detail = codec.encode(input, revision)
        try {
            val record = journal.append(revision, operation, at, detail) ?: return Appended.Conflict
            check(record.stream == revision.stream && record.seq > revision.seq &&
                record.operation == operation && record.detail == detail) { "Подтверждение записи принадлежит другому журналу" }
            return Appended.Accepted(step, revision.copy(seq = record.seq))
        } catch (failure: Exception) {
            val observed = try { journal.snapshot(revision.stream) } catch (readFailure: Exception) {
                failure.addSuppressed(readFailure)
                throw JournalOutcomeUnknown(failure)
            }
            val ours = observed.records.lastOrNull()?.takeIf {
                it.seq > revision.seq && it.operation == operation && it.detail == detail
            }
            val settled = ours != null && observed.revision.resetEpoch == revision.resetEpoch &&
                try { replay(observed); true } catch (invalid: Exception) { failure.addSuppressed(invalid); false }
            if (!settled) throw JournalOutcomeUnknown(failure)
            return Appended.Accepted(step, revision.copy(seq = checkNotNull(ours).seq))
        }
    }
}
