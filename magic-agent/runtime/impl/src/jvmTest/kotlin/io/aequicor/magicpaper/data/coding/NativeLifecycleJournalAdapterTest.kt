package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeLifecycleJournalAdapterTest {
    private val entry = NativeJournalEntry("entry", NativeLifecycleMachine.Intent.Begin(NativeRunRef("session", "request")))

    @Test fun exactNamespaceRestoresInputsAndOtherOwnersRemainIndependent() = runTest {
        val store = InMemoryEventJournal()
        val first = NativeLifecycleJournalAdapter(store, "/existing/native/home")
        val revision = first.append(first.snapshot().revision, entry)
        val restored = NativeLifecycleJournalAdapter(store, "/existing/native/home").snapshot()
        assertEquals(revision, restored.revision)
        assertEquals(listOf(entry), restored.entries)
        assertEquals(listOf(revision!!.position), restored.positions)
        assertTrue(NativeLifecycleJournalAdapter(store, "/another/native/home").snapshot().entries.isEmpty())
        val persisted = store.read(store.streams().single()).single()
        assertFalse(persisted.stream.contains("existing"))
        assertFalse(persisted.detail.contains("/existing"))
    }

    @Test fun droppedStreamCannotBeAppendedWithItsOldRevision() = runTest {
        val store = InMemoryEventJournal()
        val adapter = NativeLifecycleJournalAdapter(store, "native")
        val before = checkNotNull(adapter.append(adapter.snapshot().revision, entry))
        store.drop(store.streams().single())
        assertNull(adapter.append(before, entry.copy(id = "stale")))
        val empty = adapter.snapshot()
        assertTrue(empty.entries.isEmpty())
        assertTrue(empty.revision.position > before.position)
        assertNotNull(adapter.append(empty.revision, entry.copy(id = "fresh")))
    }

    @Test fun foreignRecordGrammarAndScopeAreRejected() = runTest {
        for (foreignScope in listOf(false, true)) {
            val store = object : EventJournal by InMemoryEventJournal() {
                override suspend fun snapshot(stream: String) = JournalSnapshot(JournalRevision(stream, 1), listOf(
                    JournalRecord(1, 0, if (foreignScope) "other-owner" else stream,
                        if (foreignScope) "native.lifecycle.input.v1" else "other.grammar", "{}")))
            }
            assertFailsWith<IllegalStateException> { NativeLifecycleJournalAdapter(store, "native").snapshot() }
        }
    }

    @Test fun mismatchedAppendAcknowledgementCannotBeReportedAsCommitted() = runTest {
        val store = object : EventJournal by InMemoryEventJournal() {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String) =
                JournalRecord(expected.seq + 1, at, expected.stream, operation, "changed-payload")
        }
        val adapter = NativeLifecycleJournalAdapter(store, "native")
        assertFailsWith<IllegalStateException> { adapter.append(adapter.snapshot().revision, entry) }
    }
}
