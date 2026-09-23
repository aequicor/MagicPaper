package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

@Serializable
data class TaskWorktreeOwnerId(val projectId: String, val sessionId: String)

/** Published upwards after the child's input is durable. It never authorizes a native run. */
data class TaskWorktreeProjection(
    val owner: TaskWorktreeOwnerId,
    val task: TaskWorktree?,
    val generation: Long,
    val stream: String,
    val sequence: Long,
    val resetEpoch: Long,
    val unknown: Boolean,
    val verificationFailed: Boolean = false,
)

/** Parent-owned projection access. Only the runtime coordinator calls this port. */
interface TaskWorktreeSessionAccess {
    suspend fun session(projectId: String, sessionId: String): CodingSession?
    suspend fun publish(projection: TaskWorktreeProjection)
}

/**
 * Reset closed admission and joined every operation, yet a recorded Git operation has no outcome. Only an erase the
 * user confirmed may pass it: those records are erased next, and the dialog said unconfirmed operations are forgotten.
 */
class TaskWorktreeResetUnconfirmed : IllegalStateException(
    "Остановка операций с рабочими копиями не подтверждена. Проверьте сохранённые результаты перед сбросом")

/** A task owner survives individual native attempts. Reads and replay execute no Git commands. */
interface TaskWorktreeOwner {
    suspend fun projection(owner: TaskWorktreeOwnerId, legacy: TaskWorktree? = null, generation: Long = 0): TaskWorktreeProjection
    suspend fun accept(owner: TaskWorktreeOwnerId, intent: TaskWorktreeMachine.Input.Intent, leases: TaskWorkspaceLeases = TaskWorkspaceLeases()): TaskWorktreeProjection
    suspend fun prepareForReset()
    suspend fun resumeAfterReset()
    suspend fun close()
}

/** Read-only evidence for one recorded Git intent, including its exact identity. */
@Serializable
data class TaskWorktreeProof(
    val operationId: String,
    val taskId: String,
    val kind: TaskWorktreeMachine.Operation,
    val resultCommit: String = "",
    val targetCommit: String = "",
    val mergeCommit: String = "",
    val behindCommits: Int = 0,
    val integratedCommit: String = "",
    val pendingTransfer: Boolean = false,
)

sealed interface TaskWorktreeInspection {
    data class Confirmed(val proof: TaskWorktreeProof) : TaskWorktreeInspection
    data object Unknown : TaskWorktreeInspection
    data object Missing : TaskWorktreeInspection
}
