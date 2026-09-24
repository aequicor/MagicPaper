package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [TaskWorktreeMachine], declared so it can be read without running anything.
 *
 * The machine has a [TaskWorktreeMachine.Stage] and the record's [TaskWorktreePhase], and neither is
 * the position. Three things the reducer gates on are spread across the state — whether an operation
 * is pending, whether its outcome is unknown, and, inside `MERGING`, how far the merge got — and
 * acceptance follows all three:
 *  - a live operation and an unknown one are different positions per kind of operation. The facts
 *    that complete an operation, the evidence that recovers it and the proof that inspects it are all
 *    refused unless they name *that* kind, so one position for "executing" would say that most of
 *    them are accepted nowhere;
 *  - `MERGING` is five positions, because integration, verification, acceptance and delivery each
 *    require the step before it: captured, merged, verified, accepted — and a failed verification
 *    blocks all of them until it is repaired or retried;
 *  - `CONFLICT` is not `MERGING`: it accepts a handoff, and integration only under an accepted plan.
 *
 * Guards that fence everything come first, as they do in the reducer. A persistence flag refuses
 * everything but a note, a neighbour report, a restore and its own fact; an uninitialized store
 * refuses everything but the import that initializes it.
 *
 * What the declaration cannot express, and leaves to `TaskWorktreeMachineTest`: a task id, generation
 * or operation id that is not the current one, an operation id already used, a record that fails
 * validation, a reuse branch or commit that does not match the finished record, and the payload of a
 * completion (a blank commit, a negative distance). Recovery is declared for the seven completion
 * facts; a proof of a *verification* is refused in every position, because a clean checkout does not
 * prove that arbitrary commands finished. An operation inspection found unapplied is forgotten only
 * for a refresh, a capture or an integration, each in its own unknown position: those resume from
 * whatever an interrupted attempt left, so the next continuation simply repeats them. `Prepare` and `PrepareReuse` differ only in the reuse fields
 * of the record; a finished record accepts only the second. `unknown-no-operation` is a neighbour that
 * went missing with nothing in flight: it recovers by inspection, and no recovery fact applies to it.
 * Nor does it express the text of a refusal, which is what the user reads: a `Capture` refused in
 * `running` says whether the agent blocked the task or ended its answer without handing off a result.
 */
object TaskWorktreeSpace : StateSpace<TaskWorktreeMachine.State, TaskWorktreeMachine.Input, TaskWorktreeMachine.Effect> {
    val UNINITIALIZED = PhaseId("uninitialized")
    val EMPTY = PhaseId("empty")
    val OPENING = PhaseId("opening")
    val RUNNING = PhaseId("running")
    val REFRESHING = PhaseId("refreshing")
    val READY = PhaseId("ready")
    val CAPTURING = PhaseId("capturing")
    val MERGING = PhaseId("merging")
    val INTEGRATING = PhaseId("integrating")
    val CONFLICT = PhaseId("conflict")
    val MERGED = PhaseId("merged")
    val VERIFYING = PhaseId("verifying")
    val VERIFIED = PhaseId("verified")
    val VERIFICATION_FAILED = PhaseId("verification-failed")
    val ACCEPTED = PhaseId("accepted")
    val DELIVERING = PhaseId("delivering")
    val COMPLETE = PhaseId("complete")
    val UNKNOWN_OPENING = PhaseId("unknown-opening")
    val UNKNOWN_REFRESHING = PhaseId("unknown-refreshing")
    val UNKNOWN_CAPTURING = PhaseId("unknown-capturing")
    val UNKNOWN_INTEGRATING = PhaseId("unknown-integrating")
    val UNKNOWN_VERIFYING = PhaseId("unknown-verifying")
    val UNKNOWN_DELIVERING = PhaseId("unknown-delivering")
    val UNKNOWN_NO_OPERATION = PhaseId("unknown-no-operation")
    val PERSISTENCE_UNKNOWN = PhaseId("persistence-unknown")

