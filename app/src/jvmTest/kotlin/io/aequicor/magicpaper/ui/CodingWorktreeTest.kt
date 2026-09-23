package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolSession
import io.aequicor.magicpaper.di.CodingRuntimeGraph
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.LogLevel
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
        var integrateAttempts = 0
        var refreshes = 0
        var refreshResult = TaskWorktreeRefresh()
        var destination = "base"
        var conflict = false
        var verifyGate: CompletableDeferred<Unit>? = null
        var verificationError: String? = null
        override suspend fun availability(project: CodingProject) = WorktreeAvailability(true)
        override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String) =
            TaskWorktree(taskId, project.path, "main", "base", "/isolated", "task-$taskId", label = label)
        override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) { opens++ }
        override suspend fun reconcile(record: TaskWorktree) = Unit
        override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation) = "result"
        override suspend fun target(record: TaskWorktree) = destination
        override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation): TaskWorktreeRefresh { refreshes++; return refreshResult }
        override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String? { integrateAttempts++; return if (conflict) null else "result" }
        override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) {
            verificationError?.let { throw TaskWorktreeVerificationFailed(it) }
            verifyGate?.await()
        }
        override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) {
            if (advanceAtDelivery) { advanceAtDelivery = false; destination = "next"; throw TaskDestinationChanged() }
            deliveries++
        }
        override suspend fun delivered(record: TaskWorktree) = deliveries > 0
    }
    private class Runtime(val worktrees: TaskWorktreeService) : CodingRuntime {
        private val outcomes = mutableMapOf<NativeRunRecoveryRef, NativeRunRecoveryItem>()
        private var noDispatch: NativeRunNoDispatchItem? = null
        private val consumptions = mutableListOf<NativeRunRecoveryConsumption>()
        val bindings = mutableListOf<NativeRunRecoveryBinding?>()
        val preflightFailures = mutableSetOf<Int>()
        override val recovery = object : NativeRunRecovery {
            override suspend fun acknowledgeNoDispatch(proof: NativeRunNoDispatchProof, parentDecisionId: String): NativeRunNoDispatchAcknowledgement {
                check(noDispatch?.proof == proof && noDispatch?.acknowledgement == null)
                return NativeRunNoDispatchAcknowledgement("ack-$parentDecisionId", proof, parentDecisionId).also {
                    noDispatch = NativeRunNoDispatchItem(proof, it)
                }
            }
            override suspend fun inspect(sessionId: String) = NativeRunRecoverySnapshot(
                outcomes.values.filter { it.ref.sessionId == sessionId }, persistenceUnknown = false,
                noDispatch = listOfNotNull(noDispatch).filter { it.proof.sessionId == sessionId },
                consumptions = consumptions.filter { it.sessionId == sessionId })
            override suspend fun stop(ref: NativeRunRecoveryRef): NativeRunRecoverySnapshot {
                check(outcomes.getValue(ref).termination == NativeRunTermination.STOPPED)
                return inspect(ref.sessionId)
            }
            override suspend fun acknowledge(ref: NativeRunRecoveryRef, parentDecisionId: String): NativeRunRecoveryAcknowledgement {
                val item = outcomes.getValue(ref)
                check(item.termination == NativeRunTermination.STOPPED && item.acknowledgement == null)
                return NativeRunRecoveryAcknowledgement("ack-$parentDecisionId", ref, parentDecisionId).also {
                    outcomes[ref] = item.copy(acknowledgement = it)
                }
            }
        }
        val calls = mutableListOf<Pair<CodingProject, CodingSession>>()
        val prompts = mutableListOf<String>()
        var onRun: (Int) -> Unit = {}
        var gate: CompletableDeferred<Unit>? = null
        var handoff = true
        var checks: List<List<String>> = emptyList()
        fun recordControlledCompletion(session: CodingSession) {
            val ref = NativeRunRecoveryRef(checkNotNull(session.engine), session.id, checkNotNull(session.pendingRun).runId, 1)
            outcomes[ref] = NativeRunRecoveryItem(ref, NativeRunOutcome.SUCCEEDED, NativeRunTermination.STOPPED, null)
        }
        override val supported = true
        override val rootPath = "/runtime"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override suspend fun uninstall() = Unit
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            val ref = NativeRunRecoveryRef(checkNotNull(session.engine), session.id, checkNotNull(session.pendingRun).runId, 1)
            calls += project to session
            prompts += prompt
            val binding = currentCoroutineContext()[NativeRunRecoveryBinding]
            bindings += binding
            binding?.let {
                val ackId = it.acknowledgement?.let { ack ->
                    check(outcomes.getValue(ack.predecessor).acknowledgement == ack)
                    ack.id
                } ?: checkNotNull(it.noDispatchAcknowledgement).let { ack ->
                    check(noDispatch?.acknowledgement == ack)
                    ack.id
                }
                check(consumptions.none { consumed -> consumed.acknowledgementId == ackId })
                consumptions += NativeRunRecoveryConsumption(ackId, ref.engine, ref.sessionId, ref.requestId)
            }
            onRun(calls.size)
            if (calls.size in preflightFailures) {
                noDispatch = NativeRunNoDispatchItem(NativeRunNoDispatchProof(ref.engine, ref.sessionId, ref.requestId,
                    "proof-${ref.requestId}", "native-journal"), null)
                throw NativeRunRecoveryRequired(recovery.inspect(session.id))
            }
            outcomes[ref] = NativeRunRecoveryItem(ref, NativeRunOutcome.UNKNOWN, NativeRunTermination.LIVE, null)
            try {
                gate?.await()
                if (handoff) worktrees.handoff(ToolExecutionContext.worker(session), true, checks)
                emit(CodingEvent.FinalText("Finished implementation"))
                emit(CodingEvent.Finished)
                outcomes[ref] = outcomes.getValue(ref).copy(outcome = NativeRunOutcome.SUCCEEDED)
            } catch (cancelled: CancellationException) {
                // This controlled fixture launches no process or external tool. Joining its flow proves a known failure.
                outcomes[ref] = outcomes.getValue(ref).copy(outcome = NativeRunOutcome.FAILED)
                throw cancelled
            } finally { outcomes[ref] = outcomes.getValue(ref).copy(termination = NativeRunTermination.STOPPED) }
        }
    }
    private suspend fun TestScope.fixture(block: suspend (DefaultCodingService, Runtime, Workspace, CodingProjectOwner) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            block(service, runtime, port, projects)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun nativeFinishedWithoutHandoffNeverMerges() = runTest { fixture { service, runtime, port, repo ->
        runtime.handoff = false
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(0, port.deliveries)
        assertEquals(ExecutionIntent.STOP, repo.sessions("p").single().pendingRun?.intent)
        assertTrue(service.state.value.coding.sessions.single().worktreeLocked)
        assertEquals("Агент завершил ответ, не передав результат задачи, поэтому изменения не влиты. Уточните запрос и продолжите",
            repo.sessions("p").single().taskWorktree?.error)
        val failed = AppLog.history().last { it.component == "coding" && it.event == "run.failed" }
        assertEquals(listOf("TaskWorktreeRejected"), failed.causeTypes, "the log names the refusal, not only a type")
        assertEquals(repo.sessions("p").single().taskWorktree?.error, failed.causeMessage)
    } }

    @Test fun handoffIsTracedWithTheChecksTheAgentChose() = runTest { fixture { service, runtime, _, _ ->
        runtime.checks = listOf(listOf("gradlew.bat", "test", "--tests", "Suite Test"))
        val level = AppLog.level
        AppLog.level = LogLevel.TRACE
        try { service.sendCodingPromptTo("s", "Task"); runCurrent() } finally { AppLog.level = level }
        val recorded = AppLog.history().last { it.component == "coding.worktree" && it.event == "handoff.recorded" }
        assertEquals("RESULT", recorded.fields["outcome"])
        assertEquals("1", recorded.fields["count"])
        assertFalse("gradlew" in recorded.line(), "the commands stay out of the normal levels")
        val traced = AppLog.history().last { it.component == "coding.worktree" && it.event == "handoff.checks" }
        assertEquals("check[0]: gradlew.bat test --tests \"Suite Test\"", traced.detail)
        assertEquals(recorded.fields["entityId"], traced.fields["entityId"])
        val merged = AppLog.history().last { it.component == "coding.worktree" && it.event == "merge.accepted" }
        assertEquals("task", merged.fields["mode"])
        assertEquals(recorded.fields["entityId"], merged.fields["entityId"], "the accepted merge belongs to the handed-off task")
    } }

    @Test fun continuedTaskRecordsDestinationDistanceItCouldNotClose() = runTest { fixture { service, runtime, port, repo ->
        runtime.gate = CompletableDeferred()
        port.refreshResult = TaskWorktreeRefresh(behind = 3, targetCommit = "tip", note = "В копии есть несохранённые изменения")
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(0, port.refreshes, "a fresh task starts at the destination tip")
        service.clarifyCodingSession("s", "Clarification"); runCurrent()
        assertEquals(1, port.refreshes)
        val stale = repo.sessions("p").single().taskWorktree!!
        assertEquals(3, stale.behindCommits)
        assertEquals("В копии есть несохранённые изменения", stale.refreshNote)
        assertEquals("", stale.integratedCommit)
    } }

    @Test fun preRunUpdateRecordsTheIntegrationPointBeforeTheAgentContinues() = runTest { fixture { service, runtime, port, repo ->
        runtime.gate = CompletableDeferred()
        port.refreshResult = TaskWorktreeRefresh(behind = 0, targetCommit = "tip", updated = true)
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        service.clarifyCodingSession("s", "Clarification"); runCurrent()
        assertEquals(1, port.refreshes)
        val updated = repo.sessions("p").single().taskWorktree!!
        assertEquals(0, updated.behindCommits)
        assertNull(updated.refreshNote)
        assertEquals("tip", updated.integratedCommit)
        assertEquals(TaskWorktreePhase.RUNNING, updated.phase)
    } }

    @Test fun resumedTransferConflictStaysOnTheWorkingBranchRecord() = runTest { fixture { service, runtime, port, repo ->
        runtime.gate = CompletableDeferred()
        port.refreshResult = TaskWorktreeRefresh(behind = 2, targetCommit = "tip", pendingTransfer = true,
            note = "Перенос остановлен конфликтом: разреши его в файлах рабочей копии")
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        service.clarifyCodingSession("s", "Clarification"); runCurrent()
        assertEquals(1, port.refreshes)
        val conflict = repo.sessions("p").single().taskWorktree!!
        assertTrue(conflict.pendingTransfer)
        assertEquals(2, conflict.behindCommits)
        assertEquals("tip", conflict.integratedCommit, "точка объединения подписывает и незавершённый перенос")
        assertEquals(TaskWorktreePhase.RUNNING, conflict.phase)
        runtime.gate!!.complete(Unit); runCurrent()
        // Успешное слияние очищает признак незавершённого переноса.
        assertFalse(repo.sessions("p").single().taskWorktree!!.pendingTransfer)
    } }

    @Test fun clarificationKeepsWorkspaceAndNextTaskReusesSlotWithFreshBranch() = runTest { fixture { service, runtime, port, repo ->
        runtime.gate = CompletableDeferred()
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        val original = repo.sessions("p").single().taskWorktree!!
        service.toggleWorktree("s"); runCurrent()
        assertTrue(repo.sessions("p").single().worktreeEnabled)
        service.clarifyCodingSession("s", "Clarification"); runCurrent()
        // Уточнение попадает в историю сразу при приёмке и переживает любой отказ запуска.
        assertEquals("Clarification", repo.messages("p", "s").last { it.role == CodingRole.USER }.text)
        assertEquals(2, runtime.calls.size)
        assertEquals(listOf("/isolated", "/isolated"), runtime.calls.map { it.first.path })
        assertEquals(original.branch, repo.sessions("p").single().taskWorktree?.branch)
        assertEquals(1, port.opens)
        runtime.gate!!.complete(Unit); runCurrent()
        assertEquals(1, port.deliveries)
        assertNull(repo.sessions("p").single().pendingRun)
        service.sendCodingPromptTo("s", "Next task"); runCurrent()
        assertEquals(2, port.deliveries)
        val next = assertNotNull(repo.sessions("p").single().taskWorktree)
        assertNotEquals(original.branch, next.branch)
        val systemMessages = repo.messages("p", "s").filter { it.systemNotice }.map { it.text }
        assertContains(systemMessages, "Создана worktree-ветка ${original.branch}")
        assertContains(systemMessages, "Результат влит в main")
        assertContains(systemMessages, "Worktree переключён с ${original.branch} на ${next.branch}")
        assertEquals(2, systemMessages.count { it == "Результат влит в main" })
    } }

    @Test fun stopDuringVerificationRetainsUnknownTaskWithoutRepeatingAnyEffect() = runTest { fixture { service, runtime, port, repo ->
        port.verifyGate = CompletableDeferred()
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(TaskWorktreePhase.MERGING, repo.sessions("p").single().taskWorktree?.phase)
        service.abortCodingSession("s"); runCurrent()
        assertEquals(0, port.deliveries)
        assertTrue(service.state.value.coding.sessions.single().worktreeLocked)
        assertTrue("workspace:s" in repo.states.value.getValue("p").unknownChildren)
        port.verifyGate!!.complete(Unit)
        service.resumeCodingSession("s"); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(0, port.deliveries)
        assertEquals(TaskWorktreePhase.MERGING, repo.sessions("p").single().taskWorktree?.phase)
        assertNotNull(repo.sessions("p").single().taskWorktree?.executionResponse)
    } }

    @Test fun snapshotFailureAfterResultResumesDeliveryWithoutRepeatingAgent() = runTest { fixture { service, runtime, port, repo ->
        port.verificationError = "Неподдерживаемый файл снимка: tools/mission-visualization"
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        val failed = repo.sessions("p").single()
        val task = failed.taskWorktree!!
        assertEquals(TaskWorktreePhase.MERGING, task.phase)
        assertEquals(ExecutionIntent.STOP, failed.pendingRun?.intent)
        assertEquals(0, port.deliveries)
        val response = assertNotNull(task.executionResponse)
        port.verificationError = null
        val recovery = service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        service.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", selected = listOf("retry")))); runCurrent()
        val completed = repo.sessions("p").single()
        assertEquals(1, runtime.calls.size)
        assertEquals(1, port.opens)
        assertEquals(1, port.deliveries)
        assertEquals(task.taskId, completed.taskWorktree?.taskId)
        assertEquals(task.branch, completed.taskWorktree?.branch)
        assertEquals(TaskWorktreePhase.COMPLETE, completed.taskWorktree?.phase)
        assertNull(completed.pendingRun)
        assertEquals(1, repo.messages("p", "s").count { it.id == response.id })
    } }

    @Test fun destinationAdvanceRepeatsIntegrationAndChecksWithoutRepeatingAgent() = runTest { fixture { service, runtime, port, repo ->
        port.advanceAtDelivery = true
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(2, port.integrateAttempts)
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

    @Test fun deliveryOnlyRepairConsumesExactNoDispatchProofAndAnotherPreflightFailureStaysUnknown() = runTest { fixture { service, runtime, port, repo ->
        port.conflict = true
        runtime.preflightFailures += setOf(2, 3)
        runtime.onRun = { if (it == 4) port.conflict = false }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        val task = repo.sessions("p").single().taskWorktree!!
        assertEquals(TaskWorktreePhase.CONFLICT, task.phase)
        assertEquals(2, runtime.calls.size)
        assertEquals(CodingMachine.Phase.UNKNOWN, service.state.value.coding.currentSession!!.runPhase)
        val failedRepair = runtime.calls.last().second.pendingRun!!.runId

        service.submitQuestionnaire(service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }.id,
            listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
        assertEquals(3, runtime.calls.size, "Delivery-only continuation launches only its repair")
        val firstAck = assertNotNull(runtime.bindings.last()?.noDispatchAcknowledgement)
        assertEquals(failedRepair, firstAck.proof.requestId)
        assertNotEquals(failedRepair, runtime.calls.last().second.pendingRun!!.runId)
        assertEquals(CodingMachine.Phase.UNKNOWN, service.state.value.coding.currentSession!!.runPhase,
            "A repair preflight is native dispatch even when the preceding delivery skipped the agent")
        assertNotNull(repo.sessions("p").single().pendingRun)
        assertEquals(0, port.deliveries)

        val secondFailure = runtime.calls.last().second.pendingRun!!.runId
        service.submitQuestionnaire(service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }.id,
            listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
        assertEquals(4, runtime.calls.size)
        val secondAck = assertNotNull(runtime.bindings.last()?.noDispatchAcknowledgement)
        assertEquals(secondFailure, secondAck.proof.requestId)
        assertNotEquals(firstAck.id, secondAck.id)
        assertEquals(task.taskId, repo.sessions("p").single().taskWorktree?.taskId)
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertNull(repo.sessions("p").single().pendingRun)
        assertEquals(1, port.deliveries)
    } }

    /**
     * Занятая исходная папка — ожидание, а не отказ: удержание без живого исполнителя снимает
     * сверка движка, и задача идёт дальше к слиянию.
     */
    @Test fun newTaskWaitsForTheSourceFolderInsteadOfFailing() = runTest {
        val f = ModelSettingsFixture()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project); repo.saveSession(session)
        val leases = LocalPlanningWorkspace()
        val otherSession = project.copy(id = "root-other-session")
        val heldWorkspace1 = assertNotNull(leases.acquire(otherSession, "lease-request-1"), "Исходную папку держит другой прогон")
        var reclaimed = false
        val port = Workspace()
        val events = InMemoryEventJournal()
        val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
        val worktrees = testTaskWorktreeService(projects, port, leases, events, f.kv, runtime = TestTaskWorktreeRuntime(reclaim = { leases.release(heldWorkspace1); reclaimed = true; true }))
        val record = worktrees.begin(project, session.id, "task")
        assertTrue(reclaimed, "Сверка удержаний предшествует ожиданию")
        assertEquals(TaskWorktreePhase.RUNNING, record.phase)
        assertEquals(1, port.opens)
    }

    /** Постоянная занятость остаётся действенной ошибкой: сохранённый ответ и фаза доставки переживают её. */
    @Test fun permanentlyBusySourceFolderKeepsTheSavedResultRecoverable() = runTest {
        val f = ModelSettingsFixture()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        val response = CodingMessage("response", CodingRole.AGENT, "Saved answer", createdAt = 2)
        repo.save(project)
        repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("task", "Task", responseId = "response"),
            taskWorktree = TaskWorktree("task", "/source", "main", "base", "/isolated", "task",
                phase = TaskWorktreePhase.READY, handoffGeneration = 0,
                executionResponse = response)))
        val leases = LocalPlanningWorkspace()
        val otherSession = project.copy(id = "root-other-session")
        val heldWorkspace2 = assertNotNull(leases.acquire(otherSession, "lease-request-2"))
        val port = Workspace()
        val events = InMemoryEventJournal()
        val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
        val worktrees = testTaskWorktreeService(projects, port, leases, events, f.kv)
        val busy = assertFailsWith<TaskWorkspaceBusy> {
            worktrees.complete(project, session.id, "task", planAccepted = true, repair = {})
        }
        assertContains(busy.message.orEmpty(), "/source")
        assertEquals(0, port.deliveries)
        assertEquals(TaskWorktreePhase.MERGING, repo.sessions(project.id).single().taskWorktree?.phase,
            "Waiting for the source lease must not claim delivery was dispatched")
        leases.release(heldWorkspace2)
        val finished = worktrees.complete(project, session.id, "task", planAccepted = true, repair = {})
        assertEquals(TaskWorktreePhase.COMPLETE, finished.phase)
        assertEquals(1, port.deliveries)
        assertEquals(response, repo.sessions(project.id).single().taskWorktree?.executionResponse)
    }

    @Test fun completedDeliveryRetriesFailedLeaseCleanupWithoutRepeatingGit() = runTest {
        val f = ModelSettingsFixture()
        val cache = JsonCodingProjectRepository(f.kv, f.json)
        cache.save(project)
        cache.saveSession(session.copy(taskWorktree = TaskWorktree("task", "/source", "main", "base", "/isolated", "task",
            phase = TaskWorktreePhase.READY, handoffGeneration = 0)))
        var releaseAttempts = 0
        val realLeases = LocalPlanningWorkspace()
        val leases = object : PlanningWorkspace by realLeases {
            override suspend fun release(lease: WorkspaceLease) {
                if (lease.ownerId == "task-delivery-s" && ++releaseAttempts == 1) error("release failed")
                realLeases.release(lease)
            }
        }
        val events = InMemoryEventJournal()
        val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = cache)
        val port = Workspace()
        val worktrees = testTaskWorktreeService(projects, port, leases, events, f.kv)
        assertFailsWith<IllegalStateException> { worktrees.complete(project, "s", "task", planAccepted = true, repair = {}) }
        assertEquals(1, port.deliveries)
        assertEquals(TaskWorktreePhase.COMPLETE, projects.sessions("p").single().taskWorktree?.phase)
        assertEquals(TaskWorktreePhase.COMPLETE, worktrees.complete(project, "s", "task", planAccepted = true, repair = {}).phase)
        assertEquals(2, releaseAttempts)
        assertEquals(1, port.deliveries)
        val heldWorkspace3 = assertNotNull(realLeases.acquire(project.copy(id = "next-owner"), "lease-request-3"))
        realLeases.release(heldWorkspace3)
    }

    @Test fun savedWorkspaceOutcomeBeforeNativeLaunchCanBeRecoveredWithoutInventingNativeProof() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val cache = JsonCodingProjectRepository(f.kv, f.json)
            cache.save(project); cache.saveSession(session)
            val port = Workspace()
            val backing = InMemoryEventJournal()
            var loseWorkspaceFacts = true
            val events = object : EventJournal by backing {
                override suspend fun append(expected: JournalRevision, operation: String, at: Long, detail: String) =
                    if (loseWorkspaceFacts && expected.stream.startsWith("task-worktree:") && port.opens > 0)
                        throw java.io.IOException("lost terminal workspace fact")
                    else backing.append(expected, operation, at, detail)
            }
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = cache)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            service.sendCodingPromptTo("s", "Task"); runCurrent()
            assertEquals(1, port.opens)
            assertTrue(runtime.calls.isEmpty())
            assertTrue("workspace:s" in projects.states.value.getValue("p").unknownChildren)
            val firstRequest = assertNotNull(projects.sessions("p").single().pendingRun).runId
            val taskId = assertNotNull(projects.sessions("p").single().taskWorktree).taskId
            service.close()
            loseWorkspaceFacts = false

            val restoredProjects = journalCodingProjects(f.kv, f.json, journal = events,
                checkpoints = JsonCodingProjectRepository(f.kv, f.json))
            val restoredWorktrees = testTaskWorktreeService(restoredProjects, port, LocalPlanningWorkspace(), events, f.kv)
            val restoredRuntime = Runtime(restoredWorktrees)
            service = f.prepareCoding(restoredRuntime, restoredProjects, taskWorktrees = restoredWorktrees)
            runCurrent()
            assertTrue(restoredRuntime.calls.isEmpty(), "Restore itself must not inspect, run Git or launch native work")
            assertEquals(1, port.opens)
            service.resumeCodingSession("s", fromQuestionnaire = true); runCurrent()
            val completed = restoredProjects.sessions("p").single()
            assertEquals(TaskWorktreePhase.COMPLETE, completed.taskWorktree?.phase, service.state.value.notice)
            assertEquals(taskId, completed.taskWorktree?.taskId)
            assertEquals(1, port.opens, "The immutable receipt must avoid repeating preparation")
            assertEquals(1, port.deliveries)
            assertEquals(1, restoredRuntime.calls.size)
            assertNotEquals(firstRequest, restoredRuntime.calls.single().second.pendingRun?.runId)
            assertNull(completed.pendingRun)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun leaseCleanupCancellationRemainsPrimaryAfterWorkspaceFailure() = runTest {
        val f = ModelSettingsFixture()
        val cache = JsonCodingProjectRepository(f.kv, f.json)
        cache.save(project); cache.saveSession(session)
        val events = InMemoryEventJournal()
        val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = cache)
        val port = object : TaskWorkspace by Workspace() {
            override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) { error("action failed") }
        }
        val delegate = LocalPlanningWorkspace()
        val leases = object : PlanningWorkspace by delegate {
            override suspend fun release(lease: WorkspaceLease) {
                delegate.release(lease)
                throw CancellationException("cleanup cancelled")
            }
        }
        val worktrees = testTaskWorktreeService(projects, port, leases, events, f.kv)
        val cancelled = assertFailsWith<CancellationException> { worktrees.begin(project, "s", "task") }
        assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { cause ->
            cause.suppressed.any { failure -> generateSequence(failure) { it.cause }.any { it.message == "action failed" } }
        }, "Cancellation must retain the preceding operation failure as diagnostic evidence")
        assertTrue("workspace:s" in projects.states.value.getValue("p").unknownChildren)
    }

    @Test fun clarificationDuringToolQuarantineKeepsTheMessageInsteadOfRelaunching() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        var graph: CodingRuntimeGraph? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
            val unused = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val base = Runtime(unused).apply { gate = CompletableDeferred() }
            graph = CodingRuntimeGraph(f.kv, f.json, f.settings, f.profiles, projects, base, LocalPlanningWorkspace(), null,
                f.usage, f.gateway, f.search, taskWorkspace = port, events = events,
                taskWorktreeOwner = testTaskWorktreeOwner(port, events, f.kv),
                planningStoreFactory = io.aequicor.magicpaper.data.planning.PlanningStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.planning.DefaultPlanningStore(io.aequicor.magicpaper.data.planning.JsonPlanningRepository(f.kv, f.json), events, secrets)
                },
                organismStoreFactory = io.aequicor.magicpaper.data.coding.SessionOrganismStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore(f.kv, events, secrets)
                },
                toolQuestions = DefaultRuntimeQuestionnaireService(
                    io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), "test"),
                toolReceipts = io.aequicor.magicpaper.domain.tools.StoredToolReceipts(f.kv),
                mediaToolReceipts = io.aequicor.magicpaper.domain.tools.DefaultMediaToolReceiptOwner(
                    io.aequicor.magicpaper.domain.tools.StoredToolReceipts(f.kv), { null }),
                toolSessionFactory = io.aequicor.magicpaper.domain.tools.DefaultToolSessionFactory(),
                mediaToolFactory = io.aequicor.magicpaper.domain.tools.DefaultMediaToolCommandsFactory(),
                questionnaireToolFactory = io.aequicor.magicpaper.domain.tools.DefaultQuestionnaireToolCommandsFactory(), modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers(), settingsCommands = f.testSettingsCommands())
            service = f.prepareCoding(graph.runtime, projects, taskWorktrees = graph.taskWorktrees, planningChat = graph.planningChat)
            service.sendCodingPromptTo("s", "Task")
            // Прогон дошёл до агента и удерживается: уточнение будет отменять живой запуск.
            withContext(Dispatchers.Default) { withTimeout(10_000) { while (base.calls.isEmpty()) delay(50) } }
            // Прерванный инструмент с недоказанным исходом регистрирует карантин.
            withContext(Dispatchers.Default) {
                val store = graph.organisms!!.store
                val organism = store.get(store.organisms.value.values.first { "s" in it.sessions }.id)
                store.quarantine(organism.id, "s", organism.sessions.getValue("s").generation,
                    "op-powershell", "Неизвестный исход powershell")
            }
            service.clarifyCodingSession("s", "Уточнение не должно пропасть")
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { service.state.first { it.coding.interactions.any { it.kind == InteractionKind.RECOVER_RUN } } }
            }
            // Сообщение сохранено в истории, а запуск не перезапускался: исход не доказан.
            assertEquals("Уточнение не должно пропасть",
                repo.messages("p", "s").last { it.role == CodingRole.USER }.text)
            assertEquals(1, base.calls.size)
        } finally { service?.close(); graph?.close(); Dispatchers.resetMain() }
    }

    /** Две задачи одного проекта не вливаются одновременно: очередь даёт второй уже влитую вершину. */
    @Test fun concurrentCompletionsSerializeTheirMergesPerProject() = runTest {
        val f = ModelSettingsFixture()
        val repo = JsonCodingProjectRepository(f.kv, f.json)
        repo.save(project)
        suspend fun task(id: String, copy: String) = repo.saveSession(CodingSession(id, "p", "Task $id", 1,
            pendingRun = CodingRunCheckpoint(id, "Task", responseId = "$id-response", worktreeEnabled = true),
            taskWorktree = TaskWorktree(id, "/source", "main", "base", copy, "branch-$id",
                phase = TaskWorktreePhase.READY, handoffGeneration = 1)))
        task("one", "/copy-1"); task("two", "/copy-2")
        val log = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var verifications = 0
        val port = object : TaskWorkspace {
            var destination = "base"
            override suspend fun availability(project: CodingProject) = WorktreeAvailability(true)
            override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String) =
                TaskWorktree(taskId, project.path, "main", "base", "/isolated", "task-$taskId", label = label)
            override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) = Unit
            override suspend fun reconcile(record: TaskWorktree) = Unit
            override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation) = "result-${record.taskId}".also { log += "capture:${record.taskId}" }
            override suspend fun target(record: TaskWorktree) = destination.also { log += "target:${record.taskId}:$it" }
            override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation) = TaskWorktreeRefresh()
            override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation): String? {
                log += "integrate:${record.taskId}:${record.targetCommit}"
                return "merged-${record.taskId}"
            }
            override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) { verifications++; log += "verify:${record.taskId}"; if (verifications == 1) gate.await() }
            override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) { destination = "merged-${record.taskId}"; log += "deliver:${record.taskId}" }
            override suspend fun delivered(record: TaskWorktree) = false
        }
        val events = InMemoryEventJournal()
        val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
        val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
        val first = launch { worktrees.complete(project, "one", "one", planAccepted = true, repair = { error("конфликт не ожидался") }) }
        val second = launch { worktrees.complete(project, "two", "two", planAccepted = true, repair = { error("конфликт не ожидался") }) }
        runCurrent()
        // Захват остаётся локальным для копии, а слияние второй задачи ждёт очереди первой.
        assertEquals(listOf("capture:one", "target:one:base", "integrate:one:base", "verify:one", "capture:two"), log)
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("capture:one", "target:one:base", "integrate:one:base", "verify:one", "capture:two",
            "deliver:one", "target:two:merged-one", "integrate:two:merged-one", "verify:two", "deliver:two"), log,
            "вторая задача читает вершину и вливается только после доставки первой")
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").first { it.id == "one" }.taskWorktree?.phase)
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").first { it.id == "two" }.taskWorktree?.phase)
        assertEquals("merged-one", repo.sessions("p").first { it.id == "two" }.taskWorktree?.targetCommit)
    }

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
                override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "snapshot"
            }
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
            val unused = testTaskWorktreeService(projects, port, leases, events, f.kv)
            val controlledNative = Runtime(unused)
            val native = object : CodingRuntime by controlledNative {
                override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                    paths += project.path
                    emit(CodingEvent.SessionStarted("native"))
                    val tools = checkNotNull(currentCoroutineContext()[ToolSession])
                    assertTrue(tools.definitions.any { it.id == "task.handoff" })
                    val receipt = tools.call("handoff", "task.handoff", buildJsonObject { put("outcome", "RESULT") }).jsonObject
                    assertEquals("RESULT", receipt["outcome"]?.jsonPrimitive?.content)
                    assertEquals("finish_response", receipt["nextAction"]?.jsonPrimitive?.content)
                    assertTrue(tools.results.value.containsKey("task.handoff"))
                    emit(CodingEvent.FinalText("Done")); emit(CodingEvent.Finished)
                    controlledNative.recordControlledCompletion(session)
                }
            }
            graph = CodingRuntimeGraph(f.kv, f.json, f.settings, f.profiles, projects, native, leases, null,
                f.usage, f.gateway, f.search, taskWorkspace = port, events = events,
                taskWorktreeOwner = testTaskWorktreeOwner(port, events, f.kv),
                planningStoreFactory = io.aequicor.magicpaper.data.planning.PlanningStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.planning.DefaultPlanningStore(io.aequicor.magicpaper.data.planning.JsonPlanningRepository(f.kv, f.json), events, secrets)
                },
                organismStoreFactory = io.aequicor.magicpaper.data.coding.SessionOrganismStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore(f.kv, events, secrets)
                },
                toolQuestions = DefaultRuntimeQuestionnaireService(
                    io.aequicor.magicpaper.data.storage.InMemoryEventJournal(), "test"),
                toolReceipts = io.aequicor.magicpaper.domain.tools.StoredToolReceipts(f.kv),
                mediaToolReceipts = io.aequicor.magicpaper.domain.tools.DefaultMediaToolReceiptOwner(
                    io.aequicor.magicpaper.domain.tools.StoredToolReceipts(f.kv), { null }),
                toolSessionFactory = io.aequicor.magicpaper.domain.tools.DefaultToolSessionFactory(),
                mediaToolFactory = io.aequicor.magicpaper.domain.tools.DefaultMediaToolCommandsFactory(),
                questionnaireToolFactory = io.aequicor.magicpaper.domain.tools.DefaultQuestionnaireToolCommandsFactory(), modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers(), settingsCommands = f.testSettingsCommands())
            service = f.prepareCoding(graph.runtime, projects, taskWorktrees = graph.taskWorktrees, planningChat = graph.planningChat)
            service.sendCodingPromptTo("s", "Task")
            val result = withContext(Dispatchers.Default) {
                withTimeout(10_000) { service.state.first { state -> state.coding.sessions.any {
                    !it.running && (it.session.taskWorktree?.phase == TaskWorktreePhase.COMPLETE || it.session.pendingRun?.intent == ExecutionIntent.STOP)
                } } }
            }
            assertEquals(TaskWorktreePhase.COMPLETE, result.coding.sessions.single { it.session.id == "s" }.session.taskWorktree?.phase,
                result.coding.sessions.first { it.session.id == "s" }.messages.lastOrNull()?.text)
            assertEquals(listOf("/isolated"), paths)
            assertEquals(1, port.deliveries)
            assertTrue(graph.runtime!!.questionnaires.value.isEmpty())
            assertNull(projects.sessions("p").single().pendingRun)
        } finally { service?.close(); graph?.close(); Dispatchers.resetMain() }
    }

    @Test fun legacyDeliveryWithoutReceiptDoesNotInferSuccessFromGitState() = runTest {
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
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = reopened)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            assertTrue(runtime.calls.isEmpty())
            assertEquals(1, port.deliveries)
            assertNotNull(reopened.sessions("p").single().pendingRun)
            assertEquals(response, reopened.sessions("p").single().taskWorktree?.executionResponse)
            assertTrue(reopened.messages("p", "s").none { it.id == "response" })
            assertFailsWith<IllegalStateException> { worktrees.complete(project, "s", "request", planAccepted = true, repair = {}) }
            assertTrue(runtime.calls.isEmpty())
            assertEquals(1, port.deliveries, "Ancestry or an old saved phase cannot authorize another delivery")
            assertTrue("workspace:s" in projects.states.value.getValue("p").unknownChildren)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    @Test fun legacyFailedVerificationNeedsEvidenceBeforeResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            val response = CodingMessage("response", CodingRole.AGENT, "Saved answer", createdAt = 2)
            repo.save(project)
            repo.saveSession(session.copy(pendingRun = CodingRunCheckpoint("request", "Task", responseId = "response",
                worktreeEnabled = true, intent = ExecutionIntent.STOP, stoppedByUser = true),
                taskWorktree = TaskWorktree("request", "/source", "main", "base", "/isolated", "task",
                    phase = TaskWorktreePhase.MERGING, resultCommit = "result", targetCommit = "base", mergeCommit = "result",
                    error = "Неподдерживаемый файл снимка: tools/mission-visualization", executionResponse = response)))
            val reopened = JsonCodingProjectRepository(f.kv, f.json)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = reopened)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            assertEquals(0, port.deliveries, "Restoring a stopped task must not start Git operations")
            assertFailsWith<IllegalStateException> { worktrees.retryFailedVerification("p", "s")
                worktrees.complete(project, "s", "request", planAccepted = true, repair = {}) }
            assertTrue(runtime.calls.isEmpty())
            assertEquals(0, port.opens)
            assertEquals(0, port.deliveries)
            val stopped = reopened.sessions("p").single()
            assertEquals(TaskWorktreePhase.MERGING, stopped.taskWorktree?.phase)
            assertEquals("task", stopped.taskWorktree?.branch)
            assertNotNull(stopped.pendingRun)
            assertEquals(response, stopped.taskWorktree?.executionResponse)
            assertTrue("workspace:s" in projects.states.value.getValue("p").unknownChildren)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    /** A new request cannot turn an unjournaled interrupted writer into a confirmed stopped one. */
    @Test fun newPromptKeepsLegacyUnknownTaskAndDoesNotLaunchNative() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project)
            // Упавшая сессия: задача осталась незавершённой, чекпоинт её запуска уже потерян,
            // поэтому новый запрос приходит с собственным идентификатором.
            repo.saveSession(session.copy(
                taskWorktree = TaskWorktree("old-task", "/source", "main", "base", "/isolated", "task",
                    phase = TaskWorktreePhase.RUNNING, error = "Не удалось продолжить работу. Проверьте подключение и состояние сессии.")))
            repo.saveMessages("p", "s", listOf(CodingMessage("old-message", CodingRole.USER, "Первая задача", createdAt = 1)))
            val reopened = JsonCodingProjectRepository(f.kv, f.json)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = reopened)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            service.sendCodingPromptTo("s", "Заверши начатую задачу"); runCurrent()
            val finished = reopened.sessions("p").single()
            assertEquals(TaskWorktreePhase.RUNNING, finished.taskWorktree?.phase)
            assertEquals("old-task", finished.taskWorktree?.taskId)
            assertTrue(runtime.calls.isEmpty())
            assertEquals(0, port.deliveries)
            assertTrue("workspace:s" in projects.states.value.getValue("p").unknownChildren)
            assertTrue(reopened.messages("p", "s").any { it.role == CodingRole.USER && it.text == "Заверши начатую задачу" },
                "A blocked continuation must retain the user's accepted input")
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    /** An unrelated stopped checkpoint cannot prove the legacy workspace operation completed. */
    @Test fun foreignCheckpointCannotAuthorizeLegacyUnknownTask() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture(); f.seed()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project)
            // После падения чекпоинт остановки ссылается на повтор пользователя, а не на задачу сессии.
            repo.saveSession(session.copy(
                pendingRun = CodingRunCheckpoint("retry-request", "Повтор после падения", worktreeEnabled = true,
                    intent = ExecutionIntent.STOP, stoppedByUser = true),
                taskWorktree = TaskWorktree("old-task", "/source", "main", "base", "/isolated", "task",
                    phase = TaskWorktreePhase.RUNNING, error = "Не удалось продолжить работу. Проверьте подключение и состояние сессии.")))
            repo.saveMessages("p", "s", listOf(
                CodingMessage("old-message", CodingRole.USER, "Первая задача", createdAt = 1),
                CodingMessage("retry-request-response", CodingRole.AGENT, "Не удалось завершить прогон", createdAt = 2, failed = true)))
            val reopened = JsonCodingProjectRepository(f.kv, f.json)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = reopened)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val runtime = Runtime(worktrees)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            service.resumeCodingSession("s"); runCurrent()
            val finished = reopened.sessions("p").single()
            assertEquals(TaskWorktreePhase.RUNNING, finished.taskWorktree?.phase)
            assertEquals("old-task", finished.taskWorktree?.taskId)
            assertTrue(runtime.calls.isEmpty())
            assertEquals(0, port.deliveries)
            assertNotNull(finished.pendingRun)
        } finally { service?.close(); Dispatchers.resetMain() }
    }
}
