package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UsageJournalTest {
    private val json = Json { encodeDefaults = true }
    private fun record(id: String = "request") = UsageRecord(id, createdAt = 1, provider = "fixture-provider", model = "fixture-model", tokens = TokenUsage(10, 5), completed = true)

    private class Journal : EventJournal {
        private var backing: EventJournal = InMemoryEventJournal()
        private var epoch = 0L
        var beforeAppend: suspend () -> Unit = {}
        var afterAppend: (JournalRecord) -> JournalRecord = { it }
        var view: (JournalSnapshot) -> JournalSnapshot = { it }
        override suspend fun append(stream: String, operation: String, at: Long, detail: String) = backing.append(stream, operation, at, detail)
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            beforeAppend()
            if (expected.resetEpoch != epoch) return null
            return backing.append(expected.copy(resetEpoch = 0), operation, at, detail)?.let(afterAppend)
        }
        override suspend fun snapshot(stream: String) = view(backing.snapshot(stream).let { it.copy(revision = it.revision.copy(resetEpoch = epoch)) })
        override suspend fun read(stream: String) = snapshot(stream).records
        override suspend fun streams() = backing.streams()
        override suspend fun drop(stream: String) = backing.drop(stream)
        override suspend fun drop(expected: JournalRevision): Boolean = expected.resetEpoch == epoch && backing.drop(expected.copy(resetEpoch = 0))
        fun reset() { backing = InMemoryEventJournal(); epoch++ }
    }
    private class Store : KeyValueStore by InMemoryKeyValueStore() {
        private val backing = InMemoryKeyValueStore()
        var afterWrite: (String) -> Unit = {}
        override fun read(key: String) = backing.read(key)
        override fun keys(prefix: String) = backing.keys(prefix)
        override fun delete(key: String) = backing.delete(key)
        override fun clear() = backing.clear()
        override fun write(key: String, value: String) { backing.write(key, value); afterWrite(key) }
    }
    private fun TestScope.ledger(store: KeyValueStore, journal: EventJournal, repository: UsageRepository = JsonUsageRepository(store, json)) =
        DefaultUsageLedger(repository, journal, store, json, UnconfinedTestDispatcher(testScheduler))

    @Test fun stateIsPublishedOnlyAfterDurableAcknowledgement() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val capture = ledger.captureObservation()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        events.beforeAppend = { entered.complete(Unit); release.await() }
        val writing = launch { ledger.record(capture, record()) }
        entered.await()
        assertTrue(ledger.state.value.records.isEmpty())
        release.complete(Unit); writing.join()
        assertEquals(listOf(record()), ledger.state.value.records)
        assertNull(ledger.failure.value)
    }

    @Test fun lostAppendAcknowledgementCommitsExactlyOnceAndReopens() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val capture = ledger.captureObservation()
        events.afterAppend = { throw IllegalStateException("lost acknowledgement") }
        ledger.record(capture, record())
        assertNull(ledger.failure.value)
        events.afterAppend = { it }
        val reopened = ledger(store, events); reopened.start()
        assertEquals(listOf(record()), reopened.exportArchive().records)
        reopened.record(reopened.captureObservation(), record())
        assertEquals(2, events.read(UsageInputJournal.STREAM).size)
    }

    @Test fun failedCommitKeepsArchiveAndNeverGrantsOrRepeatsModelCalls() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val capture = ledger.captureObservation(); ledger.record(capture, record())
        events.beforeAppend = { error("private provider payload must not be displayed") }
        ledger.record(capture, record("other"))
        assertEquals(listOf(record()), ledger.state.value.records)
        assertEquals(UsageObservation.Unavailable, ledger.captureObservation())
        assertNotNull(ledger.failure.value)
        assertFalse(ledger.failure.value!!.contains("private provider"))
        var calls = 0
        assertEquals("answer", ledger.measure(LlmProfile("p", "name")) { calls++; "answer" })
        assertEquals(1, calls)
        assertEquals(listOf(record()), ledger.state.value.records)
        assertFailsWith<IllegalStateException> { ledger.exportArchive() }
    }

    @Test fun directAcknowledgementMustMatchEveryEnvelopeField() = runTest {
        val mutations: List<(JournalRecord) -> JournalRecord> = listOf({ it.copy(stream = "foreign") },
            { it.copy(operation = "foreign") }, { it.copy(seq = 0) }, { it.copy(at = it.at + 1) }, { it.copy(detail = "{}") })
        for (mutation in mutations) {
            val store = Store(); val events = Journal(); val ledger = ledger(store, events)
            val capture = ledger.captureObservation(); events.afterAppend = mutation
            ledger.record(capture, record())
            assertTrue(ledger.state.value.records.isEmpty())
            assertEquals(UsageObservation.Unavailable, ledger.captureObservation())
            assertNotNull(ledger.failure.value)
        }
    }

    @Test fun lostAcknowledgementCannotAcceptReplacedPrefix() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val capture = ledger.captureObservation()
        events.afterAppend = {
            events.view = { snapshot -> snapshot.copy(records = snapshot.records.mapIndexed { index, record -> if (index == 0) record.copy(at = record.at + 1) else record }) }
            error("lost acknowledgement")
        }
        ledger.record(capture, record())
        assertTrue(ledger.state.value.records.isEmpty())
        assertEquals(UsageObservation.Unavailable, ledger.captureObservation())
    }

    @Test fun corruptOrMissingPrivatePayloadBlocksRestoreAndExportWithoutErasingIt() = runTest {
        for (missing in listOf(false, true)) {
            val store = Store(); val events = Journal(); val ledger = ledger(store, events)
            ledger.record(ledger.captureObservation(), record())
            val key = store.keys(UsageInputJournal.PREFIX).last()
            if (missing) store.delete(key) else store.write(key, "corrupt secret payload")
            val reopened = ledger(store, events); reopened.start()
            assertNotNull(reopened.failure.value)
            assertEquals(UsageObservation.Unavailable, reopened.captureObservation())
            assertFailsWith<IllegalStateException> { ledger.exportArchive() }
            assertEquals(if (missing) null else "corrupt secret payload", store.read(key))
        }
    }

    @Test fun corruptedRevisionAndRecordEnvelopeFailClosed() = runTest {
        val changes: List<(JournalSnapshot) -> JournalSnapshot> = listOf(
            { it.copy(revision = it.revision.copy(stream = "foreign")) },
            { it.copy(revision = it.revision.copy(seq = -1)) },
            { it.copy(revision = it.revision.copy(resetEpoch = -1)) },
            { it.copy(revision = it.revision.copy(seq = it.revision.seq + 1)) },
            { it.copy(records = it.records + it.records.last()) },
            { it.copy(records = it.records.map { record -> record.copy(stream = "foreign") }) })
        for (change in changes) {
            val store = Store(); val events = Journal(); ledger(store, events).start()
            events.view = change
            val reopened = ledger(store, events); reopened.start()
            assertEquals(UsageObservation.Unavailable, reopened.captureObservation())
            assertNotNull(reopened.failure.value)
        }
    }

    @Test fun clearAndImportFenceLateProducerResults() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val old = ledger.captureObservation(); ledger.record(old, record())
        ledger.clear()
        ledger.record(old, record("late"))
        assertTrue(ledger.state.value.records.isEmpty())
        val beforeImport = ledger.captureObservation()
        val imported = UsageArchive(startedAt = 10, records = listOf(record("imported")))
        ledger.replace(imported)
        ledger.record(beforeImport, record("late-again"))
        assertEquals(imported, ledger.state.value)
        ledger.record(ledger.captureObservation(), record("fresh"))
        assertEquals(listOf("imported", "fresh"), ledger.exportArchive().records.map { it.id })
    }

    @Test fun globalResetNeverReimportsLegacyAndStaleWriterCannotResurrectIt() = runTest {
        val store = Store(); val events = Journal()
        store.write("usage:archive:v1", json.encodeToString(UsageArchive.serializer(), UsageArchive(startedAt = 1, records = listOf(record("legacy")))))
        val stale = ledger(store, events); val old = stale.captureObservation()
        events.reset()
        val fresh = ledger(store, events); fresh.start()
        assertTrue(fresh.state.value.records.isEmpty())
        stale.record(old, record("late"))
        assertEquals(UsageObservation.Unavailable, stale.captureObservation())
        assertTrue(fresh.exportArchive().records.isEmpty())
        assertNotNull(store.read("usage:archive:v1"))
    }

    @Test fun modelCompletionAfterClearUsesOriginalCaptureAndDoesNotRepopulateArchive() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val model = async {
            ledger.measure(LlmProfile("p", "name")) {
                calls++; entered.complete(Unit); release.await()
                currentCoroutineContext()[UsageCall]!!.result.value = UsageCallResult(TokenUsage(10, 5))
                "answer"
            }
        }
        entered.await(); ledger.clear(); release.complete(Unit)
        assertEquals("answer", model.await())
        assertEquals(1, calls); assertTrue(ledger.exportArchive().records.isEmpty())
        assertNotNull(ledger.failure.value)
    }

    @Test fun unreadableLegacyNeedsExplicitImportAndRawArchiveIsRetained() = runTest {
        val store = Store(); val events = Journal(); store.write("usage:archive:v1", "corrupt legacy")
        val ledger = ledger(store, events); ledger.start()
        assertEquals(UsageObservation.Unavailable, ledger.captureObservation())
        assertTrue(events.read(UsageInputJournal.STREAM).isEmpty())
        val archive = UsageArchive(startedAt = 1, records = listOf(record("imported")))
        ledger.replace(archive)
        assertEquals(archive, ledger.exportArchive())
        assertEquals("corrupt legacy", store.read("usage:archive:v1"))
        val reopened = ledger(store, events); reopened.start()
        assertEquals(archive, reopened.exportArchive())
    }

    @Test fun noOpAcknowledgementAndExportDetectAnotherOwnerWrite() = runTest {
        val store = Store(); val events = Journal(); val first = ledger(store, events)
        first.record(first.captureObservation(), record())
        val second = ledger(store, events); second.start()
        second.record(second.captureObservation(), record("second"))
        first.record(first.captureObservation(), record())
        assertEquals(UsageObservation.Unavailable, first.captureObservation())
        assertFailsWith<IllegalStateException> { first.exportArchive() }
        assertEquals(listOf("request", "second"), second.exportArchive().records.map { it.id })
    }

    @Test fun immutableImportAndExportNeverLeakMutableAuthority() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val records = mutableListOf(record())
        ledger.replace(UsageArchive(startedAt = 1, records = records))
        records.clear()
        (ledger.state.value.records as MutableList).clear()
        assertEquals(listOf(record()), ledger.exportArchive().records)
        val reopened = ledger(store, events); reopened.start()
        assertEquals(listOf(record()), reopened.state.value.records)
    }

    @Test fun submittedImportFreezesCallerCollectionsBeforeWaitingForAnotherWriter() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        val capture = ledger.captureObservation()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        events.beforeAppend = { entered.complete(Unit); release.await() }
        val first = launch { ledger.record(capture, record("first")) }
        entered.await()
        val records = mutableListOf(record("imported"))
        val importing = launch(UnconfinedTestDispatcher(testScheduler)) {
            ledger.replace(UsageArchive(startedAt = 1, records = records))
        }
        records.clear()
        events.beforeAppend = {}; release.complete(Unit); first.join(); importing.join()
        assertEquals(listOf("imported"), ledger.exportArchive().records.map { it.id })
    }

    @Test fun lostPayloadWriteAndAppendCancellationRetainExactCommitAndPropagateCancellation() = runTest {
        for (payloadFailure in listOf(false, true)) {
            val store = Store(); val events = Journal(); val ledger = ledger(store, events)
            val capture = ledger.captureObservation()
            if (payloadFailure) store.afterWrite = { throw CancellationException("payload cancellation") }
            else events.afterAppend = { throw CancellationException("append cancellation") }
            assertFailsWith<CancellationException> { ledger.record(capture, record()) }
            store.afterWrite = {}; events.afterAppend = { it }
            val reopened = ledger(store, events); reopened.start()
            assertEquals(listOf(record()), reopened.exportArchive().records)
            assertEquals(2, events.read(UsageInputJournal.STREAM).size)
        }
    }

    @Test fun resetDuringAdmittedWriteFencesOldRevisionBeforePublication() = runTest {
        val store = Store(); val events = Journal(); val oldOwner = ledger(store, events)
        val capture = oldOwner.captureObservation()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        events.beforeAppend = { entered.complete(Unit); release.await() }
        val writer = launch { oldOwner.record(capture, record("late")) }
        entered.await(); events.reset(); events.beforeAppend = {}; release.complete(Unit); writer.join()
        assertTrue(oldOwner.state.value.records.isEmpty())
        assertEquals(UsageObservation.Unavailable, oldOwner.captureObservation())
        val fresh = ledger(store, events); fresh.start()
        assertTrue(fresh.exportArchive().records.isEmpty())
        assertEquals(1, events.read(UsageInputJournal.STREAM).size)
    }

    @Test fun historicalCounterProofsSurviveRestartAndCannotRewindCursor() = runTest {
        val store = Store(); val events = Journal(); val first = ledger(store, events)
        val capture = first.captureObservation()
        first.cumulative(capture, "thread", "one", TokenUsage(total = 100), TokenUsage(total = 10), record())
        first.cumulative(capture, "thread", "two", TokenUsage(total = 120), TokenUsage(total = 20), record())
        val reopened = ledger(store, events); val recovered = reopened.captureObservation()
        reopened.cumulative(recovered, "thread", "one", TokenUsage(total = 100), TokenUsage(total = 10), record())
        reopened.cumulative(recovered, "thread", "three", TokenUsage(total = 130), TokenUsage(total = 10), record())
        assertEquals(listOf(10L, 20L, 10L), reopened.exportArchive().records.map { it.tokens.totalTokens })
        reopened.cumulative(recovered, "thread", "one", TokenUsage(total = 101), TokenUsage(total = 10), record())
        assertNotNull(reopened.failure.value)
        assertEquals("three", reopened.exportArchive().cursors.getValue("thread").fingerprint)
    }

    @Test fun journalContainsOnlyOpaqueInputReferencesAndProviderCallsStayOutsideReplay() = runTest {
        val store = Store(); val events = Journal(); val ledger = ledger(store, events)
        var calls = 0
        ledger.measure(LlmProfile("profile", "private name", apiKey = "credential", modelId = "private-model")) { calls++; "private response" }
        val wire = events.read(UsageInputJournal.STREAM).joinToString { it.detail }
        listOf("private", "credential", "profile").forEach { assertFalse(wire.contains(it)) }
        val reopened = ledger(store, events); reopened.start()
        assertEquals(1, calls); assertEquals(1, reopened.exportArchive().records.size)
        assertFalse(store.keys(UsageInputJournal.PREFIX).any { store.read(it).orEmpty().contains("credential") })
    }
}
