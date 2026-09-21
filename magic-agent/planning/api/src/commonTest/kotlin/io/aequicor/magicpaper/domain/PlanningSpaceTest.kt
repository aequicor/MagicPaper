package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.PlanningMachine.Fact
import io.aequicor.magicpaper.domain.PlanningMachine.Intent
import io.aequicor.magicpaper.domain.planning.AssignmentRecovery
import io.aequicor.magicpaper.domain.planning.AssignmentRecoveryRef
import io.aequicor.magicpaper.domain.planning.FinalAttemptMutation
import io.aequicor.magicpaper.domain.planning.PlanClassificationStatus
import io.aequicor.magicpaper.domain.planning.PlanFailureCause
import io.aequicor.magicpaper.domain.planning.PlanRecoveryStrategy
import io.aequicor.magicpaper.domain.planning.PlanRevisionEvent
import io.aequicor.magicpaper.domain.planning.PlanSpecification
import io.aequicor.magicpaper.domain.planning.PlanStrategyMetrics
import io.aequicor.magicpaper.domain.planning.PlanStrategySelection
import io.aequicor.magicpaper.domain.planning.RefinementResult
import io.aequicor.magicpaper.domain.planning.StageEvent
import io.aequicor.magicpaper.domain.planning.StageProgress
import io.aequicor.magicpaper.domain.planning.StageRetryInputs
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The representatives of [PlanningSpace], kept here rather than in the api so a shipped binary
 * carries no fixtures.
 *
 * Every state is built by running the machine from `initial`, never by constructing one, which is
 * what the `internal constructor` on `State` is there to enforce. Every position that holds a run is
 * built from one admission — stamp `start`, run `run`, generation 1 — so a single `RunRef` fits all
 * of them, and every one but `admitted` from one attempt `a1` on the stage `stage`, so a single
 * `AttemptRef` does; `admitted` is that admission before the attempt exists, and is where an input
 * naming the attempt is refused. The second stage, `extra`, is never started, which is why
 * `StageCreated` names it. `ready` is the one place
 * a plan is seeded: the evidence `Applied` needs cannot be produced by a short chain of inputs.
 */
class PlanningSpaceTest {
    private var sequence = 0
    private fun stamp() = PlanningMachine.Stamp("input-${sequence++}", sequence.toLong())

    private val assignment = StageAssignment("profile", "model")
    private val rules = PlanningRulesSettings().snapshot()
    private val message = PlanningMessage("m1", "user", "Refine")

    // A plan imported with a legacy request that was never captured, so DiscardLegacyRefinement has
    // a request to discard wherever no refinement is in flight.
    private val plan = Plan("plan", "project", "Goal", pendingRequest = "legacy", requestId = "legacy",
        milestones = listOf(Milestone("stage", "Stage", description = "Do it"), Milestone("extra", "Extra", description = "Later")))
    private val attempt1 = StageAttempt("a1", "s1", assignment)
    private val start = PlanningMachine.Stamp("start", 1)

    private fun accepted(state: PlanningMachine.State, vararg inputs: PlanningMachine.Input) = inputs.fold(state) { current, input ->
        val step = PlanningMachine.reduce(current, input)
        assertNull(step.rejection, "Подготовка представителя отказана: $input")
        step.state
    }

    private val empty = PlanningMachine.initial(plan.id)
    private val draft = accepted(empty, Intent.Create(plan, stamp()))
    private val started = accepted(draft, Intent.Start("run", rules, start))
    private val ref = checkNotNull(started.run).ref
    private val running = accepted(started, Fact.StageCreated(ref, "stage", attempt1.id, attempt1.sessionId, assignment, 0, stamp()))
    private val a1 = running.plan!!.milestones.first { it.id == "stage" }.attempts.last()
    private val a1Ref = PlanningMachine.AttemptRef.from(a1)

    private val request = PlanningNativeRequest(ref, 9, 0, PlanJournalOperation.AGENT_INTENT, "stage", a1.id, a1Ref,
        CodingEngine.PI, a1.sessionId, "r1")
    private val stopping = accepted(running, Intent.Stop(stamp()))

    private val runningFinal = accepted(running,
        Fact.FinalAttemptCreated(ref, "f1", "fs1", assignment, "", CodingEngine.PI, 0, stamp())).let {
        accepted(it, Fact.FinalTransitioned(ref, PlanningMachine.AttemptRef.from(it.plan!!.finalAttempt!!),
            FinalAttemptMutation.VerificationPrepared("snapshot"), stamp()))
    }
    private val finalNow = runningFinal.plan!!.finalAttempt!!

