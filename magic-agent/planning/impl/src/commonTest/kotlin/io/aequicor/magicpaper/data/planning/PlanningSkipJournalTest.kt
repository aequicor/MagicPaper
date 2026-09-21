package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningSkipJournalTest {
    private fun blockedPlan(stages: Int = 1): Plan = Plan("plan", "project", "Goal", runId = "run",
        intent = ExecutionIntent.PAUSE, phase = ExecutionPhase.WAITING,
        milestones = (1..stages).map { index ->
            val criterion = AcceptanceCriterion("criterion-$index", "Inspect result")
            val scoped = criterion.copy(id = "stage-$index/${criterion.id}")
            val attempt = StageAttempt("attempt-$index", "session-$index", StageAssignment("profile", "model"),
                sessionGeneration = 2, turnIndex = 3, repairRetries = 1, phase = AttemptPhase.VERIFYING,
                error = PlanningIssue(IssueKind.VERIFICATION, "Host check unavailable", requiresUser = true),
                acceptanceRecord = AcceptanceRecord("run", "attempt-$index", "snapshot-$index", listOf(scoped),
                    listOf(AcceptanceFinding(scoped.id, CheckStatus.NOT_RUN, scoped.description, "Unavailable")),
                    status = AcceptanceStatus.PARTIAL))
            Milestone("stage-$index", "Stage $index", description = "Inspect result", status = MilestoneStatus.ACTIVE,
                acceptanceCriteria = listOf(criterion), attempts = listOf(attempt))
        })

    private fun legacyId(blocker: PlanningBlocker): String {
        val attempt = checkNotNull(blocker.attempt)
        return "${blocker.planId}-blocked-${blocker.runId}-${blocker.stage?.id ?: "plan"}-${attempt.id}-" +
            "${attempt.sessionGeneration}-${attempt.turnIndex}-${attempt.repairRetries}--174231"
    }

    private fun input(plan: Plan, legacy: Boolean = false) = plan.blockingIssues(emptyList()).let { blockers ->
        PlanningMachine.Intent.SkipVerification(blockers.map { if(legacy) legacyId(it) else it.messageId }.toSet(),
            "new-run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp("skip", 10),
            if(legacy) null else blockers.map { checkNotNull(it.verificationProof) }.toSet())
    }

    private suspend fun appendLegacy(events: EventJournal, plan: Plan, command: PlanningMachine.Intent.SkipVerification = input(plan, legacy = true)) {
        events.append(plan.id, PLAN_INPUT_OPERATION, 1, PlanInputCommit(
            PlanningMachine.Fact.LegacyImported(plan, emptySet(), PlanningMachine.Stamp("initial", 1)), 0).encode())
        val before = events.snapshot(plan.id)
        events.append(before.revision, PLAN_INPUT_OPERATION, command.stamp.at, PlanInputCommit(command, before.revision.resetEpoch).encode())
    }

    @Test fun acceptedLegacyNumericTokensReplayWithExactProofsWithoutChangingHistoryOrAdmittingWork() = runTest {
        val plan = blockedPlan(2); val events = InMemoryEventJournal()
        appendLegacy(events, plan)
        val original = events.read(plan.id)
        val repository = JsonPlanningRepository(InMemoryKeyValueStore(), Json)
        val owner = DefaultPlanningStore(repository, events)
        val restored = assertNotNull(owner.planFor(plan.id))
        assertEquals(2, restored.acceptanceWaivers.size)
        assertEquals(setOf("attempt-1", "attempt-2"), restored.acceptanceWaivers.map { it.attemptId }.toSet())
        assertTrue(restored.milestones.all { it.attempts.single().error == null })
        assertEquals(original, events.read(plan.id).take(original.size))
        assertNull(owner.currentAdmission(plan.id))
        assertEquals(restored, DefaultPlanningStore(repository, events).planFor(plan.id))
    }

    @Test fun liveCommandsNeedStableTokensAndTheFullCurrentProof() = runTest {
        val plan = blockedPlan(); val events = InMemoryEventJournal()
        val owner = DefaultPlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json), events)
        owner.command(plan.id, PlanningMachine.Intent.Create(plan, PlanningMachine.Stamp("create", 1)))
        val current = checkNotNull(owner.planFor(plan.id)); val good = input(current)
        val before = events.snapshot(plan.id)
        assertFailsWith<IllegalArgumentException> { owner.dispatch(plan.id, input(current, legacy = true)) }
        assertFailsWith<IllegalArgumentException> { owner.dispatch(plan.id, good.copy(proofs = null)) }
        val proof = checkNotNull(good.proofs).single()
        val invalid = listOf(
            good.copy(blockers = input(current, legacy = true).blockers),
            good.copy(proofs = setOf(proof.copy(sessionGeneration = proof.sessionGeneration + 1))),
            good.copy(proofs = setOf(proof.copy(acceptance = proof.acceptance.copy(snapshotId = "other-snapshot")))),
            good.copy(proofs = setOf(proof.copy(issue = proof.issue.copy(message = "Other cause")))),
            good.copy(proofs = emptySet()),
        )
        invalid.forEach { assertNotNull(owner.dispatch(plan.id, it).rejection) }
        assertEquals(before, events.snapshot(plan.id))
        owner.command(plan.id, good)
        assertNotNull(owner.currentAdmission(plan.id))
        assertEquals(1, owner.planFor(plan.id)?.acceptanceWaivers?.size)
        owner.recover()
        assertNull(owner.currentAdmission(plan.id))
        assertEquals(1, owner.planFor(plan.id)?.acceptanceWaivers?.size)
    }

    @Test fun malformedForeignAndNonBijectiveLegacyTokensCannotAuthorizeReplay() = runTest {
        val plan = blockedPlan(2); val good = input(plan, legacy = true)
        val first = good.blockers.first(); val second = good.blockers.last()
        val invalidIds = listOf(
            setOf(first.replace("-blocked-run-", "-blocked-other-"), second),
            setOf(first.substringBeforeLast('-') + "-not-a-number", second),
            setOf(first.substringBeforeLast('-') + "-0174231", second),
            setOf(first),
            setOf(first, first.replace("174231", "174232")),
            input(plan).blockers,
        )
        for(ids in invalidIds) {
            val events = InMemoryEventJournal(); appendLegacy(events, plan, good.copy(blockers = ids))
            val before = events.snapshot(plan.id)
            val owner = DefaultPlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json), events)
            assertFailsWith<PlanningPersistenceException> { owner.plans() }
            assertEquals(before, events.snapshot(plan.id)); assertNull(owner.currentAdmission(plan.id))
        }
    }

    @Test fun legacyReplayStillRequiresTheCurrentRunAttemptCriteriaAndSkippableOutcome() = runTest {
        val plan = blockedPlan(); val stage = plan.milestones.single(); val attempt = stage.attempts.single()
        val record = checkNotNull(attempt.acceptanceRecord)
        val invalidAttempts = listOf(
            attempt.copy(acceptanceRecord = record.copy(runId = "other-run")),
            attempt.copy(acceptanceRecord = record.copy(attemptId = "other-attempt")),
            attempt.copy(acceptanceRecord = record.copy(criteria = record.criteria.map { it.copy(description = "Changed") })),
            attempt.copy(pendingTool = "external operation", pendingToolExternal = true),
            attempt.copy(acceptanceRecord = record.copy(status = AcceptanceStatus.FAILED)),
        )
        for(value in invalidAttempts) {
            val candidate = plan.copy(milestones = listOf(stage.copy(attempts = listOf(value))))
            val events = InMemoryEventJournal(); appendLegacy(events, candidate, input(plan, legacy = true))
            val owner = DefaultPlanningStore(JsonPlanningRepository(InMemoryKeyValueStore(), Json), events)
            assertFailsWith<PlanningPersistenceException> { owner.plans() }
            assertNull(owner.currentAdmission(plan.id))
        }
    }
}
