package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.Skill
import io.aequicor.magicpaper.domain.SkillMachine
import io.ktor.util.Digest
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Private write authority. The old `skills` key is read once; it never overrides a journal. */
internal class SkillCommandRejected(message: String) : IllegalArgumentException(message)

internal class SkillInputJournal(private val store: KeyValueStore, private val journal: EventJournal, private val json: Json) {
    @Serializable private data class Payload(val owner: String, val resetEpoch: Long, val id: String, val input: SkillMachine.Input)
    @Serializable private data class Ref(val owner: String, val resetEpoch: Long, val id: String, val digest: String)
    var state = SkillMachine.initial()
        private set
    private var revision: JournalRevision? = null
    private var prefix: List<JournalRecord> = emptyList()

    suspend fun isCurrent(): Boolean {
        val observed = journal.snapshot(STREAM)
        validateSnapshot(observed)
        if (observed.revision != revision || observed.records != prefix) return false
        // A stable record prefix alone does not certify its private immutable payloads.
        observed.records.forEach { readInput(it, observed.revision.resetEpoch) }
        return true
    }

    suspend fun restore() {
        val snapshot = journal.snapshot(STREAM)
        validateSnapshot(snapshot)
        var restored = SkillMachine.initial()
        var previous = 0L
        val seen = mutableSetOf<String>()
        for (record in snapshot.records) {
            check(record.stream == STREAM && record.seq > previous && record.operation == OPERATION) { "Invalid skill journal" }
            previous = record.seq
            val (id, input) = readInput(record, snapshot.revision.resetEpoch)
            check(seen.add(id)) { "Duplicate skill input" }
            val transition = SkillMachine.reduce(restored, input)
            check(transition.effects.none { it is SkillMachine.Effect.Reject }) { "Invalid skill transition" }
            restored = transition.state
        }
        state = restored
        revision = snapshot.revision
        prefix = snapshot.records.toList()
        if (!restored.initialized) {
            // A dropped stream is a reset fence, never permission to revive the legacy cache.
            val legacy = if (snapshot.revision.seq == 0L && snapshot.revision.resetEpoch == 0L) store.read("skills")?.let {
                json.decodeFromString(ListSerializer(Skill.serializer()), it)
            }.orEmpty() else emptyList()
            commit(SkillMachine.Fact.Initialized(legacy, Id.new()))
        }
    }

    private suspend fun readInput(record: JournalRecord, epoch: Long): Pair<String, SkillMachine.Input> {
        val ref = json.decodeFromString(Ref.serializer(), record.detail)
        check(ref.owner == STREAM && ref.resetEpoch == epoch) { "Foreign skill journal input" }
        check(ref.id.isNotBlank() && ref.id.all { it.isLetterOrDigit() || it == '-' }) { "Invalid skill payload identity" }
        val raw = checkNotNull(store.read(PREFIX + ref.id)) { "Missing skill input" }
        val element = json.parseToJsonElement(raw)
        check(digest(element.toString()) == ref.digest) { "Changed skill input" }
        val payload = json.decodeFromJsonElement(Payload.serializer(), element)
        check(payload.owner == ref.owner && payload.resetEpoch == ref.resetEpoch && payload.id == ref.id) { "Foreign skill input" }
        return ref.id to payload.input
    }

    suspend fun commit(input: SkillMachine.Input) {
        val frozen = json.decodeFromString(SkillMachine.Input.serializer(), json.encodeToString(SkillMachine.Input.serializer(), input))
        val transition = SkillMachine.reduce(state, frozen)
        transition.effects.filterIsInstance<SkillMachine.Effect.Reject>().firstOrNull()?.let { throw SkillCommandRejected(it.reason) }
        val expected = checkNotNull(revision) { "Skills not restored" }
        if (transition.state == state) {
            try {
                val observed = journal.snapshot(STREAM)
                validateSnapshot(observed)
                check(observed.revision == expected && observed.records == prefix) { "Stale skill owner" }
            } catch (failure: Throwable) { markUnknown(); throw failure }
            return
        }
        var cancellation: CancellationException? = null
        try { withContext(NonCancellable) {
            val payload = Payload(STREAM, expected.resetEpoch, Id.new(), frozen)
            val element = json.encodeToJsonElement(Payload.serializer(), payload)
            val raw = element.toString()
            val key = PREFIX + payload.id
            check(store.read(key) == null) { "Duplicate skill input" }
            store.write(key, raw)
            check(store.read(key) == raw) { "Skill input was not saved" }
            val encoded = json.encodeToString(Ref.serializer(), Ref(payload.owner, payload.resetEpoch, payload.id, digest(element.toString())))
            val at = Id.now()
            var snapshot: JournalSnapshot? = null
            val record = try {
                checkNotNull(journal.append(expected, OPERATION, at, encoded)) { "Stale skill owner" }
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
                record.at == at && record.detail == encoded) { "Invalid skill append acknowledgement" }
            val observed = snapshot ?: journal.snapshot(STREAM)
            validateSnapshot(observed)
            check(observed.revision.resetEpoch == expected.resetEpoch && observed.records == prefix + record) {
                "Skill journal changed during commit"
            }
            check(store.read(key) == raw) { "Committed skill input changed" }
            revision = observed.revision
            prefix = observed.records.toList()
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
            "Invalid skill journal revision"
        }
        if (snapshot.records.isNotEmpty()) check(snapshot.records.last().seq == snapshot.revision.seq) {
            "Skill journal revision does not identify its prefix"
        }
    }

    private suspend fun digest(raw: String): String {
        val digest = Digest("SHA-256")
        digest += raw.encodeToByteArray()
        return digest.build().toHexString()
    }

    fun markUnknown() { state = SkillMachine.reduce(state, SkillMachine.Fact.PersistenceUnknown).state }

    companion object {
        const val STREAM = "skill-library"
        const val OPERATION = "skill.input.v1"
        const val PREFIX = "skill-input-"
    }
}
