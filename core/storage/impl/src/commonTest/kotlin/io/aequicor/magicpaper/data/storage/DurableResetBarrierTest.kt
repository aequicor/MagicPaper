package io.aequicor.magicpaper.data.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DurableResetBarrierTest {
    @Test fun failedEventsClearPreservesBytesAndBlocksEveryJournalEntryPointUntilExplicitRetry() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "saved", 1, "private payload reference")
        val old = stores.events.snapshot("owner")
        val saved = backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() }
        backend.clearAction = { area ->
            if (area == StorageArea.EVENTS) error("injected clear failure")
            backend.memory.clear(area)
        }
        assertFailsWith<IllegalStateException> { stores.clearOwnedData() }
        assertEquals(saved, backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() })
        assertFalse(StorageArea.CONTROL in backend.cleared)
        val clearCount = backend.cleared.size
        val reopened = persistenceStores(backend)
        assertBlocked { reopened.events.snapshot("owner") }
        assertBlocked { reopened.events.read("owner") }
        assertBlocked { reopened.events.streams() }
        assertBlocked { reopened.events.append("owner", "late", 2) }
        assertBlocked { reopened.events.append(old.revision, "late", 2) }
        assertBlocked { reopened.events.drop("owner") }
        assertBlocked { reopened.events.drop(old.revision) }
        assertBlocked { reopened.drafts.load("missing") }
        assertBlocked { reopened.drafts.save(DraftRecord("new", 1, "late")) }
        assertBlocked { reopened.navigation.load() }
        assertEquals(clearCount, backend.cleared.size, "Read-only reopen must not retry cleanup")
        backend.clearAction = null
        reopened.clearOwnedData()
        val fresh = persistenceStores(backend).events
        assertTrue(fresh.read("owner").isEmpty())
        assertEquals(old.revision.resetEpoch + 1, fresh.snapshot("owner").revision.resetEpoch)
        assertNull(fresh.append(old.revision, "stale", 3))
        assertNotNull(fresh.append(fresh.snapshot("owner").revision, "new", 4))
        assertEquals(listOf("new"), fresh.read("owner").map { it.operation })
        assertEquals(1, backend.memory.values(StorageArea.CONTROL).size)
    }

    @Test fun partialEventsDeletionCannotExposeAShorterHistoryAsCurrent() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "first", 1)
        stores.events.append("owner", "second", 2)
        backend.clearAction = { area ->
            if (area == StorageArea.EVENTS) {
                backend.memory.delete(area, "e000000000000000001")
                error("interrupted deletion")
            }
            backend.memory.clear(area)
        }
        assertFailsWith<IllegalStateException> { stores.clearOwnedData() }
        val retained = backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() }
        assertTrue(retained.any { "second" in it })
        assertFalse(retained.any { "first" in it })
        assertBlocked { DurableEventJournal(backend).snapshot("owner") }
        assertEquals(retained, backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() })
    }

    @Test fun aMarkerThatWasNotCommittedCannotAuthorizeAnyClear() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        val old = stores.events.append("owner", "saved", 1)
        val failedWrite = IllegalStateException("marker unavailable")
        backend.beforeWrite = { area, _ -> if (area == StorageArea.CONTROL) throw failedWrite }
        val failure = assertFailsWith<IllegalStateException> { stores.clearOwnedData() }
        assertTrue(causes(failure).any { it === failedWrite })
        assertTrue(backend.cleared.isEmpty())
        assertTrue(backend.memory.values(StorageArea.CONTROL).isEmpty())
        assertEquals(listOf(old), DurableEventJournal(backend).read("owner"))
        assertEquals(0L, DurableEventJournal(backend).snapshot("owner").revision.resetEpoch)
    }

    @Test fun exactReadbackSettlesLostBeginAndReadyAcknowledgements() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "old", 1)
        var lost = 0
        backend.afterWrite = { area, _ -> if (area == StorageArea.CONTROL) { lost++; error("lost acknowledgement") } }
        stores.clearOwnedData()
        assertEquals(2, lost)
        val restored = persistenceStores(backend)
        assertEquals(1L, restored.events.snapshot("owner").revision.resetEpoch)
        assertTrue(restored.events.read("owner").isEmpty())
        assertEquals(StorageArea.entries.filter { it != StorageArea.CONTROL }, backend.cleared)
    }

    @Test fun changedReadbackCannotAuthorizeClearEvenWhenTheWriteReturnedNormally() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "old", 1)
        backend.afterWrite = { area, _ ->
            if (area == StorageArea.CONTROL) backend.readTransform = { readArea, bytes ->
                if (readArea == StorageArea.CONTROL) bytes?.decodeToString()?.replace("RESETTING", "READY")?.encodeToByteArray()
                else bytes
            }
        }
        assertEquals(StorageException.Kind.CORRUPT, assertFailsWith<StorageException> { stores.clearOwnedData() }.kind)
        assertTrue(backend.cleared.isEmpty())
        backend.readTransform = null
        assertBlocked { persistenceStores(backend).events.read("owner") }
    }

    @Test fun cancellationRemainsPrimaryWhenMarkerProofAlsoFails() = runTest {
        val backend = FaultBackend()
        val cancelled = CancellationException("cancelled marker")
        val readFailure = IllegalStateException("readback unavailable")
        backend.beforeWrite = { area, _ -> if (area == StorageArea.CONTROL) {
            backend.beforeRead = { readArea -> if (readArea == StorageArea.CONTROL) throw readFailure }
            throw cancelled
        } }
        val failure = assertFailsWith<CancellationException> { persistenceStores(backend).clearOwnedData() }
        assertEquals(cancelled.message, failure.message)
        assertTrue(causes(failure).any { readFailure in it.suppressedExceptions })
        assertTrue(backend.cleared.isEmpty())
    }

    @Test fun actualCancellationDuringBeginCommitLeavesAReadOnlyBarrierAndDoesNotClear() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "old", 1)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.afterWrite = { area, _ -> if (area == StorageArea.CONTROL) {
            committed.complete(Unit)
            release.await()
        } }
        val reset = async { stores.clearOwnedData() }
        committed.await()
        reset.cancel(CancellationException("cancel after durable begin"))
        release.complete(Unit)
        reset.join()
        assertFailsWith<CancellationException> { reset.await() }
        assertTrue(backend.cleared.isEmpty())
        assertBlocked { persistenceStores(backend).events.read("owner") }
    }

    @Test fun cancellationDuringCleanupKeepsTheEarlierFailureAndNeverWritesReady() = runTest {
        val backend = FaultBackend()
        val priorFailure = IllegalStateException("secrets cleanup failed")
        backend.clearAction = { area -> when (area) {
            StorageArea.SECRETS -> throw priorFailure
            StorageArea.DRAFTS -> throw CancellationException("cancel during cleanup")
            else -> backend.memory.clear(area)
        } }
        val failure = assertFailsWith<CancellationException> { persistenceStores(backend).clearOwnedData() }
        assertTrue(causes(failure).any { priorFailure in it.suppressedExceptions })
        assertBlocked { persistenceStores(backend).events.streams() }
        assertEquals(listOf(StorageArea.SECRETS, StorageArea.DRAFTS), backend.cleared)
    }

    @Test fun failedReadyPublicationDoesNotPretendThatAnEmptyJournalIsConfirmed() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "old", 1)
        backend.beforeWrite = { area, bytes ->
            if (area == StorageArea.CONTROL && "READY" in bytes.decodeToString()) error("ready write failed")
        }
        assertFailsWith<IllegalStateException> { stores.clearOwnedData() }
        assertTrue(backend.memory.values(StorageArea.EVENTS).isEmpty())
        assertBlocked { DurableEventJournal(backend).snapshot("owner") }
        backend.beforeWrite = { _, _ -> }
        persistenceStores(backend).clearOwnedData()
        assertEquals(1L, DurableEventJournal(backend).snapshot("owner").revision.resetEpoch,
            "An explicit retry completes the saved reset; it must not manufacture a second generation")
    }

    @Test fun cancelledReadyAcknowledgementPreservesCancellationAndTheKnownCompletedReset() = runTest {
        val backend = FaultBackend()
        val stores = persistenceStores(backend)
        stores.events.append("owner", "old", 1)
        backend.afterWrite = { area, bytes ->
            if (area == StorageArea.CONTROL && "READY" in bytes.decodeToString()) throw CancellationException("ready acknowledgement cancelled")
        }
        assertFailsWith<CancellationException> { stores.clearOwnedData() }
        val restored = persistenceStores(backend)
        assertTrue(restored.events.read("owner").isEmpty())
        assertEquals(1L, restored.events.snapshot("owner").revision.resetEpoch)
    }

    @Test fun clearingTheOldEpochLocationCannotEraseAnInterruptedResetFence() = runTest {
        val backend = FaultBackend()
        backend.memory.write(StorageArea.NAVIGATION, "\u0000magicpaper-reset-epoch", "7".encodeToByteArray())
        val stores = persistenceStores(backend)
        stores.events.append("owner", "legacy", 1)
        backend.clearAction = { area ->
            backend.memory.clear(area)
            if (area == StorageArea.NAVIGATION) throw CancellationException("stop after navigation clear")
        }
        assertFailsWith<CancellationException> { stores.clearOwnedData() }
        assertNull(backend.memory.read(StorageArea.NAVIGATION, "\u0000magicpaper-reset-epoch"))
        assertTrue(backend.memory.values(StorageArea.EVENTS).isNotEmpty())
        assertBlocked { persistenceStores(backend).events.snapshot("owner") }
        backend.clearAction = null
        persistenceStores(backend).clearOwnedData()
        assertEquals(8L, DurableEventJournal(backend).snapshot("owner").revision.resetEpoch)
    }

    @Test fun legacyReadsDoNotManufactureBarrierProvenanceOrChangeStoredEventSchema() = runTest {
        val backend = FaultBackend()
        backend.memory.write(StorageArea.NAVIGATION, "\u0000magicpaper-reset-epoch", "7".encodeToByteArray())
        val journal = DurableEventJournal(backend)
        val record = journal.append("legacy-owner", "saved", 1)
        val bytes = backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() }
        assertEquals(listOf(record), DurableEventJournal(backend).read("legacy-owner"))
        assertEquals(7L, DurableEventJournal(backend).snapshot("legacy-owner").revision.resetEpoch)
        assertTrue(backend.memory.values(StorageArea.CONTROL).isEmpty(), "Restore cannot certify an old reset it did not observe")
        assertEquals(bytes, backend.memory.values(StorageArea.EVENTS).map { it.decodeToString() })
        assertTrue(bytes.any { "\"version\":1" in it })
        assertFalse(bytes.any { "resetEpoch" in it })
    }

    @Test fun corruptBarrierAndExhaustedLegacyEpochCannotAuthorizeReset() = runTest {
        val corrupt = FaultBackend()
        corrupt.memory.write(StorageArea.CONTROL, "application-reset", "{}".encodeToByteArray())
        assertEquals(StorageException.Kind.CORRUPT, assertFailsWith<StorageException> { persistenceStores(corrupt).clearOwnedData() }.kind)
        assertTrue(corrupt.cleared.isEmpty())
        assertEquals("{}", corrupt.memory.read(StorageArea.CONTROL, "application-reset")?.decodeToString())
        val exhausted = FaultBackend()
        exhausted.memory.write(StorageArea.NAVIGATION, "\u0000magicpaper-reset-epoch", Long.MAX_VALUE.toString().encodeToByteArray())
        assertEquals(StorageException.Kind.WRITE, assertFailsWith<StorageException> { persistenceStores(exhausted).clearOwnedData() }.kind)
        assertTrue(exhausted.cleared.isEmpty())
    }

    private suspend fun assertBlocked(block: suspend () -> Any?) {
        assertEquals(StorageException.Kind.RESET_INCOMPLETE, assertFailsWith<StorageException> { block() }.kind)
    }

    private fun causes(failure: Throwable): List<Throwable> = buildList {
        var current: Throwable? = failure
        while (true) {
            val next = current ?: break
            if (any { it === next }) break
            add(next)
            current = next.cause
        }
    }

    private class FaultBackend(val memory: InMemoryDurableByteStore = InMemoryDurableByteStore()) : DurableByteStore by memory {
        val cleared = mutableListOf<StorageArea>()
        var beforeWrite: suspend (StorageArea, ByteArray) -> Unit = { _, _ -> }
        var afterWrite: suspend (StorageArea, ByteArray) -> Unit = { _, _ -> }
        var beforeRead: suspend (StorageArea) -> Unit = { }
        var readTransform: ((StorageArea, ByteArray?) -> ByteArray?)? = null
        var clearAction: (suspend (StorageArea) -> Unit)? = null

        override suspend fun read(area: StorageArea, key: String): ByteArray? {
            beforeRead(area)
            val bytes = memory.read(area, key)
            val transform = readTransform
            return if (transform == null) bytes else transform(area, bytes)
        }
        override suspend fun write(area: StorageArea, key: String, bytes: ByteArray) {
            beforeWrite(area, bytes)
            memory.write(area, key, bytes)
            afterWrite(area, bytes)
        }
        override suspend fun clear(area: StorageArea) {
            cleared += area
            val action = clearAction
            if (action == null) memory.clear(area) else action(area)
        }
    }
}
