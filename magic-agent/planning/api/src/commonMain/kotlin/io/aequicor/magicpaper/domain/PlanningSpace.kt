package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.planning.FinalAttemptMutation
import io.aequicor.magicpaper.domain.planning.StageEvent
import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [PlanningMachine], declared so it can be read without running anything.
 *
 * The state is an aggregate — a plan, at most one run of it, the journal intents still awaiting an
 * outcome, a captured refinement request, the native-recovery protocol and two fences — and
 * `RunPhase` describes only the run, while the reducer's guards read the plan beside it. So a
 * position is named by what acceptance depends on, in the order the reducer evaluates it:
 *
 * 1. The fences come first and refuse everything but `PersistenceUnknown`: [PERSISTENCE_UNKNOWN],
 *    [DELETED] and [NEW] (no plan yet, which also accepts `Create` and `LegacyImported`). The flag
 *    fact is handled before the deleted check, so it moves a deleted plan to [PERSISTENCE_UNKNOWN].
 * 2. Without a run, what matters is whether an outcome is pending, whether a stop was requested and
 *    whether a refinement is in flight: [DRAFT], [REFINING], [DRAFT_UNRESOLVED], [DRAFT_STOPPING].
 * 3. With a run, a pending outcome outranks the run phase, because `Start`, `Delete`, `Resume` and
 *    `RecoveryConfirmed` refuse while one is pending. The unknown positions are [UNKNOWN_RUN],
 *    [UNKNOWN_STOPPING] and [STOP_UNCONFIRMED] (`StopUnknown`: the run is unknown and nothing is
 *    pending, so `RecoveryConfirmed` is accepted there and refused at [UNKNOWN_STOPPING]).
 * 4. Otherwise the run phase decides, and `RUNNING` is four positions because acceptance depends on
 *    the evidence already received: [ADMITTED] is a run nothing has started on, where no stage holds an
 *    attempt and so every input that names one is refused; [RUNNING] has begun a stage and accepts
 *    `FinalAttemptCreated`; [RUNNING_FINAL] holds a final attempt and accepts the inputs that name it;
 *    and [READY] holds an accepted final attempt over stages that are all done, which is the only place
 *    `Applied` is accepted.
 *
 * `unknown(state)` and the position agree: it is true at exactly the four unknown-outcome positions
 * and at [PERSISTENCE_UNKNOWN], which `PlanningSpaceTest` pins.
 *
 * What the declaration cannot express, and leaves to the owner's tests:
 *
 * - Identity. A run reference, an attempt reference, a stop id, a refinement reference, a request id
 *   or a stamp that names something else is a refusal of identity, not of position. Every
 *   representative that holds a run carries one admission and one run, and all but [ADMITTED] one attempt.
 * - Payload that snapshots the plan. `Edit` and `LegacyCheckpoint` carry the plan or its revision,
 *   `Retry` carries the expected plan, and `FinalRecorded`, `FinalTurnStarted`, `FinalAttemptCleared`
 *   and `MergeAcceptanceRecorded` carry the final attempt. Their rows say only where the
 *   representative's snapshot is still current — [DRAFT] and [DRAFT_UNRESOLVED] share a plan,
 *   [INTERRUPTED] shares the running plan, [RUNNING_FINAL] holds the final attempt — and read as
 *   refusals everywhere else. A fresh `Edit` is accepted at every position that has a plan, since
 *   the reducer never looks at the run phase, and a fresh `Retry` wherever `Start` is.
 * - Evidence that is not a position. Whether a stage has an attempt is a position only for a running
 *   run that has none anywhere ([ADMITTED]); which stage has one, in which phase, the workspace the run
 *   applies, a plan issue (which is why `StrategySelected` is accepted only at
 *   [STOP_UNCONFIRMED], the one representative that carries one) and the recorded native requests
 *   (which is why `ConfirmNativeRecovery` is accepted only at [UNKNOWN_RUN]) stay behind the
 *   position. A final attempt is a position only while the run is running; a paused or stopped run
 *   that holds one is named by its run phase alone, and its final-attempt rows assume none. A
 *   refinement is a position only without a run, so `RefinementCompleted` reads as refused at a
 *   running plan that has one captured. A pending outcome, a stop request and a refinement together
 *   are named by the pending outcome alone.
 * - A stop id that is missing. Only an old journal entry carries none, and `DefaultPlanningStore` refuses
 *   it from a live command, so the reducer stays lenient for replay: `StopUnknown` without an id is
 *   accepted wherever a plan exists, although `StopConfirmed` without one needs a stop request. The
 *   representatives send no id, which is why the `StopUnknown` column is accepted at every position.
 * - Replay. The matrix describes a live command. A journal is replayed with `replay = true`, which lifts the
 *   refusal of `Pause` at [STOPPED] and of `Pause` and `Stop` at [COMPLETE], because an earlier reducer
 *   accepted them and a plan holding one must still load.
 * - Columns refused everywhere. `SkipVerification` needs a plan blocked by a failed acceptance with
 *   its proofs, and `NativeProofObserved` needs a native decision that was recorded; no position
 *   here holds either. `PlanningRunAuthorityTest` and `PlanningNativeRecoveryTest` cover them.
 * - Families of one input class. `FinalTransitioned` and `StageTransitioned` carry a mutation of
 *   fifteen and twenty-two kinds; they are split only by the run guard the reducer gives them
 *   (`FinalTurnStarted`, `StageWorkerStarting`, `StageVerificationDecided`, `StageInterrupted`), and
 *   the rest share `FinalRecorded` and `StageRecorded`, although a mutation can be refused for its
 *   own reasons. `NativeObserved`, `EvidenceObserved`, `PhaseObserved`, `IssueObserved` and
 *   `JournalObserved` are split by the payload that decides acceptance in the same way.
 * - Bounds and validation: a blank or negative stamp, a plan that would overflow its revision or
 *   generation, and the validity of a plan, a message or a schedule command.
 */
