package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** The draft value beside the transition state that admitted it; see [DraftMachine]. */
@ConsistentCopyVisibility
data class DraftSessionState<T> internal constructor(
    val value: T,
    internal val machine: DraftMachine.State,
    /** Diagnostics of the failure the machine recorded; the transition itself carries a value. */
    val error: StorageException? = null,
) {
    /** Local edit identity, used to avoid clearing text typed while a send/save was running. */
    val version: Long get() = machine.version
    val loaded: Boolean get() = machine.loaded
    val saving: Boolean get() = machine.saving
    /** A committed record whose cleanup was not acknowledged; an explicit retry resolves it. */
    val unknown: Boolean get() = machine.unknown
}

/**
 * Application-owned executor of [DraftMachine]: UI disposal neither cancels pending writes nor
 * discards the draft. Every decision — the version an edit receives, the revision a write spends,
 * whether a clear still applies — belongs to the machine; this class only performs its effects
 * and reports the durable facts back.
 */
class DraftSession<T>(
    private val repository: DraftRepository,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val initial: T,
    scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val redact: (T) -> T = { it },
    private val extractSecrets: (T) -> Map<String, String> = { emptyMap() },
    private val hydrateSecrets: (T, Map<String, String>) -> T = { value, _ -> value },
    private val blobIds: (T) -> List<String> = { emptyList() },
) {
    private val generation = repository.generation
    private val mutableState = MutableStateFlow(DraftSessionState(initial, DraftMachine.initial(generation)))
    val state: StateFlow<DraftSessionState<T>> = mutableState.asStateFlow()
    private val commands = Channel<Command<T>>(Channel.UNLIMITED)
    private var hydrated = false
    private val diagnosticId = Random.nextLong().toULong().toString(16)
    private fun diagnosticFields(version: Long = mutableState.value.version) = mapOf(
        "operationId" to diagnosticId, "storageArea" to "drafts", "generation" to generation.toString(), "version" to version.toString())

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            restore()
            for (command in commands) {
                try {
                    if (!hydrated) restoreOrThrow()
                    when (command) {
                        is Command.Save -> write(command.value, command.version)
                        is Command.Settle -> {
                            retryCommittedCleanup()
                            val current = mutableState.value
                            write(current.value, current.version)
                            command.result?.complete(Unit)
                        }
                        is Command.Clear -> clear(command)
                    }
                } catch (error: CancellationException) {
                    command.fail(error)
                    throw error
                } catch (error: Exception) {
                    val failure = error as? StorageException ?: StorageException("save draft", StorageException.Kind.WRITE, error)
                    apply(DraftMachine.Fact.Failed(failure.outcome()), failure)
                    logPersistenceFailure("DraftSession", "command_failed", failure, diagnosticFields())
                    command.fail(failure)
                }
            }
        }
    }

    /** Call from the application's UI dispatcher; the latest value updates before persistence. */
    fun update(value: T) {
        val transition = apply(DraftMachine.Intent.Edit(repository.generation)) { current, next ->
            if (next.version == current.machine.version) current.value else value
        }
        val scheduled = transition.effects.filterIsInstance<DraftMachine.Effect.Schedule>().firstOrNull() ?: return
        check(commands.trySend(Command.Save(value, scheduled.version)).isSuccess)
    }

    fun update(transform: (T) -> T) = update(transform(mutableState.value.value))
    /** Stops UI callbacks and already-queued autosaves before the owning entity is removed. */
    fun revoke() { apply(DraftMachine.Intent.Revoke) }

    /** Explicit retry leaves errors in this session's observable state if persistence still fails. */
    fun retry() {
        val machine = mutableState.value.machine
        if (machine.revoked || repository.generation != machine.generation) return
        AppLog.info("DraftSession", "retry_requested", diagnosticFields())
        check(commands.trySend(Command.Settle<T>(null)).isSuccess)
    }

    /** Retries a previously failed save and waits for the durable transaction, never merely a debounce. */
    suspend fun awaitSaved() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Settle(result))
        result.await()
    }

    /** Call only after the corresponding explicit operation succeeded, using its captured version. */
    suspend fun clearIfUnchanged(expectedVersion: Long, replacement: T = initial): Boolean {
        val result = CompletableDeferred<Boolean>()
        commands.send(Command.Clear(expectedVersion, replacement, result))
        return result.await()
    }

    /**
     * One compare-and-set per transition: an edit arrives on the UI dispatcher while the command
     * loop reports durable facts, so the state both of them read must be the state they changed.
     */
    private fun apply(
        input: DraftMachine.Input,
        error: StorageException? = null,
        value: (DraftSessionState<T>, DraftMachine.State) -> T = { current, _ -> current.value },
    ): DraftMachine.Transition {
        while (true) {
            val current = mutableState.value
            val transition = DraftMachine.reduce(current.machine, input)
            val next = DraftSessionState(value(current, transition.state), transition.state,
                if (transition.state.failure == null) null else error ?: current.error)
            if (mutableState.compareAndSet(current, next)) return transition
        }
    }

    private suspend fun restore() {
        try { restoreOrThrow() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = error as? StorageException ?: StorageException("restore draft", StorageException.Kind.CORRUPT, error)
            apply(DraftMachine.Fact.Failed(failure.outcome()), failure)
            logPersistenceFailure("DraftSession", "restore_failed", failure, diagnosticFields())
        }
    }

    private suspend fun restoreOrThrow() {
        val epoch = repository.resetEpoch()
        apply(DraftMachine.Fact.ResetEpochObserved(epoch)).reject("draft reset in another window")
        val record = repository.load(key)
        val revision = maxOf(record?.revision ?: 0, repository.revision(key))
        val ownerEpoch = record?.ownerEpoch ?: repository.ownerEpoch(key)
        if (repository.resetEpoch() != epoch) throw StorageException("draft reset during restore", StorageException.Kind.WRITE)
        val restored = record?.let { hydrateSecrets(json.decodeFromString(serializer, it.payload), it.secrets) } ?: initial
        apply(DraftMachine.Fact.Restored(revision, ownerEpoch, epoch, record != null)) { current, _ ->
            // An edit typed before hydration finished wins; only an untouched draft takes the record.
            if (current.machine.version == 0L) restored else current.value
        }.reject("draft reset during restore")
        hydrated = true
        AppLog.info("DraftSession", "restored", diagnosticFields() + ("result" to if (record == null) "empty" else "existing"))
    }

    private suspend fun write(value: T, version: Long) {
        val machine = mutableState.value.machine
        val effect = apply(DraftMachine.Intent.Persist(repository.generation, version))
            .effects.filterIsInstance<DraftMachine.Effect.Write>().firstOrNull() ?: return
        val draft = DraftRecord(key, effect.revision, json.encodeToString(serializer, redact(value)),
            extractSecrets(value), blobIds(value), generation, machine.ownerEpoch, checkNotNull(machine.resetEpoch))
        var cleanupFailure: StorageException? = null
        val savedSuccessfully = try { repository.save(draft) }
            catch (failure: StorageException) {
                if (!failure.committed) throw failure
                cleanupFailure = failure
                true
            }
        if (!savedSuccessfully) {
            // A write can have committed immediately before the caller was interrupted.
            val saved = repository.load(key)
            if (saved != draft) throw StorageException("save draft revision", StorageException.Kind.WRITE)
        }
        apply(DraftMachine.Fact.Written(effect.version))
        cleanupFailure?.let { throw it }
    }

    private suspend fun clear(command: Command.Clear<T>) {
        val transition = apply(DraftMachine.Intent.Clear(repository.generation, command.expectedVersion))
        val effect = transition.effects.filterIsInstance<DraftMachine.Effect.Delete>().firstOrNull()
            ?: run { command.result.complete(false); return }
        var cleanupFailure: StorageException? = null
        val cleared = try { repository.deleteIfOwned(key, effect.revision, effect.ownerEpoch, effect.resetEpoch) }
            catch (failure: StorageException) {
                if (!failure.committed) throw failure
                cleanupFailure = failure
                true
            }
        if (!cleared) throw StorageException("clear draft", StorageException.Kind.WRITE)
        apply(DraftMachine.Fact.Cleared(effect.version)) { current, next ->
            if (next.version == current.machine.version) current.value else command.replacement
        }
        AppLog.info("DraftSession", "cleared", diagnosticFields(effect.version + 1))
        cleanupFailure?.let { throw it }
        command.result.complete(true)
    }

    private suspend fun retryCommittedCleanup() {
        val failure = mutableState.value.machine.cleanup ?: return
        if (apply(DraftMachine.Intent.Cleanup(repository.generation))
                .effects.none { it is DraftMachine.Effect.RetryCleanup }) return
        repository.retryCleanup()
        apply(DraftMachine.Fact.CleanupRetried(failure))
    }

    private fun DraftMachine.Transition.reject(operation: String) {
        if (effects.any { it is DraftMachine.Effect.Reject }) throw StorageException(operation, StorageException.Kind.WRITE)
    }

    private fun StorageException.outcome() = DraftFailure(operation, kind, committed)

    private sealed interface Command<T> {
        fun fail(error: Exception) { }
        data class Save<T>(val value: T, val version: Long) : Command<T>
        /** Retries committed cleanup and then persists the newest edit; an explicit retry has no waiter. */
        data class Settle<T>(val result: CompletableDeferred<Unit>?) : Command<T> {
            override fun fail(error: Exception) { result?.completeExceptionally(error) }
        }
        data class Clear<T>(val expectedVersion: Long, val replacement: T, val result: CompletableDeferred<Boolean>) : Command<T> {
            override fun fail(error: Exception) { result.completeExceptionally(error) }
        }
    }
}
