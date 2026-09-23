package io.aequicor.magicpaper.data.workspace

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.KeyValueStore
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64

/** The only executor of workspace effects. Its journal and operation lock share the same owner lifetime. */
class DefaultTaskWorktreeOwner(
    private val workspace: TaskWorkspace,
    private val journal: EventJournal,
    private val payloads: KeyValueStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TaskWorktreeOwner {
    private class Entry(val journal: TaskWorktreeInputJournal) { val mutex = Mutex() }
    private val entries = ConcurrentHashMap<TaskWorktreeOwnerId, Entry>()
    private val admission = Mutex()
    @Volatile private var accepting = true
    private var closed = false
    private suspend fun entry(owner: TaskWorktreeOwnerId) = admission.withLock {
        check(accepting) { "Рабочие копии временно недоступны" }
        entries.computeIfAbsent(owner) { Entry(TaskWorktreeInputJournal(journal, payloads, it)) }
    }

    override suspend fun projection(owner: TaskWorktreeOwnerId, legacy: TaskWorktree?, generation: Long): TaskWorktreeProjection = withContext(dispatcher) {
        val entry = entry(owner)
        entry.mutex.withLock {
            // A reset may have drained and replaced the entry before this caller took its lock.
            check(accepting && entries[owner] === entry) { "Рабочие копии временно недоступны" }
            entry.journal.initialize(legacy, generation)
            entry.journal.projection()
        }
    }

    override suspend fun accept(owner: TaskWorktreeOwnerId, intent: Input.Intent, leases: TaskWorkspaceLeases): TaskWorktreeProjection = withContext(dispatcher) {
        val entry = entry(owner)
        entry.mutex.withLock {
            check(accepting && entries[owner] === entry) { "Рабочие копии временно недоступны" }
            val store = entry.journal
            store.initialize(null, 0)
            val transition = store.dispatch(intent)
            // No port has been called yet. Preserve that proof if admission closes after the durable intent.
            try {
                currentCoroutineContext().ensureActive()
                check(accepting) { "Рабочие копии временно недоступны" }
            } catch (failure: Exception) {
                for (effect in transition.effects.filterIsInstance<TaskWorktreeMachine.Effect.Execute>()) {
                    try { withContext(NonCancellable) { store.dispatch(Input.Fact.Failed(effect.pending.id, beforeEffect = true)) } }
                    catch (recordFailure: Exception) { failure.addSuppressed(recordFailure); store.uncertain(failure) }
                }
                throw failure
            }
            for (effect in transition.effects) when (effect) {
                is TaskWorktreeMachine.Effect.Execute -> execute(store, effect, leases)
                is TaskWorktreeMachine.Effect.Inspect -> inspect(store, effect)
                is TaskWorktreeMachine.Effect.Reject -> throw TaskWorktreeRejected(effect.reason, effect.message)
            }
            store.projection()
        }
    }

    override suspend fun prepareForReset() = withContext(dispatcher) {
        val active = admission.withLock {
            accepting = false
            // A task need not have been opened in this application visit to retain an external effect.
            for (stream in journal.streams().filter { it.startsWith("task-worktree:") }) {
                val id = ownerFromStream(stream)
                entries.computeIfAbsent(id) { Entry(TaskWorktreeInputJournal(journal, payloads, it)) }
            }
            entries.values.toList()
        }
        // The parent first cancels native jobs; do not erase a journal while an admitted Git operation can still complete.
        var unknown = false
        active.forEach { entry -> entry.mutex.withLock {
            entry.journal.initialize(null, 0)
            unknown = unknown || entry.journal.state.stage == TaskWorktreeMachine.Stage.UNKNOWN
        } }
        if (unknown) throw TaskWorktreeResetUnconfirmed()
    }

    override suspend fun resumeAfterReset() = admission.withLock {
        check(!closed) { "Владелец рабочих копий закрыт" }
        // The caller rolls back every attempted participant, including cancellation before dispatcher admission.
        if (accepting) return@withLock
        // prepareForReset may itself have been cancelled while joining an operation.
        // Do not forget that operation or open a second writer during rollback.
        entries.values.toList().forEach { it.mutex.withLock { } }
        entries.clear()
        accepting = true
    }

    override suspend fun close() {
        val active = admission.withLock { closed = true; accepting = false; entries.values.toList() }
        // Closing retains journal evidence. Reset additionally proves it is safe to erase that evidence.
        active.forEach { it.mutex.withLock { } }
    }

    private fun ownerFromStream(stream: String): TaskWorktreeOwnerId {
        val parts = stream.split(':')
        check(parts.size == 3) { "Повреждён журнал рабочей копии" }
        fun decode(part: String): String {
            val value = Base64.getUrlDecoder().decode(part).toString(Charsets.UTF_8)
            check(value.isNotBlank() && Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8)) == part) {
                "Повреждён владелец журнала рабочей копии"
            }
            return value
        }
        return TaskWorktreeOwnerId(decode(parts[1]), decode(parts[2]))
    }

    private suspend fun execute(store: TaskWorktreeInputJournal, effect: TaskWorktreeMachine.Effect.Execute, leases: TaskWorkspaceLeases) {
        val operation = effect.pending
        var started = false
        try {
            currentCoroutineContext().ensureActive()
            // Validate presence before crossing the external boundary. The adapter validates exact live handles.
            require(leases.execution != null) { "Не передан захват рабочей копии задачи" }
            if (operation.kind in setOf(TaskWorktreeMachine.Operation.OPEN, TaskWorktreeMachine.Operation.DELIVER))
                require(leases.source != null) { "Не передан захват исходной рабочей папки" }
            val authority = TaskWorkspaceOperation(store.state.owner, operation, leases)
            started = true
            val fact = when (operation.kind) {
                TaskWorktreeMachine.Operation.OPEN -> { workspace.open(effect.record, authority, effect.previous); Input.Fact.Opened(operation.id) }
                TaskWorktreeMachine.Operation.REFRESH -> workspace.refresh(effect.record, authority).let {
                    Input.Fact.Refreshed(operation.id, it.behind, it.targetCommit, it.updated, it.note, it.pendingTransfer)
                }
                TaskWorktreeMachine.Operation.CAPTURE -> Input.Fact.Captured(operation.id, workspace.capture(effect.record, authority))
                TaskWorktreeMachine.Operation.INTEGRATE -> Input.Fact.Integrated(operation.id, workspace.integrate(effect.record, authority))
                TaskWorktreeMachine.Operation.VERIFY -> {
                    try { workspace.verify(effect.record, authority); Input.Fact.Verified(operation.id) }
                    catch (failed: TaskWorktreeVerificationFailed) { Input.Fact.VerificationFailed(operation.id, failed.safeMessage) }
                }
                TaskWorktreeMachine.Operation.DELIVER -> { workspace.deliver(effect.record, authority); Input.Fact.Delivered(operation.id) }
            }
            // The process call has returned and its cleanup joined before this immutable evidence exists.
            // A later lost journal fact can be recovered without invoking Git or arbitrary checks again.
            store.saveOutcome(operation, fact)
            store.dispatch(fact)
            if (fact is Input.Fact.VerificationFailed) {
                AppLog.info("coding.worktree", "verification.failed", mapOf("operationId" to operation.id, "sessionId" to store.state.owner.sessionId))
                throw TaskWorktreeVerificationFailed(fact.message)
            }
        } catch (failure: Exception) {
            if (failure is TaskWorktreeVerificationFailed && store.state.pending == null && !store.state.persistenceUnknown) throw failure
            // This port outcome is raised before fast-forward; a changed destination proves delivery did not start.
            val destinationChanged = operation.kind == TaskWorktreeMachine.Operation.DELIVER && failure is TaskDestinationChanged
            try { withContext(NonCancellable) { store.dispatch(Input.Fact.Failed(operation.id, beforeEffect = !started || destinationChanged)) } }
            catch (recordFailure: Exception) { failure.addSuppressed(recordFailure); store.uncertain(failure) }
            if (destinationChanged && !store.state.persistenceUnknown) throw failure
            AppLog.error("coding.worktree", "operation.failed", mapOf("operationId" to operation.id,
                "sessionId" to store.state.owner.sessionId, "phase" to operation.kind.name,
                "causeType" to failure.javaClass.simpleName, "result" to if (started) "unknown" else "not_started"))
            if (failure is CancellationException) throw failure
            throw TaskWorktreeOperationUnknown(failure)
        }
    }

    private suspend fun inspect(store: TaskWorktreeInputJournal, effect: TaskWorktreeMachine.Effect.Inspect) {
        try {
            store.outcome(effect.pending)?.let { outcome ->
                store.dispatch(Input.Fact.OutcomeRecovered(outcome))
                return
            }
            when (val result = workspace.inspect(effect.record, effect.pending)) {
                is TaskWorktreeInspection.Confirmed -> store.dispatch(Input.Fact.Inspected(result.proof))
                TaskWorktreeInspection.Unknown -> store.dispatch(Input.Fact.InspectionUnknown(effect.pending.id))
                TaskWorktreeInspection.Missing -> store.dispatch(Input.Fact.NeighbourMissing(effect.record.taskId))
            }
        } catch (failure: Exception) {
            AppLog.error("coding.worktree", "inspection.failed", mapOf("operationId" to effect.pending.id,
                "sessionId" to store.state.owner.sessionId, "causeType" to failure.javaClass.simpleName))
            if (failure is CancellationException) throw failure
            throw TaskWorktreeOperationUnknown(failure)
        }
    }
}

class TaskWorktreeOperationUnknown(cause: Throwable) : IllegalStateException(
    "Операция с рабочей копией не подтверждена. Проверьте сохранённый результат перед продолжением.", cause)
