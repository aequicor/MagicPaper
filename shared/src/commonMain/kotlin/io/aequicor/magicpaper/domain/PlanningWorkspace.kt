package io.aequicor.magicpaper.domain

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
    suspend fun finishDeliveryConflict(path: String): Boolean = false
}
class WorkspaceConflict(val workingPath: String, message: String) : IllegalStateException(message)

class LocalPlanningWorkspace : PlanningWorkspace {
    private val owners = mutableSetOf<String>()
    override suspend fun acquire(project: CodingProject) = owners.add(project.id)
    override suspend fun release(project: CodingProject) { owners.remove(project.id) }
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
    require(profile.provider == ProviderType.OPENAI_COMPATIBLE || profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
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
    return profile.copy(modelId = modelId, codingModelId = modelId,
        effort = effort, effortOverrides = profile.effortOverrides + (modelId to effort))
}
