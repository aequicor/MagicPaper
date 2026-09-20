package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.checkpointMessageEvents
import io.aequicor.magicpaper.domain.ModelDossier
import io.aequicor.magicpaper.domain.DecisionCompiler
import io.aequicor.magicpaper.domain.Plan
import io.aequicor.magicpaper.domain.resolvePlan
import io.aequicor.magicpaper.domain.PlanJournalEntry
import io.aequicor.magicpaper.domain.PlanJournalOperation
import io.aequicor.magicpaper.domain.PlanIntentReconciliation
import io.aequicor.magicpaper.domain.PlanRecoveryAuthority
import io.aequicor.magicpaper.domain.PlanIntentOutcome
import io.aequicor.magicpaper.domain.PlanIntentStatus
import io.aequicor.magicpaper.domain.JournalEntryKind
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.data.storage.JournalRecord
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.aequicor.magicpaper.logging.AppLog

class PlanningRevisionConflictException : IllegalStateException("План изменился; повторите правку")

class PlanningPersistenceException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Application-owned plan command store. Accepted journal facts are authoritative; [repo]
 * supplies legacy plans and keeps recoverable checkpoints. Readers observe the last accepted
 * projection without replaying effects. A checkpoint failure latches command admission until
 * explicit recovery, but cannot roll back a successful journal append.
 *
 * [save] creates a plan or replaces its current revision, advancing existing revisions exactly
 * once. [update] additionally checks the caller's optional logical revision. Both compare the
 * observed journal revision atomically, including changes made by another store or reset.
 */
