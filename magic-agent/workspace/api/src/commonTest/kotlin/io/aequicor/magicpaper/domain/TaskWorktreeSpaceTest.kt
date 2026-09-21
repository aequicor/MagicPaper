package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Fact
import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input.Intent
import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Operation
import io.aequicor.magicpaper.machine.verifyStateSpace
import kotlin.test.Test

/**
 * The representatives of [TaskWorktreeSpace], kept here rather than in the api so a shipped binary
 * carries no fixtures.
 *
 * Each one is built by running the machine from `initial`, never by constructing a state, which is
 * what the `internal constructor` on `State` is there to enforce. The operation that is *pending* in a
 * position is always `op`, so one representative per input fits every position that pends it; every
 * operation completed on the way there carries a `h-` id of its own, because an id already used is
 * refused.
 */
class TaskWorktreeSpaceTest {
    private val owner = TaskWorktreeOwnerId("project", "session")
    private fun record(task: String = "task") = TaskWorktree(task, "/source", "main", "base", "/copies/session", "task-branch")
    private fun step(state: TaskWorktreeMachine.State, vararg inputs: TaskWorktreeMachine.Input) =
        inputs.fold(state) { current, input -> TaskWorktreeMachine.reduce(current, input).state }
    private fun proof(kind: Operation) =
        TaskWorktreeProof("op", "task", kind, resultCommit = "result", targetCommit = "target", mergeCommit = "merged")

    private val uninitialized = TaskWorktreeMachine.initial(owner)
    private val empty = step(uninitialized, Fact.Imported(null, 1))
    private val opening = step(empty, Intent.Prepare(record(), 1, "op"))
    private val running = step(empty, Intent.Prepare(record(), 1, "h-open"), Fact.Opened("h-open"))
    private val refreshing = step(running, Intent.Refresh("task", 1, "op"))
    private val ready = step(running, Intent.Handoff("task", 1, true, listOf(listOf("test"))))
    private val capturing = step(ready, Intent.Capture("task", 1, "op"))
    private val merging = step(ready, Intent.Capture("task", 1, "h-capture"), Fact.Captured("h-capture", "result"))
    private val integrating = step(merging, Intent.Integrate("task", 1, "op", "target"))
    private val conflict = step(merging, Intent.Integrate("task", 1, "h-integrate", "target"), Fact.Integrated("h-integrate", null))
    private val merged = step(merging, Intent.Integrate("task", 1, "h-integrate", "target"), Fact.Integrated("h-integrate", "merged"))
    private val verifying = step(merged, Intent.Verify("task", 1, "op"))
    private val verified = step(merged, Intent.Verify("task", 1, "h-verify"), Fact.Verified("h-verify"))
    private val verificationFailed = step(merged, Intent.Verify("task", 1, "h-verify"), Fact.VerificationFailed("h-verify", "Check failed"))
    private val accepted = step(verified, Intent.AcceptMerge("task", 1, "merged"))
    private val delivering = step(accepted, Intent.Deliver("task", 1, "op"))
    private val complete = step(accepted, Intent.Deliver("task", 1, "h-deliver"), Fact.Delivered("h-deliver"))

