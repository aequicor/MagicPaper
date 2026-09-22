package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class OrganismJournalTest {
    private class FaultJournal(val delegate: EventJournal = InMemoryEventJournal()) : EventJournal by delegate {
        var afterAppend = false
        var corruptPrefix = false
        var replacement: ((JournalSnapshot) -> JournalSnapshot)? = null
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            val record = delegate.append(expected, operation, at, detail)
            if (afterAppend) {
                afterAppend = false
                if (corruptPrefix) replacement = { snapshot -> snapshot.copy(records = snapshot.records.mapIndexed { i, r -> if (i == 0) r.copy(at = r.at + 1) else r }) }
                error("lost acknowledgement")
            }
            return record
        }
        override suspend fun snapshot(stream: String) = delegate.snapshot(stream).let { replacement?.invoke(it) ?: it }
        // `by delegate` forwards the interface's default snapshotAll() straight to the delegate,
        // bypassing the snapshot() override above; rederive it so replay still sees the fault.
        override suspend fun snapshotAll(): Map<String, JournalSnapshot> = delegate.streams().associateWith { snapshot(it) }
    }
    private class SnapshotFailureStore : KeyValueStore by InMemoryKeyValueStore() {
        private val backing = InMemoryKeyValueStore()
        var failSnapshot = false
        override fun read(key: String) = backing.read(key)
        override fun keys(prefix: String) = backing.keys(prefix)
        override fun delete(key: String) = backing.delete(key)
        override fun write(key: String, value: String) {
            if (failSnapshot && key.startsWith("session-organism-")) error("checkpoint unavailable")
            backing.write(key, value)
        }
    }
    private suspend fun SessionOrganismStore.seed(id: String = "root"): SessionOrganism =
        adopt("project", CodingSession(id, "project", "Root", 1, researchMode = true), emptyList(), OrganismLimits(tokens = 1000, recoveryTokens = 100))
    private fun SessionOrganism.authority(): SessionAuthority = sessions.getValue(zygoteId).let { SessionAuthority(projectId, id, it.id, it.generation, it.mode) }
    private fun create(name: String = "Child") = OrganismCommand(OrganismAction.CREATE, name = name, tokens = 100, task = SessionTask("Work", "root", "Result"))

    @Test fun replayUsesCommittedInputsWhenCompatibilitySnapshotWasNeverSaved() = runTest {
        val storage = SnapshotFailureStore().apply { failSnapshot = true }; val journal = InMemoryEventJournal()
        val store = DefaultSessionOrganismStore(storage, journal); val root = store.seed()
        val saved = store.command(root.authority(), "create", create())
        assertTrue(store.failures.value.isNotEmpty())
        val restarted = DefaultSessionOrganismStore(storage, journal)
        assertEquals(saved, restarted.get(root.id))
        assertEquals(1, restarted.get(root.id).operations.size)
        assertEquals(saved, restarted.command(root.authority(), "create", create()))
    }
    @Test fun exactAppendReadbackAcceptsLostAcknowledgement() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = FaultJournal(); val store = DefaultSessionOrganismStore(storage, journal)
        val root = store.seed(); journal.afterAppend = true
        val saved = store.command(root.authority(), "create", create())
        assertEquals(1, saved.operations.size)
        assertEquals(saved, DefaultSessionOrganismStore(storage, journal).get(root.id))
    }
    @Test fun changedHistoryCannotConfirmLostAcknowledgement() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = FaultJournal(); val store = DefaultSessionOrganismStore(storage, journal)
        val root = store.seed(); journal.afterAppend = true; journal.corruptPrefix = true
        assertFails { store.command(root.authority(), "create", create()) }
        assertEquals(root, store.get(root.id))
        assertFails { store.command(root.authority(), "another", create()) }
        assertTrue(store.failures.value.isNotEmpty())
    }
    @Test fun corruptInputArtifactFailsBeforeAnyProjectionOrFurtherWrite() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = InMemoryEventJournal()
        DefaultSessionOrganismStore(storage, journal).seed()
        val key = storage.keys("organism-input:").single(); storage.write(key, "{}")
        val count = journal.read(journal.streams().single()).size
        val restarted = DefaultSessionOrganismStore(storage, journal)
        assertFails { restarted.loadAll() }
        assertTrue(restarted.organisms.value.isEmpty()); assertTrue(restarted.failures.value.isNotEmpty())
        assertFails { restarted.seed("other") }
        assertEquals(count, journal.read(journal.streams().single()).size)
    }
    @Test fun wrongEpochOrderAndTimestampAreRejectedOnReplay() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = FaultJournal()
        val store = DefaultSessionOrganismStore(storage, journal); store.seed(); store.beginRun("root", "root")
        val corruptions: List<(JournalSnapshot) -> JournalSnapshot> = listOf(
            { it.copy(revision = it.revision.copy(resetEpoch = it.revision.resetEpoch + 1)) },
            { it.copy(records = it.records.reversed()) },
            { it.copy(records = it.records.mapIndexed { i, r -> if (i == 0) r.copy(at = r.at + 1) else r }) },
            { it.copy(records = it.records.map { r -> r.copy(stream = "other") }) },
        )
        for (corrupt in corruptions) {
            journal.replacement = corrupt
            val restarted = DefaultSessionOrganismStore(storage, journal)
            assertFails { restarted.loadAll() }
            assertTrue(restarted.organisms.value.isEmpty())
        }
    }
    @Test fun privateInputsAreRedactedButOriginalArgumentsStillBindTheReceipt() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = InMemoryEventJournal()
        val store = DefaultSessionOrganismStore(storage, journal, knownSecrets = { setOf("known-secret-123") })
        val root = store.seed(); val command = create("name known-secret-123")
        store.command(root.authority(), "create", command)
        assertTrue(storage.keys("").all { "known-secret-123" !in storage.read(it).orEmpty() })
        assertTrue(journal.read(journal.streams().single()).all { "name" !in it.detail && "known-secret" !in it.detail })
        assertFails { store.command(root.authority(), "create", command.copy(name = "other")) }
    }
    @Test fun legacyImportsOnlyOnceAndNeverOverridesJournalAuthority() = runTest {
        val sourceStorage = InMemoryKeyValueStore(); val original = DefaultSessionOrganismStore(sourceStorage, InMemoryEventJournal()).seed()
        val storage = InMemoryKeyValueStore(); storage.write("session-organism-root", Json.encodeToString(original))
        val journal = InMemoryEventJournal(); val store = DefaultSessionOrganismStore(storage, journal)
        assertEquals(original, store.get("root"))
        val saved = store.command(original.authority(), "create", create())
        storage.write("session-organism-root", Json.encodeToString(original))
        assertEquals(saved, DefaultSessionOrganismStore(storage, journal).get("root"))
        assertEquals(2, journal.read(journal.streams().single()).size)
    }
    @Test fun deletedChildIdentityRemainsReservedAcrossRestart() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = InMemoryEventJournal(); val store = DefaultSessionOrganismStore(storage, journal)
        val root = store.seed(); store.command(root.authority(), "create", create())
        val child = store.get(root.id).sessions.getValue("session-create")
        store.observe(root.id, child.id, child.generation, SessionObservedState.STOPPED)
        val deleted = store.deleteHistoryByUser(root.id, "session-create")
        assertTrue("session-create" in deleted.historyDeletedIds)
        val restarted = DefaultSessionOrganismStore(storage, journal)
        assertFails { restarted.seed("session-create") }
        assertEquals(1, journal.streams().size)
    }
    @Test fun exactInputReplayDoesNotReemitAnAdmissionOrAppendAnotherRecord() = runTest {
        val storage = InMemoryKeyValueStore(); val journal = InMemoryEventJournal()
        val store = DefaultSessionOrganismStore(storage, journal); store.seed()
        val input = SessionOrganismMachine.Intent.BeginRun(SessionOrganismMachine.Stamp("same-input", 1234), "root", "root")
        val first = store.dispatch("root", input)
        assertEquals(1, first.outputs.filterIsInstance<SessionOrganismMachine.Output.RunAdmitted>().size)
        val count = journal.read(journal.streams().single()).size
        val replay = store.dispatch("root", input)
        assertEquals(first.state, replay.state); assertTrue(replay.outputs.isEmpty())
        assertEquals(count, journal.read(journal.streams().single()).size)
        assertFails { store.dispatch("root", input.copy(sessionId = "other")) }
    }
    @Test fun duplicateSessionAcrossLegacyOwnersIsRejectedBeforeAnyImport() = runTest {
        val original = DefaultSessionOrganismStore(InMemoryKeyValueStore(), InMemoryEventJournal()).seed()
        val foreign = original.copy(id = "foreign")
        val storage = InMemoryKeyValueStore()
        storage.write("session-organism-root", Json.encodeToString(original))
        storage.write("session-organism-foreign", Json.encodeToString(foreign))
        val journal = InMemoryEventJournal(); val owner = DefaultSessionOrganismStore(storage, journal)
        assertFails { owner.loadAll() }
        assertTrue(journal.streams().isEmpty()); assertTrue(owner.organisms.value.isEmpty())
    }

}
