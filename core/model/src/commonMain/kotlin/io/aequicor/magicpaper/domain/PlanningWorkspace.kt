package io.aequicor.magicpaper.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Platform ownership and filesystem reconciliation; operations use stable run/attempt IDs. */

class UnsafePlanningWorkspace(message: String) : IllegalArgumentException(message)

class WorkspaceConflict(val workingPath: String, message: String) : IllegalStateException(message)



/**
 * Профиль запуска этапа. У нативного назначения допустимость сверяется со снимком каталога, если
 * [catalog] передан: так делает допуск новой попытки. Продолжение уже принятой попытки снимок не
 * передаёт: её замороженное назначение не должно меняться вместе с каталогом. Уровень не
 * кламится к шкале приложения, а пропавшая модель и неподдерживаемый уровень названы прямо.
 */
fun StageAssignment.executionProfile(profiles: List<LlmProfile>, catalog: CodingModelSnapshot? = null): LlmProfile {
    native?.let { return nativeExecutionProfile(it, profiles, catalog) }
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

private fun StageAssignment.nativeExecutionProfile(choice: CodingModelSelection, profiles: List<LlmProfile>, catalog: CodingModelSnapshot?): LlmProfile {
    val connection = profiles.firstOrNull { it.id == profileId && it.isNativeConnectionFor(choice.engine) }
        ?: error("Источник этапа недоступен: $profileId")
    if (catalog != null) when (val resolved = catalog.resolve(choice)) {
        is CodingModelResolution.Available -> Unit
        CodingModelResolution.ModelMissing -> error("Модели ${choice.modelId} больше нет в каталоге движка: переназначьте этап")
        is CodingModelResolution.LevelUnsupported ->
            error("Модель ${resolved.model.name} не поддерживает уровень ${resolved.level}: переназначьте этап")
        is CodingModelResolution.WrongEngine -> error("Каталог другого движка: переназначьте этап")
    }
    val request = connection.forModel(choice.modelId, choice.displayEffort())
    return if (options != null) request.copy(advanced = options) else request
}
