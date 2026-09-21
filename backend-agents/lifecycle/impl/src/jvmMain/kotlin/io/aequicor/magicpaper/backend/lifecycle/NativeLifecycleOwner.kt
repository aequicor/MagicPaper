package io.aequicor.magicpaper.backend.lifecycle

import io.aequicor.magicpaper.backend.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Journal is authoritative. The adapter's process maps are only live resource lookups. */
class NativeLifecycleOwner(private val journal: NativeLifecycleJournal, private val diagnostics: NativeDiagnostics) : NativeExecutionLifecycle {
    private val lock = Mutex()
    private var state = NativeLifecycleMachine.initial()
    private var snapshot: NativeJournalSnapshot? = null
    private var started = false

    private suspend fun initialize() {
        if (started) return
        val loaded = journal.snapshot()
        validate(loaded)
        var restored = NativeLifecycleMachine.initial()
        loaded.entries.forEach { entry ->
            val transition = NativeLifecycleMachine.reduce(restored, entry.input)
            check(transition.effects.none { it is NativeLifecycleMachine.Effect.Reject }) { "Native lifecycle journal is inconsistent" }
            restored = transition.state
        }
        state = restored
        snapshot = loaded
        // Never interpret historical effects; a new attachment only records its recovery fence.
        commit(NativeLifecycleMachine.Fact.Restored)
        // Historical executions remain untouched. Only the latest admission in each session
        // may need a new proof; an older preflight was already superseded under the old protocol.
        state.runs.values.groupBy { it.ref.sessionId }.values.forEach { confirmNoDispatch(it.last().ref) }
        started = true
    }

    private suspend fun commit(input: NativeLifecycleMachine.Input): List<NativeLifecycleMachine.Effect> {
        val next = NativeLifecycleMachine.reduce(state, input)
        next.effects.filterIsInstance<NativeLifecycleMachine.Effect.Reject>().firstOrNull()?.let { throw IllegalStateException(it.reason) }
        val previous = checkNotNull(snapshot)
        val entry = NativeJournalEntry(UUID.randomUUID().toString(), input)
        try {
            val revision = journal.append(previous.revision, entry)
                ?: throw IllegalStateException("Native lifecycle journal was changed by another owner")
            check(revision.generation == previous.revision.generation && revision.position > previous.revision.position) { "Invalid native lifecycle append revision" }
            snapshot = NativeJournalSnapshot(revision, previous.entries + entry, previous.positions + revision.position)
        } catch (failure: Throwable) {
            val observed = try { withContext(NonCancellable) { journal.snapshot() } } catch (readFailure: Throwable) {
                state = NativeLifecycleMachine.reduce(state, NativeLifecycleMachine.Fact.PersistenceUnknown).state
                failure.addSuppressed(readFailure)
                report("append_unknown", failure)
                throw failure
            }
            try { validate(observed) } catch (invalid: Throwable) {
                state = NativeLifecycleMachine.reduce(state, NativeLifecycleMachine.Fact.PersistenceUnknown).state
                failure.addSuppressed(invalid)
                report("append_unknown", failure)
                throw failure
            }
            val committed = observed.revision.generation == previous.revision.generation &&
                observed.entries == previous.entries + entry && observed.positions.size == previous.positions.size + 1 &&
                observed.positions.dropLast(1) == previous.positions && observed.positions.last() > previous.revision.position
            if (committed) {
                snapshot = observed
                state = next.state
                // A cancelled append may have committed, but must not execute the derived native effect.
                if (failure is CancellationException) throw failure
            } else {
                if (observed != previous) state = NativeLifecycleMachine.reduce(state, NativeLifecycleMachine.Fact.PersistenceUnknown).state
                report(if (state.persistenceUnknown) "append_unknown" else "append_failed", failure)
                throw failure
            }
        }
        state = next.state
        currentCoroutineContext().ensureActive()
        return next.effects
    }

