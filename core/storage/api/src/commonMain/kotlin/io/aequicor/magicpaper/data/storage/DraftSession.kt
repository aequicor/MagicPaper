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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

data class DraftSessionState<T>(
    val value: T,
    /** Local edit identity, used to avoid clearing text typed while a send/save was running. */
    val version: Long = 0,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: StorageException? = null,
)

/** Application-owned session: UI disposal neither cancels pending writes nor discards the draft. */
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
    private val mutableState = MutableStateFlow(DraftSessionState(initial))
    val state: StateFlow<DraftSessionState<T>> = mutableState.asStateFlow()
    private val commands = Channel<Command<T>>(Channel.UNLIMITED)
    private val generation = repository.generation
    private var baseline = 0L
    private var savedVersion = -1L
    private var hydrated = false
    private var ownerEpoch = 0L
    private var durableResetEpoch: Long? = null
    private var revoked = false
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
                        is Command.Save -> persist(command.value, command.version)
                        is Command.Flush -> {
                            retryCommittedCleanup()
                            val current = mutableState.value
                            if (current.version > savedVersion) persist(current.value, current.version)
                            command.result.complete(Unit)
                        }
                        is Command.Clear -> {
                            val current = mutableState.value
                            if (revoked || repository.generation != generation || current.version != command.expectedVersion) command.result.complete(false)
                            else {
                                val nextVersion = current.version + 1
                                var cleanupFailure: StorageException? = null
                                val cleared = try { repository.deleteIfOwned(key, ++baseline, ownerEpoch, checkNotNull(durableResetEpoch)) }
                                    catch (failure: StorageException) {
                                        if (!failure.committed) throw failure
                                        cleanupFailure = failure
                                        true
                                    }
                                if (!cleared) throw StorageException("clear draft", StorageException.Kind.WRITE)
                                savedVersion = current.version
                                mutableState.update { latest ->
                                    if (latest.version == current.version) {
                                        savedVersion = nextVersion
                                        DraftSessionState(command.replacement, nextVersion, loaded = true)
                                    }
                                    else latest
                                }
                                AppLog.info("DraftSession", "cleared", diagnosticFields(nextVersion))
                                cleanupFailure?.let { throw it }
                                command.result.complete(true)
                            }
                        }
                        is Command.Retry -> {
                            retryCommittedCleanup()
                            val current = mutableState.value
                            persist(current.value, current.version)
                        }
                    }
                } catch (error: CancellationException) {
                    command.fail(error)
                    throw error
                } catch (error: Exception) {
                    val failure = error as? StorageException ?: StorageException("save draft", StorageException.Kind.WRITE, error)
                    mutableState.update { it.copy(saving = false, error = failure) }
                    logPersistenceFailure("DraftSession", "command_failed", failure, diagnosticFields())
                    command.fail(failure)
                }
            }
        }
    }

    /** Call from the application's UI dispatcher; the latest value updates before persistence. */
    fun update(value: T) {
        if (revoked || repository.generation != generation) return
        val current = mutableState.value
        val next = current.copy(value = value, version = current.version + 1, saving = true, error = null)
        mutableState.value = next
        check(commands.trySend(Command.Save(value, next.version)).isSuccess)
    }

    fun update(transform: (T) -> T) = update(transform(mutableState.value.value))
    /** Stops UI callbacks and already-queued autosaves before the owning entity is removed. */
    fun revoke() { revoked = true }

    /** Explicit retry leaves errors in this session's observable state if persistence still fails. */
    fun retry() {
        if (revoked || repository.generation != generation) return
        AppLog.info("DraftSession", "retry_requested", diagnosticFields())
        check(commands.trySend(Command.Retry()).isSuccess)
    }

    /** Retries a previously failed save and waits for the durable transaction, never merely a debounce. */
    suspend fun awaitSaved() {
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Flush(result))
        result.await()
    }

    /** Call only after the corresponding explicit operation succeeded, using its captured version. */
    suspend fun clearIfUnchanged(expectedVersion: Long, replacement: T = initial): Boolean {
        val result = CompletableDeferred<Boolean>()
        commands.send(Command.Clear(expectedVersion, replacement, result))
        return result.await()
    }

    private suspend fun restore() {
        try { restoreOrThrow() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val failure = error as? StorageException ?: StorageException("restore draft", StorageException.Kind.CORRUPT, error)
            mutableState.update { it.copy(error = failure) }
            logPersistenceFailure("DraftSession", "restore_failed", failure, diagnosticFields())
        }
    }

    private suspend fun restoreOrThrow() {
        val epoch = repository.resetEpoch()
        if (durableResetEpoch != null && durableResetEpoch != epoch) throw StorageException("draft reset in another window", StorageException.Kind.WRITE)
        durableResetEpoch = epoch
        val record = repository.load(key)
        baseline = maxOf(record?.revision ?: 0, repository.revision(key))
        ownerEpoch = record?.ownerEpoch ?: repository.ownerEpoch(key)
        if (repository.resetEpoch() != epoch) throw StorageException("draft reset during restore", StorageException.Kind.WRITE)
        val restored = record?.let { hydrateSecrets(json.decodeFromString(serializer, it.payload), it.secrets) } ?: initial
        mutableState.update { current ->
            if (current.version == 0L) current.copy(value = restored, loaded = true, error = null)
            else current.copy(loaded = true, error = null)
        }
        savedVersion = 0
        hydrated = true
        AppLog.info("DraftSession", "restored", diagnosticFields() + ("result" to if (record == null) "empty" else "existing"))
    }

    private suspend fun persist(value: T, version: Long) {
        if (revoked || repository.generation != generation || version <= savedVersion) return
        val draft = DraftRecord(key, ++baseline, json.encodeToString(serializer, redact(value)), extractSecrets(value), blobIds(value), generation, ownerEpoch, checkNotNull(durableResetEpoch))
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
        savedVersion = version
        mutableState.update { current ->
            if (current.version == version) current.copy(saving = false, error = null) else current
        }
        cleanupFailure?.let { throw it }
    }

    private suspend fun retryCommittedCleanup() {
        val failure = mutableState.value.error?.takeIf { it.committed } ?: return
        repository.retryCleanup()
        mutableState.update { if (it.error === failure) it.copy(error = null, saving = false) else it }
    }

    private sealed interface Command<T> {
        fun fail(error: Exception) { }
        data class Save<T>(val value: T, val version: Long) : Command<T>
        class Retry<T> : Command<T>
        data class Flush<T>(val result: CompletableDeferred<Unit>) : Command<T> {
            override fun fail(error: Exception) { result.completeExceptionally(error) }
        }
        data class Clear<T>(val expectedVersion: Long, val replacement: T, val result: CompletableDeferred<Boolean>) : Command<T> {
            override fun fail(error: Exception) { result.completeExceptionally(error) }
        }
    }
}
