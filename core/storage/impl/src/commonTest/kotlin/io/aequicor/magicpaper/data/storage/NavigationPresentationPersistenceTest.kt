package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class NavigationPresentationPersistenceTest {
    @Test fun statesLiveOutsideJournalAndReopenWithItsReferences() = runTest {
        val backend = InMemoryDurableByteStore()
        val journal = """{"visits":["visit-a"],"presentationRefs":{"visit-a":"state-a"}}"""
        val states = mapOf("state-a" to "expanded article and scroll position")
        DurableNavigationSnapshotStore(backend).saveWithPresentations(journal, states)
        val storedJournal = checkNotNull(backend.read(StorageArea.NAVIGATION, "main")).decodeToString()
        assertFalse(storedJournal.contains(states.getValue("state-a")))
        assertTrue(storedJournal.contains("state-a"))
        assertEquals(1, backend.values(StorageArea.PRESENTATION).size)
        val reopened = DurableNavigationSnapshotStore(backend)
        assertEquals(NavigationSnapshotRecord(journal, states), reopened.loadWithPresentations())
        assertEquals(journal, reopened.load())
    }

    @Test fun sharedStatesRemainUntilLastJournalReleasesThemAndAutosavesStayBounded() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = DurableNavigationSnapshotStore(backend, "first")
        val second = DurableNavigationSnapshotStore(backend, "second")
        first.saveWithPresentations("first", mapOf("shared" to "shared state"))
        second.saveWithPresentations("second", mapOf("shared" to "shared state"))
        repeat(100) { revision ->
            first.saveWithPresentations("first-$revision", mapOf("state-$revision" to "scroll-$revision"))
            assertEquals(2, backend.values(StorageArea.PRESENTATION).size)
        }
        assertNotNull(backend.read(StorageArea.PRESENTATION, "shared"))
        second.saveWithPresentations("second cleared", emptyMap())
        assertNull(backend.read(StorageArea.PRESENTATION, "shared"))
        assertEquals(1, backend.values(StorageArea.PRESENTATION).size)
    }

    @Test fun stagedStateOrJournalFailurePreservesLastCommitAndNextCommitCollectsOrphans() = runTest {
        val actual = InMemoryDurableByteStore()
        var failingArea: StorageArea? = null
        var failingKey: String? = null
        val backend = object : DurableByteStore by actual {
            override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
                if (area == failingArea && key == failingKey) throw StorageException("injected write", StorageException.Kind.WRITE)
                actual.write(area, key, bytes)
            }
        }
        val store = DurableNavigationSnapshotStore(backend)
        val previous = NavigationSnapshotRecord("previous", mapOf("old" to "old state"))
        store.saveWithPresentations(previous.snapshot, previous.presentations)
        failingArea = StorageArea.PRESENTATION
        failingKey = "failed"
        val stateFailure = assertFailsWith<StorageException> {
            store.saveWithPresentations("new", linkedMapOf("orphan" to "staged", "failed" to "never written"))
        }
        assertFalse(stateFailure.committed)
        assertEquals(previous, DurableNavigationSnapshotStore(actual).loadWithPresentations())
        assertNotNull(actual.read(StorageArea.PRESENTATION, "orphan"))
        failingArea = StorageArea.NAVIGATION
        failingKey = "main"
        assertFailsWith<StorageException> { store.saveWithPresentations("new", mapOf("other orphan" to "staged")) }
        assertEquals(previous, DurableNavigationSnapshotStore(actual).loadWithPresentations())
        failingArea = null
        store.saveWithPresentations("committed", mapOf("current" to "current state"))
        assertEquals(1, actual.values(StorageArea.PRESENTATION).size)
        assertNull(actual.read(StorageArea.PRESENTATION, "orphan"))
        assertNull(actual.read(StorageArea.PRESENTATION, "other orphan"))
    }

    @Test fun immutableReferenceCannotReplaceStateUsedByAnotherJournal() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = DurableNavigationSnapshotStore(backend, "first")
        first.saveWithPresentations("first", mapOf("ref" to "original"))
        val second = DurableNavigationSnapshotStore(backend, "second")
        val failure = assertFailsWith<StorageException> { second.saveWithPresentations("second", mapOf("ref" to "replacement")) }
        assertEquals(StorageException.Kind.CORRUPT, failure.kind)
        assertNull(second.load())
        assertEquals(mapOf("ref" to "original"), first.loadWithPresentations()?.presentations)
    }

    @Test fun forkCanCommitCapturedStateAfterSourceAlreadyReleasedItsReference() = runTest {
        val backend = InMemoryDurableByteStore()
        val source = DurableNavigationSnapshotStore(backend, "source")
        source.saveWithPresentations("source before fork", mapOf("before" to "captured scroll"))
        val fork = DurableNavigationSnapshotStore(backend, "fork", fallbackJournalId = "source")
        val captured = checkNotNull(fork.loadWithPresentations())
        source.saveWithPresentations("source after fork", mapOf("after" to "new scroll"))
        assertNull(backend.read(StorageArea.PRESENTATION, "before"))
        fork.saveWithPresentations(captured.snapshot, captured.presentations)
        assertEquals(captured, DurableNavigationSnapshotStore(backend, "fork").loadWithPresentations())
        assertEquals(mapOf("after" to "new scroll"), source.loadWithPresentations()?.presentations)
        assertEquals(2, backend.values(StorageArea.PRESENTATION).size)
    }

    @Test fun pruningCannotRaceBetweenAnotherJournalsStateStagingAndCommit() = runTest {
        val actual = InMemoryDurableByteStore()
        val staged = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : DurableByteStore by actual {
            override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
                actual.write(area, key, bytes)
                if (area == StorageArea.PRESENTATION && key == "new") {
                    staged.complete(Unit)
                    release.await()
                }
            }
        }
        val first = DurableNavigationSnapshotStore(backend, "first")
        val second = DurableNavigationSnapshotStore(backend, "second")
        first.saveWithPresentations("first", mapOf("old" to "old state"))
        val saving = async { second.saveWithPresentations("second", mapOf("new" to "new state")) }
        staged.await()
        val clearing = async { first.saveWithPresentations("first cleared", emptyMap()) }
        runCurrent()
        assertFalse(clearing.isCompleted)
        release.complete(Unit)
        saving.await()
        clearing.await()
        assertEquals(mapOf("new" to "new state"), second.loadWithPresentations()?.presentations)
        assertEquals(1, actual.values(StorageArea.PRESENTATION).size)
    }

    @Test fun missingReferenceMetadataInhibitsCleanupInsteadOfDeletingOtherTabsState() = runTest {
        val backend = InMemoryDurableByteStore()
        val other = DurableNavigationSnapshotStore(backend, "other")
        other.saveWithPresentations("other", mapOf("retained" to "other state"))
        val fields = Json.parseToJsonElement(checkNotNull(backend.read(StorageArea.NAVIGATION, "other")).decodeToString()).jsonObject
        val damaged = kotlinx.serialization.json.JsonObject(fields - "presentationReferences").toString()
        backend.write(StorageArea.NAVIGATION, "other", damaged.encodeToByteArray())
        val current = DurableNavigationSnapshotStore(backend, "current")
        val failure = assertFailsWith<StorageException> { current.saveWithPresentations("committed", emptyMap()) }
        assertTrue(failure.committed)
        assertEquals(StorageException.Kind.CLEANUP, failure.kind)
        assertNotNull(backend.read(StorageArea.PRESENTATION, "retained"))
        assertFailsWith<StorageException> { other.loadWithPresentations() }
        assertEquals("committed", current.load())
    }

    @Test fun cleanupFailureReportsCommittedJournalAndCanRetryWithoutLosingActiveState() = runTest {
        val actual = InMemoryDurableByteStore()
        var failCleanup = false
        val backend = object : DurableByteStore by actual {
            override suspend fun delete(area: StorageArea, key: String) {
                if (area == StorageArea.PRESENTATION && failCleanup) throw StorageException("injected cleanup", StorageException.Kind.WRITE)
                actual.delete(area, key)
            }
        }
        val store = DurableNavigationSnapshotStore(backend)
        store.saveWithPresentations("before", mapOf("before" to "previous"))
        failCleanup = true
        val failure = assertFailsWith<StorageException> { store.saveWithPresentations("after", mapOf("after" to "new")) }
        assertTrue(failure.committed)
        assertEquals(NavigationSnapshotRecord("after", mapOf("after" to "new")), store.loadWithPresentations())
        failCleanup = false
        store.saveWithPresentations("after", mapOf("after" to "new"))
        assertEquals(1, actual.values(StorageArea.PRESENTATION).size)
    }

    @Test fun resetFencesOldPresentationOwnersAndNewOwnersCanPersist() = runTest {
        val backend = InMemoryDurableByteStore()
        val first = persistenceStores(backend, "first")
        val second = persistenceStores(backend, "second")
        first.navigation.saveWithPresentations("old", mapOf("old" to "old state"))
        second.clearOwnedData()
        assertTrue(backend.values(StorageArea.PRESENTATION).isEmpty())
        assertFailsWith<StorageException> { first.navigation.saveWithPresentations("late", mapOf("late" to "late state")) }
        assertTrue(backend.values(StorageArea.PRESENTATION).isEmpty())
        second.navigation.saveWithPresentations("fresh", mapOf("fresh" to "fresh state"))
        assertEquals(mapOf("fresh" to "fresh state"), persistenceStores(backend, "second").navigation.loadWithPresentations()?.presentations)
    }

    @Test fun legacyInlineJournalRemainsReadableBeforeItsOwnerMigratesIt() = runTest {
        val backend = InMemoryDurableByteStore()
        val legacy = """{"visits":[],"presentation":{"visit":"legacy state"}}"""
        backend.write(StorageArea.NAVIGATION, "main", legacy.encodeToByteArray())
        assertEquals(NavigationSnapshotRecord(legacy), DurableNavigationSnapshotStore(backend).loadWithPresentations())
    }
}
