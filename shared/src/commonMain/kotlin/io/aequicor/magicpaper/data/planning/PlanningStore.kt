package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.PlanningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Единый наблюдаемый слой планирования (как [io.aequicor.magicpaper.data.skills.SkillStore]):
 * плагин наблюдает за планами и досье через StateFlow, исполнитель обновляет
 * их после каждого шага — интерфейс живёт без ручных обновлений.
 */
class PlanningStore(private val repo: PlanningRepository) : PlanningRepository {

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()

    private val _dossiers = MutableStateFlow<List<ModelDossier>>(emptyList())
    val dossiers: StateFlow<List<ModelDossier>> = _dossiers.asStateFlow()

    override suspend fun plans(): List<Plan> = refreshPlans()

    override suspend fun planFor(projectId: String): Plan? = refreshPlans().firstOrNull { it.projectId == projectId }

    override suspend fun save(plan: Plan) {
        repo.save(plan)
        refreshPlans()
    }

    override suspend fun deletePlan(projectId: String) {
        repo.deletePlan(projectId)
        refreshPlans()
    }

    override suspend fun dossiers(): List<ModelDossier> = refreshDossiers()

    override suspend fun saveDossier(dossier: ModelDossier) {
        repo.saveDossier(dossier)
        refreshDossiers()
    }

    override suspend fun wipe() {
        repo.wipe()
        refreshPlans()
        refreshDossiers()
    }

    private suspend fun refreshPlans(): List<Plan> {
        val list = repo.plans()
        _plans.value = list
        return list
    }

    private suspend fun refreshDossiers(): List<ModelDossier> {
        val list = repo.dossiers()
        _dossiers.value = list
        return list
    }
}
