package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class WorkerToolContextTest {
    private val task = TaskWorktree("task", "/source", "main", "base", "/task", "magicpaper/worktree-task", phase = TaskWorktreePhase.CONFLICT)
    private val session = CodingSession("session", "project", "Task", 1, engine = CodingEngine.PI, taskWorktree = task)
    private val handoff = ToolCatalog.get("task.handoff")

    @Test fun handoffStaysOfferedToEveryFreshRequestOfTheSameTask() {
        // A continuation, a clarification and a conflict repair each get a fresh request id.
        val repair = session.copy(pendingRun = CodingRunCheckpoint("message", "Task", runId = "repair", workspaceTaskId = task.taskId))
        val context = ToolExecutionContext.worker(repair)
        assertEquals(task.taskId, context.taskWorktreeId)
        assertTrue(handoff.allowed(context))
    }

    @Test fun theFirstRequestOfATaskIsItsIdentityWhenTheCheckpointPredatesTaskBinding() {
        val legacy = session.copy(pendingRun = CodingRunCheckpoint("message", "Task", runId = task.taskId))
        assertTrue(handoff.allowed(ToolExecutionContext.worker(legacy)))
    }

    @Test fun anotherTaskOrNoRequestIsNeverHandedOff() {
        val other = session.copy(pendingRun = CodingRunCheckpoint("message", "Task", runId = "next", workspaceTaskId = "other"))
        assertNull(ToolExecutionContext.worker(other).taskWorktreeId)
        assertFalse(handoff.allowed(ToolExecutionContext.worker(other)))
        assertNull(ToolExecutionContext.worker(session).taskWorktreeId)
    }
}
