package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SettingsInputJournalTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private suspend fun SettingsInputJournal.initialize(record: SettingsRecord = SettingsRecord(AppSettings())) =
        restore { SettingsMachine.Fact.Initialized(record, emptyList(), emptyList(), "initial") }

    @Test fun restoreDoesNotHydrateDeletedHistoricalSecrets() = runTest {
        val store = InMemoryKeyValueStore()
        val backing = InMemorySecretStore()
        val read = mutableListOf<String>()
        val secrets = object : SecretStore by backing {
            override suspend fun read(reference: String): String? { read += reference; return backing.read(reference) }
        }
        val records = SettingsCredentialRecords(store, secrets, json, null)
        val journal = InMemoryEventJournal()
        val owner = SettingsInputJournal(store, journal, json)
        val initial = records.encode(AppSettings(googleApiKey = "old-secret"))
        owner.initialize(initial)
        records.checkpoint(owner.state)
        val next = records.encode(AppSettings(googleApiKey = "current-secret"))
        records.stagePrevious(owner.state)
        owner.commit(SettingsMachine.Intent.SaveSettings("initial", next, "replacement"))
        records.checkpoint(owner.state)
        assertNull(backing.read(initial.credentials.getValue("googleApiKey")))
        read.clear()
        val restored = SettingsInputJournal(store, journal, json)
        restored.restore { error("Legacy data must not be read") }
        assertEquals("current-secret", records.hydrate(restored.state.settings).googleApiKey)
        assertEquals(listOf(next.credentials.getValue("googleApiKey")), read)
        store.keys(SettingsInputJournal.PREFIX).forEach { key ->
            assertFalse(checkNotNull(store.read(key)).contains("old-secret"))
            assertFalse(checkNotNull(store.read(key)).contains("current-secret"))
        }
    }

    @Test fun lostAppendReplyPublishesOnlyTheExactCommittedConfiguration() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = FaultJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        journal.failureAfterAppend = IllegalStateException("lost reply")
        owner.commit(SettingsMachine.Intent.SaveSettings("initial", SettingsRecord(AppSettings(onboardingDone = true)), "saved"))
        assertTrue(owner.state.settings.value.onboardingDone)
        assertFalse(owner.state.persistenceUnknown)
        val restored = SettingsInputJournal(store, journal.delegate, json)
        restored.restore { error("Legacy data must not be read") }
        assertEquals(owner.state, restored.state)
    }

    @Test fun cancellationAfterCommitKeepsKnownStateAndPropagatesCancellation() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = FaultJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        val cancellation = CancellationException("cancelled append")
        journal.failureAfterAppend = cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> {
            owner.commit(SettingsMachine.Intent.SaveSettings("initial", SettingsRecord(AppSettings(onboardingDone = true)), "saved"))
        })
        assertTrue(owner.state.settings.value.onboardingDone)
        assertFalse(owner.state.persistenceUnknown)
    }

    @Test fun foreignAcknowledgementKeepsPreviousProjectionAndFencesFurtherWrites() = runTest {
        val mutations: List<(JournalRecord) -> JournalRecord> = listOf(
            { it.copy(stream = "other") }, { it.copy(seq = 0) }, { it.copy(operation = "other") },
            { it.copy(at = it.at + 1) }, { it.copy(detail = "{}") })
        for (mutate in mutations) {
            val store = InMemoryKeyValueStore()
            val journal = FaultJournal()
            val owner = SettingsInputJournal(store, journal, json)
            owner.initialize()
            journal.ack = mutate
            assertFails { owner.commit(SettingsMachine.Intent.SaveSettings("initial", SettingsRecord(AppSettings(onboardingDone = true)), "saved")) }
            assertFalse(owner.state.settings.value.onboardingDone)
            assertTrue(owner.state.persistenceUnknown)
            assertFails { owner.commit(SettingsMachine.Intent.Clear("clear")) }
        }
    }

    @Test fun changedPrivatePayloadCannotBeAcceptedAfterLostReplyOrRestore() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = FaultJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        journal.afterAppend = { record ->
            val id = json.parseToJsonElement(record.detail).jsonObject.getValue("id").jsonPrimitive.content
            val key = SettingsInputJournal.PREFIX + id
            store.write(key, checkNotNull(store.read(key)).replace("saved", "foreign"))
        }
        journal.failureAfterAppend = IllegalStateException("lost reply")
        assertFails { owner.commit(SettingsMachine.Intent.SaveSettings("initial", SettingsRecord(AppSettings(onboardingDone = true)), "saved")) }
        assertTrue(owner.state.persistenceUnknown)
        val restored = SettingsInputJournal(store, journal.delegate, json)
        assertFails { restored.restore { error("Legacy data must not be read") } }
        assertTrue(restored.state.persistenceUnknown)
    }

    @Test fun changedPrefixCannotSettleALostAppendReply() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = FaultJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        journal.afterAppend = { journal.snapshotMap = { snapshot -> snapshot.copy(records = snapshot.records.mapIndexed { index, record ->
            if (index == 0) record.copy(at = record.at + 1) else record
        }) } }
        journal.failureAfterAppend = IllegalStateException("lost reply")
        assertFails { owner.commit(SettingsMachine.Intent.SaveSettings("initial", SettingsRecord(AppSettings(onboardingDone = true)), "saved")) }
        assertTrue(owner.state.persistenceUnknown)
        assertFalse(owner.state.settings.value.onboardingDone)
    }

    @Test fun droppedStreamDoesNotRestoreObsoleteCredentialCache() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = InMemoryEventJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize(SettingsRecord(AppSettings(onboardingDone = true)))
        journal.drop(SettingsInputJournal.STREAM)
        val restored = SettingsInputJournal(store, journal, json)
        restored.restore { error("Old snapshot must not be imported after reset") }
        assertFalse(restored.state.settings.value.onboardingDone)
        assertTrue(restored.state.profiles.isEmpty())
        assertFails { owner.commit(SettingsMachine.Intent.Clear("stale-clear")) }
    }

    @Test fun restoreRecordsInterruptionWithoutRepeatingProviderEffects() = runTest {
        val store = InMemoryKeyValueStore()
        val journal = InMemoryEventJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        owner.commit(SettingsMachine.Intent.SaveProfile(null, SettingsProfileRecord(LlmProfile("provider", "Provider")), "profile"))
        val request = SettingsCatalogRequest("request", owner.state.profileRef("provider")!!, SettingsCatalogKind.MODELS)
        assertIs<SettingsMachine.Effect.LoadCatalog>(owner.commit(SettingsMachine.Intent.BeginCatalog(request)).single())
        val restored = SettingsInputJournal(store, journal, json)
        restored.restore { error("Legacy data must not be read") }
        assertEquals(setOf("request"), restored.state.interrupted)
        val count = journal.read(SettingsInputJournal.STREAM).size
        restored.restore { error("Legacy data must not be read") }
        assertEquals(count, journal.read(SettingsInputJournal.STREAM).size)
        assertTrue(restored.commit(SettingsMachine.Fact.OperationFailed("request")).isEmpty())
    }

    @Test fun failedSecretCleanupDoesNotRevertCommittedDeletion() = runTest {
        val store = InMemoryKeyValueStore()
        val backing = InMemorySecretStore()
        var failDelete = false
        val secrets = object : SecretStore by backing {
            override suspend fun delete(reference: String) {
                if (failDelete) throw StorageException("delete secret", StorageException.Kind.WRITE)
                backing.delete(reference)
            }
        }
        val records = SettingsCredentialRecords(store, secrets, json, null)
        val journal = InMemoryEventJournal()
        val owner = SettingsInputJournal(store, journal, json)
        owner.initialize()
        val profile = records.encode(LlmProfile("provider", "Provider", apiKey = "private-key"))
        owner.commit(SettingsMachine.Intent.SaveProfile(null, profile, "profile"))
        records.checkpoint(owner.state)
        records.stagePrevious(owner.state)
        owner.commit(SettingsMachine.Intent.DeleteProfile(owner.state.profileRef("provider")!!, "delete"))
        failDelete = true
        assertTrue(assertFailsWith<StorageException> { records.checkpoint(owner.state) }.committed)
        assertTrue(owner.state.profiles.isEmpty())
        assertNotNull(backing.read(profile.credential!!))
        val restored = SettingsInputJournal(store, journal, json)
        restored.restore { error("Legacy data must not be read") }
        failDelete = false
        records.checkpoint(restored.state)
        assertTrue(restored.state.profiles.isEmpty())
        assertNull(backing.read(checkNotNull(profile.credential)))
    }

    private class FaultJournal(val delegate: EventJournal = InMemoryEventJournal()) : EventJournal by delegate {
        var ack: (JournalRecord) -> JournalRecord = { it }
        var snapshotMap: (JournalSnapshot) -> JournalSnapshot = { it }
        var afterAppend: (JournalRecord) -> Unit = {}
        var failureAfterAppend: Exception? = null
        override suspend fun snapshot(stream: String): JournalSnapshot = snapshotMap(delegate.snapshot(stream))
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val saved = delegate.append(expected, operation, at, detail) ?: return null
            afterAppend(saved)
            failureAfterAppend?.let { throw it }
            return ack(saved)
        }
    }
}
