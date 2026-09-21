package io.aequicor.magicpaper.plugins

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JournalRecord
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.data.storage.JournalSnapshot
import io.aequicor.magicpaper.domain.PluginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faults at the storage boundary must never acknowledge a different preference history. */
class PluginJournalIntegrityTest {
    private val json = Json { encodeDefaults = true }

    @Test fun malformedDirectAcknowledgementCannotPublishSuccess() = runTest {
        val corruptions: List<(JournalRecord) -> JournalRecord> = listOf(
            { it.copy(stream = "foreign-owner") },
            { it.copy(seq = 0) },
            { it.copy(at = it.at + 1) },
            { it.copy(operation = "foreign.operation") },
            { it.copy(detail = "{}") },
        )
        for (corrupt in corruptions) {
            val store = InMemoryKeyValueStore()
            val journal = FaultJournal()
            val owner = PluginInputJournal(store, journal, json)
            owner.restore()
            val before = owner.state
            journal.corruptAcknowledgement = corrupt
            assertFails { owner.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false)))) }
            assertEquals(before.preferences, owner.state.preferences)
            assertFalse("a" in owner.state.preferences)
        }
    }

    @Test fun lostAcknowledgementRequiresTheExactPreviouslyVerifiedPrefix() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = FaultJournal()
        val owner = PluginInputJournal(store, journal, json)
        owner.restore()
        val before = owner.state
        journal.afterAppend = {
            journal.transformSnapshot = { snapshot ->
                snapshot.copy(records = snapshot.records.mapIndexed { index, record ->
                    if (index == 0) record.copy(at = record.at + 1) else record
                })
            }
        }
        journal.loseAcknowledgement = true
        assertFails { owner.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false)))) }
        assertEquals(before.preferences, owner.state.preferences)
        assertEquals(2, journal.delegate.read(PluginInputJournal.STREAM).size)
    }

    @Test fun lostAcknowledgementCannotHideMissingOrChangedCommittedPrivatePayload() = runTest {
        for (remove in listOf(false, true)) {
            val store = InMemoryKeyValueStore()
            val journal = FaultJournal()
            val owner = PluginInputJournal(store, journal, json)
            owner.restore()
            val before = owner.state
            journal.afterAppend = { record ->
                val id = json.parseToJsonElement(record.detail).jsonObject.getValue("id").jsonPrimitive.content
                val key = PluginInputJournal.PREFIX + id
                if (remove) store.delete(key)
                else store.write(key, checkNotNull(store.read(key)).replace("private-value", "changed-value"))
            }
            journal.loseAcknowledgement = true
            assertFails {
                owner.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false, mapOf("setting" to "private-value")))))
            }
            assertEquals(before.preferences, owner.state.preferences)
            assertTrue(journal.delegate.read(PluginInputJournal.STREAM).none { "private-value" in it.detail })
        }
    }

    @Test fun cancelledAppendRemainsPrimaryWhenReadbackRevisionOrPayloadIsInvalid() = runTest {
        for (corruptPayload in listOf(false, true)) {
            val store = InMemoryKeyValueStore()
            val journal = FaultJournal()
            val owner = PluginInputJournal(store, journal, json)
            owner.restore()
            val before = owner.state
            val cancelled = CancellationException("Controlled append cancellation")
            journal.afterAppend = { record ->
                if (corruptPayload) {
                    val id = json.parseToJsonElement(record.detail).jsonObject.getValue("id").jsonPrimitive.content
                    store.delete(PluginInputJournal.PREFIX + id)
                } else journal.transformSnapshot = { snapshot ->
                    snapshot.copy(revision = snapshot.revision.copy(seq = snapshot.revision.seq + 1))
                }
                throw cancelled
            }
            val failure = assertFailsWith<CancellationException> {
                owner.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false))))
            }
            val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(causes.any { it === cancelled })
            assertTrue(causes.any { it.suppressedExceptions.isNotEmpty() })
            assertEquals(before.preferences, owner.state.preferences)
            assertTrue(owner.state.persistenceUnknown)
            assertFails { owner.commit(PluginMachine.Intent.Import(emptyList())) }
        }
    }

    @Test fun invalidEmptyRevisionCannotImportOrAppendLegacyPreferences() = runTest {
        val corruptions: List<(JournalRevision) -> JournalRevision> = listOf(
            { it.copy(stream = "foreign-owner") },
            { it.copy(seq = -1) },
            { it.copy(resetEpoch = -1) },
        )
        for (corrupt in corruptions) {
            val store = InMemoryKeyValueStore()
            val legacy = json.encodeToString(ListSerializer(PluginState.serializer()), listOf(PluginState("a", false)))
            store.write("plugins", legacy)
            val journal = FaultJournal().apply {
                transformSnapshot = { it.copy(revision = corrupt(it.revision)) }
            }
            val owner = PluginInputJournal(store, journal, json)
            assertFails { owner.restore() }
            assertFalse(owner.state.initialized)
            assertTrue(journal.delegate.read(PluginInputJournal.STREAM).isEmpty())
            assertTrue(store.keys(PluginInputJournal.PREFIX).isEmpty())
            assertEquals(legacy, store.read("plugins"))
        }
    }

    @Test fun revisionMustIdentifyTheLastRecordAndNotAnUnverifiedSuffix() = runTest {
        for (delta in listOf(-1L, 1L)) {
            val store = InMemoryKeyValueStore()
            val journal = FaultJournal()
            val original = PluginInputJournal(store, journal, json)
            original.restore()
            original.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false))))
            val records = journal.delegate.read(PluginInputJournal.STREAM)
            journal.transformSnapshot = { it.copy(revision = it.revision.copy(seq = it.revision.seq + delta)) }
            val restored = PluginInputJournal(store, journal, json)
            assertFails { restored.restore() }
            assertFalse(restored.state.initialized)
            assertEquals(records, journal.delegate.read(PluginInputJournal.STREAM))
        }
    }

    @Test fun emptyStreamInANewResetEpochNeverRevivesLegacyPrivateConfig() = runTest {
        val store = InMemoryKeyValueStore()
        val legacy = json.encodeToString(ListSerializer(PluginState.serializer()),
            listOf(PluginState("a", false, mapOf("stale" to "private-value"))))
        store.write("plugins", legacy)
        val delegate = InMemoryEventJournal()
        val resetJournal = object : EventJournal by delegate {
            override suspend fun snapshot(stream: String): JournalSnapshot =
                delegate.snapshot(stream).let { it.copy(revision = it.revision.copy(resetEpoch = 1)) }
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? =
                delegate.append(expected.copy(resetEpoch = 0), operation, at, detail)
        }
        val owner = PluginInputJournal(store, resetJournal, json)
        owner.restore()
        assertTrue(owner.state.initialized)
        assertTrue(owner.state.preferences.isEmpty())
        assertEquals(legacy, store.read("plugins"))
        val restored = PluginInputJournal(store, resetJournal, json)
        restored.restore()
        assertEquals(owner.state.preferences, restored.state.preferences)
    }

    @Test fun callerMutationCannotChangeCommittedPreferencesOrTheirReplay() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = InMemoryEventJournal()
        val owner = PluginInputJournal(store, journal, json)
        owner.restore()
        val config = mutableMapOf("setting" to "committed")
        val preferences = mutableListOf(PluginState("a", false, config))
        owner.commit(PluginMachine.Intent.Import(preferences))
        val records = journal.read(PluginInputJournal.STREAM)
        config["setting"] = "changed-without-command"
        config["extra"] = "not-committed"
        preferences += PluginState("foreign", false)
        assertEquals(mapOf("setting" to "committed"), owner.state.preferences.getValue("a").config)
        assertFalse("foreign" in owner.state.preferences)
        assertEquals(records, journal.read(PluginInputJournal.STREAM))
        val restored = PluginInputJournal(store, journal, json)
        restored.restore()
        assertEquals(owner.state.preferences, restored.state.preferences)
    }

    @Test fun unchangedCommandCannotAcknowledgeStalePreferencesAfterAnotherOwnersWrite() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = InMemoryEventJournal()
        val old = PluginInputJournal(store, journal, json)
        old.restore()
        old.commit(PluginMachine.Intent.Import(listOf(PluginState("a", false))))
        val current = PluginInputJournal(store, journal, json)
        current.restore()
        current.commit(PluginMachine.Intent.Toggle("a", true))
        assertFails { old.commit(PluginMachine.Intent.Toggle("a", false)) }
        assertTrue(old.state.persistenceUnknown)
        val restored = PluginInputJournal(store, journal, json)
        restored.restore()
        assertTrue(restored.state.preferences.getValue("a").enabled)
    }

    private class FaultJournal(val delegate: EventJournal = InMemoryEventJournal()) : EventJournal by delegate {
        var corruptAcknowledgement: (JournalRecord) -> JournalRecord = { it }
        var transformSnapshot: (JournalSnapshot) -> JournalSnapshot = { it }
        var afterAppend: (JournalRecord) -> Unit = {}
        var loseAcknowledgement = false

        override suspend fun snapshot(stream: String): JournalSnapshot = transformSnapshot(delegate.snapshot(stream))

        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val record = delegate.append(expected, operation, at, detail) ?: return null
            afterAppend(record)
            if (loseAcknowledgement) error("Lost append acknowledgement")
            return corruptAcknowledgement(record)
        }
    }
}
