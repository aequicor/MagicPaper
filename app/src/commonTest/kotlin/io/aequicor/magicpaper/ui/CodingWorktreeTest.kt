package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolSession
import io.aequicor.magicpaper.di.CodingRuntimeGraph
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingWorktreeTest {
    private val project = CodingProject("p", "Project", "/source", 1)
    private val session = CodingSession("s", "p", "Task", 1, engine = CodingEngine.PI)
    private class Workspace : TaskWorkspace {
        var opens = 0
        var deliveries = 0
        var advanceAtDelivery = false
        var mergeAttempts = 0
        var destination = "base"
        var conflict = false
        var verifyGate: CompletableDeferred<Unit>? = null
        override suspend fun availability(project: CodingProject) = WorktreeAvailability(true)
        override suspend fun describe(project: CodingProject, sessionId: String, taskId: String) =
            TaskWorktree(taskId, project.path, "main", "base", "/isolated", "task-$taskId")
        override suspend fun open(record: TaskWorktree, previous: TaskWorktree?) { opens++ }
        override suspend fun reconcile(record: TaskWorktree) = Unit
        override suspend fun capture(record: TaskWorktree) = "result"
        override suspend fun target(record: TaskWorktree) = destination
        override suspend fun merge(record: TaskWorktree): String? { mergeAttempts++; return if (conflict) null else "result" }
        override suspend fun verify(record: TaskWorktree) { verifyGate?.await() }
        override suspend fun deliver(record: TaskWorktree) {
            if (advanceAtDelivery) { advanceAtDelivery = false; destination = "next"; throw TaskDestinationChanged() }
            deliveries++
        }
        override suspend fun delivered(record: TaskWorktree) = deliveries > 0
    }
    private class Runtime(val worktrees: TaskWorktreeService) : CodingRuntime {
        val calls = mutableListOf<Pair<CodingProject, CodingSession>>()
        val prompts = mutableListOf<String>()
        var onRun: (Int) -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var handoff = true
        override val supported = true
        override val rootPath = "/runtime"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += project to session
            prompts += prompt
            onRun(calls.size)
            gate?.await()
            if (handoff) worktrees.handoff(ToolExecutionContext.worker(session), true, emptyList())
            emit(CodingEvent.FinalText("Finished implementation"))
            emit(CodingEvent.Finished)
        }
    }
    private suspend fun TestScope.fixture(block: suspend (DefaultCodingService, Runtime, Workspace, JsonCodingProjectRepository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = Workspace()
            val worktrees = TaskWorktreeService(repo, port, LocalPlanningWorkspace())
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, repo, taskWorktrees = worktrees)
            runCurrent()
            block(service, runtime, port, repo)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun nativeFinishedWithoutHandoffNeverMerges() = runTest { fixture { service, runtime, port, repo ->
        runtime.handoff = false
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(0, port.deliveries)
        assertEquals(ExecutionIntent.STOP, repo.sessions("p").single().pendingRun?.intent)
        assertTrue(service.state.value.coding.sessions.single().worktreeLocked)
    } }

    @Test fun clarificationKeepsWorkspaceAndNextTaskReusesSlotWithFreshBranch() = runTest { fixture { service, runtime, port, repo ->
        runtime.gate = CompletableDeferred()
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        val original = repo.sessions("p").single().taskWorktree!!
        service.toggleWorktree("s"); runCurrent()
        assertTrue(repo.sessions("p").single().worktreeEnabled)
        service.clarifyCodingSession("s", "Clarification"); runCurrent()
        assertEquals(2, runtime.calls.size)
        assertEquals(listOf("/isolated", "/isolated"), runtime.calls.map { it.first.path })
        assertEquals(original.branch, repo.sessions("p").single().taskWorktree?.branch)
        assertEquals(1, port.opens)
        runtime.gate!!.complete(Unit); runCurrent()
        assertEquals(1, port.deliveries)
        assertNull(repo.sessions("p").single().pendingRun)
        service.sendCodingPromptTo("s", "Next task"); runCurrent()
        assertEquals(2, port.deliveries)
        assertNotEquals(original.branch, repo.sessions("p").single().taskWorktree?.branch)
    } }

    @Test fun stopDuringVerificationRetainsTaskAndResumeDoesNotRerunAgent() = runTest { fixture { service, runtime, port, repo ->
        port.verifyGate = CompletableDeferred()
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(TaskWorktreePhase.MERGING, repo.sessions("p").single().taskWorktree?.phase)
        service.abortCodingSession("s"); runCurrent()
        assertEquals(0, port.deliveries)
        assertTrue(service.state.value.coding.sessions.single().worktreeLocked)
        port.verifyGate!!.complete(Unit)
        service.resumeCodingSession("s"); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(1, port.deliveries)
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
    } }

    @Test fun destinationAdvanceRepeatsMergeAndChecksWithoutRepeatingAgent() = runTest { fixture { service, runtime, port, repo ->
        port.advanceAtDelivery = true
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(2, port.mergeAttempts)
        assertEquals(1, port.deliveries)
        assertEquals("next", repo.sessions("p").single().taskWorktree?.targetCommit)
    } }

    @Test fun clarificationDuringConflictRemainsInTheSameTask() = runTest { fixture { service, runtime, port, repo ->
        port.conflict = true
        runtime.onRun = { count ->
            if (count == 2) runtime.gate = CompletableDeferred()
            if (count == 3) { runtime.gate = null; port.conflict = false }
        }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        val original = repo.sessions("p").single().taskWorktree!!
        assertEquals(TaskWorktreePhase.CONFLICT, original.phase)
        service.clarifyCodingSession("s", "Keep both public methods"); runCurrent()
        val finished = repo.sessions("p").single()
        assertEquals(TaskWorktreePhase.COMPLETE, finished.taskWorktree?.phase)
        assertEquals(original.branch, finished.taskWorktree?.branch)
        assertTrue(finished.queuedPrompts.isEmpty())
        assertEquals(3, runtime.calls.size)
        assertContains(runtime.prompts.last(), "Keep both public methods")
        assertEquals(1, port.deliveries)
    } }

    @Test fun applicationGraphBindsHandoffToActualTaskAndWaitsForNativeOwner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        var graph: CodingRuntimeGraph? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = Workspace()
            val paths = mutableListOf<String>()
            val leases = object : PlanningWorkspace by LocalPlanningWorkspace() {
                override suspend fun verificationSnapshot(path: String) = "snapshot"
            }
            val unused = TaskWorktreeService(repo, port, leases)
            val native = object : CodingRuntime by Runtime(unused) {
                override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                    paths += project.path
                    emit(CodingEvent.SessionStarted("native"))
                    val tools = checkNotNull(currentCoroutineContext()[ToolSession])
                    assertTrue(tools.definitions.any { it.id == "task.handoff" })
                    tools.call("handoff", "task.handoff", buildJsonObject { put("outcome", "RESULT") })
                    emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
                }
            }
            graph = CodingRuntimeGraph(f.kv, f.json, f.settings, f.profiles, repo, native, leases, null,
                f.usage, f.gateway, f.search, taskWorkspace = port)
            service = f.prepareCoding(graph.runtime, repo, taskWorktrees = graph.taskWorktrees, planningChat = graph.planningChat)
            service.sendCodingPromptTo("s", "Task")
            val result = withContext(Dispatchers.Default) {
                withTimeout(10_000) { service.state.first { state -> state.coding.sessions.any {
                    it.session.taskWorktree?.phase == TaskWorktreePhase.COMPLETE || it.session.pendingRun?.intent == ExecutionIntent.STOP
                } } }
            }
            assertEquals(TaskWorktreePhase.COMPLETE, result.coding.sessions.single { it.session.id == "s" }.session.taskWorktree?.phase,
                result.coding.sessions.first { it.session.id == "s" }.messages.lastOrNull()?.text)
            assertEquals(listOf("/isolated"), paths)
            assertEquals(1, port.deliveries)
        } finally { service?.close(); graph?.close(); Dispatchers.resetMain() }
    }

    @Test fun restartAfterGitDeliveryRestoresSavedAnswerWithoutAnotherNativeRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            val response = CodingMessage("response", CodingRole.AGENT, "Saved answer", createdAt = 2)
            repo.save(project)
            repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("request", "Task", responseId = "response", worktreeEnabled = true),
                taskWorktree = TaskWorktree("request", "/source", "main", "base", "/isolated", "task",
                    phase = TaskWorktreePhase.DELIVERING, resultCommit = "result", targetCommit = "base", mergeCommit = "result", executionResponse = response)))
            val reopened = JsonCodingProjectRepository(f.kv, f.json)
            val port = Workspace().apply { deliveries = 1 }
            val worktrees = TaskWorktreeService(reopened, port, LocalPlanningWorkspace())
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, reopened, taskWorktrees = worktrees)
            runCurrent()
            assertTrue(runtime.calls.isEmpty())
            assertEquals(1, port.deliveries)
            assertNull(reopened.sessions("p").single().pendingRun)
            assertEquals(response, reopened.messages("p", "s").single { it.id == "response" })
        } finally { service?.close(); Dispatchers.resetMain() }
    }
}