    val PREPARE = InputId("Prepare")
    val PREPARE_REUSE = InputId("PrepareReuse")
    val BIND_RUN = InputId("BindRun")
    val HANDOFF = InputId("Handoff")
    val REVOKE_HANDOFF = InputId("RevokeHandoff")
    val RETURN_FOR_REPAIR = InputId("ReturnForRepair")
    val RETRY_VERIFICATION = InputId("RetryVerification")
    val ATTACH_RESPONSE = InputId("AttachResponse")
    val REFRESH = InputId("Refresh")
    val CAPTURE = InputId("Capture")
    val CAPTURE_PLANNED = InputId("CapturePlanned")
    val INTEGRATE = InputId("Integrate")
    val INTEGRATE_PLANNED = InputId("IntegratePlanned")
    val VERIFY = InputId("Verify")
    val ACCEPT_MERGE = InputId("AcceptMerge")
    val DELIVER = InputId("Deliver")
    val INSPECT = InputId("Inspect")
    val NOTE_FAILURE = InputId("NoteFailure")
    val IMPORTED_EMPTY = InputId("ImportedEmpty")
    val IMPORTED_RECORD = InputId("ImportedRecord")
    val OPENED = InputId("Opened")
    val REFRESHED = InputId("Refreshed")
    val CAPTURED = InputId("Captured")
    val INTEGRATED_CLEAN = InputId("IntegratedClean")
    val INTEGRATED_CONFLICT = InputId("IntegratedConflict")
    val VERIFIED_FACT = InputId("Verified")
    val VERIFICATION_FAILED_FACT = InputId("VerificationFailed")
    val DELIVERED = InputId("Delivered")
    val FAILED_BEFORE_EFFECT = InputId("FailedBeforeEffect")
    val FAILED_AFTER_EFFECT = InputId("FailedAfterEffect")
    val INSPECTED_OPEN = InputId("InspectedOpen")
    val INSPECTED_REFRESH = InputId("InspectedRefresh")
    val INSPECTED_CAPTURE = InputId("InspectedCapture")
    val INSPECTED_INTEGRATE = InputId("InspectedIntegrate")
    val INSPECTED_VERIFY = InputId("InspectedVerify")
    val INSPECTED_DELIVER = InputId("InspectedDeliver")
    val INSPECTION_UNKNOWN = InputId("InspectionUnknown")
    val RECOVERED_OPENED = InputId("RecoveredOpened")
    val RECOVERED_REFRESHED = InputId("RecoveredRefreshed")
    val RECOVERED_CAPTURED = InputId("RecoveredCaptured")
    val RECOVERED_INTEGRATED = InputId("RecoveredIntegrated")
    val RECOVERED_VERIFIED = InputId("RecoveredVerified")
    val RECOVERED_VERIFICATION_FAILED = InputId("RecoveredVerificationFailed")
    val RECOVERED_DELIVERED = InputId("RecoveredDelivered")
    val RECOVERED_OTHER = InputId("RecoveredOther")
    val NEIGHBOUR_MISSING = InputId("NeighbourMissing")
    val RESTORED = InputId("Restored")
    val PERSISTENCE_UNKNOWN_FACT = InputId("PersistenceUnknown")
    val UNAPPLIED_REFRESH = InputId("UnappliedRefresh")
    val UNAPPLIED_CAPTURE = InputId("UnappliedCapture")
    val UNAPPLIED_INTEGRATE = InputId("UnappliedIntegrate")
    val UNAPPLIED_OTHER = InputId("UnappliedOther")

    override val phases = listOf(
        UNINITIALIZED, EMPTY, OPENING, RUNNING,
        REFRESHING, READY, CAPTURING, MERGING,
        INTEGRATING, CONFLICT, MERGED, VERIFYING,
        VERIFIED, VERIFICATION_FAILED, ACCEPTED, DELIVERING,
        COMPLETE, UNKNOWN_OPENING, UNKNOWN_REFRESHING, UNKNOWN_CAPTURING,
        UNKNOWN_INTEGRATING, UNKNOWN_VERIFYING, UNKNOWN_DELIVERING, UNKNOWN_NO_OPERATION,
        PERSISTENCE_UNKNOWN,
    )