    private val workspace = PlanWorkspace("/root", "/root/integration", "base", git = true)
    private val ready: PlanningMachine.State = run {
        val done = plan.milestones.map { it.copy(status = MilestoneStatus.DONE, attempts = if (it.id == "stage") listOf(attempt1) else emptyList()) }
        val criteria = plan.copy(milestones = done).acceptanceCriteria()
        val finalDone = StageAttempt("f1", "fs1", assignment, phase = AttemptPhase.COMPLETE, engine = CodingEngine.PI,
            acceptanceRecord = AcceptanceRecord("run", "f1", "snapshot", criteria,
                criteria.map { AcceptanceFinding(it.id, CheckStatus.PASS, it.description, "Checked") }, status = AcceptanceStatus.ACCEPTED))
        val seeded = accepted(empty, Intent.Create(plan.copy(runId = "run", workspace = workspace, finalAttempt = finalDone, milestones = done), stamp()))
        accepted(seeded, Intent.Start("run", rules, start))
    }

    private val refining = accepted(draft, Intent.BeginRefinement(message, null, false, null, SearchProvider.AUTO, stamp()))
    private val draftPlan = checkNotNull(draft.plan)
    private val unknownRun = accepted(running, Fact.NativeObserved(PlanningNativeFact.RequestAdmitted(request), stamp()),
        Fact.OperationUnknown(9, stamp()))

    private val states: Map<PhaseId, PlanningMachine.State> = mapOf(
        PlanningSpace.NEW to empty,
        PlanningSpace.DRAFT to draft,
        PlanningSpace.REFINING to refining,
        PlanningSpace.DRAFT_UNRESOLVED to accepted(draft, Fact.OperationUnknown(9, stamp())),
        PlanningSpace.DRAFT_STOPPING to accepted(draft, Intent.Stop(stamp())),
        PlanningSpace.ADMITTED to started,
        PlanningSpace.RUNNING to running,
        PlanningSpace.RUNNING_FINAL to runningFinal,
        PlanningSpace.READY to ready,
        PlanningSpace.PAUSED to accepted(running, Intent.Pause(stamp())),
        PlanningSpace.STOPPING to stopping,
        PlanningSpace.STOPPED to accepted(stopping, Fact.StopConfirmed(stamp())),
        PlanningSpace.INTERRUPTED to accepted(running, Fact.Restored(stamp())),
        PlanningSpace.UNKNOWN_RUN to unknownRun,
        PlanningSpace.UNKNOWN_STOPPING to accepted(stopping, Fact.OperationUnknown(9, stamp())),
        PlanningSpace.STOP_UNCONFIRMED to accepted(stopping, Fact.StopUnknown(stamp())),
        PlanningSpace.COMPLETE to accepted(ready, Fact.Applied(ref, workspace.copy(applied = true), stamp())),
        PlanningSpace.PERSISTENCE_UNKNOWN to accepted(running, Fact.PersistenceUnknown(stamp())),
        PlanningSpace.DELETED to accepted(draft, Intent.Delete(stamp())),
    )

