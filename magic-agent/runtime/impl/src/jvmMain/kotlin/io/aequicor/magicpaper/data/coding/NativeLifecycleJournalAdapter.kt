package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.util.Id
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Host-only storage adaptation. Native modules own the grammar and never import EventJournal. */
internal class NativeLifecycleJournalAdapter(private val journal: EventJournal, namespace: String) : NativeLifecycleJournal {
    private val stream = "native-lifecycle.v1/" + MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
    private val json = Json { encodeDefaults = true }
    override suspend fun snapshot(): NativeJournalSnapshot {
        val snapshot = journal.snapshot(stream)
        check(snapshot.revision.stream == stream) { "Native lifecycle journal scope changed" }
        return NativeJournalSnapshot(revision(snapshot.revision), snapshot.records.map { record ->
            check(record.stream == stream) { "Native lifecycle record scope changed" }
            check(record.operation == "native.lifecycle.input.v1") { "Unsupported native lifecycle journal record" }
            json.decodeFromString(NativeJournalEntry.serializer(), record.detail)
        }, snapshot.records.map { it.seq })
    }
    override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? {
        val generation = expected.generation.toLongOrNull() ?: error("Invalid native lifecycle revision")
        val payload = json.encodeToString(NativeJournalEntry.serializer(), entry)
        val record = journal.append(JournalRevision(stream, expected.position, generation), "native.lifecycle.input.v1", Id.now(), payload) ?: return null
        check(record.stream == stream && record.operation == "native.lifecycle.input.v1" && record.detail == payload && record.seq > expected.position) {
            "Native lifecycle append acknowledgement does not match the input"
        }
        return NativeJournalRevision(expected.generation, record.seq)
    }
    private fun revision(value: JournalRevision) = NativeJournalRevision(value.resetEpoch.toString(), value.seq)
}