    @Test fun declaredSpaceIsClosedAndMatchesEveryTransition() = verifyStateSpace(
        TaskWorktreeMachine,
        states = mapOf(
            TaskWorktreeSpace.UNINITIALIZED to uninitialized,
            TaskWorktreeSpace.EMPTY to empty,
            TaskWorktreeSpace.OPENING to opening,
            TaskWorktreeSpace.RUNNING to running,
            TaskWorktreeSpace.REFRESHING to refreshing,
            TaskWorktreeSpace.READY to ready,
            TaskWorktreeSpace.CAPTURING to capturing,
            TaskWorktreeSpace.MERGING to merging,
            TaskWorktreeSpace.INTEGRATING to integrating,
            TaskWorktreeSpace.CONFLICT to conflict,
            TaskWorktreeSpace.MERGED to merged,
            TaskWorktreeSpace.VERIFYING to verifying,
            TaskWorktreeSpace.VERIFIED to verified,
            TaskWorktreeSpace.VERIFICATION_FAILED to verificationFailed,
            TaskWorktreeSpace.ACCEPTED to accepted,
            TaskWorktreeSpace.DELIVERING to delivering,
            TaskWorktreeSpace.COMPLETE to complete,
            // A restart with a Git operation in flight: whether it ran, and to what end, was never observed.
            TaskWorktreeSpace.UNKNOWN_OPENING to step(opening, Fact.Restored),
            TaskWorktreeSpace.UNKNOWN_REFRESHING to step(refreshing, Fact.Restored),
            TaskWorktreeSpace.UNKNOWN_CAPTURING to step(capturing, Fact.Restored),
            TaskWorktreeSpace.UNKNOWN_INTEGRATING to step(integrating, Fact.Restored),
            TaskWorktreeSpace.UNKNOWN_VERIFYING to step(verifying, Fact.Restored),
            TaskWorktreeSpace.UNKNOWN_DELIVERING to step(delivering, Fact.Restored),
            // The task copy went missing with nothing in flight.
            TaskWorktreeSpace.UNKNOWN_NO_OPERATION to step(running, Fact.NeighbourMissing("task")),
            TaskWorktreeSpace.PERSISTENCE_UNKNOWN to step(running, Fact.PersistenceUnknown),
        ),
        inputs = mapOf(
            TaskWorktreeSpace.PREPARE to Intent.Prepare(record("new"), 2, "new-op"),
            TaskWorktreeSpace.PREPARE_REUSE to Intent.Prepare(record("new").copy(reuseBranch = "task-branch", reuseCommit = "merged"), 2, "new-op"),
            TaskWorktreeSpace.BIND_RUN to Intent.BindRun("task", 2),
            TaskWorktreeSpace.HANDOFF to Intent.Handoff("task", 1, true, listOf(listOf("test"))),
            TaskWorktreeSpace.REVOKE_HANDOFF to Intent.RevokeHandoff("task", 1),
            TaskWorktreeSpace.RETURN_FOR_REPAIR to Intent.ReturnForRepair("task", 1),
            TaskWorktreeSpace.RETRY_VERIFICATION to Intent.RetryVerification("task", 1),
            TaskWorktreeSpace.ATTACH_RESPONSE to Intent.AttachResponse("task", 1, CodingMessage("response", CodingRole.AGENT, "done", createdAt = 1)),
            TaskWorktreeSpace.REFRESH to Intent.Refresh("task", 1, "new-op"),
            TaskWorktreeSpace.CAPTURE to Intent.Capture("task", 1, "new-op"),
            TaskWorktreeSpace.CAPTURE_PLANNED to Intent.Capture("task", 1, "new-op", planAccepted = true),
            TaskWorktreeSpace.INTEGRATE to Intent.Integrate("task", 1, "new-op", "target"),
            TaskWorktreeSpace.INTEGRATE_PLANNED to Intent.Integrate("task", 1, "new-op", "target", planAccepted = true),
            TaskWorktreeSpace.VERIFY to Intent.Verify("task", 1, "new-op"),
            TaskWorktreeSpace.ACCEPT_MERGE to Intent.AcceptMerge("task", 1, "merged"),
            TaskWorktreeSpace.DELIVER to Intent.Deliver("task", 1, "new-op"),
            TaskWorktreeSpace.INSPECT to Intent.Inspect("task"),
            TaskWorktreeSpace.NOTE_FAILURE to Intent.NoteFailure("task", "Failure retained"),
            TaskWorktreeSpace.IMPORTED_EMPTY to Fact.Imported(null, 1),
            TaskWorktreeSpace.IMPORTED_RECORD to Fact.Imported(record().copy(phase = TaskWorktreePhase.RUNNING), 1),
            TaskWorktreeSpace.OPENED to Fact.Opened("op"),
            TaskWorktreeSpace.REFRESHED to Fact.Refreshed("op", 0, "target", true, null, false),
            TaskWorktreeSpace.CAPTURED to Fact.Captured("op", "result"),
            TaskWorktreeSpace.INTEGRATED_CLEAN to Fact.Integrated("op", "merged"),
            TaskWorktreeSpace.INTEGRATED_CONFLICT to Fact.Integrated("op", null),
            TaskWorktreeSpace.VERIFIED_FACT to Fact.Verified("op"),
            TaskWorktreeSpace.VERIFICATION_FAILED_FACT to Fact.VerificationFailed("op", "Check failed"),
            TaskWorktreeSpace.DELIVERED to Fact.Delivered("op"),
            TaskWorktreeSpace.FAILED_BEFORE_EFFECT to Fact.Failed("op", beforeEffect = true),
            TaskWorktreeSpace.FAILED_AFTER_EFFECT to Fact.Failed("op", beforeEffect = false),
            TaskWorktreeSpace.INSPECTED_OPEN to Fact.Inspected(proof(Operation.OPEN)),
            TaskWorktreeSpace.INSPECTED_REFRESH to Fact.Inspected(proof(Operation.REFRESH)),
            TaskWorktreeSpace.INSPECTED_CAPTURE to Fact.Inspected(proof(Operation.CAPTURE)),
            TaskWorktreeSpace.INSPECTED_INTEGRATE to Fact.Inspected(proof(Operation.INTEGRATE)),
            TaskWorktreeSpace.INSPECTED_VERIFY to Fact.Inspected(proof(Operation.VERIFY)),
            TaskWorktreeSpace.INSPECTED_DELIVER to Fact.Inspected(proof(Operation.DELIVER)),
            TaskWorktreeSpace.INSPECTION_UNKNOWN to Fact.InspectionUnknown("op"),
            TaskWorktreeSpace.RECOVERED_OPENED to Fact.OutcomeRecovered(Fact.Opened("op")),
            TaskWorktreeSpace.RECOVERED_REFRESHED to Fact.OutcomeRecovered(Fact.Refreshed("op", 0, "target", true, null, false)),
            TaskWorktreeSpace.RECOVERED_CAPTURED to Fact.OutcomeRecovered(Fact.Captured("op", "result")),
            TaskWorktreeSpace.RECOVERED_INTEGRATED to Fact.OutcomeRecovered(Fact.Integrated("op", "merged")),
            TaskWorktreeSpace.RECOVERED_VERIFIED to Fact.OutcomeRecovered(Fact.Verified("op")),
            TaskWorktreeSpace.RECOVERED_VERIFICATION_FAILED to Fact.OutcomeRecovered(Fact.VerificationFailed("op", "Check failed")),
            TaskWorktreeSpace.RECOVERED_DELIVERED to Fact.OutcomeRecovered(Fact.Delivered("op")),
            TaskWorktreeSpace.RECOVERED_OTHER to Fact.OutcomeRecovered(Fact.Failed("op", beforeEffect = false)),
            TaskWorktreeSpace.NEIGHBOUR_MISSING to Fact.NeighbourMissing("task"),
            TaskWorktreeSpace.RESTORED to Fact.Restored,
            TaskWorktreeSpace.PERSISTENCE_UNKNOWN_FACT to Fact.PersistenceUnknown,
        ),
    )
}
