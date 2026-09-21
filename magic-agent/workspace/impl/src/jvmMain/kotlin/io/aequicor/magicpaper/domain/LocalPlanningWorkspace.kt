package io.aequicor.magicpaper.domain

import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Direct filesystem adapter with process-local exclusivity for canonical checkout paths. */
class LocalPlanningWorkspace : PlanningWorkspace {
    private val lock = Mutex()
    private val leases = mutableMapOf<String, WorkspaceLease>()
    override suspend fun acquire(project: CodingProject, requestId: String): WorkspaceLease? = lock.withLock {
        require(project.id.isNotBlank() && requestId.isNotBlank()) { "Не указан владелец запроса рабочей папки" }
        val path = File(project.path).canonicalPath
        if (path in leases) null else WorkspaceLease(UUID.randomUUID().toString(), requestId, project.id, path)
            .also { leases[path] = it }
    }
    override suspend fun release(lease: WorkspaceLease) = lock.withLock {
        val held = leases.values.firstOrNull { it.token == lease.token } ?: return@withLock
        require(held == lease) { "Идентичность захвата рабочей папки изменилась" }
        leases.remove(held.canonicalPath)
        Unit
    }
    override suspend fun holderOf(path: String): String? = lock.withLock { leases[File(path).canonicalPath]?.ownerId }
    private suspend fun <T> owned(operation: WorkspaceOperation, action: () -> T): T = lock.withLock {
        require(operation.operationId.isNotBlank() && leases[operation.lease.canonicalPath] == operation.lease) {
            "Захват рабочей папки больше не действует"
        }
        action()
    }
    private fun samePath(operation: WorkspaceOperation, path: String) {
        require(File(path).canonicalPath == operation.lease.canonicalPath) { "Захват принадлежит другой рабочей папке" }
    }
    override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation) =
        owned(operation) { samePath(operation, project.path); PlanWorkspace(project.path, project.path) }
    override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) =
        owned(operation) { samePath(operation, project.path); attempt.copy(path = project.path) }
    override suspend fun capture(attempt: StageAttempt, operation: WorkspaceOperation) = owned(operation) { samePath(operation, attempt.path); "" }
    override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = owned(operation) { samePath(operation, workspace.integrationPath); true }
    override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = owned(operation) { samePath(operation, workspace.integrationPath); true }
    override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation) = owned(operation) { samePath(operation, project.path); workspace.copy(applied = true) }
    override suspend fun reconcile(attempt: StageAttempt, operation: WorkspaceOperation) = owned(operation) { Unit }
    override suspend fun validateIntegration(workspace: PlanWorkspace, operation: WorkspaceOperation) = owned(operation) { samePath(operation, workspace.integrationPath); Unit }
    override suspend fun finishDeliveryConflict(path: String, operation: WorkspaceOperation) = owned(operation) { false }
}
