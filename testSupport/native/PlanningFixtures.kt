package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.planning.recordPlanState
import io.aequicor.magicpaper.util.Id

/** Legacy fixture import is deliberately outside the production command API. */
class TestPlanningStore(
    private val checkpoints: PlanningCheckpointStore,
    private val journal: EventJournal = InMemoryEventJournal(),
    private val owner: PlanningStore = DefaultPlanningStore(checkpoints, journal),
) : PlanningStore by owner {
    suspend fun save(plan: Plan) {
        val old = owner.planFor(plan.id)
        if(old == null) owner.command(plan.id, PlanningMachine.Intent.Create(plan, PlanningMachine.Stamp(Id.new(), Id.now())))
        else {
            require(old.revision == plan.revision) { "Fixture plan revision changed" }
            seed(plan.id) { plan }
        }
    }
    /** Seeding an imported checkpoint always drops execution admission. Tests must explicitly start afterwards. */
    suspend fun edit(id: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan {
        val old = checkNotNull(owner.planFor(id))
        return owner.command(id, PlanningMachine.Intent.Edit(expectedRevision ?: old.revision, change(old), PlanningMachine.Stamp(Id.new(), Id.now())))
    }
    suspend fun seed(id: String, expectedRevision: Long? = null, change: (Plan) -> Plan): Plan {
        val old = checkNotNull(owner.planFor(id))
        require(expectedRevision == null || old.revision == expectedRevision)
        val at = Id.now()
        val value = change(old)
        require(value.id == old.id && value.projectId == old.projectId)
        val next = DecisionCompiler.migrate(value).checkpointMessageEvents(old, at).copy(revision = old.revision + 1, updatedAt = at)
        val snapshot = journal.snapshot(old.id)
        checkNotNull(journal.append(snapshot.revision, PLAN_STATE_OPERATION, at, PlanJournalCommit(recordPlanState(old, next)).encode()))
        checkpoints.save(next)
        owner.recover()
        return checkNotNull(owner.planFor(id))
    }
    suspend fun beginIntent(id: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = ""): JournalRecord =
        owner.beginIntent(id, operation, stageId, attemptId, checkNotNull(owner.currentAdmission(id)))
    suspend fun journal(id: String, operation: PlanJournalOperation, stageId: String = "", attemptId: String = ""): Plan =
        owner.command(id, PlanningMachine.Fact.JournalObserved(operation, stageId, attemptId,
            ref = owner.currentAdmission(id), stamp = PlanningMachine.Stamp(Id.new(), Id.now())))
}
