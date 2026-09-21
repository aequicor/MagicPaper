package io.aequicor.magicpaper.domain

/** One process-local resource acquisition. Tokens are never restored as execution authority. */
data class WorkspaceLease(val token: String, val requestId: String, val ownerId: String, val canonicalPath: String)

/** Caller-owned durable operation plus the exact process-local acquisition. Neither is inferred from a path. */
data class WorkspaceOperation(val lease: WorkspaceLease, val operationId: String)

interface PlanningWorkspace {
    suspend fun acquire(project: CodingProject, requestId: String): WorkspaceLease?
    /** Idempotent for an already released token; cannot release a later acquisition by the same owner. */
    suspend fun release(lease: WorkspaceLease)
    suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace
    suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): StageAttempt
    suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation): String
    suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): Boolean
    suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation): Boolean
    suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace
    /** Must ensure a previous process cannot still write before another run starts. */
    suspend fun reconcile(attempt: StageAttempt, operation: WorkspaceOperation)
    suspend fun validateIntegration(workspace: PlanWorkspace, operation: WorkspaceOperation) = Unit
    suspend fun validateExecutionPath(project: CodingProject, path: String, operation: WorkspaceOperation? = null) = Unit
    /** A content fingerprint collected by the host; null means verification is unavailable. */
    suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation? = null): String? = null
    suspend fun finishDeliveryConflict(path: String, operation: WorkspaceOperation): Boolean = false
    /** Владелец блокировки пути — для диагностики занятой папки; null, если порт не отслеживает владельцев. */
    suspend fun holderOf(path: String): String? = null
}
