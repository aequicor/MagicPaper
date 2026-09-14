package io.aequicor.magicpaper.domain

class TaskDestinationChanged : IllegalStateException("Исходная ветка обновилась; требуется повторная проверка слияния")

/** Native Git operations; the session owner persists every intent before calling this port. */
interface TaskWorkspace {
    suspend fun availability(project: CodingProject): WorktreeAvailability
    suspend fun describe(project: CodingProject, sessionId: String, taskId: String): TaskWorktree
    suspend fun open(record: TaskWorktree, previous: TaskWorktree? = null)
    suspend fun reconcile(record: TaskWorktree)
    suspend fun capture(record: TaskWorktree): String
    suspend fun target(record: TaskWorktree): String
    /** Returns null for a merge conflict, leaving it in the managed copy for repair. */
    suspend fun merge(record: TaskWorktree): String?
    suspend fun verify(record: TaskWorktree)
    suspend fun deliver(record: TaskWorktree)
    suspend fun delivered(record: TaskWorktree): Boolean
}

object UnavailableTaskWorkspace : TaskWorkspace {
    override suspend fun availability(project: CodingProject) = WorktreeAvailability(false, "Worktree недоступен на этой платформе")
    private fun unavailable(): Nothing = error("Worktree недоступен на этой платформе")
    override suspend fun describe(project: CodingProject, sessionId: String, taskId: String): TaskWorktree = unavailable()
    override suspend fun open(record: TaskWorktree, previous: TaskWorktree?) = unavailable()
    override suspend fun reconcile(record: TaskWorktree) = unavailable()
    override suspend fun capture(record: TaskWorktree): String = unavailable()
    override suspend fun target(record: TaskWorktree): String = unavailable()
    override suspend fun merge(record: TaskWorktree): String? = unavailable()
    override suspend fun verify(record: TaskWorktree) = unavailable()
    override suspend fun deliver(record: TaskWorktree) = unavailable()
    override suspend fun delivered(record: TaskWorktree): Boolean = unavailable()
}
