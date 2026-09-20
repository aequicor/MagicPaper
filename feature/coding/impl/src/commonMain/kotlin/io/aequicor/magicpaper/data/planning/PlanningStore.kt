package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.checkpointMessageEvents
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.DecisionCompiler
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.resolvePlan
import io.aequicor.magicpaper.domain.PlanJournalEntry
import io.aequicor.magicpaper.domain.PlanJournalOperation
import io.aequicor.magicpaper.domain.PlanJournalSubject
import io.aequicor.magicpaper.domain.PlanningRepository
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
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
class PlanningStore(
    private val repo: PlanningRepository,
    /** Append-only evidence of what this store was asked to record, outliving any one save. */
    private val events: EventJournal = InMemoryEventJournal(),
) : PlanningRepository {
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

    suspend fun update(projectId: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan =
        lock.withLock { updateLocked(projectId, expectedRevision, change) }

    /**
     * Records one operation of the plan, in both journals.
     *
     * The append comes first, and that is the whole point of it. The plan's own journal is
     * part of the plan document: a save that is lost or rolled back takes the entry with it,
     * and an intent nobody recorded is indistinguishable from an effect nobody requested.
     * A record without its plan entry is the harmless direction — it reads as unsettled, and
     * evidence settles it; the other direction repeats an effect.
     */
    suspend fun journal(projectId: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = ""): Plan =
        lock.withLock {
            requireWritable()
            val plan = currentPlans().resolvePlan(projectId) ?: error("План не найден")
            val at = Id.now()
            events.append(plan.id, operation.wire, at, PlanJournalSubject.encode(stageId, attemptId))
            updateLocked(projectId, null) {
                it.copy(journal = it.journal + PlanJournalEntry(Id.new(), at, operation, stageId, attemptId))
            }
        }

    private suspend fun updateLocked(projectId: String, expectedRevision: Long?, change: (Plan) -> Plan): Plan {
        requireWritable()
        val old = currentPlans().resolvePlan(projectId) ?: error("План не найден")
        require(expectedRevision == null || old.revision == expectedRevision) { "План изменился; повторите правку" }
        val at = Id.now()
        val next = change(old).checkpointMessageEvents(old, at).copy(revision = old.revision + 1, updatedAt = at)
        persisted { repo.save(next) }
        publishSaved(next)
        return next
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
        // The plan is gone, so its records answer nothing and are the journal's only bound.
        events.drop(target.id)
        Unit
    }

    override suspend fun dossiers(): List<ModelDossier> = refreshDossiers()

    override suspend fun saveDossier(dossier: ModelDossier) {
        repo.saveDossier(dossier)
        refreshDossiers()
    }

    override suspend fun wipe() = lock.withLock {
        val dropped = currentPlans().map { it.id }
        persisted { repo.wipe() }
        _plans.value = emptyList()
        dropped.forEach { events.drop(it) }
        _failure.value = null
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
