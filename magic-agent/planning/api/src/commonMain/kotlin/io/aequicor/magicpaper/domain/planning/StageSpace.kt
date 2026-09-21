package io.aequicor.magicpaper.domain.planning

import io.aequicor.magicpaper.domain.AttemptPhase
import io.aequicor.magicpaper.machine.Branch
import io.aequicor.magicpaper.machine.EffectId
import io.aequicor.magicpaper.machine.InputId
import io.aequicor.magicpaper.machine.InputSpec
import io.aequicor.magicpaper.machine.PhaseId
import io.aequicor.magicpaper.machine.StateSpace
import io.aequicor.magicpaper.machine.acceptance

/**
 * The state space of [StageMachine], declared so it can be read without running anything.
 *
 * These rules are unlike the other owners in one way that decides the shape of the declaration: they
 * do not guard. An event is applied to whatever attempt it meets, and what differs between attempts is
 * which effects come out and where the attempt lands, both of which the harness derives from the
 * reducer. So the matrix is nearly all ones. It has exactly one refusal: `WorkerTurnEnded` needs a turn
 * to end, and an attempt still in `prepared` has none. The other, `WorkerAdmitted` changing the
 * identity of the attempt, is a refusal of identity, and stays with `StageMachineTest`.
 *
 * A position is read from the attempt in the order `StageResumption` reads it, because that order is
 * the reducer's own account of what picking an attempt up means: an unconfirmed external command
 * outranks everything, then a stop, then the phase.
 *
 * What the declaration cannot express, and leaves to `StageMachineTest`: the many flags an attempt
 * carries beside its phase — whether it waits for a user or an event, hands off to a planner, holds an
 * acceptance record, how far a merge got, how many retries it has used — which change the effects an
 * event produces, not whether it is accepted; the identity and payload of each event; and
 * `unknown(state)` is narrower than an unresolved outcome in general, since a tool that is running and
 * has not been interrupted is labelled by its phase and only becomes unknown when the attempt is
 * picked up. `Reconciled` reports that same uncertainty as an effect without changing the position.
 *
 * `StageEvent` has no intent/fact split in its type, and its serialized form must not change, so the
 * branch is declared here. An input is an intent when it asks what to run next (`InspectPreparation`,
 * `Inspect`, `TurnRequested`) or records that a step is about to be dispatched before its result exists
 * (`WorkerStarting`, `ConflictRequested`, `MergeStarted`); everything else reports something that has
 * already happened.
 */
object StageSpace : StateSpace<StageState, StageEvent, StageMachine.Effect> {
    val PREPARED = PhaseId("prepared")
    val EXECUTING = PhaseId("executing")
    val FAILED = PhaseId("failed")
    val VERIFYING = PhaseId("verifying")
    val INTEGRATING = PhaseId("integrating")
    val COMPLETE = PhaseId("complete")
    val INTERRUPTED = PhaseId("interrupted")
    val UNCONFIRMED_TOOL = PhaseId("unconfirmed-tool")

    val INSPECT_PREPARATION = InputId("InspectPreparation")
    val INSPECT = InputId("Inspect")
    val TURN_REQUESTED = InputId("TurnRequested")
    val WORKER_STARTING = InputId("WorkerStarting")
    val CONFLICT_REQUESTED = InputId("ConflictRequested")
    val MERGE_STARTED = InputId("MergeStarted")
    val ENGINE_RESOLVED = InputId("EngineResolved")
    val WORKSPACE_PREPARED = InputId("WorkspacePrepared")
    val ENGINE_OUTPUT = InputId("EngineOutput")
    val RECONCILED = InputId("Reconciled")
    val CHECKPOINT_OBSERVED = InputId("CheckpointObserved")
    val WORKER_ADMITTED = InputId("WorkerAdmitted")
    val WORKER_TURN_ENDED = InputId("WorkerTurnEnded")
    val WORKER_ACCEPTED = InputId("WorkerAccepted")
    val PLANNER_DECIDED = InputId("PlannerDecided")
    val USER_ANSWERED = InputId("UserAnswered")
    val EVENT_FIRED = InputId("EventFired")
    val ACCEPTANCE_RECORDED = InputId("AcceptanceRecorded")
    val VERIFICATION_DECIDED = InputId("VerificationDecided")
    val CAPTURED = InputId("Captured")
    val CONFLICT_STARTED = InputId("ConflictStarted")
    val CONFLICT_TURN_ENDED = InputId("ConflictTurnEnded")
    val MERGE_FINISHED = InputId("MergeFinished")
    val COMPLETED = InputId("Completed")
    val TRANSPORT_FAILED = InputId("TransportFailed")
    val INTERRUPTED_CLEAN = InputId("InterruptedClean")
    val INTERRUPTED_DURING_TOOL = InputId("InterruptedDuringTool")