    private val inputs: Map<InputId, PlanningMachine.Input> = mapOf(
        PlanningSpace.CONFIRM_NATIVE_RECOVERY to Intent.ConfirmNativeRecovery(PlanningNativeRecoveryDecision("decision",
            checkNotNull(unknownRun.plan).revision, 10, 0, setOf(9),
            listOf(PlanningNativeEvidence("r1", attempts = listOf(NativeRunRecoveryRef(CodingEngine.PI, a1.sessionId, "r1", 1))))), stamp()),
        PlanningSpace.CREATE to Intent.Create(plan, stamp()),
        PlanningSpace.EDIT to Intent.Edit(draftPlan.revision, draftPlan.copy(goal = "Edited"), stamp()),
        PlanningSpace.REVISE to Intent.Revise(listOf(PlanRevisionEvent.DialogueAppended(listOf(PlanningMessage("d1", "assistant", "Note")))), stamp()),
        PlanningSpace.START to Intent.Start("run", rules, stamp()),
        PlanningSpace.RESUME to Intent.Resume(ref, stamp()),
        PlanningSpace.PAUSE to Intent.Pause(stamp()),
        PlanningSpace.STOP to Intent.Stop(stamp()),
        PlanningSpace.RETRY to Intent.Retry(checkNotNull(states.getValue(PlanningSpace.INTERRUPTED).plan), emptyMap(), "run", rules, stamp()),
        PlanningSpace.SKIP_VERIFICATION to Intent.SkipVerification(emptySet(), "run", rules, stamp()),
        PlanningSpace.ASSIGN_STAGE to Intent.AssignStage("stage", assignment, stamp()),
        PlanningSpace.NAVIGATE to Intent.Navigate(PlanningStep.REVIEW, stamp()),
        PlanningSpace.REFINE_REQUESTED to Intent.RefineRequested(PlanningMessage("m9", "user", "More"), stamp()),
        PlanningSpace.BEGIN_REFINEMENT to Intent.BeginRefinement(PlanningMessage("m2", "user", "Again"), null, false, null, SearchProvider.AUTO, stamp()),
        PlanningSpace.CANCEL_REFINEMENT to Intent.CancelRefinement(checkNotNull(refining.refinement).ref, stamp()),
        PlanningSpace.DISCARD_LEGACY_REFINEMENT to Intent.DiscardLegacyRefinement("legacy", stamp()),
        PlanningSpace.RECOVER_ASSIGNMENTS to Intent.RecoverAssignments(AssignmentRecovery(AssignmentRecoveryRef("plan", "project", "run", 1)), stamp()),
        PlanningSpace.SCHEDULE to Intent.Schedule("run", emptyList(), "origin", "author", emptySet(), null, stamp()),
        PlanningSpace.DELETE to Intent.Delete(stamp()),
        PlanningSpace.REFINEMENT_COMPLETED to Fact.RefinementCompleted(checkNotNull(refining.refinement).ref,
            RefinementResult(PlanSpecification.from(checkNotNull(refining.plan)), PlanningMessage("assistant", "assistant", "Done")), stamp()),
        PlanningSpace.NATIVE_REQUEST_ADMITTED to Fact.NativeObserved(PlanningNativeFact.RequestAdmitted(request), stamp()),
        PlanningSpace.NATIVE_PROOF_OBSERVED to Fact.NativeObserved(PlanningNativeFact.ConsumptionObserved("missing",
            NativeRunRecoveryConsumption("ack", CodingEngine.PI, a1.sessionId, "missing")), stamp()),
        PlanningSpace.LEGACY_IMPORTED to Fact.LegacyImported(plan, emptySet(), stamp()),
        PlanningSpace.LEGACY_CHECKPOINT to Fact.LegacyCheckpoint(draftPlan.copy(revision = draftPlan.revision + 1), stamp()),
        PlanningSpace.RESTORED to Fact.Restored(stamp()),
        PlanningSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown(stamp()),
        PlanningSpace.RECOVERY_CONFIRMED to Fact.RecoveryConfirmed(stamp()),
        PlanningSpace.EVIDENCE_RECONCILED to Fact.EvidenceObserved(emptySet(), stamp()),
        PlanningSpace.EVIDENCE_PENDING to Fact.EvidenceObserved(setOf(7), stamp()),
        PlanningSpace.OPERATION_UNKNOWN to Fact.OperationUnknown(9, stamp()),
        PlanningSpace.STRATEGY_SELECTED to Fact.StrategySelected(PlanStrategySelection(sourceSeq = 1, runId = "run", stageId = "stage",
            metrics = PlanStrategyMetrics("agent-intent", 2, 0, 2, 0, 2, 0, null), cause = PlanFailureCause.UNKNOWN,
            status = PlanClassificationStatus.UNAVAILABLE, strategy = PlanRecoveryStrategy.PAUSE_FOR_REVIEW), null, stamp()),
        PlanningSpace.SKIPPED_VERIFICATION_RESTORED to Fact.SkippedVerificationRestored(ref, stamp()),
        PlanningSpace.VERIFICATION_OBSERVED to Fact.VerificationObserved(ref, "stage", a1Ref, true, "ok", stamp()),
        PlanningSpace.RULES_BOUND to Fact.RulesBound(rules, stamp()),
        PlanningSpace.WORKSPACE_SELECTED to Fact.WorkspaceSelected(ref, true, stamp()),
        PlanningSpace.WORKSPACE_PREPARED to Fact.WorkspacePrepared(ref, PlanWorkspace("/source", "/work"), stamp()),
        PlanningSpace.PHASE_OBSERVED to Fact.PhaseObserved(ref, ExecutionPhase.WAITING, stamp()),
        PlanningSpace.PHASE_APPLYING to Fact.PhaseObserved(ref, ExecutionPhase.APPLYING, stamp()),
        PlanningSpace.ISSUE_OBSERVED to Fact.IssueObserved(ref, null, stamp = stamp()),
        PlanningSpace.ISSUE_OBSERVED_UNBOUND to Fact.IssueObserved(null, null, stamp = stamp()),
        PlanningSpace.FINAL_ATTEMPT_CREATED to Fact.FinalAttemptCreated(ref, "f9", "fs9", assignment, "", CodingEngine.PI, 0, stamp()),
        PlanningSpace.FINAL_RECORDED to Fact.FinalTransitioned(ref, PlanningMachine.AttemptRef.from(finalNow),
            FinalAttemptMutation.EngineResolved(CodingEngine.PI), stamp()),
        PlanningSpace.FINAL_TURN_STARTED to Fact.FinalTransitioned(ref, PlanningMachine.AttemptRef.from(finalNow),
            FinalAttemptMutation.VerificationStarted, stamp()),
        PlanningSpace.STAGE_CREATED to Fact.StageCreated(ref, "extra", "a2", "s2", assignment, 1, stamp()),
        PlanningSpace.STAGE_WORKER_STARTING to Fact.StageTransitioned(ref, "stage", a1Ref, StageEvent.WorkerStarting("go", 5), null, stamp()),
        PlanningSpace.STAGE_VERIFICATION_DECIDED to Fact.StageTransitioned(ref, "stage", a1Ref,
            StageEvent.VerificationDecided(Verdict(true, "ok"), StageRetryInputs(null, 0, 0)), null, stamp()),
        PlanningSpace.STAGE_INTERRUPTED to Fact.StageTransitioned(ref, "stage", a1Ref, StageEvent.Interrupted(a1, false, 5), null, stamp()),
        PlanningSpace.STAGE_RECORDED to Fact.StageTransitioned(ref, "stage", a1Ref, StageEvent.EngineResolved(CodingEngine.PI), null, stamp()),
        PlanningSpace.STAGE_PROGRESS_OBSERVED to Fact.StageProgressObserved(ref, "stage", a1Ref, StageProgress.from(a1).copy(report = "progress"), stamp()),
        PlanningSpace.ATTEMPT_RECORDED to Fact.AttemptRecorded(ref, "stage", a1.copy(report = "update"), stamp = stamp()),
        PlanningSpace.FINAL_ATTEMPT_CLEARED to Fact.FinalAttemptCleared(ref, finalNow, stamp()),
        PlanningSpace.APPLIED to Fact.Applied(ref, workspace.copy(applied = true), stamp()),
        PlanningSpace.STOP_CONFIRMED to Fact.StopConfirmed(stamp()),
        PlanningSpace.STOP_UNKNOWN to Fact.StopUnknown(stamp()),
        PlanningSpace.ACCEPTANCE_RECHECKED to Fact.AcceptanceRechecked(ref, AcceptanceRecord("run", "other", "snapshot", emptyList(), emptyList()), null, stamp()),
        PlanningSpace.MERGE_ACCEPTANCE_RECORDED to Fact.MergeAcceptanceRecorded(ref, PlanningMachine.AttemptRef.from(finalNow),
            AcceptanceRecord("run", finalNow.id, "snapshot", runningFinal.plan!!.acceptanceCriteria(), emptyList()), stamp()),
        PlanningSpace.PROJECTION_CONFIRMED to Fact.ProjectionConfirmed("marker", stamp()),
        PlanningSpace.PROJECTION_FAILED to Fact.ProjectionFailed("marker", "stage", a1.id, stamp()),
        PlanningSpace.JOURNAL_INTENT_OBSERVED to Fact.JournalObserved(PlanJournalOperation.AGENT_INTENT, "stage", a1.id, ref = ref, stamp = stamp()),
        PlanningSpace.JOURNAL_OUTCOME_OBSERVED to Fact.JournalObserved(PlanJournalOperation.INTENT_OUTCOME, stamp = stamp()),
        PlanningSpace.SCHEDULE_ADVANCED to Fact.ScheduleAdvanced(ref, stamp()),
        PlanningSpace.SCHEDULE_DELIVERED to Fact.ScheduleDelivered(ref, "rule", stamp()),
        PlanningSpace.SCHEDULE_FAILED to Fact.ScheduleFailed(ref, "rule", true, stamp()),
    )

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(PlanningMachine, states, inputs)