class PlanningStore(
    private val repo: PlanningRepository,
    /** Authority for plan state; repo holds rebuildable checkpoints and legacy documents. */
    private val events: EventJournal = InMemoryEventJournal(),
) : PlanningRepository {
    private val lock = Mutex()
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private suspend fun <T> persisted(block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: PlanningRevisionConflictException) { throw e }
        catch (e: Exception) {
            val message = "Ошибка сохранения планирования. Проверьте доступ к данным и повторите действие."
            AppLog.error("planning.storage", "operation.failed", fields = mapOf("causeType" to (e::class.simpleName ?: "Exception")))
            _failure.value = message
            throw PlanningPersistenceException(message, e)
        }

    private fun requireWritable() { _failure.value?.let { throw PlanningPersistenceException(it) } }

    /** Called after executors stop. Replay accepted facts, then repair their checkpoints. */
    suspend fun recover() = lock.withLock {
        persisted {
            plansLoaded = false
            val restored = loadPlans()
            restored.forEach { repo.save(it) }
            _failure.value = null
        }
    }

    suspend fun update(projectId: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan =
        lock.withLock { updateLocked(projectId, expectedRevision, change) }

    /** The operation evidence and the accepted plan transition commit in one journal record. */
    suspend fun journal(projectId: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = "", change: (Plan) -> Plan = { it }): Plan =
        lock.withLock { journalLocked(projectId, operation, stageId, attemptId, change = change).first }

    internal suspend fun beginIntent(projectId: String, operation: PlanJournalOperation, stageId: String, attemptId: String): JournalRecord =
        lock.withLock {
            require(operation.kind == JournalEntryKind.INTENT)
            journalLocked(projectId, operation, stageId, attemptId, settleFailedAdmission = true).second
        }

    private suspend fun journalLocked(projectId: String, operation: PlanJournalOperation, stageId: String, attemptId: String, settleFailedAdmission: Boolean = false, change: (Plan) -> Plan = { it }): Pair<Plan, JournalRecord> {
        requireWritable()
        val plan = currentPlans().resolvePlan(projectId) ?: error("План не найден")
        val at = Id.now()
        val next = changedPlan(plan, at) {
            change(it).let { changed -> changed.copy(journal = changed.journal +
                PlanJournalEntry(Id.new(), at, operation, stageId, attemptId)) }
        }
        return withContext(NonCancellable) {
            val record = appendState(plan, next, operation.wire, at, PlanJournalSubject.encode(stageId, attemptId))
            try {
                persisted { repo.save(next) }
                next to record.planEvidence()
            } catch (failure: Throwable) {
                if (settleFailedAdmission) {
                    try {
                        finishIntentLocked(record.planEvidence(), if (failure is CancellationException)
                            PlanIntentStatus.INTERRUPTED else PlanIntentStatus.REJECTED)
                    } catch (outcomeFailure: Throwable) {
                        AppLog.error("planning.execution", "intent.admission-outcome-write.failed",
                            fields = mapOf("planId" to plan.id, "intentSeq" to record.seq.toString()))
                        failure.addSuppressed(outcomeFailure)
                    }
                }
                throw failure
            }
        }
    }

    internal suspend fun finishIntent(intent: JournalRecord, status: PlanIntentStatus) = lock.withLock {
        finishIntentLocked(intent, status)
    }

    private suspend fun finishIntentLocked(intent: JournalRecord, status: PlanIntentStatus) {
        // A checkpoint failure cannot prevent recording a known outcome. Read authoritative
        // state here so another store's accepted changes cannot be overwritten by this writer.
        val snapshot = persisted { events.snapshot(intent.stream) }
        require(snapshot.records.any { it.planEvidence() == intent }) { "Intent no longer belongs to this plan" }
        val current = persisted { projectPlanJournal(snapshot.records) } ?: error("Missing intent plan")
        revisions[intent.stream] = snapshot.revision
        val strategy = if (snapshot.records.any { it.operation == PlanJournalOperation.STRATEGY_SELECTED.wire })
            planStrategyFacts(snapshot.records).strategyFor(intent) else null
        val detail = PlanIntentOutcome(intent.seq, status, strategy).encode()
        val displayDetail = PlanIntentOutcome(intent.seq, status).encode()
        val at = Id.now()
        val subject = PlanJournalSubject.decode(intent.detail)
        val next = changedPlan(current, at) {
            it.copy(journal = it.journal + PlanJournalEntry(Id.new(), at, PlanJournalOperation.INTENT_OUTCOME,
                subject.stage, subject.attempt, displayDetail))
        }
        withContext(NonCancellable) {
            appendState(current, next, PlanJournalOperation.INTENT_OUTCOME.wire, at, detail)
            if (_failure.value == null) persisted { repo.save(next) }
        }
    }

    /** Missing outcomes are durable uncertainty, independent of the plan checkpoint. */
    suspend fun unsettled(stream: String): List<JournalRecord> = lock.withLock {
        persisted {
            val snapshot = events.snapshot(stream)
            val pending = unsettledPlanIntents(snapshot.records)
            val plan = projectPlanJournal(snapshot.records)
            if (plan != null) {
                revisions[stream] = snapshot.revision
                publishSaved(plan)
            }
            pending
        }
    }

    internal suspend fun journalPlan(id: String): JournalPlanSnapshot? = lock.withLock {
        currentPlans().firstOrNull { it.id == id } ?: return@withLock null
        persisted {
            val snapshot = events.snapshot(id)
            unsettledPlanIntents(snapshot.records)
            val plan = projectPlanJournal(snapshot.records)
            if (plan == null) { plansLoaded = false; return@persisted null }
            revisions[id] = snapshot.revision
            publishSaved(plan)
            JournalPlanSnapshot(plan, snapshot)
        }
    }

    /** A model reply is only a proposal until both the inspected plan and journal still match. */
    internal suspend fun recordStrategy(expected: JournalPlanSnapshot, selection: PlanStrategySelection, retryLimit: Int?): Plan? = lock.withLock {
        requireWritable()
        val old = currentPlans().firstOrNull { it.id == expected.plan.id }
        if (old != expected.plan) return@withLock null
        val observed = persisted { events.snapshot(expected.plan.id) }
        if (observed.revision != expected.journal.revision) { plansLoaded = false; return@withLock null }
        require(selection.strategy == selectPlanStrategy(old, selection.cause, selection.status, retryLimit)) { "Invalid recovery strategy" }
        val facts = planStrategyFacts(observed.records)
        val trigger = detectPlanStrategy(old, facts.samples) ?: return@withLock null
        require(selection.sourceSeq == trigger.sourceSeq && selection.metrics == trigger.metrics &&
            selection.runId == trigger.runId && selection.stageId == trigger.stageId) { "Strategy evidence changed" }
        if (facts.selections.any { it.second.sourceSeq == trigger.sourceSeq }) return@withLock null
        val at = Id.now()
        val detail = selection.encode()
        // Classifier metrics belong to the authoritative journal, not the user-facing legacy
        // operation list. A pause is already visible through the accepted issue/intent state.
        val next = changedPlan(old, at) { applyPlanStrategy(it, selection) }
        revisions[old.id] = observed.revision
        withContext(NonCancellable) {
            appendState(old, next, PlanJournalOperation.STRATEGY_SELECTED.wire, at, detail)
            persisted { repo.save(next) }
        }
        next
    }

    /** Human recovery is fenced by both snapshots, including records absent from the plan. */
    internal suspend fun reconcileIntents(expectedPlan: Plan, expected: List<JournalRecord>, resolving: Set<Long>,
        authority: PlanRecoveryAuthority) = lock.withLock {
        requireWritable()
        require(currentPlans().resolvePlan(expectedPlan.id) == expectedPlan) { "Plan changed during recovery" }
        val pending = persisted { unsettledPlanIntents(events.read(expectedPlan.id)) }
        require(pending == expected && resolving.isNotEmpty() && pending.map { it.seq }.containsAll(resolving)) {
            "Journal changed during recovery"
        }
        for (intent in pending.filter { it.seq in resolving }) {
            val detail = PlanIntentReconciliation(intent.seq, authority).encode()
            val at = Id.now()
            val subject = PlanJournalSubject.decode(intent.detail)
            val old = currentPlans().resolvePlan(intent.stream) ?: error("План не найден")
            val next = changedPlan(old, at) {
                it.copy(journal = it.journal + PlanJournalEntry(Id.new(), at, PlanJournalOperation.INTENT_RECONCILED,
                    subject.stage, subject.attempt, detail))
            }
            withContext(NonCancellable) {
                appendState(old, next, PlanJournalOperation.INTENT_RECONCILED.wire, at, detail)
                persisted { repo.save(next) }
            }
        }
    }

    private suspend fun updateLocked(projectId: String, expectedRevision: Long?, change: (Plan) -> Plan): Plan {
        requireWritable()
        val old = currentPlans().resolvePlan(projectId) ?: error("План не найден")
        require(expectedRevision == null || old.revision == expectedRevision) { "План изменился; повторите правку" }
        val at = Id.now()
        val next = changedPlan(old, at, change)
        withContext(NonCancellable) {
            appendState(old, next, PLAN_STATE_OPERATION, at)
            persisted { repo.save(next) }
        }
        return next
    }

    private fun changedPlan(old: Plan, at: Long, change: (Plan) -> Plan): Plan {
        require(old.revision < Long.MAX_VALUE) { "Plan revision exhausted" }
        val changed = DecisionCompiler.migrate(change(old))
        require(changed.id == old.id && changed.projectId == old.projectId) { "Plan identity changed" }
        return changed.checkpointMessageEvents(old, at).copy(revision = old.revision + 1, updatedAt = at)
    }

    private val revisions = mutableMapOf<String, JournalRevision>()

    private fun conflict(): Nothing {
        plansLoaded = false
        throw PlanningRevisionConflictException()
    }

    /** Publication follows the authoritative append, even if the disposable checkpoint fails. */
    private suspend fun appendState(old: Plan?, next: Plan, operation: String, at: Long, evidence: String = ""): JournalRecord {
        val expected = checkNotNull(revisions[next.id])
        val detail = PlanJournalCommit(recordPlanState(old, next), evidence).encode()
        val record = persisted { events.append(expected, operation, at, detail) } ?: conflict()
        revisions[next.id] = expected.copy(seq = record.seq)
        publishSaved(next)
        return record
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
        val old = currentPlans().firstOrNull { it.id == plan.id }
        require(old == null || old.revision == plan.revision) { "План изменился; повторите правку" }
        val at = Id.now()
        if (old == null) {
            val snapshot = persisted { events.snapshot(plan.id) }
            if (snapshot.revision.seq != 0L || snapshot.records.isNotEmpty()) conflict()
            revisions[plan.id] = snapshot.revision
        }
        val next = if (old == null) DecisionCompiler.migrate(plan).checkpointMessageEvents(null, at)
            else changedPlan(old, at) { plan }
        withContext(NonCancellable) {
            appendState(old, next, PLAN_STATE_OPERATION, at)
            persisted { repo.save(next) }
        }
        Unit
    }

    override suspend fun deletePlan(projectId: String) = lock.withLock {
        requireWritable()
        val target = currentPlans().resolvePlan(projectId) ?: return@withLock
        withContext(NonCancellable) {
            if (!persisted { events.drop(checkNotNull(revisions[target.id])) }) conflict()
            _plans.value = _plans.value.filterNot { it.id == target.id }
            revisions.remove(target.id)
            persisted { repo.deletePlan(target.id) }
        }
        Unit
    }

    override suspend fun dossiers(): List<ModelDossier> = refreshDossiers()

    override suspend fun saveDossier(dossier: ModelDossier) {
        repo.saveDossier(dossier)
        refreshDossiers()
    }

    override suspend fun wipe() = lock.withLock {
        val dropped = currentPlans().map { it.id }
        withContext(NonCancellable) {
            dropped.forEach { id ->
                if (!persisted { events.drop(checkNotNull(revisions[id])) }) conflict()
                _plans.value = _plans.value.filterNot { it.id == id }
                revisions.remove(id)
            }
            persisted { repo.wipe() }
        }
        _failure.value = null
        plansLoaded = true
        refreshDossiers()
        Unit
    }

    private suspend fun currentPlans(): List<Plan> = if (plansLoaded) _plans.value else persisted { loadPlans() }

    private suspend fun loadPlans(): List<Plan> {
        val checkpoints = repo.plans().associateBy { it.id }
        val restored = mutableListOf<Plan>()
        val observed = mutableMapOf<String, JournalRevision>()
        for (id in (checkpoints.keys + events.streams()).sorted()) {
            val snapshot = events.snapshot(id)
            unsettledPlanIntents(snapshot.records)
            var plan = projectPlanJournal(snapshot.records)
            var revision = snapshot.revision
            if (plan == null) {
                // A nonzero empty stream is a deletion fence, never a legacy checkpoint.
                if (snapshot.records.isEmpty() && revision.seq != 0L) {
                    // Retry physical cleanup before removing the last owner checkpoint. A
                    // previous drop may have committed its fence but failed to erase payloads.
                    if (!events.drop(revision)) conflict()
                    repo.deletePlan(id)
                    continue
                }
                val legacy = checkpoints[id] ?: error("Plan journal has no initial state")
                plan = DecisionCompiler.migrate(legacy)
                val seed = PlanJournalCommit(recordPlanState(null, plan)).encode()
                val record = events.append(revision, PLAN_STATE_OPERATION, plan.updatedAt, seed) ?: conflict()
                revision = revision.copy(seq = record.seq)
            }
            restored += plan
            observed[id] = revision
        }
        revisions.clear()
        revisions.putAll(observed)
        _plans.value = restored.sortedByDescending { it.updatedAt }
        plansLoaded = true
        return _plans.value
    }

    private fun publishSaved(plan: Plan) {
        _plans.value = (_plans.value.filterNot { it.id == plan.id } + plan)
            .sortedByDescending { it.updatedAt }
    }

    private suspend fun refreshDossiers(): List<ModelDossier> {
        val list = repo.dossiers()
        _dossiers.value = list
        return list
    }
}
