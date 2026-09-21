package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.MediaServiceFixtures.Fixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaGenerationJournalTest {
    private fun reopen(f: Fixture, scope: CoroutineScope, journal: EventJournal = f.journal) =
        DefaultMediaGenerationService(f.settings::load, f.profiles::load, f.gateway, f.store, journal, f.usage, scope,
            pollDelayMillis = 1, waitTimeoutMillis = 20)
    private suspend fun EventJournal.entries() = streams().flatMap { read(it) }

    @Test fun freshServiceRecoversAcceptedJobByPollingAndNeverReusesThePrompt() = runTest {
        val f = Fixture(backgroundScope, timeoutMillis = 3)
        f.verify()
        f.gateway.submitAction = { MediaSubmission.Accepted("accepted-job") }
        f.gateway.pollAction = { MediaPollResult.Pending }
        assertFailsWith<IllegalStateException> { f.service.generate(f.owner, "accepted", f.request) }
        f.gateway.pollAction = { MediaPollResult.Completed(MediaRemoteOutput(MediaKind.IMAGE, url = "https://example.invalid/image")) }
        val restored = reopen(f, backgroundScope)
        assertEquals(MediaPhase.READY, restored.recover("accepted")?.phase)
        assertEquals(2, f.gateway.submissions)
        assertTrue(f.gateway.polls > 0)
        val payload = f.journal.entries().joinToString { it.detail }
        assertFalse(payload.contains("A landscape"))
        assertFalse(payload.contains("private-key"))
        assertTrue(f.store.records("operation-").isEmpty(), "New operations are journal-owned; old snapshot keys remain import sources only")
    }

    @Test fun acceptedJobWhoseFactWasNotSavedNeverBecomesANewSubmission() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        val journal = object : EventJournal by f.journal {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                if (detail.contains("\"type\":\"Accepted\"")) error("lost accepted fact")
                return f.journal.append(expected, operation, at, detail)
            }
        }
        f.gateway.submitAction = { MediaSubmission.Accepted("external-job") }
        val service = reopen(f, backgroundScope, journal)
        assertFailsWith<IllegalStateException> { service.generate(f.owner, "lost", f.request) }
        assertEquals(MediaPhase.UNKNOWN, service.operations.value[f.owner.callId]?.phase)
        val restored = reopen(f, backgroundScope)
        assertFailsWith<IllegalStateException> { restored.recover("lost") }
        assertFailsWith<IllegalStateException> { restored.generate(f.owner, "lost", f.request) }
        assertEquals(2, f.gateway.submissions)
        assertEquals(0, f.gateway.polls)
    }

    @Test fun appendThenThrowBeforeSubmitIsResolvedByExactReadback() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        var failed = false
        val journal = object : EventJournal by f.journal {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = f.journal.append(expected, operation, at, detail)
                if (!failed && detail.contains("\"type\":\"Submit\"")) { failed = true; error("ack lost") }
                return result
            }
        }
        val service = reopen(f, backgroundScope, journal)
        assertEquals(MediaPhase.READY, service.generate(f.owner, "readback", f.request).phase)
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun unprovenJournalCommitBlocksSubmissionAndRemainsUnknownAfterRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        var unreadable = false
        val journal = object : EventJournal by f.journal {
            override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String): JournalRecord? {
                val result = f.journal.append(expected, operation, at, detail)
                if (detail.contains("\"type\":\"Submit\"")) { unreadable = true; error("write response lost") }
                return result
            }
            override suspend fun snapshot(stream: String): JournalSnapshot {
                if (unreadable) error("read unavailable")
                return f.journal.snapshot(stream)
            }
        }
        val service = reopen(f, backgroundScope, journal)
        assertFailsWith<IllegalStateException> { service.generate(f.owner, "unknown", f.request) }
        assertEquals(MediaPhase.UNKNOWN, service.operations.value[f.owner.callId]?.phase)
        assertEquals(1, f.gateway.submissions, "Only the earlier explicit probe was submitted")
        assertFailsWith<IllegalStateException> { reopen(f, backgroundScope).recover("unknown") }
        assertEquals(1, f.gateway.submissions)
    }

    @Test fun legacySnapshotsImportOnceAndPreserveSavedBinaryPaths() = runTest {
        val f = Fixture(backgroundScope)
        val asset = f.store.put(byteArrayOf(9, 8), "image/png", 12, 13)
        val effective = mediaRequestDefaults(f.selection, f.request)
        val legacy = MediaOperationData("legacy", f.owner, effective, f.selection, "connection",
            GeneratedMedia(f.owner.callId, MediaKind.IMAGE, MediaPhase.READY, asset = asset), submitted = true, createdAt = 5)
        val key = "operation-${f.store.fingerprint("legacy") }"
        f.store.writeRecord(key, Json.encodeToString(legacy))
        val restored = reopen(f, backgroundScope)
        assertEquals(asset, restored.recover("legacy")?.asset)
        assertEquals("/media/${asset.id}", restored.localPath(asset))
        assertContentEquals(byteArrayOf(9, 8), restored.read(asset))
        f.store.writeRecord(key, Json.encodeToString(legacy.copy(request = effective.copy(prompt = "changed snapshot"))))
        assertEquals(asset, reopen(f, backgroundScope).recover("legacy")?.asset)
        assertEquals(1, f.journal.entries().count { it.detail.contains("\"type\":\"Import\"") })
        assertFalse(f.journal.entries().joinToString { it.detail }.contains("A landscape"))
        assertEquals(0, f.gateway.submissions)
    }

    @Test fun inlineBytesStayOutsideTheJournalAndTerminalReplayDoesNotDownloadAgain() = runTest {
        val f = Fixture(backgroundScope)
        f.gateway.submitAction = { MediaSubmission.Completed(MediaRemoteOutput(MediaKind.IMAGE, dataBase64 = "INLINE_PROVIDER_BYTES")) }
        f.verify()
        val completed = f.service.generate(f.owner, "inline", f.request)
        val count = f.gateway.submissions
        assertEquals(completed, reopen(f, backgroundScope).recover("inline"))
        assertEquals(count, f.gateway.submissions)
        assertFalse(f.journal.entries().joinToString { it.detail }.contains("INLINE_PROVIDER_BYTES"))
        assertFalse(f.store.metadata.values.any { "INLINE_PROVIDER_BYTES" in it })
    }

    @Test fun corruptedOwnStreamIsNotTreatedAsAnEmptyOperation() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.service.generate(f.owner, "corrupted", f.request)
        val stream = f.journal.entries().first { it.detail.contains("corrupted") }.stream
        f.journal.append(stream, "foreign-fact", 5, "bad")
        assertFailsWith<IllegalStateException> { reopen(f, backgroundScope).recover("corrupted") }
        assertEquals(2, f.gateway.submissions)
    }
    @Test fun mismatchedOperationIdentityIsRejectedBeforeAnyJournalWrite() = runTest {
        val f = Fixture(backgroundScope)
        val operation = MediaOperationData("other", f.owner, f.request.copy(prompt = ""), f.selection, "connection",
            GeneratedMedia(f.owner.callId, MediaKind.IMAGE), requestFingerprint = "request", createdAt = 1)
        assertFailsWith<MediaMachineRejection> {
            MediaOperationJournal(f.journal, f.store).dispatch("requested", MediaGenerationMachine.Intent.Create(operation))
        }
        assertTrue(f.journal.streams().isEmpty())
    }

    @Test fun replayRejectsWrongStreamSequenceEpochHighWaterAndEmbeddedIdentity() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.service.generate(f.owner, "own", f.request)
        val stream = f.journal.entries().first { it.detail.contains("\"id\":\"own\"") }.stream
        val original = f.journal.snapshot(stream)
        val corruptions: List<(JournalSnapshot) -> JournalSnapshot> = listOf(
            { it.copy(revision = it.revision.copy(stream = "foreign")) },
            { it.copy(records = it.records.map { record -> record.copy(stream = "foreign") }) },
            { it.copy(records = it.records.mapIndexed { index, record -> if (index == 1) record.copy(seq = it.records[0].seq) else record }) },
            { it.copy(records = it.records.reversed()) },
            { it.copy(revision = it.revision.copy(seq = it.revision.seq + 1)) },
            { it.copy(revision = it.revision.copy(resetEpoch = it.revision.resetEpoch + 1)) },
            { it.copy(records = it.records.mapIndexed { index, record -> if (index == 0)
                record.copy(detail = record.detail.replace("\"id\":\"own\"", "\"id\":\"another\"")) else record }) },
        )
        for (corrupt in corruptions) {
            val journal = object : EventJournal by f.journal {
                override suspend fun snapshot(requested: String) = if (requested == stream) corrupt(original) else f.journal.snapshot(requested)
            }
            assertFailsWith<IllegalStateException> { reopen(f, backgroundScope, journal).recover("own") }
        }
        assertEquals(2, f.gateway.submissions)
    }

    @Test fun recoveryTerminalCallbackPreservesCancellation() = runTest {
        val f = Fixture(backgroundScope)
        f.verify()
        f.service.generate(f.owner, "complete", f.request)
        val service = DefaultMediaGenerationService(f.settings::load, f.profiles::load, f.gateway, f.store, f.journal,
            f.usage, backgroundScope, onTerminal = { _, _, _ -> throw CancellationException("recovery stopped") })
        val failure = assertFailsWith<CancellationException> { service.recoverPending() }
        assertEquals("recovery stopped", failure.message)
    }

}
