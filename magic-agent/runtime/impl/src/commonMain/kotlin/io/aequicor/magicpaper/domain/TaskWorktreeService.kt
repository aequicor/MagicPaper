package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Parent coordinator: runtime admission and projections surround the child owner's durable Git effects. */
class TaskWorktreeService(
    private val sessions: TaskWorktreeSessionAccess,
    private val workspace: TaskWorkspace,
    private val leases: PlanningWorkspace,
    private val owner: TaskWorktreeOwner,
    private val runtime: TaskWorktreeRuntimeAccess,
) {
    private val retainedLeases = MutableStateFlow<Map<String, WorkspaceLease>>(emptyMap())
    private val mergeQueues = mutableMapOf<String, Mutex>()
    private val mergeQueuesLock = Mutex()
    val changes = MutableStateFlow(0L)
    suspend fun availability(project: CodingProject) = workspace.availability(project)
    private suspend fun parent(projectId: String, sessionId: String) =
        checkNotNull(sessions.session(projectId, sessionId)) { "Сессия задачи недоступна" }
    private suspend fun projection(projectId: String, sessionId: String): TaskWorktreeProjection {
        val current = parent(projectId, sessionId)
        return owner.projection(TaskWorktreeOwnerId(projectId, sessionId), current.taskWorktree, current.runtimeGeneration)
    }
    suspend fun session(projectId: String, sessionId: String): CodingSession {
        val current = parent(projectId, sessionId)
        val child = owner.projection(TaskWorktreeOwnerId(projectId, sessionId), current.taskWorktree, current.runtimeGeneration)
        return current.copy(taskWorktree = child.task)
    }
    private suspend fun accept(projectId: String, sessionId: String, intent: TaskWorktreeMachine.Input.Intent, publish: Boolean = true, handles: TaskWorkspaceLeases = TaskWorkspaceLeases()): TaskWorktree {
        val id = TaskWorktreeOwnerId(projectId, sessionId)
        try {
            val next = owner.accept(id, intent, handles)
            if (publish) sessions.publish(next)
            changes.update { it + 1 }
            check(!next.unknown) { "Исход операции с рабочей копией неизвестен. Проверьте сохранённый результат" }
            return checkNotNull(next.task)
        } catch (failure: Throwable) {
            // A failed external effect still has a durable unknown projection for the parent UI.
            try { withContext(NonCancellable) { sessions.publish(owner.projection(id)) } }
            catch (publication: Throwable) { if (publication !== failure) failure.addSuppressed(publication) }
            throw failure
        }
    }

    suspend fun begin(project: CodingProject, sessionId: String, taskId: String): TaskWorktree {
        val current = parent(project.id, sessionId)
        var previous = projection(project.id, sessionId).task
        if (previous?.taskId == taskId) {
            if (projection(project.id, sessionId).unknown)
                previous = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Inspect(taskId), publish = false)
            previous = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.BindRun(taskId, current.runtimeGeneration))
            workspace.reconcile(previous)
            if (previous.phase in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY)) {
                val refreshed = leasedOrNull(project.copy(id = "task-refresh-$sessionId", path = previous.path)) { lease ->
                    accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Refresh(taskId, current.runtimeGeneration, Id.new()), handles = TaskWorkspaceLeases(execution = lease))
                }
                if (refreshed != null) previous = refreshed
            }
            return previous
        }
        check(previous == null || previous.phase == TaskWorktreePhase.COMPLETE) { "Сначала завершите предыдущую задачу" }
        // The selected project may be a repository subdirectory. Read the canonical source
        // first; mutation still requires the exact root lease and a durable child intent.
        val described = workspace.describe(project, sessionId, taskId, taskLabel(current)).copy(
            reuseBranch = previous?.branch.orEmpty(), reuseCommit = previous?.mergeCommit.orEmpty())
        return leased(project.copy(id = "task-source-$sessionId", path = described.sourcePath), sessionId, SOURCE_FOLDER) { sourceLease ->
            // Do not wait for a task holder while retaining the source: a delivery can already
            // own that task and be waiting for this source. No Git effect has started yet.
            leasedOrNull(project.copy(id = "task-open-$sessionId", path = described.path)) { taskLease ->
                accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Prepare(described, current.runtimeGeneration, Id.new()),
                    handles = TaskWorkspaceLeases(sourceLease, taskLease))
            } ?: throw TaskWorkspaceBusy(described.path, TASK_COPY)
        }
    }

    suspend fun handoff(context: ToolExecutionContext, result: Boolean, checks: List<List<String>>) {
        require(context.mode == CodingInteractionMode.CODE && context.stageId == null && !context.auxiliaryExecution) { "Передача задачи недоступна" }
        val current = parent(context.projectId, context.ownerSessionId)
        require(current.runtimeGeneration == context.runtimeGeneration && current.pendingRun?.intent == ExecutionIntent.RUN) { "Запуск изменился" }
        val record = checkNotNull(projection(context.projectId, current.id).task) { "Задача не использует worktree" }
        val request = checkNotNull(current.pendingRun)
        require(record.taskId == (request.workspaceTaskId ?: request.runId)) { "Задача изменилась" }
        accept(context.projectId, current.id, TaskWorktreeMachine.Input.Intent.BindRun(record.taskId, context.runtimeGeneration))
        accept(context.projectId, current.id, TaskWorktreeMachine.Input.Intent.Handoff(record.taskId, context.runtimeGeneration, result, checks))
        val fields = mapOf("sessionId" to current.id, "entityId" to record.taskId)
        AppLog.info("coding.worktree", "handoff.recorded", fields + mapOf("outcome" to if (result) "RESULT" else "BLOCKED",
            "count" to checks.size.toString()))
        // The agent chose these commands; which program failed verification is read from here when it does.
        AppLog.trace("coding.worktree", "handoff.checks", fields) {
            checks.withIndex().joinToString("\n") { (index, args) -> "check[$index]: ${checkCommandLine(args)}" }
        }
    }

    suspend fun revokeHandoff(projectId: String, sessionId: String, taskId: String): TaskWorktree {
        val generation = parent(projectId, sessionId).runtimeGeneration
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.BindRun(taskId, generation))
        return accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.RevokeHandoff(taskId, generation))
    }
    suspend fun bindRun(projectId: String, sessionId: String, taskId: String): TaskWorktree =
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.BindRun(taskId, parent(projectId, sessionId).runtimeGeneration))
    suspend fun returnForRepair(projectId: String, sessionId: String, taskId: String): TaskWorktree {
        val generation = parent(projectId, sessionId).runtimeGeneration
        bindRun(projectId, sessionId, taskId)
        return accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.ReturnForRepair(taskId, generation))
    }
    /** Called only by explicit user continuation; an unknown check never becomes retryable here. */
    suspend fun retryFailedVerification(projectId: String, sessionId: String) {
        val child = projection(projectId, sessionId)
        if (!child.verificationFailed) return
        val task = checkNotNull(child.task)
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.RetryVerification(task.taskId, child.generation))
    }
    /** Explicit recovery reads exact saved evidence before the parent can admit another native attempt. */
    suspend fun inspectTaskOutcome(projectId: String, sessionId: String) {
        val child = projection(projectId, sessionId)
        if (!child.unknown) return
        val task = checkNotNull(child.task) { "Рабочая копия недоступна" }
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.Inspect(task.taskId))
    }
    suspend fun recordResponse(projectId: String, sessionId: String, taskId: String, response: CodingMessage): TaskWorktree {
        val generation = parent(projectId, sessionId).runtimeGeneration
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.BindRun(taskId, generation))
        return accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.AttachResponse(taskId, generation, response))
    }
    suspend fun failure(projectId: String, sessionId: String, taskId: String, message: String) {
        accept(projectId, sessionId, TaskWorktreeMachine.Input.Intent.NoteFailure(taskId, message))
    }

    private fun taskLabel(session: CodingSession): String = listOf(
        session.pendingRun?.prompt.orEmpty(),
        session.queuedPrompts.firstOrNull()?.prompt.orEmpty(),
        session.shortTitle,
        session.name.takeUnless { it.isDefaultSessionName() }.orEmpty(),
    ).firstOrNull { it.isNotBlank() }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        .orEmpty().trim().take(MAX_TASK_LABEL)


    /** Continuation is an explicit parent action. Journal replay never calls this coordinator. */
    suspend fun complete(project: CodingProject, sessionId: String, taskId: String,
        planAccepted: Boolean = false, executionLease: WorkspaceLease? = null,
        verifyMerged: suspend (TaskWorktree) -> Unit = {},
        repair: suspend (TaskWorktree) -> Unit,
    ): TaskWorktree {
        var child = projection(project.id, sessionId)
        var record = checkNotNull(child.task)
        require(record.taskId == taskId) { "Задача изменилась" }
        if (child.unknown) record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Inspect(taskId), publish = false)
        if (!planAccepted) runtime.requireQuiescent(sessionId)
        var generation = parent(project.id, sessionId).runtimeGeneration
        record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.BindRun(taskId, generation))
        if (record.phase == TaskWorktreePhase.COMPLETE) {
            releaseRetainedLeases(sessionId)
            return record
        }
        suspend fun execution(action: suspend (WorkspaceLease) -> Unit) {
            if (executionLease != null) action(executionLease)
            else leased(project.copy(id = "task-merge-$sessionId", path = record.path), sessionId, TASK_COPY, action)
        }
        if (record.phase in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY)) {
            if (!planAccepted) check(parent(project.id, sessionId).pendingRun?.intent == ExecutionIntent.RUN) { "Задача остановлена" }
            execution { lease -> record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Capture(taskId, generation, Id.new(), planAccepted),
                handles = TaskWorkspaceLeases(execution = lease)) }
        }
        var repairedAt: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val finished = try { mergeTurn(record.sourcePath) {
                val target = workspace.target(record)
                if (record.phase != TaskWorktreePhase.CONFLICT || record.handoffGeneration != null || planAccepted) {
                    execution { lease -> record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Integrate(taskId, generation, Id.new(), target, planAccepted),
                        handles = TaskWorkspaceLeases(execution = lease)) }
                }
                if (record.phase == TaskWorktreePhase.CONFLICT) {
                    check(repairedAt != record.targetCommit) { "Конфликт не разрешён. Уточните запрос и продолжите" }
                    return@mergeTurn null
                }
                execution { lease -> record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Verify(taskId, generation, Id.new()),
                    handles = TaskWorkspaceLeases(execution = lease)) }
                verifyMerged(record)
                record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.AcceptMerge(taskId, generation, record.mergeCommit))
                execution { taskLease ->
                    leased(project.copy(id = "task-delivery-$sessionId", path = record.sourcePath), sessionId, SOURCE_FOLDER) { sourceLease ->
                        if (!planAccepted) check(parent(project.id, sessionId).pendingRun?.intent == ExecutionIntent.RUN) { "Задача остановлена" }
                        record = accept(project.id, sessionId, TaskWorktreeMachine.Input.Intent.Deliver(taskId, generation, Id.new()),
                            handles = TaskWorkspaceLeases(sourceLease, taskLease))
                    }
                }
                record.also { AppLog.info("coding.worktree", "task.integrated", mapOf("taskId" to taskId, "sessionId" to sessionId)) }
            }
            } catch (changed: TaskDestinationChanged) {
                AppLog.debug("coding.worktree", "merge.destination-advanced", mapOf("taskId" to taskId, "sessionId" to sessionId))
                record = checkNotNull(projection(project.id, sessionId).task)
                repairedAt = null
                continue
            }
            if (finished != null) return finished
            repair(record)
            if (!planAccepted) runtime.requireQuiescent(sessionId)
            generation = parent(project.id, sessionId).runtimeGeneration
            record = checkNotNull(projection(project.id, sessionId).task)
            repairedAt = record.targetCommit
        }
    }

    /**
     * Папку удерживает один исполнитель, а удерживать её может и чужой прогон: ожидание ограничено,
     * постоянную занятость разрешает пользователь. На каждом шаге ожидания снимается блокировка без
     * живого исполнителя: доказательство остановки может появиться в любой момент.
     */
    private suspend fun <T : Any> leased(owner: CodingProject, sessionId: String, folder: String, action: suspend (WorkspaceLease) -> T): T {
        var waited = 0L
        while (true) {
            val result = leasedOrNull(owner, action)
            if (result != null) {
                if (waited > 0) AppLog.info("coding.worktree", "lease.released",
                    mapOf("sessionId" to sessionId, "waitedMillis" to waited.toString()))
                return result
            }
            if (waited >= SOURCE_LEASE_WAIT_MILLIS) {
                val busy = TaskWorkspaceBusy(owner.path, folder)
                AppLog.error("coding.worktree", "lease.busy", busy,
                    mapOf("sessionId" to sessionId, "folder" to folder, "holder" to leases.holderOf(owner.path).orEmpty(),
                        "waitedMillis" to waited.toString()))
                throw busy
            }
            // Удержание могло стать снимаемым в любой момент ожидания: сверка повторяется на каждом шаге.
            if (runtime.releaseUnownedLeases()) continue
            delay(SOURCE_LEASE_POLL_MILLIS)
            waited += SOURCE_LEASE_POLL_MILLIS
        }
    }

    private companion object {
        /** Очереди влития по исходной папке: одна незавершённая попытка слияния на проект. */
        /** Имя ветки и subject коммита ограничивает порт; здесь сырой текст просто не разрастается. */
        const val MAX_TASK_LABEL = 160
        /** Соседняя доставка держит папку секунды; дольше ждёт только пользователь, а не прогон. */
        const val SOURCE_LEASE_WAIT_MILLIS = 60_000L
        const val SOURCE_LEASE_POLL_MILLIS = 250L
        const val SOURCE_FOLDER = "Исходная папка проекта"
        const val TASK_COPY = "Рабочая копия задачи"
    }

    private suspend fun <T> mergeTurn(path: String, action: suspend () -> T): T {
        val queue = mergeQueuesLock.withLock { mergeQueues.getOrPut(path.trimEnd('/', '\\')) { Mutex() } }
        return queue.withLock { action() }
    }

    /** Retry only cleanup whose original release failed, including an already completed delivery. */
    suspend fun releaseRetainedLeases(sessionId: String? = null) = withContext(NonCancellable) {
        val identities = sessionId?.let { id ->
            setOf("task-source-$id", "task-open-$id", "task-refresh-$id", "task-merge-$id", "task-delivery-$id")
        }
        var failure: Throwable? = null
        for ((token, previous) in retainedLeases.value) {
            if (identities != null && previous.ownerId !in identities) continue
            try {
                leases.release(previous)
                retainedLeases.update { current -> if (current[token] == previous) current - token else current }
            } catch (error: Throwable) {
                AppLog.error("coding.worktree", "lease.cleanup.failed", mapOf("ownerId" to previous.ownerId, "causeType" to (error::class.simpleName ?: "Throwable")))
                if (error is CancellationException && failure !is CancellationException) {
                    failure?.takeUnless { it === error }?.let(error::addSuppressed)
                    failure = error
                } else if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun <T> leasedOrNull(owner: CodingProject, action: suspend (WorkspaceLease) -> T): T? {
        retainedLeases.value.values.filter { it.ownerId == owner.id }.forEach { previous ->
            withContext(NonCancellable) { leases.release(previous) }
            retainedLeases.update { current -> if (current[previous.token] == previous) current - previous.token else current }
        }
        val lease = withContext(NonCancellable) { leases.acquire(owner, Id.new()) } ?: return null
        var failure: Throwable? = null
        try { currentCoroutineContext().ensureActive(); return action(lease) } catch (e: Throwable) { failure = e; throw e }
        finally { withContext(NonCancellable) {
            try { leases.release(lease) } catch (e: Throwable) {
                retainedLeases.update { it + (lease.token to lease) }
                AppLog.error("coding.worktree", "lease.release.failed", mapOf("ownerId" to owner.id, "causeType" to (e::class.simpleName ?: "Throwable")))
                if (failure == null) throw e
                if (e is CancellationException && failure !is CancellationException) {
                    if (e !== failure) e.addSuppressed(failure)
                    throw e
                }
                if (e !== failure) failure.addSuppressed(e)
            }
        } }
    }
}

/** A check as it would be typed, for TRACE only: an argument with spaces is quoted so the line reads back unambiguously. */
internal fun checkCommandLine(args: List<String>): String =
    args.joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
