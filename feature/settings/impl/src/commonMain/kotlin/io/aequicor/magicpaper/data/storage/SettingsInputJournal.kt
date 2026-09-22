package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.SettingsMachine
import io.ktor.util.Digest
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private write authority. Legacy snapshots are imported only before the first input. */
internal class SettingsInputRejected(message: String) : IllegalArgumentException(message)

internal class SettingsInputJournal(private val store: KeyValueStore, private val journal: EventJournal, private val json: Json) {
    @Serializable private data class Payload(val id: String, val input: SettingsMachine.Input)
    @Serializable private data class Ref(val id: String, val digest: String)
    var state = SettingsMachine.initial()
        private set
    private var revision: JournalRevision? = null
    private var prefix: List<JournalRecord> = emptyList()

    suspend fun restore(legacy: suspend () -> SettingsMachine.Fact.Initialized) {
        try {
        val snapshot = journal.snapshot(STREAM)
        validateSnapshot(snapshot)
        var restored = SettingsMachine.initial()
        var previous = 0L
        val seen = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == STREAM && record.seq > previous && record.operation == OPERATION) { "Invalid settings journal" }
            previous = record.seq
            val ref = json.decodeFromString(Ref.serializer(), record.detail)
            check(seen.add(ref.id)) { "Duplicate settings input" }
            check(ref.id.isNotBlank() && ref.id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid settings payload identity" }
            val raw = checkNotNull(store.read(PREFIX + ref.id)) { "Missing settings input" }
            val element = json.parseToJsonElement(raw)
            check(digest(element.toString()) == ref.digest) { "Changed settings input" }
            val payload = json.decodeFromJsonElement(Payload.serializer(), element)
            check(payload.id == ref.id) { "Foreign settings input" }
            val before = restored
            val transition = SettingsMachine.reduce(before, payload.input)
            check(transition.effects.none { it is SettingsMachine.Effect.Reject }) { "Invalid settings transition" }
            MachineTransitionLog.replay(SettingsMachine.id, SettingsMachine.space, before, payload.input, transition.state, transition.effects)
            restored = transition.state
        }
        state = restored
        revision = snapshot.revision
        prefix = snapshot.records.toList()
        if (!restored.initialized) {
            // A reset epoch never grants permission to revive an obsolete legacy snapshot.
            val initial = if (snapshot.revision.seq == 0L && snapshot.revision.resetEpoch == 0L) legacy()
                else SettingsMachine.Fact.Initialized(io.aequicor.magicpaper.domain.SettingsRecord(
                    io.aequicor.magicpaper.domain.AppSettings()), emptyList(), emptyList(), Id.new())
            commit(initial)
        }
        if ((state.catalogs.keys + state.descriptions.keys).any { it !in state.interrupted } || state.change?.unknown == false)
            commit(SettingsMachine.Fact.Interrupted)
        } catch (failure: Throwable) { markUnknown(); throw failure }
    }

    suspend fun commit(input: SettingsMachine.Input): List<SettingsMachine.Effect> {
        val frozen = json.decodeFromString(SettingsMachine.Input.serializer(), json.encodeToString(SettingsMachine.Input.serializer(), input))
        val before = state
        val transition = SettingsMachine.reduce(before, frozen)
        transition.effects.filterIsInstance<SettingsMachine.Effect.Reject>().firstOrNull()?.let {
            MachineTransitionLog.append(SettingsMachine.id, SettingsMachine.space, before, frozen, transition.state, transition.effects)
            throw SettingsInputRejected(it.reason)
        }
        val expected = checkNotNull(revision) { "Settings not restored" }
        if (transition.state == state) {
            try {
                val observed = journal.snapshot(STREAM)
                validateSnapshot(observed)
                check(observed.revision == expected && observed.records == prefix) { "Stale settings owner" }
            } catch (failure: Throwable) { markUnknown(); throw failure }
            return transition.effects
        }
        var cancellation: CancellationException? = null
        try { withContext(NonCancellable) {
            val payload = Payload(Id.new(), frozen)
            val element = json.encodeToJsonElement(Payload.serializer(), payload)
            val raw = element.toString()
            val key = PREFIX + payload.id
            check(store.read(key) == null) { "Duplicate settings input" }
            store.write(key, raw)
            check(store.read(key) == raw) { "Settings input was not saved" }
            val encoded = json.encodeToString(Ref.serializer(), Ref(payload.id, digest(element.toString())))
            val at = Id.now()
            var snapshot: JournalSnapshot? = null
            val record = try {
                checkNotNull(journal.append(expected, OPERATION, at, encoded)) { "Stale settings owner" }
            } catch (failure: Exception) {
                if (failure is CancellationException) cancellation = failure
                val observed = try { journal.snapshot(STREAM) } catch (readFailure: Exception) {
                    if (readFailure is CancellationException && failure !is CancellationException) {
                        readFailure.addSuppressed(failure); throw readFailure
                    }
                    if (failure !== readFailure) failure.addSuppressed(readFailure)
                    throw failure
                }
                validateSnapshot(observed)
                snapshot = observed
                // Only an exact preserved prefix plus this one input settles a lost acknowledgement.
                observed.records.lastOrNull()?.takeIf {
                    observed.revision.resetEpoch == expected.resetEpoch &&
                        observed.records.dropLast(1) == prefix && it.seq > expected.seq &&
                        it.stream == STREAM && it.operation == OPERATION && it.at == at && it.detail == encoded
                } ?: throw failure
            }
            check(record.stream == STREAM && record.seq > expected.seq && record.operation == OPERATION &&
                record.at == at && record.detail == encoded) { "Invalid settings append acknowledgement" }
            val observed = snapshot ?: journal.snapshot(STREAM)
            validateSnapshot(observed)
            check(observed.revision.resetEpoch == expected.resetEpoch && observed.records == prefix + record) {
                "Settings journal changed during commit"
            }
            check(store.read(key) == raw) { "Committed settings input changed" }
            revision = observed.revision
            prefix = observed.records.toList()
            MachineTransitionLog.append(SettingsMachine.id, SettingsMachine.space, before, frozen, transition.state, transition.effects)
            state = transition.state
        } } catch (failure: Throwable) {
            markUnknown()
            cancellation?.let { cancelled ->
                if (failure !== cancelled) cancelled.addSuppressed(failure)
                throw cancelled
            }
            throw failure
        }
        cancellation?.let { throw it }
        return transition.effects
    }

    private fun validateSnapshot(snapshot: JournalSnapshot) {
        check(snapshot.revision.stream == STREAM && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) {
            "Invalid settings journal revision"
        }
        if (snapshot.records.isNotEmpty()) check(snapshot.records.last().seq == snapshot.revision.seq) {
            "Settings journal revision does not identify its prefix"
        }
    }

    private suspend fun digest(raw: String): String {
        val digest = Digest("SHA-256")
        digest += raw.encodeToByteArray()
        return digest.build().toHexString()
    }

    fun markUnknown() {
        val before = state
        val transition = SettingsMachine.reduce(before, SettingsMachine.Fact.PersistenceUnknown)
        MachineTransitionLog.append(SettingsMachine.id, SettingsMachine.space, before, SettingsMachine.Fact.PersistenceUnknown, transition.state, transition.effects)
        state = transition.state
    }

    companion object {
        const val STREAM = "settings-configuration"
        const val OPERATION = "settings.input.v1"
        const val PREFIX = "settings-input-"
    }
}