    override val inputs = listOf(
        InputSpec(PREPARE, Branch.INTENT),
        InputSpec(PREPARE_REUSE, Branch.INTENT),
        InputSpec(BIND_RUN, Branch.INTENT),
        InputSpec(HANDOFF, Branch.INTENT),
        InputSpec(REVOKE_HANDOFF, Branch.INTENT),
        InputSpec(RETURN_FOR_REPAIR, Branch.INTENT),
        InputSpec(RETRY_VERIFICATION, Branch.INTENT),
        InputSpec(ATTACH_RESPONSE, Branch.INTENT),
        InputSpec(REFRESH, Branch.INTENT),
        InputSpec(CAPTURE, Branch.INTENT),
        InputSpec(CAPTURE_PLANNED, Branch.INTENT),
        InputSpec(INTEGRATE, Branch.INTENT),
        InputSpec(INTEGRATE_PLANNED, Branch.INTENT),
        InputSpec(VERIFY, Branch.INTENT),
        InputSpec(ACCEPT_MERGE, Branch.INTENT),
        InputSpec(DELIVER, Branch.INTENT),
        InputSpec(INSPECT, Branch.INTENT),
        InputSpec(NOTE_FAILURE, Branch.INTENT),
        InputSpec(IMPORTED_EMPTY, Branch.FACT),
        InputSpec(IMPORTED_RECORD, Branch.FACT),
        InputSpec(OPENED, Branch.FACT),
        InputSpec(REFRESHED, Branch.FACT),
        InputSpec(CAPTURED, Branch.FACT),
        InputSpec(INTEGRATED_CLEAN, Branch.FACT),
        InputSpec(INTEGRATED_CONFLICT, Branch.FACT),
        InputSpec(VERIFIED_FACT, Branch.FACT),
        InputSpec(VERIFICATION_FAILED_FACT, Branch.FACT),
        InputSpec(DELIVERED, Branch.FACT),
        InputSpec(FAILED_BEFORE_EFFECT, Branch.FACT),
        InputSpec(FAILED_AFTER_EFFECT, Branch.FACT),
        InputSpec(INSPECTED_OPEN, Branch.FACT),
        InputSpec(INSPECTED_REFRESH, Branch.FACT),
        InputSpec(INSPECTED_CAPTURE, Branch.FACT),
        InputSpec(INSPECTED_INTEGRATE, Branch.FACT),
        InputSpec(INSPECTED_VERIFY, Branch.FACT),
        InputSpec(INSPECTED_DELIVER, Branch.FACT),
        InputSpec(INSPECTION_UNKNOWN, Branch.FACT),
        InputSpec(RECOVERED_OPENED, Branch.FACT),
        InputSpec(RECOVERED_REFRESHED, Branch.FACT),
        InputSpec(RECOVERED_CAPTURED, Branch.FACT),
        InputSpec(RECOVERED_INTEGRATED, Branch.FACT),
        InputSpec(RECOVERED_VERIFIED, Branch.FACT),
        InputSpec(RECOVERED_VERIFICATION_FAILED, Branch.FACT),
        InputSpec(RECOVERED_DELIVERED, Branch.FACT),
        InputSpec(RECOVERED_OTHER, Branch.FACT),
        InputSpec(NEIGHBOUR_MISSING, Branch.FACT),
        InputSpec(RESTORED, Branch.FACT),
        InputSpec(PERSISTENCE_UNKNOWN_FACT, Branch.FACT),
        InputSpec(UNAPPLIED_REFRESH, Branch.FACT),
        InputSpec(UNAPPLIED_CAPTURE, Branch.FACT),
        InputSpec(UNAPPLIED_INTEGRATE, Branch.FACT),
        InputSpec(UNAPPLIED_OTHER, Branch.FACT),
    )

    override val effects = listOf(EffectId("Execute"), EffectId("Inspect"), EffectId("Reject"))

