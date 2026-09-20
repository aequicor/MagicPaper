package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.*
import kotlin.test.*

class PlanStrategyTest {
    private val plan = Plan("plan", "project", "Goal", runId = "run", intent = ExecutionIntent.RUN,
        issue = PlanningIssue(IssueKind.TRANSIENT, "provider", retryAt = 999, retries = 2))
    private fun sample(seq: Long, status: PlanIntentStatus = PlanIntentStatus.REJECTED, run: String = "run", stage: String = "work") =
        PlanStrategySample(seq, seq - 1, run, PlanJournalOperation.AGENT_INTENT.wire, stage, status, 10)

    @Test fun detectorIsBoundedDeterministicAndDoesNotPoolOtherRunsOrStages() {
        assertNull(detectPlanStrategy(plan, listOf(sample(2))))
        assertNull(detectPlanStrategy(plan, listOf(sample(2, run = "old"), sample(4))))
        assertNull(detectPlanStrategy(plan, listOf(sample(2, stage = "other"), sample(4))))
        assertNull(detectPlanStrategy(plan, listOf(sample(2), sample(4, PlanIntentStatus.COMPLETED))))
        val samples = (1..40).map { sample(it * 2L) }
        val detected = assertNotNull(detectPlanStrategy(plan, samples))
        assertEquals(detected, detectPlanStrategy(plan, samples))
        assertEquals(16, detected.metrics.samples)
        assertEquals(16, detected.metrics.consecutiveFailures)
        assertEquals(10L, detected.metrics.meanDurationMillis)
        assertEquals(80L, detected.sourceSeq)
    }

    @Test fun unknownDurationsDoNotBecomeZeroAndMeanCannotOverflow() {
        val samples = listOf(sample(2).copy(durationMillis = Long.MAX_VALUE), sample(4).copy(durationMillis = null))
        assertEquals(Long.MAX_VALUE, detectPlanStrategy(plan, samples)!!.metrics.meanDurationMillis)
        assertNull(detectPlanStrategy(plan, samples.map { it.copy(durationMillis = null) })!!.metrics.meanDurationMillis)
    }

    @Test fun modelCannotGrantPermissionOrIncreaseAnyBudget() {
        for (blocked in listOf(plan.copy(intent = ExecutionIntent.PAUSE), plan.copy(stopping = true),
            plan.copy(issue = plan.issue!!.copy(requiresUser = true)), plan.copy(issue = plan.issue!!.copy(retryBlocked = true)),
            plan.copy(issue = plan.issue!!.copy(kind = IssueKind.UNCERTAIN)), plan.copy(phase = ExecutionPhase.COMPLETE),
            plan.copy(finalAttempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), pendingToolExternal = true)))) {
            assertNull(detectPlanStrategy(blocked, listOf(sample(2), sample(4))))
            assertEquals(PlanRecoveryStrategy.PAUSE_FOR_REVIEW,
                selectPlanStrategy(blocked, PlanFailureCause.TRANSIENT_TRANSPORT, PlanClassificationStatus.MODEL, null))
        }
        assertEquals(PlanRecoveryStrategy.PAUSE_FOR_REVIEW,
            selectPlanStrategy(plan, PlanFailureCause.TRANSIENT_TRANSPORT, PlanClassificationStatus.MODEL, 1))
        for (cause in PlanFailureCause.entries.filter { it != PlanFailureCause.TRANSIENT_TRANSPORT })
            assertEquals(PlanRecoveryStrategy.PAUSE_FOR_REVIEW, selectPlanStrategy(plan, cause, PlanClassificationStatus.MODEL, null))
    }

    @Test fun existingBackoffPreservesTheEntireCheckpointAndPauseIsVisibleAndSerializable() {
        val trigger = detectPlanStrategy(plan, listOf(sample(2), sample(4)))!!
        val selection = PlanStrategySelection(sourceSeq = trigger.sourceSeq, runId = trigger.runId, stageId = trigger.stageId,
            metrics = trigger.metrics, cause = PlanFailureCause.TRANSIENT_TRANSPORT, status = PlanClassificationStatus.MODEL,
            strategy = PlanRecoveryStrategy.EXISTING_BACKOFF)
        assertEquals(selection, PlanStrategySelection.decode(selection.encode()))
        assertSame(plan, applyPlanStrategy(plan, selection))
        val paused = applyPlanStrategy(plan, selection.copy(cause = PlanFailureCause.UNKNOWN, strategy = PlanRecoveryStrategy.PAUSE_FOR_REVIEW))
        assertEquals(ExecutionIntent.PAUSE, paused.intent)
        assertTrue(paused.issue!!.requiresUser)
        assertEquals(plan.issue!!.retries, paused.issue!!.retries)
        assertEquals(plan.issue!!.retryAt, paused.issue!!.retryAt)
        assertEquals(plan.runId, paused.runId)
        assertFailsWith<IllegalArgumentException> { applyPlanStrategy(plan, selection.copy(runId = "other")) }
        assertFailsWith<IllegalArgumentException> { PlanStrategySelection.decode(selection.copy(version = 2).encode()) }
    }
}
