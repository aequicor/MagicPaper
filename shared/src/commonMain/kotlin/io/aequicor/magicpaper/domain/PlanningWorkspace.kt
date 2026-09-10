package io.aequicor.magicpaper.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform ownership and filesystem reconciliation; operations use stable run/attempt IDs. */
interface PlanningWorkspace {
    suspend fun acquire(project: CodingProject): Boolean
    suspend fun release(project: CodingProject)
    suspend fun prepare(project: CodingProject, runId: String): PlanWorkspace
    suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt): StageAttempt
    suspend fun capture(attempt: StageAttempt): String
    suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt): Boolean
    suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt): Boolean
    suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace
    /** Must ensure a previous process cannot still write before another run starts. */
    suspend fun reconcile(attempt: StageAttempt)
    suspend fun validateIntegration(workspace: PlanWorkspace) = Unit
    /** A content fingerprint collected by the host; null means verification is unavailable. */
    suspend fun verificationSnapshot(path: String): String? = null
    suspend fun finishDeliveryConflict(path: String): Boolean = false
}
class WorkspaceConflict(val workingPath: String, message: String) : IllegalStateException(message)

class LocalPlanningWorkspace : PlanningWorkspace {
    private val lock = Mutex()
    private val owners = mutableMapOf<String, String>()
    private fun workspaceKey(project: CodingProject) = project.path.ifBlank { project.id }.trimEnd('/', '\\')
    override suspend fun acquire(project: CodingProject) = lock.withLock {
        val key = workspaceKey(project)
        if (key in owners) false else { owners[key] = project.id; true }
    }
    override suspend fun release(project: CodingProject) = lock.withLock {
        val key = workspaceKey(project)
        if (owners[key] == project.id) owners.remove(key)
        Unit
    }
    override suspend fun prepare(project: CodingProject, runId: String) = PlanWorkspace(project.path, project.path)
    override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt) = attempt.copy(path = project.path)
    override suspend fun capture(attempt: StageAttempt) = ""
    override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt) = true
    override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt) = true
    override suspend fun apply(project: CodingProject, workspace: PlanWorkspace) = workspace.copy(applied = true)
    override suspend fun reconcile(attempt: StageAttempt) = Unit
}

fun StageAssignment.executionProfile(profiles: List<LlmProfile>): LlmProfile {
    val profile = profiles.firstOrNull { it.id == profileId && it.configured }
        ?: error("Источник этапа недоступен: $profileId")
    require(profile.supportsCoding) {
        "Coding-движок не поддерживает источник ${profile.name}"
    }
    require(modelId in profile.displayModels) { "Модель этапа отсутствует в источнике: $modelId" }
    val resolved = ModelDefaults.capability(profile.copy(modelId = modelId)).resolveEffort(effort)
    require(effort.isDefault || resolved.level == effort.level) {
        "Модель $modelId не поддерживает выбранный effort ${effort.shortLabel}"
    }
    require(effectiveEffort.isDefault || effectiveEffort.level == resolved.level) {
        "Поддержка effort модели $modelId изменилась; проверьте назначение"
    }
    val request = profile.forModel(modelId, effort)
    return if (options != null) request.copy(advanced = options) else request
}