    // Rows follow `phases`, columns follow `inputs` in the order they are declared above:
    //    0  Prepare
    //    1  PrepareReuse
    //    2  BindRun
    //    3  Handoff
    //    4  RevokeHandoff
    //    5  ReturnForRepair
    //    6  RetryVerification
    //    7  AttachResponse
    //    8  Refresh
    //    9  Capture
    //   10  CapturePlanned
    //   11  Integrate
    //   12  IntegratePlanned
    //   13  Verify
    //   14  AcceptMerge
    //   15  Deliver
    //   16  Inspect
    //   17  NoteFailure
    //   18  ImportedEmpty
    //   19  ImportedRecord
    //   20  Opened
    //   21  Refreshed
    //   22  Captured
    //   23  IntegratedClean
    //   24  IntegratedConflict
    //   25  Verified
    //   26  VerificationFailed
    //   27  Delivered
    //   28  FailedBeforeEffect
    //   29  FailedAfterEffect
    //   30  InspectedOpen
    //   31  InspectedRefresh
    //   32  InspectedCapture
    //   33  InspectedIntegrate
    //   34  InspectedVerify
    //   35  InspectedDeliver
    //   36  InspectionUnknown
    //   37  RecoveredOpened
    //   38  RecoveredRefreshed
    //   39  RecoveredCaptured
    //   40  RecoveredIntegrated
    //   41  RecoveredVerified
    //   42  RecoveredVerificationFailed
    //   43  RecoveredDelivered
    //   44  RecoveredOther
    //   45  NeighbourMissing
    //   46  Restored
    //   47  PersistenceUnknown
    //   48  UnappliedRefresh
    //   49  UnappliedCapture
    //   50  UnappliedIntegrate
    //   51  UnappliedOther
    override val accepts = acceptance(phases, inputs, listOf(
        /* uninitialized          */ "0000000000000000001100000000000000000000000000110000",
        /* empty                  */ "1100000000000000000000000000000000000000000000110000",
        /* opening                */ "0000000000000000010010000000110000001000000001110000",
        /* running                */ "0011100110100000110000000000000000000000000001110000",
        /* refreshing             */ "0000000000000000010001000000110000001000000001110000",
        /* ready                  */ "0011100111100000110000000000000000000000000001110000",
        /* capturing              */ "0000000000000000010000100000110000001000000001110000",
        /* merging                */ "0010000100011000110000000000000000000000000001110000",
        /* integrating            */ "0000000000000000010000011000110000001000000001110000",
        /* conflict               */ "0011100100101000110000000000000000000000000001110000",
        /* merged                 */ "0010000100011100110000000000000000000000000001110000",
        /* verifying              */ "0000000000000000010000000110110000001000000001110000",
        /* verified               */ "0010000100011110110000000000000000000000000001110000",
        /* verification-failed    */ "0010011100000000110000000000000000000000000001110000",
        /* accepted               */ "0010000100011111110000000000000000000000000001110000",
        /* delivering             */ "0000000000000000010000000001110000001000000001110000",
        /* complete               */ "0110000000000000110000000000000000000000000001110000",
        /* unknown-opening        */ "0000000000000000110000000000001000001100000001110000",
        /* unknown-refreshing     */ "0000000000000000110000000000000100001010000001111000",
        /* unknown-capturing      */ "0000000000000000110000000000000010001001000001110100",
        /* unknown-integrating    */ "0000000000000000110000000000000001001000100001110010",
        /* unknown-verifying      */ "0000000000000000110000000000000000001000011001110000",
        /* unknown-delivering     */ "0000000000000000110000000000000000011000000101110000",
        /* unknown-no-operation   */ "0000000000000000110000000000000000000000000001110000",
        /* persistence-unknown    */ "0000000000000000010000000000000000000000000001110000",
    ))

    private fun executing(kind: TaskWorktreeMachine.Operation) = when (kind) {
        TaskWorktreeMachine.Operation.OPEN -> OPENING
        TaskWorktreeMachine.Operation.REFRESH -> REFRESHING
        TaskWorktreeMachine.Operation.CAPTURE -> CAPTURING
        TaskWorktreeMachine.Operation.INTEGRATE -> INTEGRATING
        TaskWorktreeMachine.Operation.VERIFY -> VERIFYING
        TaskWorktreeMachine.Operation.DELIVER -> DELIVERING
    }

    private fun unknownDuring(kind: TaskWorktreeMachine.Operation) = when (kind) {
        TaskWorktreeMachine.Operation.OPEN -> UNKNOWN_OPENING
        TaskWorktreeMachine.Operation.REFRESH -> UNKNOWN_REFRESHING
        TaskWorktreeMachine.Operation.CAPTURE -> UNKNOWN_CAPTURING
        TaskWorktreeMachine.Operation.INTEGRATE -> UNKNOWN_INTEGRATING
        TaskWorktreeMachine.Operation.VERIFY -> UNKNOWN_VERIFYING
        TaskWorktreeMachine.Operation.DELIVER -> UNKNOWN_DELIVERING
    }