    override val phases = listOf(PREPARED, EXECUTING, FAILED, VERIFYING, INTEGRATING, COMPLETE, INTERRUPTED, UNCONFIRMED_TOOL)

    override val inputs = listOf(
        InputSpec(INSPECT_PREPARATION, Branch.INTENT),
        InputSpec(INSPECT, Branch.INTENT),
        InputSpec(TURN_REQUESTED, Branch.INTENT),
        InputSpec(WORKER_STARTING, Branch.INTENT),
        InputSpec(CONFLICT_REQUESTED, Branch.INTENT),
        InputSpec(MERGE_STARTED, Branch.INTENT),
        InputSpec(ENGINE_RESOLVED, Branch.FACT),
        InputSpec(WORKSPACE_PREPARED, Branch.FACT),
        InputSpec(ENGINE_OUTPUT, Branch.FACT),
        InputSpec(RECONCILED, Branch.FACT),
        InputSpec(CHECKPOINT_OBSERVED, Branch.FACT),
        InputSpec(WORKER_ADMITTED, Branch.FACT),
        InputSpec(WORKER_TURN_ENDED, Branch.FACT),
        InputSpec(WORKER_ACCEPTED, Branch.FACT),
        InputSpec(PLANNER_DECIDED, Branch.FACT),
        InputSpec(USER_ANSWERED, Branch.FACT),
        InputSpec(EVENT_FIRED, Branch.FACT),
        InputSpec(ACCEPTANCE_RECORDED, Branch.FACT),
        InputSpec(VERIFICATION_DECIDED, Branch.FACT),
        InputSpec(CAPTURED, Branch.FACT),
        InputSpec(CONFLICT_STARTED, Branch.FACT),
        InputSpec(CONFLICT_TURN_ENDED, Branch.FACT),
        InputSpec(MERGE_FINISHED, Branch.FACT),
        InputSpec(COMPLETED, Branch.FACT),
        InputSpec(TRANSPORT_FAILED, Branch.FACT),
        InputSpec(INTERRUPTED_CLEAN, Branch.FACT),
        InputSpec(INTERRUPTED_DURING_TOOL, Branch.FACT),
    )

    override val effects = listOf(
        EffectId("ResolveEngine"), EffectId("PrepareWorkspace"), EffectId("Persist"), EffectId("RunWorker"),
        EffectId("PrepareWorker"), EffectId("Coordinate"), EffectId("RunVerifier"), EffectId("Capture"),
        EffectId("RunMerge"), EffectId("RunConflictAgent"), EffectId("RunMergeVerifier"), EffectId("AskUser"),
        EffectId("WaitForEvent"), EffectId("Finish"), EffectId("Yield"), EffectId("RecordVerification"),
        EffectId("Block"), EffectId("Delay"), EffectId("Reject"),
    )

    // Rows follow `phases`, columns follow `inputs`. Every event is taken by every position, save one:
    // `WorkerTurnEnded` from `prepared`, where no turn has started.
    private val everything = "1".repeat(27)
    private val withoutATurn = "1".repeat(12) + "0" + "1".repeat(14)

    override val accepts = acceptance(phases, inputs, listOf(
        /* prepared         */ withoutATurn,
        /* executing        */ everything,
        /* failed           */ everything,
        /* verifying        */ everything,
        /* integrating      */ everything,
        /* complete         */ everything,
        /* interrupted      */ everything,
        /* unconfirmed-tool */ everything,
    ))

    override fun label(state: StageState): PhaseId {
        val attempt = state.attempt
        return when {
            // A command that ran outside and was cut off with no confirmed result: resuming would repeat it.
            attempt.interrupted && attempt.pendingToolExternal && attempt.pendingTool.isNotBlank() -> UNCONFIRMED_TOOL
            attempt.interrupted -> INTERRUPTED
            else -> when (attempt.phase) {
                AttemptPhase.PREPARED -> PREPARED
                AttemptPhase.EXECUTING -> EXECUTING
                AttemptPhase.FAILED -> FAILED
                AttemptPhase.VERIFYING -> VERIFYING
                AttemptPhase.INTEGRATING -> INTEGRATING
                AttemptPhase.COMPLETE -> COMPLETE
            }
        }
    }

