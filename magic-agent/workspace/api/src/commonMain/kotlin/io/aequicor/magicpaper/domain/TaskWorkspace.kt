package io.aequicor.magicpaper.domain

class TaskDestinationChanged : IllegalStateException("Исходная ветка обновилась; требуется повторная проверка слияния")

/** The check process and its cleanup finished; a new user request may repair this known failed result. */
class TaskWorktreeVerificationFailed(val safeMessage: String) : IllegalStateException(safeMessage)

/**
 * Папку удерживает другой исполнитель. Ожидание ограничено, поэтому занятая папка — действенная
 * ошибка с продолжением, а не каскадный отказ всех задач проекта: сохранённый результат задачи
 * переживает её, а «Продолжить» довершает Git-операцию без повторного прогона агента.
 */
class TaskWorkspaceBusy(val path: String, folder: String) : IllegalStateException(
    "$folder $path занята другой сессией. Дождитесь её завершения и нажмите «Продолжить»")

/** Live capabilities are never serialized or recovered from the task journal. */
data class TaskWorkspaceLeases(val source: WorkspaceLease? = null, val execution: WorkspaceLease? = null)

/** Built only by the task owner from the Pending it has just committed, before invoking this port. */
data class TaskWorkspaceOperation(
    val owner: TaskWorktreeOwnerId,
    val pending: TaskWorktreeMachine.Pending,
    val leases: TaskWorkspaceLeases,
)

/** Native Git operations; the session owner persists every intent before calling this port. */
interface TaskWorkspace {
    suspend fun availability(project: CodingProject): WorktreeAvailability
    /** [label] — содержательный текст задачи: по нему именуются ветка и коммит копии. */
    suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree
    suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree? = null)
    suspend fun reconcile(record: TaskWorktree)
    suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation): String
    suspend fun target(record: TaskWorktree): String
    /**
     * Read-only distance to the destination branch; brings a clean copy onto the destination tip
     * before a run when that needs no conflict resolution. A conflicted update is not rolled
     * back: it stays in the copy (`pendingTransfer`) so the resumed agent resolves it on the
     * working branch. Any other declined update is an ordinary outcome reported in
     * [TaskWorktreeRefresh], never a partially modified copy.
     */
    suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation): TaskWorktreeRefresh
    /**
     * Brings the captured result onto the recorded destination tip and returns the integrated
     * commit. Returns null for a conflict, leaving it in the managed copy for repair.
     */
    suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String?
    suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation)
    suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation)
    suspend fun delivered(record: TaskWorktree): Boolean
    /** Inspects an interrupted operation without changing Git, starting checks or repeating delivery. */
    suspend fun inspect(record: TaskWorktree, pending: TaskWorktreeMachine.Pending): TaskWorktreeInspection = TaskWorktreeInspection.Unknown
    /**
     * Application reset, once no record refers to a managed copy: deletes every copy with its uncommitted changes.
     * Task branches and their commits stay in the source repositories.
     */
    suspend fun eraseForReset() = Unit
}

object UnavailableTaskWorkspace : TaskWorkspace {
    override suspend fun availability(project: CodingProject) = WorktreeAvailability(false, "Worktree недоступен на этой платформе")
    private fun unavailable(): Nothing = error("Worktree недоступен на этой платформе")
    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree = unavailable()
    override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) = unavailable()
    override suspend fun reconcile(record: TaskWorktree) = unavailable()
    override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation): String = unavailable()
    override suspend fun target(record: TaskWorktree): String = unavailable()
    override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation): TaskWorktreeRefresh = unavailable()
    override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String? = unavailable()
    override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) = unavailable()
    override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) = unavailable()
    override suspend fun delivered(record: TaskWorktree): Boolean = unavailable()
}
