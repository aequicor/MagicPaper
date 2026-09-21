package io.aequicor.magicpaper.backend.lifecycle

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.CodingEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Single admission boundary. Concrete adapters receive only the scoped native observation capability. */
class JournaledBackendAgent(private val native: NativeAgentAdapter, private val lifecycle: NativeLifecycleOwner) : BackendAgent,
    NativeAgentAdapter by native {
    private val jobs = ConcurrentHashMap<NativeRunRef, Job>()
    override fun run(request: NativeAgentRequest): Flow<CodingEvent> = flow {
        // A child job scopes abort to this run while synchronous emission preserves event order.
        coroutineScope {
            val run = NativeRunRef(request.session.id, request.session.pendingRun?.runId ?: request.requestId)
            lifecycle.begin(run, request.recovery, request.noDispatchRecovery)
            val job = currentCoroutineContext().job
            check(jobs.putIfAbsent(run, job) == null) { "Native request already has an executor" }
            var primary: Throwable? = null
            var finished = false
            try {
                native.run(request).flowOn(NativeAttemptContext(run, lifecycle)).collect { event ->
                    if (event is CodingEvent.Finished) finished = true else emit(event)
                }
                check(finished) { "Native adapter returned without terminal evidence" }
            } catch (failure: Throwable) {
                primary = failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (primary is CancellationException) lifecycle.cancel(run)
                        lifecycle.finished(run)
                    } catch (cleanup: Throwable) {
                        val previous = primary
                        if (previous == null) primary = cleanup
                        else if (previous !== cleanup) {
                            if (cleanup is CancellationException && previous !is CancellationException) {
                                cleanup.addSuppressed(previous)
                                primary = cleanup
                            } else previous.addSuppressed(cleanup)
                        }
                    } finally { jobs.remove(run, job) }
                }
            }
            primary?.let { if (it is CancellationException) throw it }
            val recovery = lifecycle.inspect(run.sessionId)
            if (recovery.persistenceUnknown || recovery.noDispatch.any { it.proof.run == run } || recovery.items.any { it.attempt.run == run &&
                    (it.outcome == NativeOutcome.UNKNOWN || it.termination != NativeTermination.STOPPED) }) {
                throw NativeRecoveryRequired(recovery).apply { primary?.let(::initCause) }
            }
            primary?.let { throw it }
            check(recovery.items.any { it.attempt.run == run }) { "Native adapter returned without an admitted attempt" }
            emit(CodingEvent.Finished)
        }
    }

    override fun abort(sessionId: String) {
        // Cancellation revokes the local executor, whose noncancellable cleanup records the durable facts.
        jobs.filterKeys { it.sessionId == sessionId }.values.forEach { it.cancel(CancellationException("Native run stopped")) }
    }
    override fun abortAll() = jobs.values.forEach { it.cancel(CancellationException("Native runtime stopped")) }
    override suspend fun inspectRecovery(sessionId: String) = lifecycle.inspect(sessionId)
    override suspend fun stopRecovery(attempt: NativeAttemptRef): NativeRecoverySummary {
        lifecycle.requestStop(attempt)
        val executor = jobs[attempt.run]
        if (executor != null) {
            executor.cancel(CancellationException("Native recovery stopped the active attempt"))
            executor.join()
        } else {
            // Legacy PID reconciliation may stop an exact parent, but cannot prove that an
            // already-dead parent left no descendants. Only the live interpreter reports Stopped.
            native.reconcile(attempt.run.sessionId)
        }
        return lifecycle.inspect(attempt.run.sessionId)
    }
    override suspend fun acknowledgeRecovery(attempt: NativeAttemptRef, parentDecisionId: String) = lifecycle.acknowledge(attempt, parentDecisionId)
    override suspend fun acknowledgeNoDispatch(proof: NativeNoDispatchProof, parentDecisionId: String) = lifecycle.acknowledgeNoDispatch(proof, parentDecisionId)
    override suspend fun reconcile(sessionId: String) {
        val before = lifecycle.inspect(sessionId)
        before.items.filter { it.termination != NativeTermination.STOPPED }.forEach { stopRecovery(it.attempt) }
        // Preserve migration recovery for an older native receipt with no lifecycle journal record.
        if (before.items.isEmpty() && before.noDispatch.isEmpty()) native.reconcile(sessionId)
        val after = lifecycle.inspect(sessionId)
        if (after.persistenceUnknown || after.items.any { it.outcome == NativeOutcome.UNKNOWN || it.termination != NativeTermination.STOPPED })
            throw NativeRecoveryRequired(after)
    }
    override suspend fun prepareForReset() {
        lifecycle.closing()
        abortAll()
        jobs.values.toList().joinAll()
        val recovery = lifecycle.inspect()
        recovery.items.filter { it.termination != NativeTermination.STOPPED }.forEach { stopRecovery(it.attempt) }
        check(lifecycle.inspect().items.all { it.termination == NativeTermination.STOPPED }) { "Native cleanup is not confirmed" }
    }
    override suspend fun resumeAfterReset() = lifecycle.reload()
    override suspend fun shutdown() {
        lifecycle.closing()
        abortAll()
        var primary: Throwable? = null
        try { jobs.values.toList().joinAll() } catch (failure: Throwable) { primary = failure }
        try { native.close() } catch (failure: Throwable) { if (primary == null) primary = failure else primary.addSuppressed(failure) }
        try { if (primary == null) { lifecycle.closed() } }
        catch (failure: Throwable) { if (primary == null) primary = failure else primary.addSuppressed(failure) }
        primary?.let { throw it }
    }
    override fun close() {
        // Emergency resource cleanup cannot pretend to have committed suspended journal writes.
        abortAll()
        native.close()
    }
}

fun journaledBackendAgent(contribution: BackendAgentContribution, environment: NativeBackendEnvironment): BackendAgent =
    JournaledBackendAgent(contribution.create(environment), NativeLifecycleOwner(environment.lifecycleJournal, environment.diagnostics))