    override suspend fun begin(run: NativeRunRef, acknowledgement: NativeRecoveryAcknowledgement?,
        noDispatchAcknowledgement: NativeNoDispatchAcknowledgement?) = lock.withLock {
        initialize()
        try {
            state.runs.values.lastOrNull { it.ref.sessionId == run.sessionId }?.let { confirmNoDispatch(it.ref) }
            commit(NativeLifecycleMachine.Intent.Begin(run, acknowledgement, noDispatchAcknowledgement))
        }
        catch (failure: CancellationException) {
            // A committed Begin whose caller was cancelled returned no Execute permission.
            // Fence that exact admission before exposing the proof, even in this same process.
            try { withContext(NonCancellable) {
                state.runs[run]?.takeIf { it.active && it.attempts.isEmpty() }?.let {
                    commit(NativeLifecycleMachine.Fact.RunFinished(run))
                    confirmNoDispatch(run)
                }
            } } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
        catch (failure: Throwable) {
            val recovery = summary(run.sessionId)
            if (recovery.persistenceUnknown || recovery.noDispatch.isNotEmpty() || recovery.items.any { it.outcome == NativeOutcome.UNKNOWN || it.termination == NativeTermination.UNKNOWN })
                throw NativeRecoveryRequired(recovery).apply { initCause(failure) }
            throw failure
        }
        Unit
    }

    override suspend fun admitLaunch(run: NativeRunRef): NativeAttemptRef = lock.withLock {
        initialize()
        commit(NativeLifecycleMachine.Fact.LaunchRequested(run)).filterIsInstance<NativeLifecycleMachine.Effect.Launch>().single().attempt
    }
    private suspend fun observe(fact: NativeLifecycleMachine.Fact) = lock.withLock { initialize(); commit(fact); Unit }
    override suspend fun attached(attempt: NativeAttemptRef, process: NativeProcessIdentity) = observe(NativeLifecycleMachine.Fact.Attached(attempt, process))
    override suspend fun deliver(attempt: NativeAttemptRef, stage: NativeDelivery) = observe(NativeLifecycleMachine.Fact.DeliveryRequested(attempt, stage))
    override suspend fun accepted(attempt: NativeAttemptRef, nativeThreadId: String?, nativeTurnId: String?) = observe(NativeLifecycleMachine.Fact.Accepted(attempt, nativeThreadId, nativeTurnId))
    override suspend fun terminal(attempt: NativeAttemptRef, outcome: NativeOutcome) = observe(NativeLifecycleMachine.Fact.Terminal(attempt, outcome))
    override suspend fun stopping(attempt: NativeAttemptRef) = observe(NativeLifecycleMachine.Fact.Stopping(attempt))
    override suspend fun stopped(attempt: NativeAttemptRef) = observe(NativeLifecycleMachine.Fact.Stopped(attempt))
    override suspend fun unavailable(attempt: NativeAttemptRef) = observe(NativeLifecycleMachine.Fact.Unavailable(attempt))
    override suspend fun finished(run: NativeRunRef) = lock.withLock {
        initialize()
        commit(NativeLifecycleMachine.Fact.RunFinished(run))
        confirmNoDispatch(run)
    }
    override suspend fun cancel(run: NativeRunRef) = lock.withLock { initialize(); commit(NativeLifecycleMachine.Intent.Cancel(run)); Unit }
    override suspend fun closing() = lock.withLock { initialize(); commit(NativeLifecycleMachine.Intent.Close); Unit }
    override suspend fun closed() = observe(NativeLifecycleMachine.Fact.Closed)

    override suspend fun inspect(sessionId: String?): NativeRecoverySummary = lock.withLock {
        initialize()
        if (!state.persistenceUnknown) state.runs.values.filter { sessionId == null || it.ref.sessionId == sessionId }
            .groupBy { it.ref.sessionId }.values.forEach { confirmNoDispatch(it.last().ref) }
        summary(sessionId)
    }
    private fun summary(sessionId: String?): NativeRecoverySummary {
        val runs = state.runs.values.filter { sessionId == null || it.ref.sessionId == sessionId }
        return NativeRecoverySummary(runs.flatMap { run -> run.attempts.map { NativeRecoveryItem(it.ref, it.outcome, it.termination, it.acknowledgement) } },
            state.persistenceUnknown, runs.mapNotNull { run -> run.noDispatchProof?.let { NativeNoDispatchItem(it, run.noDispatchAcknowledgement) } },
            runs.flatMap { run -> listOfNotNull(run.recoveryAcknowledgement?.id, run.previousNoDispatchAcknowledgement?.id)
                .map { NativeRecoveryConsumption(it, run.ref) } })
    }

    private suspend fun confirmNoDispatch(ref: NativeRunRef) {
        val run = state.runs[ref] ?: return
        if (run.active || run.attempts.isNotEmpty() || run.noDispatchProof != null ||
            state.runs.values.lastOrNull { it.ref.sessionId == ref.sessionId }?.ref != ref) return
        commit(NativeLifecycleMachine.Fact.NoDispatchConfirmed(NativeNoDispatchProof(ref,
            UUID.randomUUID().toString(), checkNotNull(snapshot).revision.generation)))
    }

    suspend fun requestStop(attempt: NativeAttemptRef) = lock.withLock {
        initialize()
        commit(NativeLifecycleMachine.Intent.Stop(attempt))
        Unit
    }

    private fun validate(value: NativeJournalSnapshot) {
        check(value.revision.generation.isNotBlank() && value.revision.position >= 0) { "Native lifecycle revision is invalid" }
        check(value.entries.all { it.id.isNotBlank() } && value.entries.map { it.id }.toSet().size == value.entries.size) { "Native lifecycle entry identity is invalid" }
        check(value.entries.size == value.positions.size && value.positions.all { it > 0 } &&
            value.positions.zipWithNext().all { (a, b) -> a < b } &&
            (value.positions.isEmpty() || value.positions.last() == value.revision.position)) { "Native lifecycle journal order is invalid" }
        check(value.entries.mapNotNull { (it.input as? NativeLifecycleMachine.Fact.NoDispatchConfirmed)?.proof }
            .all { it.journalGeneration == value.revision.generation }) { "Native no-dispatch proof belongs to another journal generation" }
    }

    override suspend fun reload() = lock.withLock {
        check(state.closing) { "Native owner must pause before journal reset" }
        started = false
        initialize()
    }

    suspend fun acknowledge(attempt: NativeAttemptRef, parentDecisionId: String): NativeRecoveryAcknowledgement = lock.withLock {
        initialize()
        val existing = state.runs[attempt.run]?.attempts?.getOrNull(attempt.ordinal)?.acknowledgement
        if (existing != null) {
            check(existing.parentDecisionId == parentDecisionId) { "Native recovery decision changed" }
            return@withLock existing
        }
        NativeRecoveryAcknowledgement(UUID.randomUUID().toString(), attempt, parentDecisionId).also {
            commit(NativeLifecycleMachine.Intent.Acknowledge(it))
        }
    }

    suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String): NativeNoDispatchAcknowledgement = lock.withLock {
        initialize()
        check(proof.journalGeneration == checkNotNull(snapshot).revision.generation && state.runs[proof.run]?.noDispatchProof == proof) {
            "Native no-dispatch proof is missing or belongs to another journal generation"
        }
        state.runs[proof.run]?.noDispatchAcknowledgement?.let { existing ->
            check(existing.parentDecisionId == parentDecisionId) { "Native recovery decision changed" }
            return@withLock existing
        }
        NativeNoDispatchAcknowledgement(UUID.randomUUID().toString(), proof, parentDecisionId).also {
            commit(NativeLifecycleMachine.Intent.AcknowledgeNoDispatch(it))
        }
    }

    private fun report(event: String, failure: Throwable) = diagnostics.error("native_lifecycle", event,
        IllegalStateException("Native lifecycle persistence failed").apply { stackTrace = failure.stackTrace },
        mapOf("result" to if (state.persistenceUnknown) "unknown" else "unchanged", "failure" to failure.javaClass.simpleName))
}
