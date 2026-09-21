package io.aequicor.magicpaper.data.storage

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FileResetBarrierTest {
    @Test fun failedResetRetainsItsFenceAcrossRealFileStoreReopeningAndExplicitRetry() = runTest {
        val root = Files.createTempDirectory("magicpaper-reset-barrier-").toFile()
        try {
            val original = FileDurableByteStore(root)
            val journal = DurableEventJournal(original)
            journal.append("owner", "saved", 1, "immutable private reference")
            val old = journal.snapshot("owner")
            val bytes = original.values(StorageArea.EVENTS).map { it.decodeToString() }.sorted()
            val broken = object : DurableByteStore by original {
                override suspend fun clear(area: StorageArea) {
                    if (area == StorageArea.EVENTS) throw StorageException("injected file deletion", StorageException.Kind.CLEANUP)
                    original.clear(area)
                }
            }
            assertFailsWith<StorageException> { persistenceStores(broken).clearOwnedData() }
            val reopened = FileDurableByteStore(root)
            assertEquals(bytes, reopened.values(StorageArea.EVENTS).map { it.decodeToString() }.sorted())
            assertEquals(StorageException.Kind.RESET_INCOMPLETE,
                assertFailsWith<StorageException> { DurableEventJournal(reopened).snapshot("owner") }.kind)
            assertEquals(1, reopened.values(StorageArea.CONTROL).size)
            persistenceStores(reopened).clearOwnedData()
            val confirmed = DurableEventJournal(FileDurableByteStore(root))
            assertTrue(confirmed.read("owner").isEmpty())
            assertEquals(old.revision.resetEpoch + 1, confirmed.snapshot("owner").revision.resetEpoch)
            assertNull(confirmed.append(old.revision, "late", 2))
            confirmed.append(confirmed.snapshot("owner").revision, "fresh", 3)
            assertEquals(listOf("fresh"), DurableEventJournal(FileDurableByteStore(root)).read("owner").map { it.operation })
        } finally { root.deleteRecursively() }
    }
}
