package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.util.Id
import io.ktor.util.Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class UsageInputRejected(message: String) : IllegalArgumentException(message)

/** Private journal interpreter; only the usage ledger serializes access to it. */
internal class UsageInputJournal(
    private val repository: UsageRepository,
    private val journal: EventJournal,
    private val payloads: KeyValueStore,
    json: Json,
) {
    private val json = Json(json) { encodeDefaults = true }
    @Serializable private data class Payload(val stream: String, val input: UsageMachine.Input)
    @Serializable private data class Ref(val id: String, val digest: String)
    var state = UsageMachine.initial()
        private set
    private var revision: JournalRevision? = null
    private var prefix: List<JournalRecord> = emptyList()
    private var replacementAfterLegacyFailure = false

    suspend fun restore() {
        replacementAfterLegacyFailure = false
        val snapshot = journal.snapshot(STREAM)
        validateSnapshot(snapshot)
        var restored = UsageMachine.initial()
        val seen = mutableSetOf<String>()
        // Every usage fact keeps its own payload record; reading a batch together lets the store
        // overlap the reads instead of paying one storage round trip per fact on every start.
        for (batch in snapshot.records.chunked(REPLAY_BATCH)) {
            val refs = batch.map { record -> json.decodeFromString(Ref.serializer(), record.detail).also {
                require(seen.add(it.id)) { "Duplicate usage input" }
                validateIdentity(it)
            } }
            val raws = payloads.readAll(refs.map { PREFIX + it.id })
            for ((record, ref) in batch.zip(refs)) {
                val input = decodeInput(ref, raws[PREFIX + ref.id])
                require(record.at == input.stamp.at) { "Changed usage input time" }
                val before = restored
                val next = UsageMachine.reduce(before, input)
                check(next.rejection == null) { "Invalid usage history" }
                MachineTransitionLog.replay(UsageMachine.id, UsageMachine.space, before, input, next.state, next.effects())
                restored = next.state
            }
        }
        state = restored
        prefix = snapshot.records.toList()
        revision = snapshot.revision
        if (!state.initialized) {
            replacementAfterLegacyFailure = snapshot.records.isEmpty()
            val stamp = UsageMachine.Stamp(Id.new(), Id.now())
            val legacy = if (snapshot.revision.seq == 0L && snapshot.revision.resetEpoch == 0L) repository.load()
                else UsageArchive(startedAt = stamp.at)
            commit(UsageMachine.Fact.Initialized(legacy, stamp))
            replacementAfterLegacyFailure = false
        }
    }

    suspend fun replace(input: UsageMachine.Intent) {
        if (replacementAfterLegacyFailure && !state.initialized) state = UsageMachine.initial()
        commit(input)
        replacementAfterLegacyFailure = false
    }

    suspend fun verifiedArchive(): UsageArchive {
        check(state.initialized && !state.persistenceUnknown) { "Usage archive unavailable" }
        verifyCurrent()
        return json.decodeFromString(UsageArchive.serializer(), json.encodeToString(UsageArchive.serializer(), state.archive))
    }

    suspend fun commit(input: UsageMachine.Input) {
        val frozen = json.decodeFromString(UsageMachine.Input.serializer(), json.encodeToString(UsageMachine.Input.serializer(), input))
        val before = state
        val transition = UsageMachine.reduce(before, frozen)
        transition.rejection?.let {
            MachineTransitionLog.append(UsageMachine.id, UsageMachine.space, before, frozen, transition.state, transition.effects())
            throw UsageInputRejected(it)
        }
        val expected = checkNotNull(revision) { "Usage journal was not loaded" }
        if (transition.state == state) { verifyCurrent(); return }
        var cancellation: CancellationException? = null
        try { withContext(NonCancellable) {
            val payload = Payload(STREAM, frozen)
            val raw = json.encodeToString(Payload.serializer(), payload)
            val key = PREFIX + input.stamp.id
            check(payloads.read(key) == null) { "Usage input identity already exists" }
            try { payloads.write(key, raw) }
            catch (failure: Exception) {
                val saved = try { payloads.read(key) } catch (readFailure: Exception) {
                    failure.addSuppressed(readFailure); throw failure
                }
                if (saved != raw) throw failure
                if (failure is CancellationException) cancellation = failure
            }
            check(payloads.read(key) == raw) { "Usage payload was not saved" }
            val ref = Ref(input.stamp.id, digest(raw))
            val encoded = json.encodeToString(Ref.serializer(), ref)
            val record = try {
                checkNotNull(journal.append(expected, OPERATION, input.stamp.at, encoded)) { "Usage journal changed" }
            } catch (failure: Exception) {
                val observed = try { journal.snapshot(STREAM) } catch (readFailure: Exception) {
                    failure.addSuppressed(readFailure); throw failure
                }
                val acknowledged = try {
                    validateSnapshot(observed)
                    checkNotNull(observed.records.lastOrNull()?.takeIf {
                        observed.revision.resetEpoch == expected.resetEpoch && observed.records.dropLast(1) == prefix &&
                            it.seq > expected.seq && it.stream == STREAM && it.operation == OPERATION &&
                            it.at == input.stamp.at && it.detail == encoded
                    }) { "Usage acknowledgement not proven" }
                } catch (proofFailure: Exception) {
                    failure.addSuppressed(proofFailure); throw failure
                }
                if (failure is CancellationException) cancellation = failure
                acknowledged
            }
            check(record.stream == STREAM && record.operation == OPERATION && record.seq > expected.seq &&
                record.at == input.stamp.at && record.detail == encoded) { "Invalid usage acknowledgement" }
            val observed = journal.snapshot(STREAM)
            validateSnapshot(observed)
            check(observed.revision.resetEpoch == expected.resetEpoch && observed.records == prefix + record) { "Usage history changed" }
            check(readInput(ref) == frozen) { "Usage payload changed" }
            MachineTransitionLog.append(UsageMachine.id, UsageMachine.space, before, frozen, transition.state, transition.effects())
            state = transition.state
            revision = observed.revision
            prefix = observed.records.toList()
        } } catch (failure: Throwable) {
            markUnknown()
            val primary = cancellation
            if (primary != null && primary !== failure) { primary.addSuppressed(failure); throw primary }
            throw failure
        }
        cancellation?.let { throw it }
    }

    fun markUnknown() {
        val before = state
        val input = UsageMachine.Fact.PersistenceUnknown(UsageMachine.Stamp("unknown", 0))
        val next = UsageMachine.reduce(before, input)
        MachineTransitionLog.append(UsageMachine.id, UsageMachine.space, before, input, next.state, next.effects())
        state = next.state
    }

    private fun UsageMachine.Transition.effects(): List<UsageMachine.Effect> = listOfNotNull(rejection?.let(UsageMachine.Effect::Reject))

    private suspend fun verifyCurrent() {
        try {
            val observed = journal.snapshot(STREAM)
            validateSnapshot(observed)
            check(observed.revision == revision && observed.records == prefix) { "Usage history changed" }
            // Export and no-op acknowledgements must not silently bless missing private history.
            prefix.forEach { record -> readInput(json.decodeFromString(Ref.serializer(), record.detail)) }
        } catch (failure: Throwable) { markUnknown(); throw failure }
    }

    private suspend fun readInput(ref: Ref): UsageMachine.Input {
        validateIdentity(ref)
        return decodeInput(ref, payloads.read(PREFIX + ref.id))
    }

    private fun validateIdentity(ref: Ref) =
        require(ref.id.isNotBlank() && ref.id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid usage input identity" }

    private suspend fun decodeInput(ref: Ref, stored: String?): UsageMachine.Input {
        val raw = checkNotNull(stored) { "Missing usage payload" }
        check(digest(raw) == ref.digest) { "Changed usage payload" }
        val payload = json.decodeFromString(Payload.serializer(), raw)
        require(payload.stream == STREAM && payload.input.stamp.id == ref.id) { "Foreign usage input" }
        return payload.input
    }

    private fun validateSnapshot(snapshot: JournalSnapshot) {
        require(snapshot.revision.stream == STREAM && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) { "Invalid usage revision" }
        var previous = 0L
        snapshot.records.forEach {
            require(it.stream == STREAM && it.operation == OPERATION && it.seq > previous && it.at >= 0) { "Invalid usage record" }
            previous = it.seq
        }
        require(snapshot.records.isEmpty() || previous == snapshot.revision.seq) { "Invalid usage high-water mark" }
    }

    private suspend fun digest(raw: String): String {
        val digest = Digest("SHA-256")
        digest += raw.encodeToByteArray()
        return digest.build().toHexString()
    }
    companion object {
        const val STREAM = "usage-ledger"
        const val OPERATION = "usage.input.v1"
        const val PREFIX = "usage-input-"
        private const val REPLAY_BATCH = 256
    }
}
