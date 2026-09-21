package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Input
import io.aequicor.magicpaper.domain.TaskWorktreeMachine.Operation
import kotlin.test.*

class TaskWorktreeMachineTest {
    private val owner = TaskWorktreeOwnerId("project", "session")
    private fun empty() = next(TaskWorktreeMachine.initial(owner), Input.Fact.Imported(null, 1))
    private fun record(task: String = "task") = TaskWorktree(task, "/source", "main", "base", "/copies/session", "task-branch")
    private fun next(state: TaskWorktreeMachine.State, input: Input): TaskWorktreeMachine.State {
        val transition = TaskWorktreeMachine.reduce(state, input)
        assertFalse(transition.effects.any { it is TaskWorktreeMachine.Effect.Reject }, "$input rejected: ${transition.effects}")
        return transition.state
    }
    private fun running(): TaskWorktreeMachine.State = next(next(empty(), Input.Intent.Prepare(record(), 1, "open")), Input.Fact.Opened("open"))
    private fun ready() = next(running(), Input.Intent.Handoff("task", 1, true, listOf(listOf("test"))))
    private fun merging(): TaskWorktreeMachine.State = next(next(ready(), Input.Intent.Capture("task", 1, "capture")), Input.Fact.Captured("capture", "result"))
    private fun merged(): TaskWorktreeMachine.State = next(next(merging(), Input.Intent.Integrate("task", 1, "integrate", "target")), Input.Fact.Integrated("integrate", "merged"))
    private fun verified(): TaskWorktreeMachine.State = next(next(merged(), Input.Intent.Verify("task", 1, "verify")), Input.Fact.Verified("verify"))
    private fun accepted() = next(verified(), Input.Intent.AcceptMerge("task", 1, "merged"))
    private fun reject(state: TaskWorktreeMachine.State, input: Input, reason: TaskWorktreeMachine.Reason) {
        val transition = TaskWorktreeMachine.reduce(state, input)
        assertEquals(state, transition.state)
        assertEquals(listOf(TaskWorktreeMachine.Effect.Reject(reason)), transition.effects)
    }

    @Test fun deliveryRequiresCurrentHandoffCaptureIntegrationAndBothChecks() {
        reject(running(), Input.Intent.Capture("task", 1, "capture"), TaskWorktreeMachine.Reason.NOT_READY)
        reject(ready(), Input.Intent.Capture("task", 0, "capture"), TaskWorktreeMachine.Reason.STALE)
        reject(merged(), Input.Intent.Deliver("task", 1, "deliver"), TaskWorktreeMachine.Reason.NOT_READY)
        reject(verified(), Input.Intent.Deliver("task", 1, "deliver"), TaskWorktreeMachine.Reason.NOT_READY)
        val complete = next(next(accepted(), Input.Intent.Deliver("task", 1, "deliver")), Input.Fact.Delivered("deliver"))
        assertEquals(TaskWorktreeMachine.Stage.COMPLETE, complete.stage)
        assertEquals(setOf("open", "capture", "integrate", "verify", "deliver"), complete.completedOperations)
    }

    @Test fun cartesianStateAndIntentTableKeepsUnknownAndBusyAheadOfAdmission() {
        val opening = next(empty(), Input.Intent.Prepare(record(), 1, "open"))
        val unknown = next(opening, Input.Fact.Restored)
        val complete = next(next(accepted(), Input.Intent.Deliver("task", 1, "deliver")), Input.Fact.Delivered("deliver"))
        val states = listOf(empty(), opening, running(), ready(), merging(), merged(), verified(), accepted(), complete, unknown,
            next(complete, Input.Fact.PersistenceUnknown))
        val intents = listOf(
            Input.Intent.Prepare(record("new"), 2, "new-open"), Input.Intent.BindRun("task", 2),
            Input.Intent.Handoff("task", 1, true, emptyList()), Input.Intent.RevokeHandoff("task", 1),
            Input.Intent.Refresh("task", 1, "new-refresh"), Input.Intent.Capture("task", 1, "new-capture"),
            Input.Intent.Integrate("task", 1, "new-integrate", "target"), Input.Intent.Verify("task", 1, "new-verify"),
            Input.Intent.AcceptMerge("task", 1, "merged"), Input.Intent.Deliver("task", 1, "new-deliver"),
            Input.Intent.ReturnForRepair("task", 1), Input.Intent.RetryVerification("task", 1),
            Input.Intent.AttachResponse("task", 1, CodingMessage("response", CodingRole.AGENT, "done", createdAt = 1)),
            Input.Intent.Inspect("task"), Input.Intent.NoteFailure("task", "Failure retained"),
        )
        val allowed = listOf(
            setOf(0), setOf(14), setOf(1, 2, 3, 4, 12, 13, 14), setOf(1, 2, 3, 4, 5, 12, 13, 14),
            setOf(1, 6, 12, 13, 14), setOf(1, 6, 7, 12, 13, 14), setOf(1, 6, 7, 8, 12, 13, 14), setOf(1, 6, 7, 8, 9, 12, 13, 14),
            setOf(1, 13, 14), setOf(13, 14), setOf(14),
        )
        states.forEachIndexed { stateIndex, state -> intents.forEachIndexed { inputIndex, input ->
            val transition = TaskWorktreeMachine.reduce(state, input)
            val rejection = transition.effects.filterIsInstance<TaskWorktreeMachine.Effect.Reject>().singleOrNull()
            assertEquals(inputIndex !in allowed[stateIndex], rejection != null, "state=$stateIndex input=$inputIndex $input")
            if (state.stage == TaskWorktreeMachine.Stage.UNKNOWN && rejection != null) assertEquals(TaskWorktreeMachine.Reason.UNKNOWN, rejection.reason)
            if (rejection != null) assertEquals(state, transition.state)
        } }
    }

