package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.checkpointMessageEvents
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.DecisionCompiler
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
            plansLoaded = true
            _failure.value = null
        }
    }

    suspend fun update(projectId: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan = lock.withLock {
        requireWritable()
        val old = currentPlans().resolvePlan(projectId) ?: error("План не найден")
        require(expectedRevision == null || old.revision == expectedRevision) { "План изменился; повторите правку" }
        val at = Id.now()
        val next = change(old).checkpointMessageEvents(old, at).copy(revision = old.revision + 1, updatedAt = at)
        persisted { repo.save(next) }
        publishSaved(next)
        next
    }

    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans.asStateFlow()
    // This store owns plan writes. Keep one canonical snapshot between commits:
    // rereading JSON on every status lookup also forces deep equality on the UI thread.
    private var plansLoaded = false

    private val _dossiers = MutableStateFlow<List<ModelDossier>>(emptyList())
    val dossiers: StateFlow<List<ModelDossier>> = _dossiers.asStateFlow()

    override suspend fun plans(): List<Plan> = lock.withLock { currentPlans() }

    override suspend fun planFor(projectId: String): Plan? = lock.withLock { currentPlans().resolvePlan(projectId) }

    override suspend fun save(plan: Plan) = lock.withLock {
        requireWritable()
        val saved = plan.checkpointMessageEvents(currentPlans().resolvePlan(plan.id), Id.now())
        persisted { repo.save(saved) }
        publishSaved(saved)
        Unit
    }

    override suspend fun deletePlan(projectId: String) = lock.withLock {
        requireWritable()
        val target = currentPlans().resolvePlan(projectId) ?: return@withLock
        persisted { repo.deletePlan(projectId) }
        _plans.value = _plans.value.filterNot { it.id == target.id }
        Unit
    }

    override suspend fun dossiers(): List<ModelDossier> = refreshDossiers()

    override suspend fun saveDossier(dossier: ModelDossier) {
        repo.saveDossier(dossier)
        refreshDossiers()
    }

    override suspend fun wipe() = lock.withLock {
        persisted { repo.wipe() }
        _plans.value = emptyList()
        plansLoaded = true
        refreshDossiers()
        Unit
    }

    private suspend fun currentPlans(): List<Plan> {
        if (!plansLoaded) {
            _plans.value = persisted { repo.plans() }
            plansLoaded = true
        }
        return _plans.value
    }

    private fun publishSaved(plan: Plan) {
        _plans.value = (_plans.value.filterNot { it.id == plan.id } + DecisionCompiler.migrate(plan))
            .sortedByDescending { it.updatedAt }
    }

    private suspend fun refreshDossiers(): List<ModelDossier> {
        val list = repo.dossiers()
        _dossiers.value = list
        return list
    }
}
