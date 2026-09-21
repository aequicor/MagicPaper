package io.aequicor.magicpaper.backend.lifecycle

import io.aequicor.magicpaper.backend.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeLifecycleOwnerTest {
    private val run = NativeRunRef("session", "run")
    private val diagnostics = NativeDiagnostics { _, _, _, _ -> }
    private class Memory : NativeLifecycleJournal {
        var revision = NativeJournalRevision("generation", 0)
        var entries = emptyList<NativeJournalEntry>()
        var positions = emptyList<Long>()
        var appendFailure: ((NativeJournalEntry) -> Throwable?)? = null
        var commitBeforeFailure = false
        var readFailure: Throwable? = null
        override suspend fun snapshot(): NativeJournalSnapshot {
            readFailure?.let { throw it }
            return NativeJournalSnapshot(revision, entries.toList(), positions.toList())
        }
        override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? {
            if (expected != revision) return null
            val failure = appendFailure?.invoke(entry)
            if (failure == null || commitBeforeFailure) { entries = entries + entry; revision = revision.copy(position = revision.position + 1); positions = positions + revision.position }
            failure?.let { throw it }
            return revision
        }
    }
    @Test fun lostAcknowledgementAcceptsOnlyTheExactAppendedInputOnce() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.commitBeforeFailure = true
        journal.appendFailure = { IllegalStateException("lost ack") }
        owner.begin(run, null)
        val attempt = owner.admitLaunch(run)
        assertEquals(0, attempt.ordinal)
        assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Intent.Begin })
        assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Fact.LaunchRequested })
    }
    @Test fun dirtyPrefixIsNeverAcceptedBecauseTheLastEntryMatches() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.commitBeforeFailure = true
        journal.appendFailure = {
            journal.entries = journal.entries.map { it.copy(id = "divergent-prefix") }
            IllegalStateException("lost ack")
        }
        assertFailsWith<NativeRecoveryRequired> { owner.begin(run, null) }
        assertTrue(owner.inspect().persistenceUnknown)
        assertFailsWith<IllegalStateException> { owner.admitLaunch(run) }
    }
    @Test fun failedReadbackFencesAllSubsequentNativeWork() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.appendFailure = { journal.readFailure = IllegalStateException("offline"); IllegalStateException("write") }
        assertFailsWith<NativeRecoveryRequired> { owner.begin(run, null) }
        journal.readFailure = null
        assertTrue(owner.inspect().persistenceUnknown)
        assertFailsWith<IllegalStateException> { owner.admitLaunch(run) }
    }
    @Test fun identicalInputsRecreatedAtDifferentPositionsAreNotTheSamePrefix() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.commitBeforeFailure = true
        journal.appendFailure = {
            journal.positions = journal.positions.map { it + 100 }
            journal.revision = journal.revision.copy(position = journal.revision.position + 100)
            IllegalStateException("drop and recreate")
        }
        assertFailsWith<NativeRecoveryRequired> { owner.begin(run, null) }
        assertTrue(owner.inspect().persistenceUnknown)
    }
    @Test fun malformedSnapshotsAreRejectedBeforeAnyAppend() = runTest {
        val entry = NativeJournalEntry("entry", NativeLifecycleMachine.Fact.Restored)
        val malformed = listOf(
            NativeJournalSnapshot(NativeJournalRevision("", 0), emptyList(), emptyList()),
            NativeJournalSnapshot(NativeJournalRevision("epoch", -1), emptyList(), emptyList()),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 1), listOf(entry.copy(id = " ")), listOf(1)),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 2), listOf(entry, entry), listOf(1, 2)),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 0), listOf(entry), listOf(0)),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 1), listOf(entry), listOf(-1)),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 2), listOf(entry), listOf(1)),
            NativeJournalSnapshot(NativeJournalRevision("epoch", 1), listOf(entry), listOf(2)),
        )
        malformed.forEach { value ->
            var writes = 0
            val journal = object : NativeLifecycleJournal {
                override suspend fun snapshot() = value
                override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? { writes++; error("Unexpected append") }
            }
            assertFailsWith<IllegalStateException> { NativeLifecycleOwner(journal, diagnostics).begin(run) }
            assertEquals(0, writes)
        }
    }
    @Test fun lostAcknowledgementCannotAcceptMalformedHighwater() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.commitBeforeFailure = true
        journal.appendFailure = { IllegalStateException("lost ack") }
        val corrupt = object : NativeLifecycleJournal by journal {
            var reads = 0
            override suspend fun snapshot(): NativeJournalSnapshot {
                val value = journal.snapshot()
                return if (++reads > 1) value.copy(revision = value.revision.copy(position = value.revision.position + 1)) else value
            }
        }
        val restored = NativeLifecycleOwner(corrupt, diagnostics)
        assertFailsWith<IllegalStateException> { restored.begin(run) }
        // No model/launch permission was returned from the malformed readback.
        assertTrue(journal.entries.none { it.input is NativeLifecycleMachine.Intent.Begin })
    }
    @Test fun resetReloadRestoresTheNewGenerationWithoutExecutingAnything() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.begin(run, null)
        owner.finished(run)
        owner.closing()
        journal.entries = emptyList(); journal.positions = emptyList()
        journal.revision = NativeJournalRevision("new-reset", 0)
        owner.reload()
        assertTrue(owner.inspect().items.isEmpty())
        assertTrue(journal.entries.all { it.input == NativeLifecycleMachine.Fact.Restored })
        owner.begin(run, null)
        assertEquals(0, owner.admitLaunch(run).ordinal)
    }
    @Test fun unchangedFailedAppendLeavesLastValidStateAvailableForExplicitRetry() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.appendFailure = { IllegalStateException("offline") }
        assertFailsWith<IllegalStateException> { owner.begin(run, null) }
        assertFalse(owner.inspect().persistenceUnknown)
        journal.appendFailure = null
        owner.begin(run, null)
        assertEquals(0, owner.admitLaunch(run).ordinal)
    }
    @Test fun cancellationAfterCommitDoesNotDeliverItsDerivedEffect() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.begin(run, null)
        journal.commitBeforeFailure = true
        journal.appendFailure = { CancellationException("cancelled acknowledgement") }
        assertFailsWith<CancellationException> { owner.admitLaunch(run) }
        journal.appendFailure = null
        // The persisted launch is not offered a second time, even though the first caller got cancellation.
        assertFailsWith<IllegalStateException> { owner.admitLaunch(run) }
        assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Fact.LaunchRequested })
    }
    @Test fun resetAndConcurrentAttachmentRejectStaleWriters() = runTest {
        for (reset in listOf(false, true)) {
            val journal = Memory()
            val owner = NativeLifecycleOwner(journal, diagnostics)
            owner.begin(run, null)
            if (reset) { journal.entries = emptyList(); journal.positions = emptyList(); journal.revision = NativeJournalRevision("reset", 0) }
            else NativeLifecycleOwner(journal, diagnostics).inspect()
            assertFailsWith<IllegalStateException> { owner.admitLaunch(run) }
            assertTrue(owner.inspect().persistenceUnknown)
        }
    }
    @Test fun restartAndProcessTerminationPreserveUnknownUntilExplicitAcknowledgement() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.begin(run, null)
        val attempt = owner.admitLaunch(run)
        owner.attached(attempt, NativeProcessIdentity("session", Long.MAX_VALUE, 1))
        owner.deliver(attempt, NativeDelivery.PI_STDIN)
        val restored = NativeLifecycleOwner(journal, diagnostics)
        assertEquals(NativeOutcome.UNKNOWN, restored.inspect().items.single().outcome)
        // PID absence alone is not cleanup evidence: descendants may still be running.
        assertEquals(NativeTermination.UNKNOWN, restored.inspect().items.single().termination)
        assertFailsWith<IllegalStateException> { restored.acknowledge(attempt, "parent-abandon") }
        restored.stopped(attempt) // Explicit native interpreter cleanup proof.
        assertEquals(NativeTermination.STOPPED, restored.inspect().items.single().termination)
        assertEquals(NativeOutcome.UNKNOWN, restored.inspect().items.single().outcome)
        assertFailsWith<NativeRecoveryRequired> { restored.begin(run.copy(requestId = "new"), null) }
        val ack = restored.acknowledge(attempt, "parent-abandon")
        assertEquals(ack, restored.acknowledge(attempt, "parent-abandon"))
        assertFailsWith<IllegalStateException> { restored.acknowledge(attempt, "different-decision") }
        restored.begin(run.copy(requestId = "new"), ack)
        assertEquals(NativeOutcome.UNKNOWN, restored.inspect().items.single().outcome)
    }

    @Test fun noDispatchProofSurvivesReopenAndItsDecisionIsConsumedExactlyOnce() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        assertTrue(owner.inspect().noDispatch.isEmpty(), "Missing runs are not evidence")
        owner.begin(run)
        assertTrue(owner.inspect().noDispatch.isEmpty(), "An active preflight may still admit a launch")
        owner.finished(run)
        val proof = owner.inspect().noDispatch.single().proof
        assertEquals(run, proof.run)
        assertEquals("generation", proof.journalGeneration)
        assertTrue(owner.inspect().items.isEmpty())
        val reopened = NativeLifecycleOwner(journal, diagnostics)
        assertEquals(proof, reopened.inspect().noDispatch.single().proof)
        val fresh = run.copy(requestId = "fresh")
        assertFailsWith<NativeRecoveryRequired> { reopened.begin(fresh) }
        val ack = reopened.acknowledgeNoDispatch(proof, "parent-decision")
        assertEquals(ack, reopened.acknowledgeNoDispatch(proof, "parent-decision"))
        assertFailsWith<IllegalStateException> { reopened.acknowledgeNoDispatch(proof, "changed-decision") }
        reopened.begin(fresh, noDispatchAcknowledgement = ack)
        assertEquals(listOf(NativeRecoveryConsumption(ack.id, fresh)), reopened.inspect().consumptions)
        val attempt = reopened.admitLaunch(fresh)
        assertEquals(0, attempt.ordinal)
        reopened.unavailable(attempt)
        reopened.finished(fresh)
        assertFailsWith<NativeRecoveryRequired> { reopened.begin(run.copy(requestId = "reuse"), noDispatchAcknowledgement = ack) }
        assertFailsWith<NativeRecoveryRequired> { reopened.begin(run) }
        assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Fact.NoDispatchConfirmed })
    }

    @Test fun restartClosesAnAcceptedPreflightWithoutLaunchingOrInventingAnAttempt() = runTest {
        val journal = Memory()
        val first = NativeLifecycleOwner(journal, diagnostics)
        first.begin(run)
        val reopened = NativeLifecycleOwner(journal, diagnostics)
        val proof = reopened.inspect().noDispatch.single().proof
        assertEquals(run, proof.run)
        assertTrue(reopened.inspect().items.isEmpty())
        assertFailsWith<IllegalStateException> { first.admitLaunch(run) }
        assertTrue(journal.entries.none { it.input is NativeLifecycleMachine.Fact.LaunchRequested })
        assertFailsWith<IllegalStateException> { reopened.acknowledgeNoDispatch(proof.copy(run = run.copy(requestId = "missing")), "decision") }
        assertFailsWith<IllegalStateException> { reopened.acknowledgeNoDispatch(proof.copy(journalGeneration = "stale"), "decision") }
    }

    @Test fun noDispatchProofAndDecisionRecoverLostAcknowledgementsIncludingCancellation() = runTest {
        for (cancelled in listOf(false, true)) {
            val journal = Memory()
            val owner = NativeLifecycleOwner(journal, diagnostics)
            owner.begin(run)
            journal.commitBeforeFailure = true
            journal.appendFailure = { entry ->
                if (entry.input is NativeLifecycleMachine.Fact.NoDispatchConfirmed || entry.input is NativeLifecycleMachine.Intent.AcknowledgeNoDispatch)
                    if (cancelled) CancellationException("lost acknowledgement") else IllegalStateException("lost acknowledgement")
                else null
            }
            if (cancelled) assertFailsWith<CancellationException> { owner.finished(run) } else owner.finished(run)
            val proof = owner.inspect().noDispatch.single().proof
            if (cancelled) assertFailsWith<CancellationException> { owner.acknowledgeNoDispatch(proof, "decision") }
            else owner.acknowledgeNoDispatch(proof, "decision")
            val saved = checkNotNull(owner.inspect().noDispatch.single().acknowledgement)
            assertEquals(saved, NativeLifecycleOwner(journal, diagnostics).acknowledgeNoDispatch(proof, "decision"))
            assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Fact.NoDispatchConfirmed })
            assertEquals(1, journal.entries.count { it.input is NativeLifecycleMachine.Intent.AcknowledgeNoDispatch })
            assertTrue(journal.entries.none { it.input is NativeLifecycleMachine.Fact.LaunchRequested })
        }
    }

    @Test fun cancelledCommittedBeginClosesItsAdmissionBeforePublishingNoDispatchProof() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.inspect()
        journal.commitBeforeFailure = true
        journal.appendFailure = { if (it.input is NativeLifecycleMachine.Intent.Begin) CancellationException("cancelled begin") else null }
        assertFailsWith<CancellationException> { owner.begin(run) }
        assertEquals(run, owner.inspect().noDispatch.single().proof.run)
        assertFailsWith<IllegalStateException> { owner.admitLaunch(run) }
        assertTrue(owner.inspect().items.isEmpty())
    }

    @Test fun proofAppendAndReadbackFailureNeverReturnsNoDispatchPermission() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        owner.begin(run)
        journal.appendFailure = {
            if (it.input is NativeLifecycleMachine.Fact.NoDispatchConfirmed) {
                journal.readFailure = IllegalStateException("readback unavailable")
                IllegalStateException("proof unavailable")
            } else null
        }
        assertFailsWith<IllegalStateException> { owner.finished(run) }
        journal.readFailure = null
        val snapshot = owner.inspect()
        assertTrue(snapshot.persistenceUnknown)
        assertTrue(snapshot.noDispatch.isEmpty())
        assertFailsWith<NativeRecoveryRequired> { owner.begin(run.copy(requestId = "new")) }
    }

    @Test fun resetAndCorruptGenerationCannotReuseAnEarlierNoDispatchProof() = runTest {
        for (corrupt in listOf(false, true)) {
            val journal = Memory()
            val owner = NativeLifecycleOwner(journal, diagnostics)
            owner.begin(run); owner.finished(run)
            val proof = owner.inspect().noDispatch.single().proof
            owner.closing()
            if (!corrupt) { journal.entries = emptyList(); journal.positions = emptyList() }
            journal.revision = NativeJournalRevision("reset", if (corrupt) journal.revision.position else 0)
            if (corrupt) assertFailsWith<IllegalStateException> { owner.reload() }
            else {
                owner.reload()
                assertTrue(owner.inspect().noDispatch.isEmpty())
                assertFailsWith<IllegalStateException> { owner.acknowledgeNoDispatch(proof, "decision") }
            }
        }
    }

    @Test fun oldSupersededPreflightRecordsDoNotIntroduceANewRecoveryDecision() = runTest {
        val journal = Memory()
        val newer = run.copy(requestId = "already-executed")
        val inputs = listOf<NativeLifecycleMachine.Input>(NativeLifecycleMachine.Intent.Begin(run),
            NativeLifecycleMachine.Fact.RunFinished(run), NativeLifecycleMachine.Intent.Begin(newer),
            NativeLifecycleMachine.Fact.LaunchRequested(newer), NativeLifecycleMachine.Fact.Unavailable(NativeAttemptRef(newer, 0)),
            NativeLifecycleMachine.Fact.RunFinished(newer))
        journal.entries = inputs.mapIndexed { index, input -> NativeJournalEntry("legacy-$index", input) }
        journal.positions = inputs.indices.map { it.toLong() + 1 }
        journal.revision = journal.revision.copy(position = inputs.size.toLong())
        val restored = NativeLifecycleOwner(journal, diagnostics).inspect()
        assertTrue(restored.noDispatch.isEmpty())
        assertEquals(NativeAttemptRef(newer, 0), restored.items.single().attempt)
    }
}
