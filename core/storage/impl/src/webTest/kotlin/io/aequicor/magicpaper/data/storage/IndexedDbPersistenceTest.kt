package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IndexedDbPersistenceTest {
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
