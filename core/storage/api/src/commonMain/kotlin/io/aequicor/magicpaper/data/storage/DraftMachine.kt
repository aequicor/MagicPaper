package io.aequicor.magicpaper.data.storage

/** A write outcome as a comparable value: transitions must not carry a platform exception. */
data class DraftFailure(val operation: String, val kind: StorageException.Kind, val committed: Boolean)

/**
 * Edit identity and durable revision allocation for one draft key.
 *
 * The draft owner keeps no separate input journal: the durable record is already the projection
 * of its inputs, ordered by a monotone revision with a delete tombstone, and an autosave runs at
 * typing rate, so a journal would grow without bound while re-deriving exactly what the record
 * already states. What a journal is needed for here is the unknown outcome, and that is a state:
 * a committed record whose cleanup was not acknowledged stays [State.unknown] until an explicit
 * retry resolves it, and no transition treats it as a completed write.
 *
 * The payload itself stays outside the machine. Versions identify an edit; the executor owns the
 * value, its secrets and its blobs, so a transition can be compared without deserializing a draft.
 */
object DraftMachine {
    @ConsistentCopyVisibility
    data class State internal constructor(
        /** Repository generation captured when the writer was opened; reset replaces it. */
        val generation: Long = 0,
        val loaded: Boolean = false,
        /** Local edit identity, used to avoid clearing text typed while a send/save was running. */
        val version: Long = 0,
        val savedVersion: Long = -1,
        val revision: Long = 0,
        val ownerEpoch: Long = 0,
        /** Durable across repository instances and browser tabs; captured when the writer opens. */
        val resetEpoch: Long? = null,
        val saving: Boolean = false,
        val revoked: Boolean = false,
        val failure: DraftFailure? = null,
        /**
         * A committed record whose cleanup was not acknowledged. It is kept apart from [failure]
         * because the next keystroke clears the notice: an unknown durable outcome must outlive
         * the edit that follows it, and only its own acknowledged retry may drop it.
         */
        val cleanup: DraftFailure? = null,
    ) {
        val unknown: Boolean get() = cleanup != null
        val pendingWrite: Boolean get() = version > savedVersion
    }

    sealed interface Input

    sealed interface Intent : Input {
        /** The observed generation is a value: the machine never reads the repository itself. */
        data class Edit(val generation: Long) : Intent
        data class Persist(val generation: Long, val version: Long) : Intent
        data class Clear(val generation: Long, val expectedVersion: Long) : Intent
        data class Cleanup(val generation: Long) : Intent
        data object Revoke : Intent
    }

    sealed interface Fact : Input {
        data class ResetEpochObserved(val resetEpoch: Long) : Fact
        data class Restored(val revision: Long, val ownerEpoch: Long, val resetEpoch: Long, val stored: Boolean) : Fact
        data class Written(val version: Long) : Fact
        data class Cleared(val version: Long) : Fact
        data class CleanupRetried(val failure: DraftFailure) : Fact
        data class Failed(val failure: DraftFailure) : Fact
    }

    sealed interface Effect {
        data class Reject(val reason: String) : Effect
        /** Queue a write for this edit; the executor keeps the value that belongs to the version. */
        data class Schedule(val version: Long) : Effect
        data class Write(val version: Long, val revision: Long) : Effect
        data class Delete(val version: Long, val revision: Long, val ownerEpoch: Long, val resetEpoch: Long) : Effect
        data object RetryCleanup : Effect
    }

    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial(generation: Long = 0) = State(generation = generation)

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        fun current(generation: Long) = !state.revoked && state.generation == generation
        return when (input) {
            Intent.Revoke -> Transition(state.copy(revoked = true))

            is Intent.Edit ->
                if (!current(input.generation)) reject("Черновик принадлежит прошлой сессии хранилища")
                else state.copy(version = state.version + 1, saving = true, failure = null)
                    .let { Transition(it, listOf(Effect.Schedule(it.version))) }

            is Intent.Persist ->
                if (!state.loaded) reject("Черновик ещё не восстановлен")
                // A superseded version is not a refusal: a newer edit already carries this text.
                else if (!current(input.generation) || input.version <= state.savedVersion) Transition(state)
                else (state.revision + 1).let { revision ->
                    Transition(state.copy(revision = revision), listOf(Effect.Write(input.version, revision)))
                }

            is Intent.Clear -> {
                val resetEpoch = state.resetEpoch
                if (!state.loaded || resetEpoch == null) reject("Черновик ещё не восстановлен")
                else if (!current(input.generation)) reject("Черновик принадлежит прошлой сессии хранилища")
                else if (state.version != input.expectedVersion) reject("Черновик изменился; очистка отменена")
                else (state.revision + 1).let { revision ->
                    Transition(state.copy(revision = revision),
                        listOf(Effect.Delete(state.version, revision, state.ownerEpoch, resetEpoch)))
                }
            }

            is Intent.Cleanup ->
                if (state.cleanup == null) Transition(state) else Transition(state, listOf(Effect.RetryCleanup))

            is Fact.ResetEpochObserved ->
                if (state.resetEpoch != null && state.resetEpoch != input.resetEpoch) reject("Черновики сброшены в другом окне")
                else Transition(state.copy(resetEpoch = input.resetEpoch))

            is Fact.Restored ->
                if (state.resetEpoch != input.resetEpoch) reject("Черновики сброшены во время восстановления")
                // The restored revision never lowers an allocation an interrupted write already used.
                else Transition(state.copy(loaded = true, savedVersion = 0, ownerEpoch = input.ownerEpoch,
                    revision = maxOf(state.revision, input.revision), failure = null))

            is Fact.Written -> state.copy(savedVersion = maxOf(state.savedVersion, input.version)).let { saved ->
                Transition(if (state.version == input.version) saved.copy(saving = false, failure = null) else saved)
            }

            is Fact.Cleared ->
                // Text typed while the delete ran keeps its version, so the next write still saves it.
                if (state.version != input.version) Transition(state.copy(savedVersion = maxOf(state.savedVersion, input.version)))
                else (state.version + 1).let { next ->
                    Transition(state.copy(version = next, savedVersion = next, loaded = true, saving = false, failure = null))
                }

            is Fact.CleanupRetried ->
                if (state.cleanup != input.failure) Transition(state)
                // An edit after the failure already took the notice; only the cleanup is settled here.
                else if (state.failure == input.failure) Transition(state.copy(cleanup = null, failure = null, saving = false))
                else Transition(state.copy(cleanup = null))

            is Fact.Failed -> Transition(state.copy(saving = false, failure = input.failure,
                cleanup = if (input.failure.committed) input.failure else state.cleanup))
        }
    }
}
