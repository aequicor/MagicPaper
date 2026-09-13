package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.SessionOrganismStore
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SessionPlanBudgetTest {
    private fun plan(f: SessionOrganismTestFixture, stages: Int = 4): Plan {
        val assignment = StageAssignment("profile", "model")
        return Plan("plan", f.project.id, "Audit project", parentSessionId = f.root.id, runId = "run",
            intent = ExecutionIntent.RUN, confirmedRevision = 1,
            milestones = (1..stages).map { index ->
                Milestone("stage-$index", "Stage $index", "Inspect project", assignment = assignment,
                    acceptance = "Report verified findings", attempts = listOf(StageAttempt("attempt-$index", "worker-$index",
                        assignment, path = "/worktree-$index", baseCommit = "base", engine = CodingEngine.PI)))
            })
    }

    private suspend fun admit(f: SessionOrganismTestFixture, plan: Plan, index: Int = 0): StageAttempt =
        plan.milestones[index].let { f.service.preparePlanAttempt(plan, it.id, it.attempts.last()) }

    private fun SessionOrganism.authority(sessionId: String): SessionAuthority = sessions.getValue(sessionId).let {
        SessionAuthority(projectId, id, it.id, it.generation, it.mode)
    }

    @Test fun confirmedFourStagePlanUsesTaskBudgetAndSurvivesRecordedReadOnlyUsage() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        admit(f, plan(f))
        val before = f.store.get(f.root.organismId!!)
        assertEquals(198_000L, before.sessions.getValue("worker-1").remainingTokens)
        assertEquals(792_000L, before.sessions.getValue(f.root.id).remainingTokens)

        f.store.beginRun(before.id, "worker-1")
        val running = f.store.get(before.id)
        val charged = f.store.charge(running.authority("worker-1"), 93_956)
        assertTrue(charged.sessions.getValue("worker-1").acceptsWork)
        assertEquals(104_044L, charged.sessions.getValue("worker-1").remainingTokens)
        assertEquals(93_956L, charged.sessions.getValue("worker-1").spentTokens)
        assertEquals(1_000_000L, charged.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(10_000L, charged.sessions.getValue(charged.immunityId!!).remainingTokens)
    }

    @Test fun concurrentStagesReserveEqualSharesAndLeaveParentAndRecoveryBudget() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f)
        plan.milestones.indices.map { index -> async { admit(f, plan, index) } }.awaitAll()
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(listOf(198_000L, 198_000L, 198_000L, 198_000L),
            plan.milestones.map { saved.sessions.getValue(it.attempts.single().sessionId).remainingTokens })
        assertEquals(198_000L, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens })
    }

    @Test fun continuationRetainsItsRemainingAllocationAcrossStoreReload() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); val attempt = admit(f, plan)
        val running = f.store.beginRun(f.root.organismId!!, attempt.sessionId)
        f.store.charge(f.store.get(f.root.organismId!!).authority(attempt.sessionId), 93_956)
        val before = f.store.observe(f.root.organismId!!, attempt.sessionId, running.generation, SessionObservedState.PENDING)
        val nextAttempt = attempt.copy(turnIndex = attempt.turnIndex + 1)
        val node = before.sessions.getValue(attempt.sessionId)
        val session = f.projects.sessions(f.project.id).single { it.id == attempt.sessionId }
        val reloaded = SessionOrganismStore(f.storage) { 1_000 }
        val after = reloaded.admitPlanWorker(before.id, session, node.task!!,
            node.legacyAttempt!!.copy(turnIndex = nextAttempt.turnIndex), node.rules, plan.milestones.map { it.id }.toSet())
        assertEquals(104_044L, after.sessions.getValue(attempt.sessionId).remainingTokens)
        assertEquals(before.sessions.getValue(f.root.id).remainingTokens, after.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(before.sessions.values.sumOf { it.remainingTokens }, after.sessions.values.sumOf { it.remainingTokens })
        assertEquals(running.generation + 1, after.sessions.getValue(attempt.sessionId).generation)
    }

    @Test fun failedAttemptReturnsUnusedBudgetAndRetryOnlyAllocatesUnspentTaskShare() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); val attempt = admit(f, plan)
        val node = f.store.beginRun(f.root.organismId!!, attempt.sessionId)
        f.store.charge(f.store.get(f.root.organismId!!).authority(attempt.sessionId), 93_956)
        f.store.observe(f.root.organismId!!, attempt.sessionId, node.generation, SessionObservedState.FAILED)
        val nextAttempt = attempt.copy(turnIndex = attempt.turnIndex + 1)
        val retryPlan = plan.copy(milestones = plan.milestones.mapIndexed { index, stage ->
            if (index == 0) stage.copy(attempts = listOf(nextAttempt)) else stage
        })
        admit(f, retryPlan)
        val saved = f.store.get(f.root.organismId!!)
        assertEquals(179_208L, saved.sessions.getValue(attempt.sessionId).remainingTokens)
        assertEquals(93_956L, saved.sessions.getValue(attempt.sessionId).spentTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
    }

    @Test fun explicitlyAllocatedQuotaIsRetainedWhenAnExistingWorkerJoinsAPlan() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val initial = f.store.get(f.root.organismId!!)
        f.store.command(initial.authority(f.root.id), "explicit-worker", OrganismCommand(OrganismAction.CREATE,
            name = "Worker", tokens = 1_000, task = SessionTask("Inspect project", f.root.id, "Report findings")))
        val original = plan(f)
        val stage = original.milestones.first()
        val plan = original.copy(milestones = listOf(stage.copy(attempts = listOf(stage.attempts.single().copy(sessionId = "session-explicit-worker")))) + original.milestones.drop(1))
        val before = f.store.get(initial.id)
        admit(f, plan)
        val saved = f.store.get(initial.id)
        assertEquals(1_000L, saved.sessions.getValue("session-explicit-worker").remainingTokens)
        assertEquals(before.sessions.getValue(f.root.id).remainingTokens, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens })
    }

    @Test fun completedAndSkippedStagesDoNotReserveFutureShares() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val original = plan(f)
        val plan = original.copy(milestones = original.milestones.mapIndexed { index, stage ->
            when (index) {
                2 -> stage.copy(status = MilestoneStatus.DONE)
                3 -> stage.copy(status = MilestoneStatus.SKIPPED)
                else -> stage
            }
        })
        admit(f, plan)
        assertEquals(330_000L, f.store.get(f.root.organismId!!).sessions.getValue("worker-1").remainingTokens)
    }

    @Test fun stageOverrunUsesTheRemainingTaskBudgetAndDebitsActualExcessFromParent() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        admit(f, plan(f))
        f.store.beginRun(f.root.organismId!!, "worker-1")
        val before = f.store.get(f.root.organismId!!)
        val saved = f.store.charge(before.authority("worker-1"), 228_000)
        assertEquals(228_000L, saved.sessions.getValue("worker-1").spentTokens)
        assertEquals(0L, saved.sessions.getValue("worker-1").remainingTokens)
        assertEquals(SessionDesiredState.RUN, saved.sessions.getValue("worker-1").desired)
        assertEquals(SessionObservedState.RUNNING, saved.sessions.getValue("worker-1").observed)
        f.store.check(saved.authority("worker-1"))
        assertEquals(762_000L, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(setOf(f.root.id), saved.audit.last { it.action == "BUDGET_RECONCILE" }.affected)
    }

    @Test fun persistedLegacyOverrunCannotBecomeBudgetForTheNextStage() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f); admit(f, plan)
        val before = f.store.get(f.root.organismId!!)
        // Exact pre-fix accounting state: 93,956 actual tokens consumed from a 64k
        // stage grant, with the task still incorrectly retaining all 926k tokens.
        val legacy = before.copy(sessions = before.sessions +
            (f.root.id to before.sessions.getValue(f.root.id).copy(remainingTokens = 926_000)) +
            ("worker-1" to before.sessions.getValue("worker-1").copy(remainingTokens = 0, spentTokens = 93_956,
                observed = SessionObservedState.STOPPED, desired = SessionDesiredState.STOP)))
        f.storage.write("session-organism-${legacy.id}", Json.encodeToString(legacy))
        admit(f, plan, index = 1)
        val saved = f.store.get(legacy.id)
        assertEquals(179_208L, saved.sessions.getValue("worker-2").remainingTokens)
        assertEquals(716_836L, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(93_956L, saved.sessions.getValue("worker-1").spentTokens)
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(setOf(f.root.id), saved.audit.last { it.action == "BUDGET_RECONCILE" }.affected)
    }

    @Test fun overrunCannotLeaveSiblingGrantsExceedingUnspentTaskBudget() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f, stages = 2)
        admit(f, plan, 0); admit(f, plan, 1)
        f.store.beginRun(f.root.organismId!!, "worker-1")
        val before = f.store.get(f.root.organismId!!)
        val saved = f.store.charge(before.authority("worker-1"), 750_000)
        assertEquals(750_000L, saved.sessions.getValue("worker-1").spentTokens)
        assertEquals(0L, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(240_000L, saved.sessions.getValue("worker-2").remainingTokens)
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
    }

    @Test fun reportedCostBeyondWholeTaskLimitIsPreservedAndLeavesNoSpendableGrants() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f, stages = 2)
        admit(f, plan, 0); admit(f, plan, 1)
        f.store.beginRun(f.root.organismId!!, "worker-1")
        val before = f.store.get(f.root.organismId!!)
        val saved = f.store.charge(before.authority("worker-1"), 1_050_000)
        assertEquals(1_050_000L, saved.sessions.getValue("worker-1").spentTokens)
        assertTrue(saved.sessions.values.all { it.remainingTokens == 0L })
        assertFailsWith<IllegalArgumentException> { f.store.beginRun(saved.id, "worker-2") }
    }

    @Test fun auxiliaryPlannerOverrunUsesTheSameConservativeTaskAccounting() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        admit(f, plan(f))
        val before = f.store.get(f.root.organismId!!)
        val context = ToolExecutionContext(f.project.id, f.root.id, "planner-alias", "request", ToolRole.PLANNER,
            CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = before.id,
            runtimeGeneration = before.sessions.getValue(f.root.id).generation)
        val auxiliary = f.store.beginAuxiliary(context)
        val saved = f.store.chargeAuxiliary(before.id, auxiliary.id, "usage", 800_000)
        assertEquals(800_000L, saved.sessions.getValue(f.root.id).spentTokens)
        assertEquals(0L, saved.sessions.getValue(f.root.id).remainingTokens)
        assertEquals(190_000L, saved.sessions.getValue("worker-1").remainingTokens)
        assertEquals(10_000L, saved.sessions.getValue(saved.immunityId!!).remainingTokens)
        assertEquals(1_000_000L, saved.sessions.values.sumOf { it.remainingTokens + it.spentTokens })
        assertEquals(800_000L, saved.auxiliaryRuns.getValue(auxiliary.id).usage.getValue("usage"))
    }

    @Test fun runningSiblingUsageIsRecordedAfterAnotherWorkerExhaustsItsGrant() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val plan = plan(f, stages = 2)
        admit(f, plan, 0); admit(f, plan, 1)
        f.store.beginRun(f.root.organismId!!, "worker-1")
        f.store.beginRun(f.root.organismId!!, "worker-2")
        val running = f.store.get(f.root.organismId!!)
        f.store.charge(running.authority("worker-1"), 1_050_000)
        val exhausted = f.store.get(running.id)
        val sibling = exhausted.authority("worker-2")
        assertEquals(SessionObservedState.RUNNING, exhausted.sessions.getValue("worker-2").observed)
        assertEquals(0L, exhausted.sessions.getValue("worker-2").remainingTokens)
        assertFailsWith<IllegalArgumentException> { f.store.check(sibling) }
        assertFailsWith<IllegalArgumentException> { f.store.charge(sibling.copy(generation = sibling.generation - 1), 17) }
        assertFailsWith<IllegalArgumentException> { f.store.charge(sibling.copy(mode = CodingInteractionMode.RESEARCH), 17) }
        assertFailsWith<IllegalArgumentException> { f.store.charge(sibling.copy(projectId = "other"), 17) }

        // The native operation was already in flight; even a subsequently expired
        // deadline cannot erase its reported cost or authorize another operation.
        val expired = SessionOrganismStore(f.storage) { 9_000_000 }
        val charged = expired.charge(sibling, 17)
        assertEquals(17L, charged.sessions.getValue("worker-2").spentTokens)
        assertEquals(1_050_017L, charged.sessions.values.sumOf { it.spentTokens })
        assertTrue(charged.sessions.values.all { it.remainingTokens == 0L })
        assertEquals(SessionDesiredState.STOP, charged.sessions.getValue("worker-2").desired)
        assertFailsWith<IllegalArgumentException> { expired.check(sibling) }
        f.store.observe(running.id, sibling.sessionId, sibling.generation, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.store.charge(sibling, 1) }
        assertEquals(17L, f.store.get(running.id).sessions.getValue("worker-2").spentTokens)
    }

    @Test fun liveAuxiliaryUsageIsRecordedAfterAnotherAliasExhaustsTheirOwner() = runTest {
        val f = SessionOrganismTestFixture(); f.initialize(CodingInteractionMode.PLANNING)
        val initial = f.store.get(f.root.organismId!!)
        fun context(alias: String) = ToolExecutionContext(f.project.id, f.root.id, alias, "request-$alias", ToolRole.PLANNER,
            CodingInteractionMode.PLANNING, planId = "plan", runId = "run", organismId = initial.id,
            runtimeGeneration = initial.sessions.getValue(f.root.id).generation)
        val first = f.store.beginAuxiliary(context("first"))
        val second = f.store.beginAuxiliary(context("second"))
        val exhausted = f.store.chargeAuxiliary(initial.id, first.id, "usage", 1_050_000)
        assertEquals(SessionObservedState.STOPPING, exhausted.sessions.getValue(f.root.id).observed)
        assertFailsWith<IllegalArgumentException> { f.store.beginAuxiliary(context("third")) }

        f.store.chargeAuxiliary(initial.id, second.id, "usage", 17)
        val charged = f.store.chargeAuxiliary(initial.id, second.id, "usage", 18)
        val duplicate = f.store.chargeAuxiliary(initial.id, second.id, "usage", 18)
        assertEquals(charged, duplicate)
        assertEquals(18L, charged.auxiliaryRuns.getValue(second.id).usage.getValue("usage"))
        assertEquals(1_050_018L, charged.sessions.getValue(f.root.id).spentTokens)
        assertTrue(charged.sessions.values.all { it.remainingTokens == 0L })
        assertFailsWith<IllegalArgumentException> { f.store.check(charged.authority(f.root.id)) }
        f.store.finishAuxiliary(initial.id, first.id, SessionObservedState.STOPPED)
        f.store.finishAuxiliary(initial.id, second.id, SessionObservedState.STOPPED)
        f.store.observe(initial.id, f.root.id, initial.sessions.getValue(f.root.id).generation, SessionObservedState.STOPPED)
        assertFailsWith<IllegalArgumentException> { f.store.chargeAuxiliary(initial.id, second.id, "usage", 19) }
        assertEquals(1_050_018L, f.store.get(initial.id).sessions.getValue(f.root.id).spentTokens)
    }
}
