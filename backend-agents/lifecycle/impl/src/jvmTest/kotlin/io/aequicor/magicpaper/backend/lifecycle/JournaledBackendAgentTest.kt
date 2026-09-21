package io.aequicor.magicpaper.backend.lifecycle

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class JournaledBackendAgentTest {
    private val request = NativeAgentRequest("request", CodingSession("session", "project", "", 0, engine = CodingEngine.PI),
        ".", "private prompt", "", null, LlmProfile("profile", "", modelId = "model"), JsonObject(emptyMap()), emptyList(),
        CodingInteractionMode.CODE, NativeAgentTools(emptyList(), emptyMap(), emptyMap(), emptySet(), JsonObject(emptyMap())))
    private val diagnostics = NativeDiagnostics { _, _, _, _ -> }

    @Test fun failedPreflightRestoresAnExactNoDispatchProofAndRequiresOneExplicitFreshDecision() = runTest {
        val journal = Memory()
        var entered = 0
        var launches = 0
        var preflightFails = true
        val preflight = IllegalStateException("Working directory unavailable")
        val native = Adapter {
            entered++
            if (preflightFails) throw preflight
            val (events, attempt) = admit()
            launches++
            events.terminal(attempt, NativeOutcome.SUCCEEDED)
            events.stopped(attempt)
            emit(CodingEvent.Finished)
        }
        val first = JournaledBackendAgent(native, NativeLifecycleOwner(journal, diagnostics))
        val observed = mutableListOf<CodingEvent>()
        val failure = assertFailsWith<NativeRecoveryRequired> { first.run(request).toList(observed) }
        assertTrue(failure.recovery.items.isEmpty())
        assertSame(preflight, failure.cause)
        assertTrue(observed.isEmpty())
        val proof = failure.recovery.noDispatch.single().proof
        val restored = JournaledBackendAgent(native, NativeLifecycleOwner(journal, diagnostics))
        assertEquals(proof, restored.inspectRecovery("session").noDispatch.single().proof)
        restored.reconcile("session")
        assertEquals(0, native.reconciliations, "This proof does not authorize legacy process cleanup")
        assertFailsWith<NativeRecoveryRequired> { restored.run(request.copy(requestId = "fresh")).toList() }
        assertEquals(1, entered)
        assertEquals(0, launches)
        val ack = restored.acknowledgeNoDispatch(proof, "explicit-parent-decision")
        preflightFails = false
        assertEquals(listOf<CodingEvent>(CodingEvent.Finished),
            restored.run(request.copy(requestId = "fresh", noDispatchRecovery = ack)).toList())
        assertEquals(listOf(NativeRecoveryConsumption(ack.id, NativeRunRef("session", "fresh"))),
            restored.inspectRecovery("session").consumptions)
        assertFailsWith<NativeRecoveryRequired> { restored.run(request.copy(requestId = "another", noDispatchRecovery = ack)).toList() }
        assertEquals(2, entered)
        assertEquals(1, launches)
    }

    @Test fun anAdmittedLaunchWithoutAnAttachedProcessNeverBecomesANoDispatchProof() = runTest {
        val agent = JournaledBackendAgent(Adapter {
            val context = checkNotNull(currentCoroutineContext()[NativeAttemptContext])
            context.events.admitLaunch(context.run)
            error("Interrupted immediately after launch admission")
        }, NativeLifecycleOwner(Memory(), diagnostics))
        val failure = assertFailsWith<NativeRecoveryRequired> { agent.run(request).toList() }
        assertEquals(0, failure.recovery.items.single().attempt.ordinal)
        assertTrue(failure.recovery.noDispatch.isEmpty())
    }

    @Test fun finishedIsPublishedOnlyAfterNativeCleanupAndDurableFinish() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        var cleaned = false
        val agent = JournaledBackendAgent(Adapter {
            val (events, attempt) = admit()
            emit(CodingEvent.FinalText("answer"))
            events.terminal(attempt, NativeOutcome.SUCCEEDED)
            emit(CodingEvent.Finished)
            events.stopping(attempt)
            cleaned = true
            events.stopped(attempt)
        }, owner)
        val events = agent.run(request).onEach {
            if (it is CodingEvent.Finished) {
                assertTrue(cleaned)
                assertTrue(journal.entries.any { entry -> entry.input is NativeLifecycleMachine.Fact.RunFinished })
            }
        }.toList()
        assertEquals(1, events.count { it is CodingEvent.Finished })
        assertEquals(NativeOutcome.SUCCEEDED, agent.inspectRecovery("session").items.single().outcome)
        assertFailsWith<IllegalStateException> { agent.run(request).toList() }
    }

    @Test fun interruptedProtocolCannotPublishFinishedOrBeReplayedOnRestore() = runTest {
        val journal = Memory()
        var starts = 0
        val native = Adapter {
            starts++
            val (events, attempt) = admit()
            emit(CodingEvent.Finished) // A disconnected protocol may close its stream without terminal proof.
            events.stopped(attempt)
        }
        val first = JournaledBackendAgent(native, NativeLifecycleOwner(journal, diagnostics))
        val observed = mutableListOf<CodingEvent>()
        assertFailsWith<NativeRecoveryRequired> { first.run(request).toList(observed) }
        assertTrue(observed.none { it is CodingEvent.Finished })
        val restored = JournaledBackendAgent(native, NativeLifecycleOwner(journal, diagnostics))
        assertEquals(NativeOutcome.UNKNOWN, restored.inspectRecovery("session").items.single().outcome)
        assertFailsWith<NativeRecoveryRequired> { restored.run(request.copy(requestId = "fresh")).toList() }
        assertEquals(1, starts)
        val attempt = restored.inspectRecovery("session").items.single().attempt
        val ack = restored.acknowledgeRecovery(attempt, "explicit-parent-abandon")
        assertEquals(NativeOutcome.UNKNOWN, restored.inspectRecovery("session").items.single().outcome)
        assertEquals(attempt, ack.predecessor)
    }

    @Test fun legacyPidReconciliationDoesNotProveDescendantTermination() = runTest {
        val journal = Memory()
        val owner = NativeLifecycleOwner(journal, diagnostics)
        val run = NativeRunRef("session", "request")
        owner.begin(run)
        val attempt = owner.admitLaunch(run)
        owner.attached(attempt, NativeProcessIdentity("receipt", Long.MAX_VALUE, 1))
        owner.deliver(attempt, NativeDelivery.PI_STDIN)
        val native = Adapter { error("Restore cannot launch") }
        val restored = JournaledBackendAgent(native, NativeLifecycleOwner(journal, diagnostics))
        val recovery = restored.stopRecovery(attempt)
        assertEquals(1, native.reconciliations)
        assertEquals(NativeTermination.UNKNOWN, recovery.items.single().termination)
        assertFailsWith<IllegalStateException> { restored.acknowledgeRecovery(attempt, "abandon") }
        assertFailsWith<NativeRecoveryRequired> { restored.run(request.copy(requestId = "new")).toList() }
    }

    @Test fun transportFailureAfterDeliveryRemainsTypedUnknownAndRetainsItsCause() = runTest {
        val transport = IllegalStateException("private transport failure")
        val agent = JournaledBackendAgent(Adapter {
            val (events, attempt) = admit()
            try { throw transport } finally { events.stopping(attempt); events.stopped(attempt) }
        }, NativeLifecycleOwner(Memory(), diagnostics))
        val observed = mutableListOf<CodingEvent>()
        val unknown = assertFailsWith<NativeRecoveryRequired> { agent.run(request).toList(observed) }
        assertTrue(generateSequence<Throwable>(unknown) { it.cause }.any { it === transport })
        assertEquals(NativeOutcome.UNKNOWN, unknown.recovery.items.single().outcome)
        assertEquals(NativeTermination.STOPPED, unknown.recovery.items.single().termination)
        assertTrue(observed.none { it is CodingEvent.Finished })
    }

    @Test fun slowCollectorReceivesEveryPartialEventBeforeExactRecoveryFailure() = runTest {
        val transport = IllegalStateException("transport outcome is unknown")
        val partial = listOf<CodingEvent>(CodingEvent.Notice("opening"), CodingEvent.FinalText("partial answer"))
        var cleaned = false
        val agent = JournaledBackendAgent(Adapter {
            val (events, attempt) = admit()
            try {
                partial.forEach { emit(it) }
                throw transport
            } finally {
                events.stopping(attempt)
                cleaned = true
                events.stopped(attempt)
            }
        }, NativeLifecycleOwner(Memory(), diagnostics))
        val received = mutableListOf<CodingEvent>()

        val unknown = assertFailsWith<NativeRecoveryRequired> {
            agent.run(request).collect { event ->
                // A buffered producer could fail while the first event is suspended here,
                // cancelling delivery of this value and any following queued values.
                delay(100)
                received += event
            }
        }

        assertEquals(partial, received)
        assertTrue(cleaned)
        assertSame(transport, unknown.cause)
        assertEquals(NativeAttemptRef(NativeRunRef("session", "request"), 0), unknown.recovery.items.single().attempt)
        assertEquals(NativeOutcome.UNKNOWN, unknown.recovery.items.single().outcome)
        assertEquals(NativeTermination.STOPPED, unknown.recovery.items.single().termination)
        assertTrue(unknown.recovery.noDispatch.isEmpty())
    }

    @Test fun abortCancelsOnlyItsDedicatedRunAndPreservesCallerContinuation() = runTest {
        lateinit var agent: BackendAgent
        val cancellation = assertFailsWith<CancellationException> {
            agent = JournaledBackendAgent(Adapter {
                val (events, attempt) = admit()
                try { emit(CodingEvent.Notice("ready")); awaitCancellation() }
                finally { withContext(NonCancellable) { events.stopped(attempt) } }
            }, NativeLifecycleOwner(Memory(), diagnostics))
            agent.run(request).collect { agent.abort("session") }
        }

        assertTrue(currentCoroutineContext().isActive)
        assertTrue(cancellation.message.orEmpty().contains("stopped"))
        val recovery = agent.inspectRecovery("session")
        assertEquals(NativeOutcome.UNKNOWN, recovery.items.single().outcome)
        assertEquals(NativeTermination.STOPPED, recovery.items.single().termination)
    }

    @Test fun cancelledFinishRecordRetainsCancellationAndEarlierTransportFailure() = runTest {
        val transport = IllegalStateException("transport")
        val cancellation = CancellationException("finish write cancelled")
        val journal = Memory().apply { rejectedInput = { if (it is NativeLifecycleMachine.Fact.RunFinished) cancellation else null } }
        val agent = JournaledBackendAgent(Adapter {
            val (events, attempt) = admit()
            try { throw transport } finally { events.stopping(attempt); events.stopped(attempt) }
        }, NativeLifecycleOwner(journal, diagnostics))
        val failure = assertFailsWith<CancellationException> { agent.run(request).toList() }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { transport in it.suppressed })
    }

    @Test fun resetWaitsForCancelledExecutorCleanupAndNeverRestartsIt() = runTest {
        val journal = Memory()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        var starts = 0
        val agent = JournaledBackendAgent(Adapter {
            starts++
            val (events, attempt) = admit()
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) {
                events.stopping(attempt); cleaned = true; events.stopped(attempt)
            } }
        }, NativeLifecycleOwner(journal, diagnostics))
        val job = launch { agent.run(request).collect() }
        entered.await()
        agent.prepareForReset()
        assertTrue(cleaned)
        job.join() // The external collector observes producer cancellation on its next dispatch.
        assertTrue(job.isCancelled)
        journal.reset()
        agent.resumeAfterReset()
        assertTrue(agent.inspectRecovery("session").items.isEmpty())
        assertEquals(1, starts)
    }

    private suspend fun admit(): Pair<NativeAttemptEvents, NativeAttemptRef> {
        val context = checkNotNull(currentCoroutineContext()[NativeAttemptContext])
        val attempt = context.events.admitLaunch(context.run)
        context.events.attached(attempt, NativeProcessIdentity("receipt", 42, 1))
        context.events.deliver(attempt, NativeDelivery.PI_STDIN)
        return context.events to attempt
    }
    private class Adapter(val body: suspend FlowCollector<CodingEvent>.() -> Unit) : NativeAgentAdapter {
        var reconciliations = 0
        override val descriptor = BackendAgentDescriptor(CodingEngine.PI, "fixture", emptySet(), "", "", "", "")
        override val rootPath = "fixture"
        override val approvals: NativeApprovalRequests? = null
        override val history: NativeToolHistory? = null
        override val removal: NativeRemoval? = null
        override suspend fun status() = NativeInstallationStatus(NativeInstallationPhase.READY, "Ready")
        override fun prepare() = flowOf(NativeInstallationStatus(NativeInstallationPhase.READY, "Ready"))
        override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile
        override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
        override fun run(request: NativeAgentRequest) = flow(body)
        override suspend fun reconcile(sessionId: String) { reconciliations++ }
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun close() = Unit
    }
    private class Memory : NativeLifecycleJournal {
        var entries = emptyList<NativeJournalEntry>()
        var rejectedInput: ((NativeLifecycleMachine.Input) -> Throwable?)? = null
        private var revision = NativeJournalRevision("initial", 0)
        override suspend fun snapshot() = NativeJournalSnapshot(revision, entries, entries.indices.map { it.toLong() + 1 })
        override suspend fun append(expected: NativeJournalRevision, entry: NativeJournalEntry): NativeJournalRevision? {
            if (expected != revision) return null
            rejectedInput?.invoke(entry.input)?.let { throw it }
            entries = entries + entry
            revision = revision.copy(position = revision.position + 1)
            return revision
        }
        fun reset() { entries = emptyList(); revision = NativeJournalRevision("reset", 0) }
    }
}
