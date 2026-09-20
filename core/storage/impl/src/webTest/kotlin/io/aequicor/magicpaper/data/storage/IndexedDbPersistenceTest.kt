package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlin.test.*

class IndexedDbPersistenceTest {
    @Test fun journalUpgradePreservesVersionTwoData() = runTest {
        val database = "magicpaper-journal-upgrade-" + storageId()
        seedLegacyJournalDatabase(database)
        val backend = IndexedDbDurableByteStore(database)
        val journal = DurableEventJournal(backend)
        val empty = journal.snapshot("plan")
        assertNotNull(journal.append(empty.revision, "created", 1))
        assertEquals("v2-payload", backend.read(StorageArea.DRAFTS, "legacy")?.decodeToString())
        assertEquals(listOf("created"), DurableEventJournal(IndexedDbDurableByteStore(database)).read("plan").map { it.operation })
    }

    @Test fun journalRevisionCoordinatesInstancesAndRejectsDeletedAndResetGenerations() = runTest {
        val database = "magicpaper-journal-cas-" + storageId()
        val backend = IndexedDbDurableByteStore(database)
        val one = DurableEventJournal(backend)
        val two = DurableEventJournal(IndexedDbDurableByteStore(database))
        val initial = one.snapshot("plan").revision
        val a = async { one.append(initial, "first", 1) }
        val b = async { two.append(initial, "second", 1) }
        assertEquals(1, listOf(a.await(), b.await()).count { it != null })
        val saved = one.snapshot("plan")
        assertEquals(1, saved.records.size)
        assertEquals(listOf("plan"), two.streams())
        assertTrue(two.drop(saved.revision))
        assertNull(one.append(saved.revision, "late", 2))
        assertTrue(one.read("plan").isEmpty())
        val deleted = one.snapshot("plan").revision
        persistenceStores(backend).clearOwnedData()
        assertNull(two.append(deleted, "after-reset", 3))
        val fresh = two.snapshot("plan").revision
        assertNotEquals(deleted.resetEpoch, fresh.resetEpoch)
        assertNotNull(two.append(fresh, "new-owner", 4))
        assertEquals(listOf("new-owner"), DurableEventJournal(IndexedDbDurableByteStore(database)).read("plan").map { it.operation })
    }

    @Test fun reopenedTabRestoresSeparatePresentationRecordsAndReleasesOnlyUnsharedState() = runTest {
        val database = "magicpaper-views-" + storageId()
        val backend = IndexedDbDurableByteStore(database)
        val original = persistenceStores(backend, "original")
        original.navigation.saveWithPresentations("journal refs", mapOf("shared" to "scroll and expansion"))
        assertFalse(checkNotNull(backend.read(StorageArea.NAVIGATION, "original")).decodeToString().contains("scroll and expansion"))
        val clone = persistenceStores(IndexedDbDurableByteStore(database), "clone", fallbackJournalId = "original")
        val captured = checkNotNull(clone.navigation.loadWithPresentations())
        assertEquals(mapOf("shared" to "scroll and expansion"), captured.presentations)
        clone.navigation.saveWithPresentations(captured.snapshot, captured.presentations)
        original.navigation.saveWithPresentations("original moved", mapOf("current" to "new position"))
        assertEquals(2, backend.values(StorageArea.PRESENTATION).size)
        assertEquals(captured, persistenceStores(IndexedDbDurableByteStore(database), "clone").navigation.loadWithPresentations())
        clone.navigation.saveWithPresentations("clone cleared", emptyMap())
        assertNull(backend.read(StorageArea.PRESENTATION, "shared"))
        assertEquals(1, backend.values(StorageArea.PRESENTATION).size)
        clone.clearOwnedData()
        assertTrue(backend.values(StorageArea.PRESENTATION).isEmpty())
        assertFailsWith<StorageException> { original.navigation.saveWithPresentations("late", mapOf("late" to "stale state")) }
        assertTrue(backend.values(StorageArea.PRESENTATION).isEmpty())
    }

    @Test fun interopKeepsDatabaseStoreKeyAndValueArgumentsIndependent() = runTest {
        val first = "magicpaper-interop-a-" + storageId()
        val second = "magicpaper-interop-b-" + storageId()
        indexedDbOperation(first, "drafts", "same-key", "write", "first value")
        indexedDbOperation(second, "drafts", "same-key", "write", "second value")
        indexedDbOperation(first, "navigation", "same-key", "write", "journal value")
        assertEquals("first value", indexedDbOperation(first, "drafts", "same-key", "read", null))
        assertEquals("second value", indexedDbOperation(second, "drafts", "same-key", "read", null))
        assertEquals("journal value", indexedDbOperation(first, "navigation", "same-key", "read", null))
        indexedDbOperation(first, "drafts", "same-key", "delete", null)
        assertNull(indexedDbOperation(first, "drafts", "same-key", "read", null))
        assertEquals("second value", indexedDbOperation(second, "drafts", "same-key", "read", null))
    }

    @Test fun committedTransactionsRestoreSeparateSecretsAndAttachmentBytes() = runTest {
        val database = "magicpaper-test-" + storageId()
        val backend = IndexedDbDurableByteStore(database)
        val original = persistenceStores(backend, "first")
        val bytes = byteArrayOf(0, -1, 127, 64)
        original.blobs.write("attachment", bytes)
        original.drafts.save(DraftRecord("draft", 1, "raw 1..2", mapOf("secret" to "private-answer"), listOf("attachment")))
        original.navigation.save("journal")
        val restarted = persistenceStores(IndexedDbDurableByteStore(database), "second", fallbackJournalId = "first")
        assertEquals("private-answer", restarted.drafts.load("draft")?.secrets?.get("secret"))
        assertFalse(checkNotNull(backend.read(StorageArea.DRAFTS, "draft")).decodeToString().contains("private-answer"))
        assertContentEquals(bytes, restarted.blobs.read("attachment"))
        assertEquals("journal", restarted.navigation.load())
        restarted.clearOwnedData()
        assertNull(original.drafts.load("draft"))
        assertNull(original.blobs.read("attachment"))
        assertFailsWith<StorageException> { original.navigation.save("late journal") }
        assertNull(persistenceStores(IndexedDbDurableByteStore(database), "first").navigation.load())
    }
}

internal expect suspend fun seedLegacyJournalDatabase(database: String)
