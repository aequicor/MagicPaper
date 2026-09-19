package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.aequicor.magicpaper.util.Id

class LocalPlanningWorkspace : PlanningWorkspace {
    private val lock = Mutex()
    private val owners = mutableMapOf<String, String>()
    private fun workspaceKey(project: CodingProject) = project.path.trimEnd('/', '\\')
    override suspend fun acquire(project: CodingProject) = lock.withLock {
        val key = workspaceKey(project)
        if (key in owners) false else { owners[key] = project.id; true }
    }
    override suspend fun release(project: CodingProject) = lock.withLock {
        val key = workspaceKey(project)
        if (owners[key] == project.id) owners.remove(key)
        Unit
    }
    override suspend fun holderOf(path: String) = owners[path.trimEnd('/', '\\')]
    override suspend fun prepare(project: CodingProject, runId: String) = PlanWorkspace(project.path, project.path)
    override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt) = attempt.copy(path = project.path)
    override suspend fun capture(attempt: StageAttempt) = ""
    override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt) = true
    override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt) = true
    override suspend fun apply(project: CodingProject, workspace: PlanWorkspace) = workspace.copy(applied = true)
    override suspend fun reconcile(attempt: StageAttempt) = Unit
}