    override fun name(input: StageEvent): InputId = when (input) {
        StageEvent.InspectPreparation -> INSPECT_PREPARATION
        StageEvent.Inspect -> INSPECT
        is StageEvent.TurnRequested -> TURN_REQUESTED
        is StageEvent.WorkerStarting -> WORKER_STARTING
        is StageEvent.ConflictRequested -> CONFLICT_REQUESTED
        is StageEvent.MergeStarted -> MERGE_STARTED
        is StageEvent.EngineResolved -> ENGINE_RESOLVED
        is StageEvent.WorkspacePrepared -> WORKSPACE_PREPARED
        is StageEvent.EngineOutput -> ENGINE_OUTPUT
        is StageEvent.Reconciled -> RECONCILED
        is StageEvent.CheckpointObserved -> CHECKPOINT_OBSERVED
        is StageEvent.WorkerAdmitted -> WORKER_ADMITTED
        is StageEvent.WorkerTurnEnded -> WORKER_TURN_ENDED
        is StageEvent.WorkerAccepted -> WORKER_ACCEPTED
        is StageEvent.PlannerDecided -> PLANNER_DECIDED
        StageEvent.UserAnswered -> USER_ANSWERED
        StageEvent.EventFired -> EVENT_FIRED
        is StageEvent.AcceptanceRecorded -> ACCEPTANCE_RECORDED
        is StageEvent.VerificationDecided -> VERIFICATION_DECIDED
        is StageEvent.Captured -> CAPTURED
        StageEvent.ConflictStarted -> CONFLICT_STARTED
        StageEvent.ConflictTurnEnded -> CONFLICT_TURN_ENDED
        is StageEvent.MergeFinished -> MERGE_FINISHED
        StageEvent.Completed -> COMPLETED
        is StageEvent.TransportFailed -> TRANSPORT_FAILED
        // One event, two inputs: an interruption that caught an external command in flight leaves an
        // outcome nobody observed, and is the only way to reach `unconfirmed-tool`.
        is StageEvent.Interrupted ->
            if (input.live.pendingToolExternal && input.live.pendingTool.isNotBlank()) INTERRUPTED_DURING_TOOL else INTERRUPTED_CLEAN
    }

    override fun name(effect: StageMachine.Effect): EffectId = when (effect) {
        is StageMachine.Effect.Reject -> EffectId("Reject")
        is StageMachine.Effect.Emit -> when (effect.effect) {
            StageEffect.ResolveEngine -> EffectId("ResolveEngine")
            StageEffect.PrepareWorkspace -> EffectId("PrepareWorkspace")
            StageEffect.Persist -> EffectId("Persist")
            StageEffect.RunWorker -> EffectId("RunWorker")
            StageEffect.PrepareWorker -> EffectId("PrepareWorker")
            StageEffect.Coordinate -> EffectId("Coordinate")
            StageEffect.RunVerifier -> EffectId("RunVerifier")
            StageEffect.Capture -> EffectId("Capture")
            StageEffect.RunMerge -> EffectId("RunMerge")
            StageEffect.RunConflictAgent -> EffectId("RunConflictAgent")
            StageEffect.RunMergeVerifier -> EffectId("RunMergeVerifier")
            StageEffect.AskUser -> EffectId("AskUser")
            StageEffect.WaitForEvent -> EffectId("WaitForEvent")
            StageEffect.Finish -> EffectId("Finish")
            StageEffect.Yield -> EffectId("Yield")
            StageEffect.RecordVerification -> EffectId("RecordVerification")
            is StageEffect.Block -> EffectId("Block")
            is StageEffect.Delay -> EffectId("Delay")
        }
    }

    // Written from the attempt rather than through `label`, so that a fault in one cannot hide in the other.
    override fun unknown(state: StageState) = state.attempt.let {
        it.interrupted && it.pendingToolExternal && it.pendingTool.isNotBlank()
    }

    override fun rejected(effect: StageMachine.Effect) = effect is StageMachine.Effect.Reject
}
