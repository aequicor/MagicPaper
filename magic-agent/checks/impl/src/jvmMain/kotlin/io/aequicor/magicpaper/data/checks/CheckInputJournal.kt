package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Called under the workspace owner's lock. Shared envelopes never contain commands, paths or output. */
internal class CheckInputJournal(private val events: EventJournal, private val payloads: KeyValueStore,
    private val workspace: String) {
    @Serializable private data class Payload(val id: String, val workspace: String, val input: CommandCheckMachine.Input)
    @Serializable private data class Envelope(val id: String, val digest: String, val epoch: Long)
    @Serializable private data class Completion(val command: CheckCommand, val proof: CheckCompletionProof,
        val result: CheckResult, val epoch: Long)
    @Serializable private data class StoredCompletion(val payload: String, val digest: String)
    private val json = Json { encodeDefaults = true }
    val stream = "command-check:" + hash(workspace)
    private var snapshot: JournalSnapshot? = null
    var state = CommandCheckMachine.initial(workspace)
        private set

    suspend fun initialize() {
        if (snapshot != null) return
        if (state.persistenceUnknown) throw CheckOutcomeUnknown()
        try {
            val observed = events.snapshot(stream)
            state = replay(observed)
            snapshot = observed
            if (observed.records.isNotEmpty()) append(CommandCheckMachine.Input.Fact.Restored)
        } catch (failure: Exception) {
            uncertain(failure, "restore.failed")
            if (failure is CancellationException) throw failure
            throw CheckOutcomeUnknown(failure)
        }
    }

    suspend fun append(input: CommandCheckMachine.Input): CommandCheckMachine.Transition {
        if (state.persistenceUnknown) throw CheckOutcomeUnknown()
        val before = checkNotNull(snapshot)
        val id = UUID.randomUUID().toString()
        val raw = json.encodeToString(Payload.serializer(), Payload(id, workspace, input))
        // Freeze caller-owned collections before either reduction or persistence can suspend.
        val frozen = json.decodeFromString(Payload.serializer(), raw).input
        val next = CommandCheckMachine.reduce(state, frozen)
        next.effects.filterIsInstance<CommandCheckMachine.Effect.Reject>().firstOrNull()?.let { throw CheckRejected(it.reason) }
        val envelope = Envelope(id, hash(raw), before.revision.resetEpoch)
        val detail = json.encodeToString(Envelope.serializer(), envelope)
        val at = System.currentTimeMillis()
        try {
            withContext(NonCancellable) { immutable(inputKey(id), raw) }
            currentCoroutineContext().ensureActive()
            val after = try {
                val record = events.append(before.revision, OPERATION, at, detail) ?: error("Check revision changed")
                check(record.stream == stream && record.operation == OPERATION && record.at == at &&
                    record.seq > before.revision.seq && record.detail == detail)
                JournalSnapshot(before.revision.copy(seq = record.seq), before.records + record)
            } catch (failure: Exception) {
                val recovered = try { withContext(NonCancellable) {
                    events.snapshot(stream).also { observed ->
                        replay(observed)
                        val last = observed.records.lastOrNull()
                        check(observed.revision.resetEpoch == before.revision.resetEpoch &&
                            observed.records.size == before.records.size + 1 && observed.records.dropLast(1) == before.records &&
                            last != null && last.seq > before.revision.seq && last.operation == OPERATION &&
                            last.at == at && last.detail == detail && readInput(envelope) == frozen)
                    }
                } } catch (readFailure: Exception) { failure.addSuppressed(readFailure); throw failure }
                snapshot = recovered
                state = next.state
                if (failure is CancellationException) throw failure
                recovered
            }
            snapshot = after
            state = next.state
            return next
        } catch (failure: Exception) {
            uncertain(failure, "commit.unknown")
            if (failure is CancellationException) throw failure
            throw CheckOutcomeUnknown(failure)
        }
    }

    suspend fun saveCompletion(ref: CheckRef, proof: CheckCompletionProof, result: CheckResult) = withContext(NonCancellable) {
        val check = checkNotNull(state.checks[ref])
        check(check.process?.id == proof.receiptId && proof.groupStopped.isNotBlank() &&
            proof.authorityRestored.isNotBlank() && proof.artifactsCommitted.isNotBlank())
        check(check.result == null || check.result == result)
        check(check.groupStopped == null || check.groupStopped == proof.groupStopped)
        check(check.authorityRestored == null || check.authorityRestored == proof.authorityRestored)
        val raw = json.encodeToString(Completion.serializer(), Completion(check.command, proof, result, checkNotNull(snapshot).revision.resetEpoch))
        immutable(completionKey(ref), json.encodeToString(StoredCompletion.serializer(), StoredCompletion(raw, hash(raw))))
    }

    suspend fun recordAuthority(ref: CheckRef, id: String, bytes: ByteArray): String {
        require(id.isNotBlank())
        val key = "check-authority:${hash(workspace)}:$id:${checkNotNull(snapshot).revision.resetEpoch}"
        withContext(NonCancellable) { immutable(key, Base64.getEncoder().encodeToString(bytes)) }
        append(CommandCheckMachine.Input.Fact.AuthorityRecorded(ref, key))
        return key
    }

    fun completion(ref: CheckRef): CommandCheckMachine.Input.Fact.CompletionRecovered? {
        val raw = payloads.read(completionKey(ref)) ?: return null
        val stored = json.decodeFromString(StoredCompletion.serializer(), raw)
        check(hash(stored.payload) == stored.digest)
        val value = json.decodeFromString(Completion.serializer(), stored.payload)
        check(value.command == state.checks[ref]?.command && value.command.ref == ref &&
            value.epoch == checkNotNull(snapshot).revision.resetEpoch && state.checks[ref]?.process?.id == value.proof.receiptId)
        return CommandCheckMachine.Input.Fact.CompletionRecovered(ref, value.proof, value.result)
    }

    fun uncertain(failure: Throwable, event: String = "effect.unknown") {
        state = CommandCheckMachine.reduce(state, CommandCheckMachine.Input.Fact.PersistenceUnknown).state
        AppLog.error("checks", event, mapOf("workspaceId" to hash(workspace),
            "causeType" to failure.javaClass.simpleName, "result" to "effects_blocked"))
    }

    private fun replay(observed: JournalSnapshot): CommandCheckMachine.State {
        check(observed.revision.stream == stream && observed.revision.seq >= 0 && observed.revision.resetEpoch >= 0)
        check(observed.records.isEmpty() || observed.records.last().seq == observed.revision.seq)
        var result = CommandCheckMachine.initial(workspace)
        var sequence = 0L
        val ids = mutableSetOf<String>()
        observed.records.forEach { record ->
            check(record.stream == stream && record.seq > sequence && record.seq <= observed.revision.seq && record.operation == OPERATION)
            val envelope = json.decodeFromString(Envelope.serializer(), record.detail)
            check(envelope.id.isNotBlank() && ids.add(envelope.id) && envelope.epoch == observed.revision.resetEpoch)
            val next = CommandCheckMachine.reduce(result, readInput(envelope))
            check(next.effects.none { it is CommandCheckMachine.Effect.Reject })
            result = next.state
            sequence = record.seq
        }
        return result
    }

    private fun readInput(envelope: Envelope): CommandCheckMachine.Input {
        val raw = payloads.read(inputKey(envelope.id)) ?: throw StorageException("check-input-read", StorageException.Kind.CORRUPT)
        check(hash(raw) == envelope.digest)
        val value = json.decodeFromString(Payload.serializer(), raw)
        check(value.id == envelope.id && value.workspace == workspace)
        return value.input
    }
    private fun immutable(key: String, raw: String) {
        val previous = payloads.read(key)
        check(previous == null || previous == raw) { "Check receipt already has another value" }
        if (previous != null) return
        var failure: Exception? = null
        try { payloads.write(key, raw) } catch (error: Exception) { failure = error }
        val observed = try { payloads.read(key) } catch (readFailure: Exception) {
            if (failure == null) throw readFailure
            failure.addSuppressed(readFailure)
            throw failure
        }
        if (observed != raw) throw failure ?: StorageException("check-input-write", StorageException.Kind.WRITE)
        if (failure is CancellationException) throw failure
    }
    private fun inputKey(id: String) = "check-input:${hash(workspace)}:$id"
    private fun completionKey(ref: CheckRef) = "check-completion:${hash(workspace)}:" + hash(json.encodeToString(CheckRef.serializer(), ref))
    companion object {
        const val OPERATION = "check.input.v1"
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        suspend fun workspace(events: EventJournal, payloads: KeyValueStore, stream: String): String? {
            require(stream.startsWith("command-check:"))
            val first = events.snapshot(stream).records.firstOrNull() ?: return null
            val json = Json { encodeDefaults = true }
            val envelope = json.decodeFromString(Envelope.serializer(), first.detail)
            val raw = checkNotNull(payloads.read("check-input:${stream.removePrefix("command-check:")}:${envelope.id}"))
            check(hash(raw) == envelope.digest)
            val input = json.decodeFromString(Payload.serializer(), raw)
            check(input.id == envelope.id && "command-check:" + hash(input.workspace) == stream)
            return input.workspace
        }
    }
}

internal class CheckRejected(val reason: CommandCheckMachine.Reason) : IllegalStateException(when (reason) {
    CommandCheckMachine.Reason.UNKNOWN -> "Завершение предыдущей проверки не подтверждено"
    CommandCheckMachine.Reason.BUSY -> "Проверка этой рабочей копии уже выполняется"
    CommandCheckMachine.Reason.PAYLOAD_CHANGED -> "Этот запрос проверки уже содержит другую команду"
    else -> "Запрос проверки устарел или недоступен"
})
