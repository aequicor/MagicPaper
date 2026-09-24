package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.CodingTaskWorktreeSessionAccess
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.workspace.DefaultTaskWorktreeOwner
import kotlinx.coroutines.Dispatchers

class TestTaskWorktreeRuntime(
    private val quiescent: suspend (String) -> Unit = {},
    private val reclaim: suspend () -> Boolean = { false },
) : TaskWorktreeRuntimeAccess {
    override suspend fun requireQuiescent(sessionId: String) = quiescent(sessionId)
    override suspend fun releaseUnownedLeases() = reclaim()
}

fun testTaskWorktreeOwner(workspace: TaskWorkspace, journal: EventJournal = InMemoryEventJournal(),
    payloads: KeyValueStore = InMemoryKeyValueStore()): TaskWorktreeOwner =
    DefaultTaskWorktreeOwner(workspace, journal, payloads, Dispatchers.Unconfined)

fun testTaskWorktreeService(projects: CodingProjectOwner, workspace: TaskWorkspace, leases: PlanningWorkspace,
    journal: EventJournal = InMemoryEventJournal(), payloads: KeyValueStore = InMemoryKeyValueStore(),
    runtime: TaskWorktreeRuntimeAccess = TestTaskWorktreeRuntime(),
    sessions: TaskWorktreeSessionAccess = CodingTaskWorktreeSessionAccess(projects),
    questions: RuntimeQuestionnaireService? = null): TaskWorktreeService =
    TaskWorktreeService(sessions, workspace, leases, testTaskWorktreeOwner(workspace, journal, payloads), runtime, questions)