    /** How far a merge got: each step needs the one before it, and a failed verification blocks all. */
    private fun merging(state: TaskWorktreeMachine.State, record: TaskWorktree): PhaseId {
        val merged = record.mergeCommit.isNotBlank()
        return when {
            state.verificationFailed -> VERIFICATION_FAILED
            merged && state.verifiedCommit == record.mergeCommit && state.acceptedCommit == record.mergeCommit -> ACCEPTED
            merged && state.verifiedCommit == record.mergeCommit -> VERIFIED
            merged -> MERGED
            else -> MERGING
        }
    }

    override fun label(state: TaskWorktreeMachine.State): PhaseId? {
        val pending = state.pending
        val record = state.record
        return when {
            // The same order as the reducer's gates: an import can still initialize a store that a
            // persistence flag has already fenced, so uninitialized comes before the flag.
            !state.initialized -> UNINITIALIZED
            state.persistenceUnknown -> PERSISTENCE_UNKNOWN
            state.unknown -> if (pending == null) UNKNOWN_NO_OPERATION else unknownDuring(pending.kind)
            pending != null -> executing(pending.kind)
            record == null -> EMPTY
            else -> when (record.phase) {
                TaskWorktreePhase.RUNNING -> RUNNING
                TaskWorktreePhase.READY -> READY
                TaskWorktreePhase.CONFLICT -> CONFLICT
                TaskWorktreePhase.COMPLETE -> COMPLETE
                TaskWorktreePhase.MERGING -> merging(state, record)
                // These three exist only while their operation is pending, which is named above.
                TaskWorktreePhase.PREPARING, TaskWorktreePhase.CAPTURING, TaskWorktreePhase.DELIVERING -> null
            }
        }
    }

