package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DurableEventJournalTest {
    private suspend fun EventJournal.record(stream: String, operation: String, at: Long = 0) =
        append(stream, operation, at)

    @Test fun recordsComeBackInTheOrderTheyWereAppended() = runTest {
        val journal = DurableEventJournal(InMemoryDurableByteStore())
        journal.record("plan-1", "agent-intent")
        journal.record("plan-1", "agent-outcome")
        assertContentEquals(listOf("agent-intent", "agent-outcome"), journal.read("plan-1").map { it.operation })
        assertContentEquals(listOf(1L, 2L), journal.read("plan-1").map { it.seq })
    }

    @Test fun theSequenceOrdersRecordsThatShareAMillisecond() = runTest {
        // The wall clock can repeat, stand still or run backwards; the sequence cannot.
        val journal = DurableEventJournal(InMemoryDurableByteStore())
        journal.record("plan-1", "first", at = 500)
        journal.record("plan-1", "second", at = 500)
        journal.record("plan-1", "third", at = 100)
        assertContentEquals(listOf("first", "second", "third"), journal.read("plan-1").map { it.operation })
    }

    @Test fun theSequenceIsSharedByEveryStreamAndEveryInstance() = runTest {
        // Two windows against one backend must not hand out the same number twice, and the
        // order between plans is what reconstructs what the application actually did.
        val backend = InMemoryDurableByteStore()
        val one = DurableEventJournal(backend)
        val other = DurableEventJournal(backend)
        assertEquals(1L, one.record("plan-1", "a").seq)
        assertEquals(2L, other.record("plan-2", "b").seq)
        assertEquals(3L, one.record("plan-1", "c").seq)
        assertContentEquals(listOf(1L, 3L), one.read("plan-1").map { it.seq })
        assertContentEquals(listOf(2L), other.read("plan-2").map { it.seq })
    }

    @Test fun theSequenceSurvivesAReopenedJournal() = runTest {
        val backend = InMemoryDurableByteStore()
        DurableEventJournal(backend).record("plan-1", "before restart")
        assertEquals(2L, DurableEventJournal(backend).record("plan-1", "after restart").seq)
    }

    @Test fun aLostCursorIsRecoveredFromTheRecordsAndNeverWritesOverThem() = runTest {
        // A cursor lost to a partial write would otherwise restart the sequence on top of
        // records that did commit, replacing evidence instead of adding to it.
        val backend = InMemoryDurableByteStore()
        val journal = DurableEventJournal(backend)
        journal.record("plan-1", "one")
        journal.record("plan-1", "two")
        backend.delete(StorageArea.EVENTS, "seq")
        assertEquals(3L, DurableEventJournal(backend).record("plan-1", "three").seq)
        assertContentEquals(listOf("one", "two", "three"), journal.read("plan-1").map { it.operation })
    }

    @Test fun nothingEverModifiesARecord() = runTest {
        val backend = InMemoryDurableByteStore()
        val journal = DurableEventJournal(backend)
        val first = journal.record("plan-1", "agent-intent", at = 10)
        journal.record("plan-1", "agent-outcome", at = 20)
        journal.record("plan-1", "agent-intent", at = 30)
        // The same operation twice is two facts, not one row updated.
        assertEquals(3, journal.read("plan-1").size)
        assertEquals(first, journal.read("plan-1").first())
    }

    @Test fun droppingAStreamLeavesTheOthersAndDoesNotRewindTheSequence() = runTest {
        val journal = DurableEventJournal(InMemoryDurableByteStore())
        journal.record("plan-1", "a")
        journal.record("plan-2", "b")
        journal.drop("plan-1")
        assertTrue(journal.read("plan-1").isEmpty())
        assertContentEquals(listOf(2L), journal.read("plan-2").map { it.seq })
        assertEquals(3L, journal.record("plan-1", "c").seq, "Номер выбывшей записи не выдаётся заново")
    }

    @Test fun aResetClearsTheJournalWithTheStateItRecorded() = runTest {
        val backend = InMemoryDurableByteStore()
        val stores = persistenceStores(backend)
        stores.events.record("plan-1", "agent-intent")
        stores.clearOwnedData()
        assertTrue(stores.events.read("plan-1").isEmpty())
        assertEquals(1L, stores.events.record("plan-1", "after reset").seq, "Журнал начинается заново вместе с данными")
    }

    @Test fun aCorruptRecordIsNotReadAsAShorterHistory() = runTest {
        // Silently skipping an unreadable record would answer "this effect was never requested"
        // for an effect that was, which is the one answer the journal exists to prevent.
        val backend = InMemoryDurableByteStore()
        val journal = DurableEventJournal(backend)
        journal.record("plan-1", "agent-intent")
        backend.write(StorageArea.EVENTS, "e000000000000000002", "{\"storageFormat\":\"other\",\"version\":1,\"seq\":2,\"at\":0,\"stream\":\"plan-1\",\"operation\":\"x\"}".encodeToByteArray())
        assertEquals(StorageException.Kind.CORRUPT, assertFailsWith<StorageException> { journal.read("plan-1") }.kind)
    }

    @Test fun aRecordWithoutAStreamIsRefusedBeforeItIsWritten() = runTest {
        val journal = DurableEventJournal(InMemoryDurableByteStore())
        assertFailsWith<IllegalArgumentException> { journal.record(" ", "agent-intent") }
        assertEquals(1L, journal.record("plan-1", "agent-intent").seq, "Отказ не тратит номер")
    }

    @Test fun theTestJournalKeepsTheSamePromisesAsTheDurableOne() = runTest {
        // A double that numbers or drops differently from production turns a passing test into
        // evidence of nothing. These are the promises the contract makes, checked on both.
        listOf(InMemoryEventJournal(), DurableEventJournal(InMemoryDurableByteStore())).forEach { journal ->
            val name = journal::class.simpleName
            assertEquals(1L, journal.record("plan-1", "a", at = 900).seq, name)
            assertEquals(2L, journal.record("plan-2", "b", at = 100).seq, name)
            assertEquals(3L, journal.record("plan-1", "c", at = 100).seq, name)
            assertContentEquals(listOf(1L, 3L), journal.read("plan-1").map { it.seq }, name)
            journal.drop("plan-2")
            assertTrue(journal.read("plan-2").isEmpty(), name)
            assertEquals(4L, journal.record("plan-2", "d").seq, "$name: номер выбывшей записи не выдаётся заново")
            assertFailsWith<IllegalArgumentException>(name) { journal.record("", "e") }
        }
    }
}
