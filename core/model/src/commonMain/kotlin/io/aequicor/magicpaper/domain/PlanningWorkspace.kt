package io.aequicor.magicpaper.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform ownership and filesystem reconciliation; operations use stable run/attempt IDs. */

class UnsafePlanningWorkspace(message: String) : IllegalArgumentException(message)

class WorkspaceConflict(val workingPath: String, message: String) : IllegalStateException(message)



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
