package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.PluginState
import io.ktor.util.Digest
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Private write authority. The old `plugins` key is read once; it never overrides a journal. */
internal class PluginPreferenceRejected(message: String) : IllegalArgumentException(message)

internal class PluginInputJournal(private val store: KeyValueStore, private val journal: EventJournal, private val json: Json) {
    @Serializable private data class Payload(val id: String, val input: PluginMachine.Input)
    @Serializable private data class Ref(val id: String, val digest: String)
    var state = PluginMachine.initial()
        private set
    private var revision: JournalRevision? = null
    private var prefix: List<JournalRecord> = emptyList()

    suspend fun restore() {
        val snapshot = journal.snapshot(STREAM)
        validateSnapshot(snapshot)
        var restored = PluginMachine.initial()
        var previous = 0L
        val seen = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == STREAM && record.seq > previous && record.operation == OPERATION) { "Invalid plugin journal" }
            previous = record.seq
            val ref = json.decodeFromString(Ref.serializer(), record.detail)
            check(seen.add(ref.id)) { "Duplicate plugin input" }
            check(ref.id.isNotBlank() && ref.id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid plugin payload identity" }
            val raw = checkNotNull(store.read(PREFIX + ref.id)) { "Missing plugin input" }
            val element = json.parseToJsonElement(raw)
            check(digest(element.toString()) == ref.digest) { "Changed plugin input" }
            val payload = json.decodeFromJsonElement(Payload.serializer(), element)
            check(payload.id == ref.id) { "Foreign plugin input" }
            val before = restored
            val transition = PluginMachine.reduce(before, payload.input)
            check(transition.effects.none { it is PluginMachine.Effect.Reject }) { "Invalid plugin transition" }
            MachineTransitionLog.replay(PluginMachine.id, PluginMachine.space, before, payload.input, transition.state, transition.effects)
            restored = transition.state
        }
        state = restored
        revision = snapshot.revision
        prefix = snapshot.records.toList()
        if (!restored.initialized) {
            // A dropped stream is a reset fence, never permission to revive the legacy cache.
            val legacy = if (snapshot.revision.seq == 0L && snapshot.revision.resetEpoch == 0L) store.read("plugins")?.let {
                json.decodeFromString(ListSerializer(PluginState.serializer()), it)
            }.orEmpty() else emptyList()
            commit(PluginMachine.Fact.Initialized(legacy))
        }
    }

    suspend fun commit(input: PluginMachine.Input) {
        val frozen = json.decodeFromString(PluginMachine.Input.serializer(), json.encodeToString(PluginMachine.Input.serializer(), input))
        val before = state
        val transition = PluginMachine.reduce(before, frozen)
        transition.effects.filterIsInstance<PluginMachine.Effect.Reject>().firstOrNull()?.let {
            MachineTransitionLog.append(PluginMachine.id, PluginMachine.space, before, frozen, transition.state, transition.effects)
            throw PluginPreferenceRejected(it.reason)
        }
        val expected = checkNotNull(revision) { "Plugins not restored" }
        if (transition.state == state) {
            try {
                val observed = journal.snapshot(STREAM)
                validateSnapshot(observed)
                check(observed.revision == expected && observed.records == prefix) { "Stale plugin owner" }
            } catch (failure: Throwable) { markUnknown(); throw failure }
            return
        }
        var cancellation: CancellationException? = null
        try { withContext(NonCancellable) {
            val payload = Payload(Id.new(), frozen)
            val element = json.encodeToJsonElement(Payload.serializer(), payload)
            val raw = element.toString()
            val key = PREFIX + payload.id
            check(store.read(key) == null) { "Duplicate plugin input" }
            store.write(key, raw)
            check(store.read(key) == raw) { "Plugin input was not saved" }
            val encoded = json.encodeToString(Ref.serializer(), Ref(payload.id, digest(element.toString())))
            val at = Id.now()
            var snapshot: JournalSnapshot? = null
            val record = try {
                checkNotNull(journal.append(expected, OPERATION, at, encoded)) { "Stale plugin owner" }
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
                record.at == at && record.detail == encoded) { "Invalid plugin append acknowledgement" }
            val observed = snapshot ?: journal.snapshot(STREAM)
            validateSnapshot(observed)
            check(observed.revision.resetEpoch == expected.resetEpoch && observed.records == prefix + record) {
                "Plugin journal changed during commit"
            }
            check(store.read(key) == raw) { "Committed plugin input changed" }
            revision = observed.revision
            prefix = observed.records.toList()
            MachineTransitionLog.append(PluginMachine.id, PluginMachine.space, before, frozen, transition.state, transition.effects)
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
    }

    private fun validateSnapshot(snapshot: JournalSnapshot) {
        check(snapshot.revision.stream == STREAM && snapshot.revision.seq >= 0 && snapshot.revision.resetEpoch >= 0) {
            "Invalid plugin journal revision"
        }
        if (snapshot.records.isNotEmpty()) check(snapshot.records.last().seq == snapshot.revision.seq) {
            "Plugin journal revision does not identify its prefix"
        }
    }

    private suspend fun digest(raw: String): String {
        val digest = Digest("SHA-256")
        digest += raw.encodeToByteArray()
        return digest.build().toHexString()
    }

    fun markUnknown() {
        val before = state
        val transition = PluginMachine.reduce(before, PluginMachine.Fact.PersistenceUnknown)
        MachineTransitionLog.append(PluginMachine.id, PluginMachine.space, before, PluginMachine.Fact.PersistenceUnknown, transition.state, transition.effects)
        state = transition.state
    }

    companion object {
        const val STREAM = "plugin-preferences"
        const val OPERATION = "plugin.input.v1"
        const val PREFIX = "plugin-input-"
    }
}