object PlanningSpace : StateSpace<PlanningMachine.State, PlanningMachine.Input, PlanningMachine.Effect> {
    val NEW = PhaseId("new")
    val DRAFT = PhaseId("draft")
    val REFINING = PhaseId("refining")
    val DRAFT_UNRESOLVED = PhaseId("draft-unresolved")
    val DRAFT_STOPPING = PhaseId("draft-stopping")
    val ADMITTED = PhaseId("admitted")
    val RUNNING = PhaseId("running")
    val RUNNING_FINAL = PhaseId("running-final")
    val READY = PhaseId("ready-to-apply")
    val PAUSED = PhaseId("paused")
    val STOPPING = PhaseId("stopping")
    val STOPPED = PhaseId("stopped")
    val INTERRUPTED = PhaseId("interrupted")
    val UNKNOWN_RUN = PhaseId("unknown-outcome")
    val UNKNOWN_STOPPING = PhaseId("unknown-outcome-stopping")
    val STOP_UNCONFIRMED = PhaseId("stop-unconfirmed")
    val COMPLETE = PhaseId("complete")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")
    val DELETED = PhaseId("deleted")

    val CONFIRM_NATIVE_RECOVERY = InputId("ConfirmNativeRecovery")
    val CREATE = InputId("Create")
    val EDIT = InputId("Edit")
    val REVISE = InputId("Revise")
    val START = InputId("Start")
    val RESUME = InputId("Resume")
    val PAUSE = InputId("Pause")
    val STOP = InputId("Stop")
    val RETRY = InputId("Retry")
    val SKIP_VERIFICATION = InputId("SkipVerification")
    val ASSIGN_STAGE = InputId("AssignStage")
    val NAVIGATE = InputId("Navigate")
    val REFINE_REQUESTED = InputId("RefineRequested")
    val BEGIN_REFINEMENT = InputId("BeginRefinement")
    val CANCEL_REFINEMENT = InputId("CancelRefinement")
    val DISCARD_LEGACY_REFINEMENT = InputId("DiscardLegacyRefinement")
    val RECOVER_ASSIGNMENTS = InputId("RecoverAssignments")
    val SCHEDULE = InputId("Schedule")
    val DELETE = InputId("Delete")

