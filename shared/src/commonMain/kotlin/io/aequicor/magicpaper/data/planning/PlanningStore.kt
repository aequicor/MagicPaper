package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.checkpointMessageEvents
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.resolvePlan
import io.aequicor.magicpaper.domain.PlanningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.CancellationException

class PlanningPersistenceException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Единый наблюдаемый слой планирования (как [io.aequicor.magicpaper.data.skills.SkillStore]):
 * плагин наблюдает за планами и досье через StateFlow, исполнитель обновляет
 * их после каждого шага — интерфейс живёт без ручных обновлений.
 */
class PlanningStore(private val repo: PlanningRepository) : PlanningRepository {
    private val lock = Mutex()
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private suspend fun <T> persisted(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val message = "Ошибка сохранения планирования: ${e.message}"
            _failure.value = message
            throw PlanningPersistenceException(message, e)
        }

    private fun requireWritable() { _failure.value?.let { throw PlanningPersistenceException(it) } }

    /** Called only after executors have stopped: reread the durable checkpoint and probe a write. */
    suspend fun recover() = lock.withLock {
        persisted {
            val restored = repo.plans()
            restored.firstOrNull()?.let { repo.save(it) }
            _plans.value = restored
            _failure.value = null
        }
    }

    suspend fun update(projectId: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan = lock.withLock {
        requireWritable()
        val old = persisted { repo.planFor(projectId) } ?: error("План не найден")
        require(expectedRevision == null || old.revision == expectedRevision) { "План изменился; повторите правку" }
        val at = Id.now()
        val next = change(old).checkpointMessageEvents(old, at).copy(revision = old.revision + 1, updatedAt = at)
        persisted { repo.save(next) }
        refreshPlans()
        next
    }

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()

    private val _dossiers = MutableStateFlow<List<ModelDossier>>(emptyList())
    val dossiers: StateFlow<List<ModelDossier>> = _dossiers.asStateFlow()

    override suspend fun plans(): List<Plan> = lock.withLock { refreshPlans() }

    override suspend fun planFor(projectId: String): Plan? = lock.withLock { refreshPlans().resolvePlan(projectId) }

    override suspend fun save(plan: Plan) = lock.withLock {
        requireWritable()
        persisted { repo.save(plan.checkpointMessageEvents(repo.planFor(plan.id), Id.now())) }
        refreshPlans()
        Unit
    }

    override suspend fun deletePlan(projectId: String) = lock.withLock {
        requireWritable()
        persisted { repo.deletePlan(projectId) }
        refreshPlans()
        Unit
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
        val list = persisted { repo.plans() }
        _plans.value = list
        return list
    }

    private suspend fun refreshDossiers(): List<ModelDossier> {
        val list = repo.dossiers()
        _dossiers.value = list
        return list
    }
}