    @Test fun everyOperationAcceptsOnlyItsOwnTerminalFactsAndUnknownRequiresRecoveryEvidence() {
        val states = listOf(
            next(empty(), Input.Intent.Prepare(record(), 1, "op")),
            next(running(), Input.Intent.Refresh("task", 1, "op")),
            next(ready(), Input.Intent.Capture("task", 1, "op")),
            next(merging(), Input.Intent.Integrate("task", 1, "op", "target")),
            next(merged(), Input.Intent.Verify("task", 1, "op")),
            next(accepted(), Input.Intent.Deliver("task", 1, "op")),
        )
        fun facts(id: String) = listOf(
            Input.Fact.Opened(id), Input.Fact.Refreshed(id, 0, "target", true, null, false),
            Input.Fact.Captured(id, "result"), Input.Fact.Integrated(id, "merged"),
            Input.Fact.Verified(id), Input.Fact.VerificationFailed(id, "Check failed"), Input.Fact.Delivered(id),
            Input.Fact.Failed(id, beforeEffect = true), Input.Fact.Failed(id, beforeEffect = false),
        )
        val allowed = listOf(setOf(0), setOf(1), setOf(2), setOf(3), setOf(4, 5), setOf(6))
        states.forEachIndexed { operation, state ->
            facts("op").forEachIndexed { fact, input ->
                val transition = TaskWorktreeMachine.reduce(state, input)
                assertEquals(fact !in allowed[operation] && fact !in setOf(7, 8),
                    transition.effects.any { it is TaskWorktreeMachine.Effect.Reject }, "operation=$operation fact=$fact")
                assertTrue(transition.effects.none { it is TaskWorktreeMachine.Effect.Execute })
                reject(next(state, Input.Fact.Restored), input, TaskWorktreeMachine.Reason.UNKNOWN)
            }
            facts("foreign-operation").forEach { reject(state, it, TaskWorktreeMachine.Reason.STALE) }
        }
    }

    @Test fun restoreNeverEmitsGitAndExactInspectionCannotProveVerificationCommands() {
        for ((pendingState, kind) in listOf(
            next(empty(), Input.Intent.Prepare(record(), 1, "op")) to Operation.OPEN,
            next(ready(), Input.Intent.Capture("task", 1, "op")) to Operation.CAPTURE,
            next(merging(), Input.Intent.Integrate("task", 1, "op", "target")) to Operation.INTEGRATE,
            next(merged(), Input.Intent.Verify("task", 1, "op")) to Operation.VERIFY,
            next(accepted(), Input.Intent.Deliver("task", 1, "op")) to Operation.DELIVER,
        )) {
            val restored = TaskWorktreeMachine.reduce(pendingState, Input.Fact.Restored)
            assertTrue(restored.effects.isEmpty())
            assertEquals(TaskWorktreeMachine.Stage.UNKNOWN, restored.state.stage)
            reject(restored.state, Input.Intent.BindRun("task", 2), TaskWorktreeMachine.Reason.UNKNOWN)
            reject(restored.state, Input.Fact.Inspected(TaskWorktreeProof("other", "task", kind)), TaskWorktreeMachine.Reason.STALE)
            val proof = TaskWorktreeProof("op", "task", kind, resultCommit = "result", targetCommit = "target", mergeCommit = "merged")
            if (kind == Operation.VERIFY) reject(restored.state, Input.Fact.Inspected(proof), TaskWorktreeMachine.Reason.UNKNOWN)
            else assertNotEquals(TaskWorktreeMachine.Stage.UNKNOWN, next(restored.state, Input.Fact.Inspected(proof)).stage)
        }
    }