    @Test fun aRunIsAdmittedOnlyUntilAnyStageHoldsAnAttempt() {
        val admitted = states.getValue(PlanningSpace.ADMITTED)
        assertEquals(PlanningSpace.ADMITTED, PlanningSpace.label(admitted))
        // Any stage counts, not only the one the representatives name.
        val elsewhere = accepted(admitted, Fact.StageCreated(ref, "extra", "a2", "s2", assignment, 1, stamp()))
        assertEquals(PlanningSpace.RUNNING, PlanningSpace.label(elsewhere))
        // Only a running run splits on it: a paused one is named by its run phase alone.
        assertEquals(PlanningSpace.PAUSED, PlanningSpace.label(accepted(admitted, Intent.Pause(stamp()))))
    }

    @Test fun persistenceUnknownOutranksDeletion() {
        // The flag fact is accepted even by a deleted plan, and the flag is the stronger fence.
        val both = accepted(states.getValue(PlanningSpace.DELETED), Fact.PersistenceUnknown(stamp()))
        assertTrue(both.deleted && both.persistenceUnknown)
        assertEquals(PlanningSpace.PERSISTENCE_UNKNOWN, PlanningSpace.label(both))
    }

    @Test fun aPendingOutcomeOutranksAStopRequestAndARefinementWithoutARun() {
        for (state in listOf(states.getValue(PlanningSpace.DRAFT_STOPPING), states.getValue(PlanningSpace.REFINING))) {
            assertEquals(PlanningSpace.DRAFT_UNRESOLVED, PlanningSpace.label(accepted(state, Fact.OperationUnknown(9, stamp()))))
        }
    }

