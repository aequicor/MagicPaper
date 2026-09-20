package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

class JournalRevisionTest {
    @Test fun appendAndDeleteCompareTheObservedStreamWithoutConsumingASequenceOnConflict() = runTest {
        for (journal in listOf(InMemoryEventJournal(), DurableEventJournal(InMemoryDurableByteStore()))) {
            val initial = journal.snapshot("plan")
            assertTrue(initial.records.isEmpty())
            assertEquals(0L, initial.revision.seq)
            assertEquals(1L, assertNotNull(journal.append(initial.revision, "created", 10)).seq)
            assertNull(journal.append(initial.revision, "stale", 11))
            assertFalse(journal.drop(initial.revision))
            val current = journal.snapshot("plan")
            assertEquals(2L, journal.append("other", "created", 12).seq)
            assertEquals(3L, assertNotNull(journal.append(current.revision, "changed", 13)).seq,
                "Another stream does not invalidate this revision")
            assertEquals(listOf("other", "plan"), journal.streams())
            assertEquals(listOf("created", "changed"), journal.read("plan").map { it.operation })
        }
    }

    @Test fun concurrentStoreInstancesCannotBothCommitAgainstTheSameObservation() = runTest {
        val memory = InMemoryDurableByteStore()
        val backend = object : DurableByteStore by memory {
            override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
                yield()
                memory.write(area, key, bytes)
            }
        }
        val one = DurableEventJournal(backend)
        val two = DurableEventJournal(backend)
        val revision = one.snapshot("plan").revision
        val first = async { one.append(revision, "first", 1) }
        val second = async { two.append(revision, "second", 1) }
        assertEquals(1, listOf(first.await(), second.await()).count { it != null })
        assertEquals(1, DurableEventJournal(backend).read("plan").size)
    }

    @Test fun deletingEvenAnEmptyStreamRevokesEarlierWriters() = runTest {
        for (journal in listOf(InMemoryEventJournal(), DurableEventJournal(InMemoryDurableByteStore()))) {
            val initial = journal.snapshot("plan").revision
            assertTrue(journal.drop(initial))
            val deleted = journal.snapshot("plan")
            assertEquals(1L, deleted.revision.seq)
            assertTrue(deleted.records.isEmpty())
            assertTrue(journal.streams().isEmpty())
            assertNull(journal.append(initial, "late-create", 1))
            assertEquals(2L, assertNotNull(journal.append(deleted.revision, "new-owner", 2)).seq)
            assertFalse(journal.drop(initial))
            assertEquals(listOf("new-owner"), journal.read("plan").map { it.operation })
        }
    }

    @Test fun resetRejectsTheOldTokenEvenWhenItsSequenceIsReused() = runTest {
        val backend = InMemoryDurableByteStore()
        val stores = persistenceStores(backend)
        val stale = DurableEventJournal(backend)
        stale.append("plan", "before", 1)
        val old = stale.snapshot("plan").revision
        stores.clearOwnedData()
        val fresh = DurableEventJournal(backend)
        fresh.append("plan", "after", 2)
        val current = fresh.snapshot("plan").revision
        assertEquals(old.seq, current.seq)
        assertNotEquals(old.resetEpoch, current.resetEpoch)
        assertNull(stale.append(old, "late-write", 3))
        assertFalse(stale.drop(old))
        assertEquals(listOf("after"), fresh.read("plan").map { it.operation })
    }

    @Test fun failedDeletionCleanupCannotExposeOldRecordsOrAdmitAnOldWriter() = runTest {
        val memory = InMemoryDurableByteStore()
        var failDelete = true
        val backend = object : DurableByteStore by memory {
            override suspend fun delete(area: StorageArea, key: String) {
                if (area == StorageArea.EVENTS && key.startsWith("e") && failDelete) error("cleanup failed")
                memory.delete(area, key)
            }
        }
        val journal = DurableEventJournal(backend)
        journal.append("plan", "created", 1, "owner data")
        val old = journal.snapshot("plan").revision
        val failure = assertFailsWith<StorageException> { journal.drop(old) }
        assertTrue(failure.committed)
        val reopened = DurableEventJournal(backend)
        assertTrue(reopened.read("plan").isEmpty())
        assertTrue(reopened.streams().isEmpty())
        assertNull(reopened.append(old, "late", 2))
        assertTrue(memory.values(StorageArea.EVENTS).any { "owner data" in it.decodeToString() })
        failDelete = false
        reopened.drop("plan")
        assertTrue(memory.values(StorageArea.EVENTS).none { "owner data" in it.decodeToString() })
    }

    @Test fun cursorRecoveryIncludesDeletionFencesAfterTheLastRecordWasRemoved() = runTest {
        val backend = InMemoryDurableByteStore()
        val journal = DurableEventJournal(backend)
        journal.append("plan", "created", 1)
        journal.drop("plan")
        val deleted = journal.snapshot("plan").revision
        backend.delete(StorageArea.EVENTS, "seq")
        assertEquals(deleted.seq + 1, DurableEventJournal(backend).append("other", "created", 3).seq)
    }

    @Test fun failedAppendReservationLeavesTheObservedRevisionUsable() = runTest {
        val memory = InMemoryDurableByteStore()
        var fail = true
        val backend = object : DurableByteStore by memory {
            override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
                if (area == StorageArea.EVENTS && key.startsWith("e") && fail) error("write failed")
                memory.write(area, key, bytes)
            }
        }
        val journal = DurableEventJournal(backend)
        val observed = journal.snapshot("plan").revision
        assertFailsWith<StorageException> { journal.append(observed, "created", 1) }
        assertEquals(observed, journal.snapshot("plan").revision)
        fail = false
        assertEquals(2L, assertNotNull(journal.append(observed, "created", 2)).seq)
    }
}
