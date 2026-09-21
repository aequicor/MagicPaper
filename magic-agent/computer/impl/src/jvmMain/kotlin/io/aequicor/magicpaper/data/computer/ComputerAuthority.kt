package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.ComputerMachine
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One machine is projected immediately and its inputs are committed in the same order. Revocation
 * cannot wait for a slow disk; only permission/input effects wait for their own durable acknowledgement.
 * A queued effect must also pass the live lease fence immediately before touching the operating system.
 */
internal class ComputerAuthority(
    private val journal: EventJournal,
    private val changed: (ComputerMachine.State) -> Unit,
) : AutoCloseable {
    private data class Pending(val input: ComputerMachine.Input, val transition: ComputerMachine.Transition,
        val completion: CompletableDeferred<ComputerMachine.Transition>)
    private data class Buffered(val input: ComputerMachine.Input, val completion: CompletableDeferred<ComputerMachine.Transition>)
    @Serializable private data class Entry(val id: String, val input: ComputerMachine.Input, val resetEpoch: Long = 0)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("computer-authority"))
    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val buffered = mutableListOf<Buffered>()
    private var initialized = false
    private var closed = false
    private var value = ComputerMachine.initial()
    val state: ComputerMachine.State get() = synchronized(lock) { value }

    init { scope.launch {
        try {
            val snapshot = journal.snapshot(STREAM)
            val restored = replay(snapshot)
            synchronized(lock) {
                if (closed) return@launch
                value = restored
                initialized = true
                // This recorded boundary ensures a later replay observes the same revoked generation.
                // Its outputs are ignored: startup cannot invoke cleanup, capture or input.
                enqueueLocked(ComputerMachine.Fact.Restored(Id.new()), CompletableDeferred())
                buffered.toList().also { buffered.clear() }.forEach { enqueueLocked(it.input, it.completion) }
            }
            changed(state)
            var revision = snapshot.revision
            val committedHistory = snapshot.records.toMutableList()
            for (pending in queue) {
                try {
                    val record = append(revision, committedHistory, pending.input)
                    committedHistory += record
                    revision = revision.copy(seq = record.seq)
                    pending.completion.complete(pending.transition)
                } catch (failure: Exception) {
                    try { if (!synchronized(lock) { closed }) fail(failure) }
                    finally { pending.completion.completeExceptionally(failure) }
                    break
                }
            }
        } catch (cancelled: CancellationException) {
            if (!synchronized(lock) { closed }) fail(cancelled)
            throw cancelled
        } catch (failure: Exception) { fail(failure) }
    } }

    fun enqueue(input: ComputerMachine.Input): Deferred<ComputerMachine.Transition> {
        val completion = CompletableDeferred<ComputerMachine.Transition>()
        synchronized(lock) {
            if (closed) completion.completeExceptionally(ComputerAuthorityUnavailable())
            else if (!initialized) buffered += Buffered(input, completion)
            else enqueueLocked(input, completion)
        }
        changed(state)
        return completion
    }

    suspend fun dispatch(input: ComputerMachine.Input): ComputerMachine.Transition = enqueue(input).await()

    /** Conditional Enable has an initialized policy capture. Its exact projected lease is
     * available before persistence suspends, so cancellation can revoke only that admission. */
    data class Admission(val transition: ComputerMachine.Transition, val acknowledgement: Deferred<ComputerMachine.Transition>)
    fun admit(input: ComputerMachine.Input): Admission {
        val completion = CompletableDeferred<ComputerMachine.Transition>()
        val transition = synchronized(lock) {
            check(initialized && !closed) { "Computer authority is unavailable" }
            enqueueLocked(input, completion)
        }
        changed(state)
        return Admission(transition, completion)
    }

    fun releaseFailed() {
        synchronized(lock) {
            // Teardown may already have stopped the journal writer. The live fence still owns failure.
            if (closed) value = ComputerMachine.reduce(value, ComputerMachine.Fact.ReleaseFailed).state
            else enqueueLocked(ComputerMachine.Fact.ReleaseFailed, CompletableDeferred())
        }
        changed(state)
    }

    private fun enqueueLocked(input: ComputerMachine.Input, completion: CompletableDeferred<ComputerMachine.Transition>): ComputerMachine.Transition {
        val transition = ComputerMachine.reduce(value, input)
        val rejection = transition.effects.filterIsInstance<ComputerMachine.Effect.Reject>().firstOrNull()
        if (rejection != null) {
            completion.completeExceptionally(if (value.persistenceUnknown) ComputerAuthorityUnavailable() else ComputerAuthorityRejected(rejection.reason))
            return transition
        }
        value = transition.state
        if (value.persistenceUnknown) {
            completion.completeExceptionally(ComputerAuthorityUnavailable())
            return transition
        }
        check(queue.trySend(Pending(input, transition, completion)).isSuccess) { "Computer journal owner is closed" }
        return transition
    }

    private fun fail(failure: Exception) {
        val waiting = synchronized(lock) {
            value = ComputerMachine.reduce(value, ComputerMachine.Fact.PersistenceUnknown).state
            val completions = buffered.map { it.completion }.toMutableList()
            buffered.clear()
            initialized = true
            while (true) {
                val pending = queue.tryReceive().getOrNull() ?: break
                completions += pending.completion
            }
            completions
        }
        AppLog.error("computer", "journal_outcome_unknown", mapOf("causeType" to failure::class.simpleName.orEmpty()))
        try { changed(state) } finally {
            waiting.forEach { it.completeExceptionally(ComputerAuthorityUnavailable(failure)) }
        }
    }

    private fun replay(snapshot: JournalSnapshot): ComputerMachine.State {
        check(snapshot.revision.stream == STREAM && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Invalid computer journal identity" }
        if (snapshot.records.isNotEmpty()) check(snapshot.records.last().seq == snapshot.revision.seq) { "Invalid computer journal revision" }
        var state = ComputerMachine.initial()
        var sequence = 0L
        val entries = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == STREAM && record.seq > sequence && record.seq <= snapshot.revision.seq && record.operation == OPERATION) { "Invalid computer journal sequence" }
            sequence = record.seq
            val entry = json.decodeFromString<Entry>(record.detail)
            check(entry.id.isNotBlank() && entries.add(entry.id) && entry.resetEpoch == snapshot.revision.resetEpoch) { "Invalid computer journal generation" }
            val next = ComputerMachine.replay(state, entry.input)
            check(next.effects.none { it is ComputerMachine.Effect.Reject }) { "Invalid computer journal transition" }
            state = next.state
        }
        return state
    }

    private suspend fun append(revision: JournalRevision, history: List<JournalRecord>, input: ComputerMachine.Input): JournalRecord {
        val detail = json.encodeToString(Entry.serializer(), Entry(Id.new(), input, revision.resetEpoch))
        try {
            val record = journal.append(revision, OPERATION, Id.now(), detail) ?: error("Computer journal changed")
            check(record.stream == STREAM && record.seq > revision.seq && record.operation == OPERATION && record.detail == detail) { "Invalid computer journal acknowledgement" }
            return record
        } catch (failure: Exception) {
            val observed = try { withContext(NonCancellable) { journal.snapshot(STREAM) } }
            catch (read: Exception) { failure.addSuppressed(read); throw failure }
            try { replay(observed) } catch (invalid: Exception) { failure.addSuppressed(invalid); throw failure }
            val committed = observed.records.lastOrNull()?.let { it.seq > revision.seq && it.detail == detail && it.operation == OPERATION } == true
            if (!committed || observed.revision.resetEpoch != revision.resetEpoch || observed.records.dropLast(1) != history) throw failure
            if (failure is CancellationException) throw failure
            return observed.records.last()
        }
    }

    override fun close() {
        val failure = CancellationException("Computer owner closed")
        synchronized(lock) {
            if (closed) return
            closed = true
            value = ComputerMachine.reduce(value, ComputerMachine.Intent.Revoke()).state
            buffered.forEach { it.completion.completeExceptionally(failure) }
            buffered.clear()
            while (true) {
                val pending = queue.tryReceive().getOrNull() ?: break
                pending.completion.completeExceptionally(failure)
            }
            queue.close()
        }
        try { changed(state) } finally { scope.cancel(failure) }
    }

    suspend fun awaitClosed() { scope.coroutineContext.job.join() }

    private companion object {
        const val STREAM = "computer-authority:application"
        const val OPERATION = "computer-authority.input.v1"
    }
}
internal class ComputerAuthorityRejected(message: String) : IllegalStateException(message)
internal class ComputerAuthorityUnavailable(cause: Throwable? = null) : IllegalStateException("Не удалось подтвердить сохранение доступа. Новые действия отключены.", cause)
