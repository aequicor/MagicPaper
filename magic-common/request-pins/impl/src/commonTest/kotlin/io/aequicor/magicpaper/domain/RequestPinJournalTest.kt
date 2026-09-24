package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.AppLogEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPinJournalTest {
    private val key = PinConversation("chat")
    private val source = PinMessage("request", "Source text", true)
    private val profile = LlmProfile("profile", "Private connection", "https://private.invalid/private-path", "fixture-secret", modelId = "model")
    private val answer = """{"summary":"Summary","newRequest":true}"""
    private fun repository() = JsonRequestPinRepository(InMemoryKeyValueStore(), Json)
    private fun gateway(onCall: suspend () -> String) = object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = onCall()
    }
    private fun type(detail: String) = Json.parseToJsonElement(detail).jsonObject["input"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    @Test fun restoredActiveJournalShowsUnknownAndNeverReplaysForReopenOrChangedConnection() = runTest {
        val original = InMemoryEventJournal()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var originalCalls = 0
        val first = DefaultRequestPinService(repository(), gateway { originalCalls++; awaitCancellation() }, firstScope, original, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        first.sync(key, listOf(source), profile); runCurrent()
        assertEquals(1, originalCalls)
        val crash = InMemoryEventJournal()
        for (stream in original.streams()) for (record in original.read(stream)) crash.append(stream, record.operation, record.at, record.detail)
        firstScope.cancel(); runCurrent()
        var restoredCalls = 0
        val second = DefaultRequestPinService(repository(), gateway { restoredCalls++; answer }, backgroundScope, crash, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        second.sync(key, listOf(source), profile); runCurrent()
        repeat(2) { index -> second.sync(key, listOf(source), profile.copy(apiKey = "new-$index"), reopened = true); runCurrent() }
        assertEquals(0, restoredCalls)
        assertEquals(source.text, second.groups.value.getValue(key).single().request.summary)
        assertNotNull(second.failures.value[key])
        second.sync(key, listOf(source.copy(text = "Explicit edit")), profile); runCurrent()
        assertEquals(1, restoredCalls)
        assertEquals("Summary", second.groups.value.getValue(key).single().request.summary)
    }

    @Test fun exactLostAppendAcknowledgementIsProvedBeforeCallingProviderOnce() = runTest {
        val backing = InMemoryEventJournal()
        var lost = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val record = backing.append(expected, operation, at, detail)
                if (type(detail) == "Analyse" && !lost) { lost = true; throw StorageException("lost acknowledgement", StorageException.Kind.WRITE) }
                return record
            }
        }
        var calls = 0
        val service = DefaultRequestPinService(repository(), gateway { calls++; answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        assertTrue(lost)
        assertEquals(1, calls)
        assertEquals("Summary", service.groups.value.getValue(key).single().request.summary)
        assertNull(service.failures.value[key])
        assertEquals(1, backing.read(backing.streams().single()).count { type(it.detail) == "Analyse" })
    }

    @Test fun anotherWritersEquivalentInputIsNotProofOfThisAttemptAcknowledgement() = runTest {
        val backing = InMemoryEventJournal()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (type(detail) == "Analyse") {
                    val envelope = Json.parseToJsonElement(detail).jsonObject
                    val foreign = JsonObject(envelope + ("id" to JsonPrimitive("another-operation")))
                    backing.append(expected, operation, at, foreign.toString())
                    throw StorageException("ambiguous acknowledgement", StorageException.Kind.WRITE)
                }
                return backing.append(expected, operation, at, detail)
            }
        }
        var calls = 0
        val service = DefaultRequestPinService(repository(), gateway { calls++; answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        assertEquals(0, calls)
        assertNotNull(service.failures.value[key])
        assertEquals(source.text, service.groups.value.getValue(key).single().request.summary)
        service.sync(key, listOf(source), profile.copy(modelId = "other"), reopened = true); runCurrent()
        assertEquals(0, calls, "Unknown accepted intent cannot become a new provider call after restore")
    }

    @Test fun unreadableAppendOutcomeBlocksProviderUntilAnExplicitReopenCanProveJournalState() = runTest {
        val backing = InMemoryEventJournal()
        var unreadable = false
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (type(detail) == "Analyse") {
                    unreadable = true
                    throw StorageException("append outcome unknown", StorageException.Kind.WRITE)
                }
                return backing.append(expected, operation, at, detail)
            }
            override suspend fun snapshot(stream: String): JournalSnapshot {
                if (unreadable) throw StorageException("read unavailable", StorageException.Kind.UNAVAILABLE)
                return backing.snapshot(stream)
            }
        }
        var calls = 0
        val service = DefaultRequestPinService(repository(), gateway { calls++; answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        repeat(3) { service.sync(key, listOf(source), profile); runCurrent() }
        assertEquals(0, calls)
        assertNotNull(service.failures.value[key])
        assertEquals(source.text, service.groups.value.getValue(key).single().request.summary)
    }

    /** What the pin service reported after [marker], the last entry seen before the step under test. */
    private fun reportedSince(marker: AppLogEntry?): List<AppLogEntry> =
        AppLog.history(after = marker).filter { it.component == "request-pins" }

    /** An append whose outcome can never be read back, so the owner has to stop at persistence-unknown. */
    private fun unreadable(backing: InMemoryEventJournal, refuse: Boolean = false) = object : EventJournal by backing {
        var broken = false
        override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
            if (type(detail) == "Analyse") {
                broken = true
                if (refuse) return null
                throw StorageException("append outcome unknown", StorageException.Kind.WRITE)
            }
            return backing.append(expected, operation, at, detail)
        }
        override suspend fun snapshot(stream: String): JournalSnapshot {
            if (broken && !refuse) throw StorageException("read unavailable", StorageException.Kind.UNAVAILABLE)
            return backing.snapshot(stream)
        }
    }

    @Test fun aSyncThatChangesNothingIsNotJournaled() = runTest {
        val journal = InMemoryEventJournal()
        val service = DefaultRequestPinService(repository(), gateway { answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        val stream = journal.streams().single()
        val written = journal.read(stream).size
        service.sync(key, listOf(source), profile); runCurrent()
        assertEquals(written, journal.read(stream).size, "An input that changes nothing and asks for nothing is not a record")
    }

    @Test fun theFailureThatLostTheAppendOutcomeIsTheOneReportedNotAWrapper() = runTest {
        val conversation = PinConversation("lostoutcome")
        val service = DefaultRequestPinService(repository(), gateway { answer }, backgroundScope, unreadable(InMemoryEventJournal()), storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        val marker = AppLog.history().lastOrNull()
        service.sync(conversation, listOf(source), profile); runCurrent()
        val causes = reportedSince(marker).mapNotNull { it.fields["causeType"] }
        assertTrue("StorageException" in causes, causes.toString())
        assertFalse("JournalOutcomeUnknown" in causes, causes.toString())
    }

    @Test fun anInputTheStateRefusesIsReportedAsAFailureNotDroppedInSilence() = runTest {
        val conversation = PinConversation("refusedinput")
        val service = DefaultRequestPinService(repository(), gateway { answer }, backgroundScope, unreadable(InMemoryEventJournal()), storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(conversation, listOf(source), profile); runCurrent()
        val marker = AppLog.history().lastOrNull()
        // The state is now persistence-unknown, which refuses every input until a reopen restores it.
        service.sync(conversation, listOf(source), profile); runCurrent()
        val updates = reportedSince(marker).filter { it.fields["operation"] == "update" }
        assertEquals(1, updates.size, reportedSince(marker).toString())
    }

    @Test fun aJournalThatRefusesTheAppendAsChangedByAnotherOwnerBlocksTheProvider() = runTest {
        val conversation = PinConversation("anotherowner")
        var calls = 0
        val backing = InMemoryEventJournal()
        val service = DefaultRequestPinService(repository(), gateway { calls++; answer }, backgroundScope, unreadable(backing, refuse = true), storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        val marker = AppLog.history().lastOrNull()
        service.sync(conversation, listOf(source), profile); runCurrent()
        assertEquals(0, calls)
        assertNotNull(service.failures.value[conversation])
        assertTrue(reportedSince(marker).any { it.event == "operation.failed" }, "A refused append is a failure, not an accepted input")
        assertEquals(0, backing.streams().flatMap { backing.read(it) }.count { type(it.detail) == "Analyse" })
    }

    @Test fun anAdmittedAppendCompletesEvenWhenItsOwnerIsCancelledMidWrite() = runTest {
        val backing = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>()
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (type(detail) == "Analyse") { entered.complete(Unit); delay(1_000) }
                return backing.append(expected, operation, at, detail)
            }
        }
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = DefaultRequestPinService(repository(), gateway { answer }, owner, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        entered.await()
        owner.cancel(); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, backing.read(backing.streams().single()).count { type(it.detail) == "Analyse" }, "An admitted write is non-cancellable")
    }

    @Test fun profileCredentialsAndConnectionMetadataNeverEnterTheJournal() = runTest {
        val journal = InMemoryEventJournal()
        val service = DefaultRequestPinService(repository(), gateway { answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        val records = journal.streams().flatMap { journal.read(it) }
        assertTrue(records.any { type(it.detail) == "Analyse" })
        for (record in records) {
            assertFalse(record.detail.contains(profile.apiKey))
            assertFalse(record.detail.contains(profile.baseUrl))
            assertFalse(record.detail.contains(profile.name))
        }
    }

    @Test fun checkpointFailureAfterDurableCompletionRestoresSummaryWithoutAnotherModelCall() = runTest {
        val journal = InMemoryEventJournal()
        val backing = repository()
        var failCheckpoint = true
        val repository = object : RequestPinRepository by backing {
            override fun save(conversation: PinConversation, records: List<RequestPinRecord>) {
                if (failCheckpoint && records.any { it.analysed }) throw StorageException("checkpoint", StorageException.Kind.WRITE)
                backing.save(conversation, records)
            }
        }
        var calls = 0
        val model = gateway { calls++; answer }
        val service = DefaultRequestPinService(repository, model, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        assertEquals(1, calls)
        assertNotNull(service.failures.value[key])
        failCheckpoint = false
        val restored = DefaultRequestPinService(repository, model, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        restored.sync(key, listOf(source), profile, reopened = true); runCurrent()
        assertEquals(1, calls)
        assertEquals("Summary", restored.groups.value.getValue(key).single().request.summary)
        assertTrue(backing.load(key).single().analysed)
        assertNull(restored.failures.value[key])
    }

    @Test fun resetWaitsForAlreadyAcceptedAppendBeforeDroppingTheOwnersJournal() = runTest {
        val backing = InMemoryEventJournal()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = true
        val journal = object : EventJournal by backing {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (block) { block = false; entered.complete(Unit); release.await() }
                return backing.append(expected, operation, at, detail)
            }
        }
        var calls = 0
        val repo = repository()
        val service = DefaultRequestPinService(repo, gateway { calls++; answer }, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); entered.await()
        val reset = backgroundScope.async { service.resetForWipe() }
        runCurrent()
        release.complete(Unit); runCurrent(); reset.await()
        assertEquals(0, calls)
        assertTrue(service.groups.value.isEmpty())
        assertTrue(repo.load(key).isEmpty())
        assertTrue(backing.streams().isEmpty(), "A late accepted append must not resurrect pre-reset state")
    }

    @Test fun deletedConversationStaysDeletedAcrossRestartEvenAfterAnUncooperativeModelReturns() = runTest {
        val journal = InMemoryEventJournal()
        val repo = repository()
        val release = CompletableDeferred<String>()
        var calls = 0
        val model = gateway { calls++; withContext(NonCancellable) { release.await() } }
        val service = DefaultRequestPinService(repo, model, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        service.sync(key, listOf(source), profile); runCurrent()
        service.remove(key)
        release.complete(answer); runCurrent()
        val restored = DefaultRequestPinService(repo, model, backgroundScope, journal, storageDispatcher = UnconfinedTestDispatcher(testScheduler))
        restored.sync(key, listOf(source), profile, reopened = true); runCurrent()
        assertEquals(1, calls)
        assertNull(restored.groups.value[key])
        assertTrue(repo.load(key).isEmpty())
    }
}
