package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Durable task ownership is separate from native run ownership. No screen owns Git resources. */
class TaskWorktreeService(
    private val projects: CodingProjectRepository,
    private val workspace: TaskWorkspace,
    private val leases: PlanningWorkspace,
) {
    var requireQuiescent: suspend (String) -> Unit = {}
    /**
     * Снимает блокировки папок, у которых не осталось живого исполнителя; true — если что-то освобождено.
     * Прерванная очистка рантайма иначе удерживала бы исходную папку до перезапуска приложения,
     * и каждая новая задача проекта падала бы вместо ожидания.
     */
    var releaseUnownedLeases: suspend () -> Boolean = { false }
    private val retainedLeases = MutableStateFlow<Map<String, CodingProject>>(emptyMap())
    val changes = MutableStateFlow(0L)
    suspend fun availability(project: CodingProject) = workspace.availability(project)
    suspend fun session(projectId: String, sessionId: String) = projects.sessions(projectId).first { it.id == sessionId }
    private suspend fun save(projectId: String, sessionId: String, taskId: String, guard: (CodingSession) -> Unit = {}, change: (TaskWorktree) -> TaskWorktree): TaskWorktree {
        val saved = projects.updateSession(projectId, sessionId) { current ->
            guard(current)
            val record = checkNotNull(current.taskWorktree)
            check(record.taskId == taskId) { "Задача изменилась" }
            current.copy(taskWorktree = change(record))
        }
        changes.update { it + 1 }
        AppLog.debug("coding.worktree", "task.phase", mapOf("taskId" to taskId, "sessionId" to sessionId,
            "phase" to saved.taskWorktree?.phase.toString()))
        return checkNotNull(saved.taskWorktree)
    }

    suspend fun begin(project: CodingProject, sessionId: String, taskId: String): TaskWorktree {
        val current = session(project.id, sessionId)
        val previous = current.taskWorktree
        if (previous?.taskId == taskId) {
            if (previous.phase == TaskWorktreePhase.PREPARING) workspace.open(previous)
            else workspace.reconcile(previous)
            // Опорная точка запуска: рантайм ещё не работает, поэтому копию можно безопасно подтянуть к ветке назначения.
            // PREPARING не обновляется: подготовленная копия обязана остаться на сохранённом базовом коммите.
            val refreshed = if (previous.phase in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY))
                refresh(project, sessionId, previous) else null
            return save(project.id, sessionId, taskId) { current ->
                val running = if (current.phase == TaskWorktreePhase.PREPARING) current.copy(phase = TaskWorktreePhase.RUNNING) else current
                if (refreshed == null) running else running.copy(behindCommits = refreshed.behind, refreshNote = refreshed.note,
                    integratedCommit = if (refreshed.updated) refreshed.targetCommit else running.integratedCommit)
            }
        }
        check(previous == null || previous.phase == TaskWorktreePhase.COMPLETE) { "Сначала завершите предыдущую задачу" }
        return leased(project.copy(id = "task-source-$sessionId"), sessionId, SOURCE_FOLDER) {
            val record = workspace.describe(project, sessionId, taskId, taskLabel(current)).copy(
                reuseBranch = previous?.branch.orEmpty(), reuseCommit = previous?.mergeCommit.orEmpty())
            projects.updateSession(project.id, sessionId) { current ->
                check(current.taskWorktree == previous) { "Задача изменилась" }
                current.copy(taskWorktree = record)
            }
            changes.update { it + 1 }
            workspace.open(record, previous)
            save(project.id, sessionId, taskId) { it.copy(phase = TaskWorktreePhase.RUNNING) }
        }
    }

    suspend fun handoff(context: ToolExecutionContext, result: Boolean, checks: List<List<String>>) {
        require(context.mode == CodingInteractionMode.CODE && context.stageId == null && !context.auxiliaryExecution) { "Передача задачи недоступна" }
        val current = session(context.projectId, context.ownerSessionId)
        require(current.runtimeGeneration == context.runtimeGeneration && current.pendingRun?.intent == ExecutionIntent.RUN) { "Запуск изменился" }
        val record = checkNotNull(current.taskWorktree) { "Задача не использует worktree" }
        require(record.taskId == current.pendingRun?.runId && record.phase in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY, TaskWorktreePhase.CONFLICT)) { "Задача уже передана на слияние" }
        save(context.projectId, current.id, record.taskId, guard = { latest ->
            require(latest.runtimeGeneration == context.runtimeGeneration && latest.pendingRun?.intent == ExecutionIntent.RUN &&
                latest.pendingRun?.runId == record.taskId) { "Запуск изменился" }
        }) {
            it.copy(phase = if (it.phase == TaskWorktreePhase.CONFLICT) it.phase else if (result) TaskWorktreePhase.READY else TaskWorktreePhase.RUNNING,
                handoffGeneration = if (result) context.runtimeGeneration else null, checks = checks,
                error = if (result) null else "Задача заблокирована агентом. Уточните запрос и продолжите")
        }
    }

    suspend fun failure(projectId: String, sessionId: String, taskId: String, message: String) {
        save(projectId, sessionId, taskId) { it.copy(error = message) }
    }

    /**
     * Ветка и коммит задачи подписываются её собственным запросом, а не идентификатором:
     * первая непустая строка текущего запроса, затем название сессии. Пустой запрос
     * оставляет имя на откате к идентификатору задачи внутри порта.
     */
    private fun taskLabel(session: CodingSession): String = listOf(
        session.pendingRun?.prompt.orEmpty(),
        session.queuedPrompts.firstOrNull()?.prompt.orEmpty(),
        session.shortTitle,
        session.name.takeUnless { it.isDefaultSessionName() }.orEmpty(),
    ).firstOrNull { it.isNotBlank() }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }
        .orEmpty().trim().take(MAX_TASK_LABEL)

    /** Bringing the copy up to date is an optimization: a folder owned by another delivery must not block the run. */
    private suspend fun refresh(project: CodingProject, sessionId: String, record: TaskWorktree): TaskWorktreeRefresh? =
        leasedOrNull(project.copy(id = "task-refresh-$sessionId", path = record.path)) { workspace.refresh(record) }

    /** Called only after the runtime (and its children) has reconciled. Each Git effect has a saved intent. */
    suspend fun complete(project: CodingProject, sessionId: String, taskId: String,
        planAccepted: Boolean = false, executionLeaseHeld: Boolean = false,
        verifyMerged: suspend (TaskWorktree) -> Unit = {},
        repair: suspend (TaskWorktree) -> Unit,
    ): TaskWorktree {
        var record = checkNotNull(session(project.id, sessionId).taskWorktree)
        require(record.taskId == taskId) { "Задача изменилась" }
        if (record.phase == TaskWorktreePhase.COMPLETE) return if (record.error == null) record
            else save(project.id, sessionId, taskId) { it.copy(error = null) }
        if (!planAccepted) requireQuiescent(sessionId)
        if (record.phase == TaskWorktreePhase.DELIVERING && workspace.delivered(record))
            return save(project.id, sessionId, taskId) { it.copy(phase = TaskWorktreePhase.COMPLETE, error = null) }
        suspend fun execution(action: suspend () -> Unit) {
            if (executionLeaseHeld) action() else leased(project.copy(id = "task-merge-$sessionId", path = record.path),
                sessionId, TASK_COPY, action)
        }
        if (record.phase in setOf(TaskWorktreePhase.RUNNING, TaskWorktreePhase.READY, TaskWorktreePhase.CAPTURING)) {
            check(planAccepted || record.phase == TaskWorktreePhase.CAPTURING ||
                (record.phase == TaskWorktreePhase.READY && record.handoffGeneration == session(project.id, sessionId).runtimeGeneration)) {
                record.error ?: "Агент не подтвердил завершение задачи через task.handoff"
            }
            record = save(project.id, sessionId, taskId, guard = { latest ->
                if (!planAccepted) {
                    check(latest.pendingRun?.intent == ExecutionIntent.RUN) { "Задача остановлена" }
                    check(latest.taskWorktree?.phase == TaskWorktreePhase.CAPTURING ||
                        (latest.taskWorktree?.phase == TaskWorktreePhase.READY && latest.taskWorktree?.handoffGeneration == latest.runtimeGeneration)) { "Принято уточнение; задача ещё не завершена" }
                }
            }) { it.copy(phase = TaskWorktreePhase.CAPTURING, error = null) }
            execution {
                val sha = workspace.capture(record)
                record = save(project.id, sessionId, taskId) { it.copy(resultCommit = sha, phase = TaskWorktreePhase.MERGING) }
            }
        }
        // Another task may advance the destination during checks. Re-merge and recheck its new tip.
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val target = workspace.target(record)
                record = save(project.id, sessionId, taskId) { it.copy(targetCommit = target,
                    phase = if (it.phase == TaskWorktreePhase.CONFLICT) it.phase else TaskWorktreePhase.MERGING, error = null) }
                var merged: String? = null
                if (record.phase != TaskWorktreePhase.CONFLICT || record.handoffGeneration != null || planAccepted)
                    execution { merged = workspace.integrate(record) }
                if (merged == null) {
                    record = save(project.id, sessionId, taskId) { it.copy(phase = TaskWorktreePhase.CONFLICT, handoffGeneration = null) }
                    repair(record)
                    if (!planAccepted) requireQuiescent(sessionId)
                    record = checkNotNull(session(project.id, sessionId).taskWorktree)
                    execution { merged = workspace.integrate(record) }
                    check(merged != null) { "Конфликт не разрешён. Уточните запрос и продолжите" }
                }
                record = save(project.id, sessionId, taskId) { it.copy(mergeCommit = checkNotNull(merged),
                    integratedCommit = it.targetCommit, behindCommits = 0, refreshNote = null, phase = TaskWorktreePhase.MERGING) }
                execution { workspace.verify(record) }
                verifyMerged(record)
                record = save(project.id, sessionId, taskId) { it.copy(phase = TaskWorktreePhase.DELIVERING) }
                leased(project.copy(id = "task-delivery-$sessionId", path = record.sourcePath), sessionId, SOURCE_FOLDER) {
                    if (!planAccepted) check(session(project.id, sessionId).pendingRun?.intent == ExecutionIntent.RUN) { "Задача остановлена" }
                    workspace.deliver(record)
                }
                return save(project.id, sessionId, taskId) { it.copy(phase = TaskWorktreePhase.COMPLETE, error = null) }.also {
                    AppLog.info("coding.worktree", "task.integrated", mapOf("taskId" to taskId, "sessionId" to sessionId))
                }

            } catch (changed: TaskDestinationChanged) {
                AppLog.debug("coding.worktree", "merge.destination-advanced", mapOf("taskId" to taskId, "sessionId" to sessionId))
            }
        }
    }

    /**
     * Папку удерживает один исполнитель, а удерживать её может и чужой прогон: ожидание ограничено,
     * постоянную занятость разрешает пользователь. До первого повтора снимается блокировка без живого
     * исполнителя, иначе задача ждала бы таймаут из-за уже завершившегося прогона.
     */
    private suspend fun <T : Any> leased(owner: CodingProject, sessionId: String, folder: String, action: suspend () -> T): T {
        var waited = 0L
        var reconciled = false
        while (true) {
            val result = leasedOrNull(owner, action)
            if (result != null) {
                if (waited > 0) AppLog.info("coding.worktree", "lease.released",
                    mapOf("sessionId" to sessionId, "waitedMillis" to waited.toString()))
                return result
            }
            if (!reconciled) {
                reconciled = true
                if (releaseUnownedLeases()) continue
            }
            if (waited >= SOURCE_LEASE_WAIT_MILLIS) {
                val busy = TaskWorkspaceBusy(owner.path, folder)
                AppLog.error("coding.worktree", "lease.busy", busy,
                    mapOf("sessionId" to sessionId, "folder" to folder, "waitedMillis" to waited.toString()))
                throw busy
            }
            delay(SOURCE_LEASE_POLL_MILLIS)
            waited += SOURCE_LEASE_POLL_MILLIS
        }
    }

    private companion object {
        /** Имя ветки и subject коммита ограничивает порт; здесь сырой текст просто не разрастается. */
        const val MAX_TASK_LABEL = 160
        /** Соседняя доставка держит папку секунды; дольше ждёт только пользователь, а не прогон. */
        const val SOURCE_LEASE_WAIT_MILLIS = 60_000L
        const val SOURCE_LEASE_POLL_MILLIS = 250L
        const val SOURCE_FOLDER = "Исходная папка проекта"
        const val TASK_COPY = "Рабочая копия задачи"
    }

    private suspend fun <T> leasedOrNull(owner: CodingProject, action: suspend () -> T): T? {
        retainedLeases.value[owner.id]?.let { previous ->
            require(previous.path == owner.path) { "Не завершено освобождение другой рабочей папки" }
            withContext(NonCancellable) { leases.release(previous) }
            retainedLeases.update { it - owner.id }
        }
        if (!leases.acquire(owner)) return null
        var failure: Throwable? = null
        try { return action() } catch (e: Throwable) { failure = e; throw e }
        finally { withContext(NonCancellable) {
            try { leases.release(owner) } catch (e: Throwable) {
                retainedLeases.update { it + (owner.id to owner) }
                AppLog.error("coding.worktree", "lease.release.failed", e, mapOf("ownerId" to owner.id))
                if (failure == null) throw e else failure.addSuppressed(e)
            }
        } }
    }
}