    @Test fun taskIdentitySurvivesNativeGenerationsAndOldHandoffCannotCapture() {
        val state = next(ready(), Input.Intent.BindRun("task", 2))
        assertEquals("task", state.record?.taskId)
        assertNull(state.record?.handoffGeneration)
        reject(state, Input.Intent.Handoff("task", 1, true, emptyList()), TaskWorktreeMachine.Reason.STALE)
        reject(state, Input.Intent.Capture("task", 2, "capture"), TaskWorktreeMachine.Reason.NOT_READY)
        val admitted = next(state, Input.Intent.Handoff("task", 2, true, emptyList()))
        assertEquals(TaskWorktreePhase.CAPTURING, next(admitted, Input.Intent.Capture("task", 2, "capture")).record?.phase)
    }

    @Test fun failedEffectAndMissingNeighbourRemainUnknownAcrossNotesAndRestore() {
        val dispatched = next(ready(), Input.Intent.Capture("task", 1, "capture"))
        val unknown = next(dispatched, Input.Fact.Failed("capture", beforeEffect = false))
        assertEquals(TaskWorktreeMachine.Stage.UNKNOWN, next(unknown, Input.Intent.NoteFailure("task", "Retry later")).stage)
        assertEquals(TaskWorktreeMachine.Stage.UNKNOWN, next(unknown, Input.Fact.Restored).stage)
        reject(unknown, Input.Fact.Captured("capture", "result"), TaskWorktreeMachine.Reason.UNKNOWN)
        assertEquals(TaskWorktreeMachine.Stage.UNKNOWN, next(running(), Input.Fact.NeighbourMissing("task")).stage)
        val known = next(dispatched, Input.Fact.Failed("capture", beforeEffect = true))
        assertEquals(TaskWorktreePhase.READY, known.record?.phase)
        assertNull(known.pending)
    }

    @Test fun legacyDangerousPhasesAndCompletedIdentityHaveExplicitRules() {
        for (phase in TaskWorktreePhase.entries) {
            val imported = next(TaskWorktreeMachine.initial(owner), Input.Fact.Imported(record().copy(phase = phase), 1))
            assertEquals(phase !in setOf(TaskWorktreePhase.READY, TaskWorktreePhase.COMPLETE), imported.stage == TaskWorktreeMachine.Stage.UNKNOWN, "$phase")
            assertTrue(TaskWorktreeMachine.reduce(imported, Input.Fact.Restored).effects.isEmpty())
        }
        val complete = next(next(accepted(), Input.Intent.Deliver("task", 1, "deliver")), Input.Fact.Delivered("deliver"))
        val new = record("next").copy(reuseBranch = complete.record!!.branch, reuseCommit = complete.record.mergeCommit)
        val opened = next(next(complete, Input.Intent.Prepare(new, 2, "open-next")), Input.Fact.Opened("open-next"))
        assertTrue("task" in opened.retiredTasks)
        reject(opened, Input.Intent.Prepare(record("task"), 3, "open-old"), TaskWorktreeMachine.Reason.ALREADY_USED)
        reject(opened, Input.Intent.Refresh("next", 2, "open-next"), TaskWorktreeMachine.Reason.ALREADY_USED)
    }

    @Test fun knownFailedVerificationNeedsExplicitRepairButUnknownCanNeverBeRepairedByAssumption() {
        val started = next(merged(), Input.Intent.Verify("task", 1, "verify"))
        val failed = next(started, Input.Fact.VerificationFailed("verify", "Check failed"))
        assertFalse(failed.unknown)
        assertTrue(failed.verificationFailed)
        reject(failed, Input.Intent.Verify("task", 1, "again"), TaskWorktreeMachine.Reason.NOT_READY)
        val repair = next(failed, Input.Intent.ReturnForRepair("task", 1))
        assertEquals(TaskWorktreePhase.RUNNING, repair.record?.phase)
        assertNull(repair.record?.handoffGeneration)
        val retry = next(failed, Input.Intent.RetryVerification("task", 1))
        assertEquals(TaskWorktreePhase.MERGING, retry.record?.phase)
        assertFalse(retry.verificationFailed)
        assertEquals(TaskWorktreeMachine.Operation.VERIFY, next(retry, Input.Intent.Verify("task", 1, "retry")).pending?.kind)
        reject(retry, Input.Intent.Deliver("task", 1, "unsafe-delivery"), TaskWorktreeMachine.Reason.NOT_READY)
        val unknown = next(started, Input.Fact.Restored)
        reject(unknown, Input.Intent.ReturnForRepair("task", 1), TaskWorktreeMachine.Reason.UNKNOWN)
        reject(unknown, Input.Intent.RetryVerification("task", 1), TaskWorktreeMachine.Reason.UNKNOWN)
        val saved = next(unknown, Input.Fact.OutcomeRecovered(Input.Fact.VerificationFailed("verify", "Check failed")))
        assertEquals(failed, saved)
    }
}