    val REFINEMENT_COMPLETED = InputId("RefinementCompleted")
    val NATIVE_REQUEST_ADMITTED = InputId("NativeRequestAdmitted")
    val NATIVE_PROOF_OBSERVED = InputId("NativeProofObserved")
    val LEGACY_IMPORTED = InputId("LegacyImported")
    val LEGACY_CHECKPOINT = InputId("LegacyCheckpoint")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")
    val RECOVERY_CONFIRMED = InputId("RecoveryConfirmed")
    val EVIDENCE_RECONCILED = InputId("EvidenceReconciled")
    val EVIDENCE_PENDING = InputId("EvidencePending")
    val OPERATION_UNKNOWN = InputId("OperationUnknown")
    val STRATEGY_SELECTED = InputId("StrategySelected")
    val SKIPPED_VERIFICATION_RESTORED = InputId("SkippedVerificationRestored")
    val VERIFICATION_OBSERVED = InputId("VerificationObserved")
    val RULES_BOUND = InputId("RulesBound")
    val WORKSPACE_SELECTED = InputId("WorkspaceSelected")
    val WORKSPACE_PREPARED = InputId("WorkspacePrepared")
    val PHASE_OBSERVED = InputId("PhaseObserved")
    val PHASE_APPLYING = InputId("PhaseApplying")
    val ISSUE_OBSERVED = InputId("IssueObserved")
    val ISSUE_OBSERVED_UNBOUND = InputId("IssueObservedUnbound")
    val FINAL_ATTEMPT_CREATED = InputId("FinalAttemptCreated")
    val FINAL_RECORDED = InputId("FinalRecorded")
    val FINAL_TURN_STARTED = InputId("FinalTurnStarted")
    val STAGE_CREATED = InputId("StageCreated")
    val STAGE_WORKER_STARTING = InputId("StageWorkerStarting")
    val STAGE_VERIFICATION_DECIDED = InputId("StageVerificationDecided")
    val STAGE_INTERRUPTED = InputId("StageInterrupted")
    val STAGE_RECORDED = InputId("StageRecorded")
    val STAGE_PROGRESS_OBSERVED = InputId("StageProgressObserved")
    val ATTEMPT_RECORDED = InputId("AttemptRecorded")
    val FINAL_ATTEMPT_CLEARED = InputId("FinalAttemptCleared")
    val APPLIED = InputId("Applied")
    val STOP_CONFIRMED = InputId("StopConfirmed")
    val STOP_UNKNOWN = InputId("StopUnknown")
    val ACCEPTANCE_RECHECKED = InputId("AcceptanceRechecked")
    val MERGE_ACCEPTANCE_RECORDED = InputId("MergeAcceptanceRecorded")
    val PROJECTION_CONFIRMED = InputId("ProjectionConfirmed")
    val PROJECTION_FAILED = InputId("ProjectionFailed")
    val JOURNAL_INTENT_OBSERVED = InputId("JournalIntentObserved")
    val JOURNAL_OUTCOME_OBSERVED = InputId("JournalOutcomeObserved")
    val SCHEDULE_ADVANCED = InputId("ScheduleAdvanced")
    val SCHEDULE_DELIVERED = InputId("ScheduleDelivered")
    val SCHEDULE_FAILED = InputId("ScheduleFailed")

    override val phases = listOf(
        NEW, DRAFT, REFINING, DRAFT_UNRESOLVED, DRAFT_STOPPING,
        ADMITTED, RUNNING, RUNNING_FINAL, READY, PAUSED, STOPPING, STOPPED, INTERRUPTED,
        UNKNOWN_RUN, UNKNOWN_STOPPING, STOP_UNCONFIRMED, COMPLETE,
        PERSISTENCE_UNKNOWN, DELETED,
    )

