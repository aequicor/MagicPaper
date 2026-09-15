package io.aequicor.magicpaper.domain

class TaskDestinationChanged : IllegalStateException("Исходная ветка обновилась; требуется повторная проверка слияния")

/** Native Git operations; the session owner persists every intent before calling this port. */
interface TaskWorkspace {
    suspend fun availability(project: CodingProject): WorktreeAvailability
    /** [label] — содержательный текст задачи: по нему именуются ветка и коммит копии. */
    suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree
    suspend fun open(record: TaskWorktree, previous: TaskWorktree? = null)
    suspend fun reconcile(record: TaskWorktree)
    suspend fun capture(record: TaskWorktree): String
    suspend fun target(record: TaskWorktree): String
    /**
     * Read-only distance to the destination branch; brings a clean copy onto the destination tip
     * before a run when that needs no conflict resolution. A declined update is an ordinary
     * outcome reported in [TaskWorktreeRefresh], never a partially modified copy.
     */
    suspend fun refresh(record: TaskWorktree): TaskWorktreeRefresh
    /**
     * Brings the captured result onto the recorded destination tip and returns the integrated
     * commit. Returns null for a conflict, leaving it in the managed copy for repair.
     */
    suspend fun integrate(record: TaskWorktree): String?
    suspend fun verify(record: TaskWorktree)
    suspend fun deliver(record: TaskWorktree)
    suspend fun delivered(record: TaskWorktree): Boolean
}

object UnavailableTaskWorkspace : TaskWorkspace {
    override suspend fun availability(project: CodingProject) = WorktreeAvailability(false, "Worktree недоступен на этой платформе")
    private fun unavailable(): Nothing = error("Worktree недоступен на этой платформе")
    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String): TaskWorktree = unavailable()
    override suspend fun open(record: TaskWorktree, previous: TaskWorktree?) = unavailable()
    override suspend fun reconcile(record: TaskWorktree) = unavailable()
    override suspend fun capture(record: TaskWorktree): String = unavailable()
    override suspend fun target(record: TaskWorktree): String = unavailable()
    override suspend fun refresh(record: TaskWorktree): TaskWorktreeRefresh = unavailable()
    override suspend fun integrate(record: TaskWorktree): String? = unavailable()
    override suspend fun verify(record: TaskWorktree) = unavailable()
    override suspend fun deliver(record: TaskWorktree) = unavailable()
    override suspend fun delivered(record: TaskWorktree): Boolean = unavailable()
}
