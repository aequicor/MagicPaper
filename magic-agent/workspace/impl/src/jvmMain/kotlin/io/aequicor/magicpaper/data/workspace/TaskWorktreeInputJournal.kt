package io.aequicor.magicpaper.data.workspace

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Serialized under the owner's lock. Payload bytes remain private; the shared journal has exact references only. */
internal class TaskWorktreeInputJournal(
    private val events: EventJournal,
    private val payloads: KeyValueStore,
    private val owner: TaskWorktreeOwnerId,
) {
    @Serializable private data class Payload(val id: String, val owner: TaskWorktreeOwnerId, val input: TaskWorktreeMachine.Input)
    @Serializable private data class Envelope(val id: String, val owner: TaskWorktreeOwnerId, val digest: String, val resetEpoch: Long)
    @Serializable private data class Outcome(val owner: TaskWorktreeOwnerId, val operationId: String,
        val taskId: String, val generation: Long, val kind: TaskWorktreeMachine.Operation,
        val resetEpoch: Long, val fact: TaskWorktreeMachine.Input.Fact)
    @Serializable private data class StoredOutcome(val payload: String, val digest: String)
    private val json = Json { encodeDefaults = true }
    val stream = "task-worktree:" + encoded(owner.projectId) + ":" + encoded(owner.sessionId)
    var state = TaskWorktreeMachine.initial(owner)
        private set
    private var snapshot: JournalSnapshot? = null

    suspend fun initialize(legacy: TaskWorktree?, generation: Long) {
        if (snapshot != null) return
        if (state.persistenceUnknown) throw TaskWorktreeJournalUnknown()
        try {
            val loaded = events.snapshot(stream)
            state = replay(loaded)
            snapshot = loaded
            if (loaded.records.isEmpty()) dispatch(TaskWorktreeMachine.Input.Fact.Imported(legacy, generation))
            else dispatch(TaskWorktreeMachine.Input.Fact.Restored)
        } catch (failure: Exception) {
            fail(failure, "restore.failed")
            if (failure is CancellationException) throw failure
            throw TaskWorktreeJournalUnknown(failure)
        }
    }

    fun projection(): TaskWorktreeProjection {
        val revision = snapshot?.revision ?: JournalRevision(stream, 0)
        return TaskWorktreeProjection(owner, state.record, state.generation, stream, revision.seq, revision.resetEpoch,
            state.stage == TaskWorktreeMachine.Stage.UNKNOWN, state.verificationFailed)
    }

    suspend fun dispatch(input: TaskWorktreeMachine.Input): TaskWorktreeMachine.Transition {
        if (state.persistenceUnknown) throw TaskWorktreeJournalUnknown()
        val next = TaskWorktreeMachine.reduce(state, input)
        next.effects.filterIsInstance<TaskWorktreeMachine.Effect.Reject>().firstOrNull()?.let { throw TaskWorktreeRejected(it.reason, it.message) }
        val before = checkNotNull(snapshot)
        val id = UUID.randomUUID().toString()
        val payload = json.encodeToString(Payload.serializer(), Payload(id, owner, input))
        val envelope = Envelope(id, owner, digest(payload), before.revision.resetEpoch)
        val detail = json.encodeToString(Envelope.serializer(), envelope)
        try {
            // A payload acknowledgement is verified before the journal can reference it.
            withContext(NonCancellable) {
                val key = payloadKey(id)
                val previous = payloads.read(key)
                check(previous == null || previous == payload)
                if (previous == null) {
                    var writeFailure: Exception? = null
                    try { payloads.write(key, payload) } catch (failure: Exception) { writeFailure = failure }
                    val written = readBack(key, writeFailure)
                    if (written != payload) throw writeFailure ?: StorageException("worktree-input-write", StorageException.Kind.WRITE)
                    if (writeFailure is CancellationException) throw writeFailure
                }
                readInput(envelope)
            }
            currentCoroutineContext().ensureActive()
            val observed = try {
                val appended = events.append(before.revision, OPERATION, System.currentTimeMillis(), detail)
                    ?: error("Workspace revision changed")
                check(appended.stream == stream && appended.seq > before.revision.seq && appended.operation == OPERATION && appended.detail == detail)
                JournalSnapshot(before.revision.copy(seq = appended.seq), before.records + appended)
            } catch (failure: Exception) {
                val confirmed = try {
                    withContext(NonCancellable) {
                        events.snapshot(stream).also { recovered ->
                            replay(recovered)
                            check(recovered.revision.resetEpoch == before.revision.resetEpoch && recovered.records.size == before.records.size + 1 &&
                                recovered.records.dropLast(1) == before.records && recovered.records.last().seq > before.revision.seq && recovered.records.last().detail == detail)
                            check(readInput(envelope) == input)
                        }
                    }
                } catch (readFailure: Exception) { failure.addSuppressed(readFailure); throw failure }
                snapshot = confirmed
                state = next.state
                if (failure is CancellationException) throw failure
                confirmed
            }
            snapshot = observed
            state = next.state
            return next
        } catch (failure: Exception) {
            fail(failure, "commit.unknown")
            if (failure is CancellationException) throw failure
            throw TaskWorktreeJournalUnknown(failure)
        }
    }

    fun uncertain(failure: Throwable) = fail(failure, "effect.record.failed")

    suspend fun saveOutcome(pending: TaskWorktreeMachine.Pending, fact: TaskWorktreeMachine.Input.Fact) = withContext(NonCancellable) {
        val revision = checkNotNull(snapshot).revision
        val outcome = Outcome(owner, pending.id, pending.taskId, pending.generation, pending.kind, revision.resetEpoch, fact)
        val payload = json.encodeToString(Outcome.serializer(), outcome)
        val encoded = json.encodeToString(StoredOutcome.serializer(), StoredOutcome(payload, digest(payload)))
        val key = outcomeKey(pending.id)
        val prior = payloads.read(key)
        check(prior == null || prior == encoded) { "Workspace operation already has another outcome" }
        var failure: Exception? = null
        if (prior == null) try { payloads.write(key, encoded) } catch (error: Exception) { failure = error }
        if (readBack(key, failure) != encoded) throw failure ?: StorageException("worktree-outcome-write", StorageException.Kind.WRITE)
        if (failure is CancellationException) throw failure
    }

    fun outcome(pending: TaskWorktreeMachine.Pending): TaskWorktreeMachine.Input.Fact? {
        val raw = payloads.read(outcomeKey(pending.id)) ?: return null
        val stored = json.decodeFromString(StoredOutcome.serializer(), raw)
        check(digest(stored.payload) == stored.digest)
        val result = json.decodeFromString(Outcome.serializer(), stored.payload)
        check(result.owner == owner && result.operationId == pending.id && result.taskId == pending.taskId &&
            result.generation == pending.generation && result.kind == pending.kind && result.resetEpoch == checkNotNull(snapshot).revision.resetEpoch)
        return result.fact
    }

    private fun fail(failure: Throwable, event: String) {
        state = TaskWorktreeMachine.reduce(state, TaskWorktreeMachine.Input.Fact.PersistenceUnknown).state
        AppLog.error("coding.worktree", event, mapOf("projectId" to owner.projectId, "sessionId" to owner.sessionId,
            "causeType" to failure.javaClass.simpleName, "result" to "effects_blocked"))
    }

    private fun replay(observed: JournalSnapshot): TaskWorktreeMachine.State {
        check(observed.revision.stream == stream && observed.revision.seq >= 0 && observed.revision.resetEpoch >= 0)
        check(observed.records.isEmpty() || observed.records.last().seq == observed.revision.seq)
        var result = TaskWorktreeMachine.initial(owner)
        var sequence = 0L
        val ids = mutableSetOf<String>()
        for (record in observed.records) {
            check(record.stream == stream && record.seq > sequence && record.seq <= observed.revision.seq && record.operation == OPERATION)
            val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
            check(envelope.owner == owner && envelope.id.isNotBlank() && ids.add(envelope.id) && envelope.resetEpoch == observed.revision.resetEpoch)
            val transition = TaskWorktreeMachine.reduce(result, readInput(envelope))
            check(transition.effects.none { it is TaskWorktreeMachine.Effect.Reject })
            result = transition.state
            sequence = record.seq
        }
        return result
    }

    private fun readInput(envelope: Envelope): TaskWorktreeMachine.Input {
        val raw = payloads.read(payloadKey(envelope.id)) ?: throw StorageException("worktree-input-read", StorageException.Kind.CORRUPT)
        check(digest(raw) == envelope.digest)
        val payload = json.decodeFromString(Payload.serializer(), raw)
        check(payload.id == envelope.id && payload.owner == owner && envelope.owner == owner)
        return payload.input
    }
    private fun readBack(key: String, primary: Exception?): String? = try { payloads.read(key) }
    catch (failure: Exception) {
        if (primary == null) throw failure
        primary.addSuppressed(failure)
        throw primary
    }
    private fun payloadKey(id: String) = "worktree-input:" + encoded(owner.projectId) + ":" + encoded(owner.sessionId) + ":" + encoded(id)
    private fun outcomeKey(id: String) = "worktree-outcome:" + encoded(owner.projectId) + ":" + encoded(owner.sessionId) + ":" + encoded(id)
    private fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private companion object { const val OPERATION = "worktree.input.v1" }
}

class TaskWorktreeJournalUnknown(cause: Throwable? = null) : IllegalStateException(
    "Не удалось подтвердить состояние рабочей копии. Проверьте сохранённый результат; автоматический повтор отключён.", cause)

class TaskWorktreeRejected(val reason: TaskWorktreeMachine.Reason, message: String? = null) : IllegalStateException(message ?: when (reason) {
    TaskWorktreeMachine.Reason.UNKNOWN -> "Исход операции с рабочей копией неизвестен. Проверьте сохранённый результат"
    TaskWorktreeMachine.Reason.BUSY -> "Операция с рабочей копией ещё выполняется"
    TaskWorktreeMachine.Reason.NOT_READY -> "Рабочая копия ещё не готова к этой операции"
    TaskWorktreeMachine.Reason.MISSING -> "Рабочая копия недоступна"
    else -> "Состояние задачи изменилось. Откройте её заново"
})