    override val inputs = listOf(
        InputSpec(CONFIRM_NATIVE_RECOVERY, Branch.INTENT), // 0
        InputSpec(CREATE, Branch.INTENT), // 1
        InputSpec(EDIT, Branch.INTENT), // 2
        InputSpec(REVISE, Branch.INTENT), // 3
        InputSpec(START, Branch.INTENT), // 4
        InputSpec(RESUME, Branch.INTENT), // 5
        InputSpec(PAUSE, Branch.INTENT), // 6
        InputSpec(STOP, Branch.INTENT), // 7
        InputSpec(RETRY, Branch.INTENT), // 8
        InputSpec(SKIP_VERIFICATION, Branch.INTENT), // 9
        InputSpec(ASSIGN_STAGE, Branch.INTENT), // 10
        InputSpec(NAVIGATE, Branch.INTENT), // 11
        InputSpec(REFINE_REQUESTED, Branch.INTENT), // 12
        InputSpec(BEGIN_REFINEMENT, Branch.INTENT), // 13
        InputSpec(CANCEL_REFINEMENT, Branch.INTENT), // 14
        InputSpec(DISCARD_LEGACY_REFINEMENT, Branch.INTENT), // 15
        InputSpec(RECOVER_ASSIGNMENTS, Branch.INTENT), // 16
        InputSpec(SCHEDULE, Branch.INTENT), // 17
        InputSpec(DELETE, Branch.INTENT), // 18
        InputSpec(REFINEMENT_COMPLETED, Branch.FACT), // 19
        InputSpec(NATIVE_REQUEST_ADMITTED, Branch.FACT), // 20
        InputSpec(NATIVE_PROOF_OBSERVED, Branch.FACT), // 21
        InputSpec(LEGACY_IMPORTED, Branch.FACT), // 22
        InputSpec(LEGACY_CHECKPOINT, Branch.FACT), // 23
        InputSpec(RESTORED, Branch.FACT), // 24
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT), // 25
        InputSpec(RECOVERY_CONFIRMED, Branch.FACT), // 26
        InputSpec(EVIDENCE_RECONCILED, Branch.FACT), // 27
        InputSpec(EVIDENCE_PENDING, Branch.FACT), // 28
        InputSpec(OPERATION_UNKNOWN, Branch.FACT), // 29
        InputSpec(STRATEGY_SELECTED, Branch.FACT), // 30
        InputSpec(SKIPPED_VERIFICATION_RESTORED, Branch.FACT), // 31
        InputSpec(VERIFICATION_OBSERVED, Branch.FACT), // 32
        InputSpec(RULES_BOUND, Branch.FACT), // 33
        InputSpec(WORKSPACE_SELECTED, Branch.FACT), // 34
        InputSpec(WORKSPACE_PREPARED, Branch.FACT), // 35
        InputSpec(PHASE_OBSERVED, Branch.FACT), // 36
        InputSpec(PHASE_APPLYING, Branch.FACT), // 37
        InputSpec(ISSUE_OBSERVED, Branch.FACT), // 38
        InputSpec(ISSUE_OBSERVED_UNBOUND, Branch.FACT), // 39
        InputSpec(FINAL_ATTEMPT_CREATED, Branch.FACT), // 40
        InputSpec(FINAL_RECORDED, Branch.FACT), // 41
        InputSpec(FINAL_TURN_STARTED, Branch.FACT), // 42
        InputSpec(STAGE_CREATED, Branch.FACT), // 43
        InputSpec(STAGE_WORKER_STARTING, Branch.FACT), // 44
        InputSpec(STAGE_VERIFICATION_DECIDED, Branch.FACT), // 45
        InputSpec(STAGE_INTERRUPTED, Branch.FACT), // 46
        InputSpec(STAGE_RECORDED, Branch.FACT), // 47
        InputSpec(STAGE_PROGRESS_OBSERVED, Branch.FACT), // 48
        InputSpec(ATTEMPT_RECORDED, Branch.FACT), // 49
        InputSpec(FINAL_ATTEMPT_CLEARED, Branch.FACT), // 50
        InputSpec(APPLIED, Branch.FACT), // 51
        InputSpec(STOP_CONFIRMED, Branch.FACT), // 52
        InputSpec(STOP_UNKNOWN, Branch.FACT), // 53
        InputSpec(ACCEPTANCE_RECHECKED, Branch.FACT), // 54
        InputSpec(MERGE_ACCEPTANCE_RECORDED, Branch.FACT), // 55
        InputSpec(PROJECTION_CONFIRMED, Branch.FACT), // 56
        InputSpec(PROJECTION_FAILED, Branch.FACT), // 57
        InputSpec(JOURNAL_INTENT_OBSERVED, Branch.FACT), // 58
        InputSpec(JOURNAL_OUTCOME_OBSERVED, Branch.FACT), // 59
        InputSpec(SCHEDULE_ADVANCED, Branch.FACT), // 60
        InputSpec(SCHEDULE_DELIVERED, Branch.FACT), // 61
        InputSpec(SCHEDULE_FAILED, Branch.FACT), // 62
    )

    override val effects = listOf(
        EffectId("RunRequested"), EffectId("StageDecision"), EffectId("StopRequested"), EffectId("Reject"),
    )

    override val accepts = acceptance(phases, inputs, listOf(
        //                            0         1         2         3         4         5         6  
        //                            012345678901234567890123456789012345678901234567890123456789012
        /* new                      */ "010000000000000000000010010000000000000000000000000000000000000",
        /* draft                    */ "001110110011111110100001111111000100000100000000000001001101000",
        /* refining                 */ "000110110011111010110000111111000100000100000000000001001101000",
        /* draft-unresolved         */ "001100110011111110000001110111000100000100000000000001001101000",
        /* draft-stopping           */ "000100010011111110100000111111000100000100000000000011001101000",
        /* admitted                 */ "000100110011111111000000111111010111111110010000010001101111111",
        /* running                  */ "000100110011111111001000111111011111111110011111110001101111111",
        /* running-final            */ "000100110011111111001000111111011111111101111111111001111111111",
        /* ready-to-apply           */ "000100110011111111001000111111011111111100011111110101101111111",
        /* paused                   */ "000111110011111111100000111111011111101100000111110001101101111",
        /* stopping                 */ "000100010011111111000000111111011111101100000011110011101101111",
        /* stopped                  */ "000110010011111111100000111111011111101100000011010001101101111",
        /* interrupted              */ "000110111011111111100000111111011111101100000011110001101101111",
        /* unknown-outcome          */ "100100010011111111000000110111011111101100000011110001101101111",
        /* unknown-outcome-stopping */ "000100010011111111000000110111011111101100000011110011101101111",
        /* stop-unconfirmed         */ "000100010011111111000000111111111111101100000011110011101101111",
        /* complete                 */ "000100000011111111100000111111000100000100000000000001001101000",
        /* persistence-unknown      */ "000000000000000000000000010000000000000000000000000000000000000",
        /* deleted                  */ "000000000000000000000000010000000000000000000000000000000000000",
    ))

    private fun ready(plan: Plan): Boolean {
        val final = plan.finalAttempt ?: return false
        return final.phase == AttemptPhase.COMPLETE && final.acceptanceRecord?.permitsProgress == true &&
            plan.selectedMilestones.all { it.completed }
    }

    override fun label(state: PlanningMachine.State): PhaseId? {
        if (state.persistenceUnknown) return PERSISTENCE_UNKNOWN
        if (state.deleted) return DELETED
        val plan = state.plan ?: return NEW
        val run = state.run
        val pending = state.pendingOperations.isNotEmpty()
        if (run == null) return when {
            pending -> DRAFT_UNRESOLVED
            plan.stopping -> DRAFT_STOPPING
            state.refinement != null -> REFINING
            else -> DRAFT
        }
        if (pending || run.phase == PlanningMachine.RunPhase.UNKNOWN) return when {
            plan.stopping && pending -> UNKNOWN_STOPPING
            plan.stopping -> STOP_UNCONFIRMED
            else -> UNKNOWN_RUN
        }
        return when (run.phase) {
            PlanningMachine.RunPhase.RUNNING -> when {
                ready(plan) -> READY
                plan.finalAttempt != null -> RUNNING_FINAL
                plan.milestones.none { it.attempts.isNotEmpty() } -> ADMITTED
                else -> RUNNING
            }
            PlanningMachine.RunPhase.PAUSED -> PAUSED
            PlanningMachine.RunPhase.STOPPING -> STOPPING
            PlanningMachine.RunPhase.STOPPED -> STOPPED
            PlanningMachine.RunPhase.INTERRUPTED -> INTERRUPTED
            PlanningMachine.RunPhase.COMPLETE -> COMPLETE
            PlanningMachine.RunPhase.UNKNOWN -> UNKNOWN_RUN
        }
    }

    override fun name(input: PlanningMachine.Input): InputId = when (input) {
        is PlanningMachine.Intent.ConfirmNativeRecovery -> CONFIRM_NATIVE_RECOVERY
        is PlanningMachine.Intent.Create -> CREATE
        is PlanningMachine.Intent.Edit -> EDIT
        is PlanningMachine.Intent.Revise -> REVISE
        is PlanningMachine.Intent.Start -> START
        is PlanningMachine.Intent.Resume -> RESUME
        is PlanningMachine.Intent.Pause -> PAUSE
        is PlanningMachine.Intent.Stop -> STOP
        is PlanningMachine.Intent.Retry -> RETRY
        is PlanningMachine.Intent.SkipVerification -> SKIP_VERIFICATION
        is PlanningMachine.Intent.AssignStage -> ASSIGN_STAGE
        is PlanningMachine.Intent.Navigate -> NAVIGATE
        is PlanningMachine.Intent.RefineRequested -> REFINE_REQUESTED
        is PlanningMachine.Intent.BeginRefinement -> BEGIN_REFINEMENT
        is PlanningMachine.Intent.CancelRefinement -> CANCEL_REFINEMENT
        is PlanningMachine.Intent.DiscardLegacyRefinement -> DISCARD_LEGACY_REFINEMENT
        is PlanningMachine.Intent.RecoverAssignments -> RECOVER_ASSIGNMENTS
        is PlanningMachine.Intent.Schedule -> SCHEDULE
        is PlanningMachine.Intent.Delete -> DELETE
        is PlanningMachine.Fact.RefinementCompleted -> REFINEMENT_COMPLETED
        is PlanningMachine.Fact.NativeObserved -> when (input.value) {
            is PlanningNativeFact.RequestAdmitted -> NATIVE_REQUEST_ADMITTED
            is PlanningNativeFact.ReleaseAuthorized, is PlanningNativeFact.Acknowledged,
            is PlanningNativeFact.ConsumptionObserved -> NATIVE_PROOF_OBSERVED
        }
        is PlanningMachine.Fact.LegacyImported -> LEGACY_IMPORTED
        is PlanningMachine.Fact.LegacyCheckpoint -> LEGACY_CHECKPOINT
        is PlanningMachine.Fact.Restored -> RESTORED
        is PlanningMachine.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
        is PlanningMachine.Fact.RecoveryConfirmed -> RECOVERY_CONFIRMED
        is PlanningMachine.Fact.EvidenceObserved -> if (input.pending.isEmpty()) EVIDENCE_RECONCILED else EVIDENCE_PENDING
        is PlanningMachine.Fact.OperationUnknown -> OPERATION_UNKNOWN
        is PlanningMachine.Fact.StrategySelected -> STRATEGY_SELECTED
        is PlanningMachine.Fact.SkippedVerificationRestored -> SKIPPED_VERIFICATION_RESTORED
        is PlanningMachine.Fact.VerificationObserved -> VERIFICATION_OBSERVED
        is PlanningMachine.Fact.RulesBound -> RULES_BOUND
        is PlanningMachine.Fact.WorkspaceSelected -> WORKSPACE_SELECTED
        is PlanningMachine.Fact.WorkspacePrepared -> WORKSPACE_PREPARED
        is PlanningMachine.Fact.PhaseObserved -> if (input.phase == ExecutionPhase.APPLYING) PHASE_APPLYING else PHASE_OBSERVED
        is PlanningMachine.Fact.IssueObserved -> if (input.ref == null) ISSUE_OBSERVED_UNBOUND else ISSUE_OBSERVED
        is PlanningMachine.Fact.FinalAttemptCreated -> FINAL_ATTEMPT_CREATED
        is PlanningMachine.Fact.FinalTransitioned -> when (input.mutation) {
            is FinalAttemptMutation.VerificationStarted, is FinalAttemptMutation.DeliveryStarted,
            is FinalAttemptMutation.DeliveryRequested -> FINAL_TURN_STARTED
            else -> FINAL_RECORDED
        }
        is PlanningMachine.Fact.StageCreated -> STAGE_CREATED
        is PlanningMachine.Fact.StageTransitioned -> when (input.mutation) {
            is StageEvent.WorkerStarting -> STAGE_WORKER_STARTING
            is StageEvent.VerificationDecided -> STAGE_VERIFICATION_DECIDED
            is StageEvent.Interrupted -> STAGE_INTERRUPTED
            else -> STAGE_RECORDED
        }
        is PlanningMachine.Fact.StageProgressObserved -> STAGE_PROGRESS_OBSERVED
        is PlanningMachine.Fact.AttemptRecorded -> ATTEMPT_RECORDED
        is PlanningMachine.Fact.FinalAttemptCleared -> FINAL_ATTEMPT_CLEARED
        is PlanningMachine.Fact.Applied -> APPLIED
        is PlanningMachine.Fact.StopConfirmed -> STOP_CONFIRMED
        is PlanningMachine.Fact.StopUnknown -> STOP_UNKNOWN
        is PlanningMachine.Fact.AcceptanceRechecked -> ACCEPTANCE_RECHECKED
        is PlanningMachine.Fact.MergeAcceptanceRecorded -> MERGE_ACCEPTANCE_RECORDED
        is PlanningMachine.Fact.ProjectionConfirmed -> PROJECTION_CONFIRMED
        is PlanningMachine.Fact.ProjectionFailed -> PROJECTION_FAILED
        is PlanningMachine.Fact.JournalObserved ->
            if (input.operation.kind == JournalEntryKind.INTENT) JOURNAL_INTENT_OBSERVED else JOURNAL_OUTCOME_OBSERVED
        is PlanningMachine.Fact.ScheduleAdvanced -> SCHEDULE_ADVANCED
        is PlanningMachine.Fact.ScheduleDelivered -> SCHEDULE_DELIVERED
        is PlanningMachine.Fact.ScheduleFailed -> SCHEDULE_FAILED
    }

    override fun name(effect: PlanningMachine.Effect): EffectId = when (effect) {
        is PlanningMachine.Effect.RunRequested -> EffectId("RunRequested")
        is PlanningMachine.Effect.StageDecision -> EffectId("StageDecision")
        is PlanningMachine.Effect.StopRequested -> EffectId("StopRequested")
        is PlanningMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: PlanningMachine.State) =
        state.persistenceUnknown || state.pendingOperations.isNotEmpty() || state.run?.phase == PlanningMachine.RunPhase.UNKNOWN

    override fun rejected(effect: PlanningMachine.Effect) = effect is PlanningMachine.Effect.Reject
}