    override fun name(input: TaskWorktreeMachine.Input): InputId = when (input) {
        is TaskWorktreeMachine.Input.Intent.Prepare ->
            // The reuse fields are what lets a finished record be followed by another task.
            if (input.record.reuseBranch.isNotBlank() || input.record.reuseCommit.isNotBlank()) PREPARE_REUSE else PREPARE
        is TaskWorktreeMachine.Input.Intent.BindRun -> BIND_RUN
        is TaskWorktreeMachine.Input.Intent.Handoff -> HANDOFF
        is TaskWorktreeMachine.Input.Intent.RevokeHandoff -> REVOKE_HANDOFF
        is TaskWorktreeMachine.Input.Intent.ReturnForRepair -> RETURN_FOR_REPAIR
        is TaskWorktreeMachine.Input.Intent.RetryVerification -> RETRY_VERIFICATION
        is TaskWorktreeMachine.Input.Intent.AttachResponse -> ATTACH_RESPONSE
        is TaskWorktreeMachine.Input.Intent.Refresh -> REFRESH
        // An accepted plan lets a capture or an integration go ahead without a fresh handoff.
        is TaskWorktreeMachine.Input.Intent.Capture -> if (input.planAccepted) CAPTURE_PLANNED else CAPTURE
        is TaskWorktreeMachine.Input.Intent.Integrate -> if (input.planAccepted) INTEGRATE_PLANNED else INTEGRATE
        is TaskWorktreeMachine.Input.Intent.Verify -> VERIFY
        is TaskWorktreeMachine.Input.Intent.AcceptMerge -> ACCEPT_MERGE
        is TaskWorktreeMachine.Input.Intent.Deliver -> DELIVER
        is TaskWorktreeMachine.Input.Intent.Inspect -> INSPECT
        is TaskWorktreeMachine.Input.Intent.NoteFailure -> NOTE_FAILURE
        is TaskWorktreeMachine.Input.Fact.Imported -> if (input.record == null) IMPORTED_EMPTY else IMPORTED_RECORD
        is TaskWorktreeMachine.Input.Fact.Opened -> OPENED
        is TaskWorktreeMachine.Input.Fact.Refreshed -> REFRESHED
        is TaskWorktreeMachine.Input.Fact.Captured -> CAPTURED
        // One fact, two inputs: no merge commit means the merge stopped on a conflict.
        is TaskWorktreeMachine.Input.Fact.Integrated -> if (input.commit == null) INTEGRATED_CONFLICT else INTEGRATED_CLEAN
        is TaskWorktreeMachine.Input.Fact.Verified -> VERIFIED_FACT
        is TaskWorktreeMachine.Input.Fact.VerificationFailed -> VERIFICATION_FAILED_FACT
        is TaskWorktreeMachine.Input.Fact.Delivered -> DELIVERED
        // Only a failure after the effect was dispatched can leave the operation's outcome unknown.
        is TaskWorktreeMachine.Input.Fact.Failed -> if (input.beforeEffect) FAILED_BEFORE_EFFECT else FAILED_AFTER_EFFECT
        is TaskWorktreeMachine.Input.Fact.Inspected -> when (input.proof.kind) {
            TaskWorktreeMachine.Operation.OPEN -> INSPECTED_OPEN
            TaskWorktreeMachine.Operation.REFRESH -> INSPECTED_REFRESH
            TaskWorktreeMachine.Operation.CAPTURE -> INSPECTED_CAPTURE
            TaskWorktreeMachine.Operation.INTEGRATE -> INSPECTED_INTEGRATE
            TaskWorktreeMachine.Operation.VERIFY -> INSPECTED_VERIFY
            TaskWorktreeMachine.Operation.DELIVER -> INSPECTED_DELIVER
        }
        is TaskWorktreeMachine.Input.Fact.InspectionUnknown -> INSPECTION_UNKNOWN
        // A recovered outcome is one of the seven completion facts; anything else is one refused input.
        is TaskWorktreeMachine.Input.Fact.OutcomeRecovered -> when (input.outcome) {
            is TaskWorktreeMachine.Input.Fact.Opened -> RECOVERED_OPENED
            is TaskWorktreeMachine.Input.Fact.Refreshed -> RECOVERED_REFRESHED
            is TaskWorktreeMachine.Input.Fact.Captured -> RECOVERED_CAPTURED
            is TaskWorktreeMachine.Input.Fact.Integrated -> RECOVERED_INTEGRATED
            is TaskWorktreeMachine.Input.Fact.Verified -> RECOVERED_VERIFIED
            is TaskWorktreeMachine.Input.Fact.VerificationFailed -> RECOVERED_VERIFICATION_FAILED
            is TaskWorktreeMachine.Input.Fact.Delivered -> RECOVERED_DELIVERED
            is TaskWorktreeMachine.Input.Fact.Imported, is TaskWorktreeMachine.Input.Fact.Failed,
            is TaskWorktreeMachine.Input.Fact.Inspected, is TaskWorktreeMachine.Input.Fact.InspectionUnknown,
            is TaskWorktreeMachine.Input.Fact.InspectedUnapplied,
            is TaskWorktreeMachine.Input.Fact.OutcomeRecovered, is TaskWorktreeMachine.Input.Fact.NeighbourMissing,
            TaskWorktreeMachine.Input.Fact.Restored, TaskWorktreeMachine.Input.Fact.PersistenceUnknown -> RECOVERED_OTHER
        }
        is TaskWorktreeMachine.Input.Fact.NeighbourMissing -> NEIGHBOUR_MISSING
        TaskWorktreeMachine.Input.Fact.Restored -> RESTORED
        TaskWorktreeMachine.Input.Fact.PersistenceUnknown -> PERSISTENCE_UNKNOWN_FACT
        // Only an operation that resumes from its own partial state can be forgotten; the rest are refused everywhere.
        is TaskWorktreeMachine.Input.Fact.InspectedUnapplied -> when (input.kind) {
            TaskWorktreeMachine.Operation.REFRESH -> UNAPPLIED_REFRESH
            TaskWorktreeMachine.Operation.CAPTURE -> UNAPPLIED_CAPTURE
            TaskWorktreeMachine.Operation.INTEGRATE -> UNAPPLIED_INTEGRATE
            TaskWorktreeMachine.Operation.OPEN, TaskWorktreeMachine.Operation.VERIFY, TaskWorktreeMachine.Operation.DELIVER -> UNAPPLIED_OTHER
        }
    }

    override fun name(effect: TaskWorktreeMachine.Effect): EffectId = when (effect) {
        is TaskWorktreeMachine.Effect.Execute -> EffectId("Execute")
        is TaskWorktreeMachine.Effect.Inspect -> EffectId("Inspect")
        is TaskWorktreeMachine.Effect.Reject -> EffectId("Reject")
    }

    override fun unknown(state: TaskWorktreeMachine.State) = state.stage == TaskWorktreeMachine.Stage.UNKNOWN

    override fun rejected(effect: TaskWorktreeMachine.Effect) = effect is TaskWorktreeMachine.Effect.Reject
}