    @Test fun aStopAfterAnUnknownOutcomeStaysAtTheUnknownPosition() {
        // The run phase becomes STOPPING while the intent stays pending, so the pending set alone decides.
        val stopped = accepted(running, Fact.OperationUnknown(9, stamp()), Intent.Stop(stamp()))
        assertEquals(PlanningMachine.RunPhase.STOPPING, stopped.run?.phase)
        assertEquals(PlanningSpace.UNKNOWN_STOPPING, PlanningSpace.label(stopped))
    }

    @Test fun unknownIsExactlyTheOutcomesNeverConfirmed() {
        val expected = setOf(PlanningSpace.DRAFT_UNRESOLVED, PlanningSpace.UNKNOWN_RUN, PlanningSpace.UNKNOWN_STOPPING,
            PlanningSpace.STOP_UNCONFIRMED, PlanningSpace.PERSISTENCE_UNKNOWN)
        assertEquals(expected, states.filterValues { PlanningSpace.unknown(it) }.keys)
    }

    @Test fun everyPieceOfEvidenceIsNeededBeforeAnApplyIsReady() {
        // `ready` differs from `running-final` in every conjunct at once, so dropping one from the
        // label would go unseen without a state that lacks exactly that one.
        fun labelled(stagesDone: Boolean, permits: Boolean, complete: Boolean): PhaseId {
            val stages = plan.milestones.map { it.copy(status = if (stagesDone) MilestoneStatus.DONE else MilestoneStatus.PENDING) }
            val criteria = plan.copy(milestones = stages).acceptanceCriteria()
            val record = AcceptanceRecord("run", "f1", "snapshot", criteria, emptyList(),
                status = if (permits) AcceptanceStatus.ACCEPTED else AcceptanceStatus.FAILED)
            val final = StageAttempt("f1", "fs1", assignment, engine = CodingEngine.PI, acceptanceRecord = record,
                phase = if (complete) AttemptPhase.COMPLETE else AttemptPhase.VERIFYING)
            val seeded = accepted(empty, Intent.Create(plan.copy(runId = "run", finalAttempt = final, milestones = stages), stamp()))
            return checkNotNull(PlanningSpace.label(accepted(seeded, Intent.Start("run", rules, start))))
        }
        assertEquals(PlanningSpace.READY, labelled(stagesDone = true, permits = true, complete = true))
        assertEquals(PlanningSpace.RUNNING_FINAL, labelled(stagesDone = false, permits = true, complete = true))
        assertEquals(PlanningSpace.RUNNING_FINAL, labelled(stagesDone = true, permits = false, complete = true))
        assertEquals(PlanningSpace.RUNNING_FINAL, labelled(stagesDone = true, permits = true, complete = false))
    }
}
