package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.CodingCommandRejected
import io.aequicor.magicpaper.data.coding.CodingTaskWorktreeSessionAccess
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.journalCodingProjects
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.JournalRevision
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolCatalog
import io.aequicor.magicpaper.domain.tools.ToolExecutionContext
import io.aequicor.magicpaper.domain.tools.ToolSession
import io.aequicor.magicpaper.di.CodingRuntimeGraph
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.logging.LogLevel
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
        /** Seatbelt refuses program start to every check the user has neither allowed nor waived. */
        var refusesSpawn = false
        val grants = mutableListOf<TaskCheckGrants>()
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
            val decided = operation.leases.checks
            grants += decided
            if (refusesSpawn) record.checks.firstOrNull { it !in decided.spawning && it !in decided.skipped }?.let {
                throw TaskWorktreeVerificationFailed("Песочница проверок не дала команде запустить программу\n" +
                    "xargs: /bin/echo: Operation not permitted", spawnRefused = it)
            }
            verificationError?.let { throw TaskWorktreeVerificationFailed(it) }
            verifyGate?.await()
        }
        override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) {
            if (advanceAtDelivery) { advanceAtDelivery = false; destination = "next"; throw TaskDestinationChanged() }
            deliveries++
        }
        override suspend fun delivered(record: TaskWorktree) = deliveries > 0
    }
    private class Runtime(val worktrees: TaskWorktreeService, private val questions: RuntimeQuestionnaireService? = null) : CodingRuntime {
        override val questionnaires: StateFlow<List<UserInteractionRequest>> = questions?.requests ?: MutableStateFlow(emptyList())
        override suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) = checkNotNull(questions).respond(id, answers)
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
        /** Pi records no terminal outcome for a turn stopped midway. */
        var cancelledOutcome = NativeRunOutcome.FAILED
        var reply: (Int) -> String = { "Finished implementation" }
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
        /** The native journal's contract: an unproven exit or an unknown outcome refuses, even after the user's decision. */
        override suspend fun reconcile(sessionId: String) {
            val snapshot = recovery.inspect(sessionId)
            if (snapshot.items.any { it.outcome == NativeRunOutcome.UNKNOWN || it.termination != NativeRunTermination.STOPPED })
                throw NativeRunRecoveryRequired(snapshot)
        }
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
                // The engine is offered only the tools its context allows: an unoffered handoff never reaches the service.
                val context = ToolExecutionContext.worker(session)
                if (handoff && ToolCatalog.get("task.handoff").allowed(context)) worktrees.handoff(context, true, checks)
                emit(CodingEvent.FinalText(reply(calls.size)))
                emit(CodingEvent.Finished)
                outcomes[ref] = outcomes.getValue(ref).copy(outcome = NativeRunOutcome.SUCCEEDED)
            } catch (cancelled: CancellationException) {
                // This controlled fixture launches no process or external tool. Joining its flow proves the stop.
                outcomes[ref] = outcomes.getValue(ref).copy(outcome = cancelledOutcome)
                throw cancelled
            } finally { outcomes[ref] = outcomes.getValue(ref).copy(termination = NativeRunTermination.STOPPED) }
        }
    }
    private suspend fun TestScope.fixture(sessions: (TaskWorktreeSessionAccess) -> TaskWorktreeSessionAccess = { it },
        questions: RuntimeQuestionnaireService? = null,
        block: suspend (DefaultCodingService, Runtime, Workspace, CodingProjectOwner) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = Workspace()
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv,
                sessions = sessions(CodingTaskWorktreeSessionAccess(projects)), questions = questions)
            val runtime = Runtime(worktrees, questions)
            service = f.prepareCoding(runtime, projects, taskWorktrees = worktrees)
            runCurrent()
            block(service, runtime, port, projects)
        } finally { service?.close(); Dispatchers.resetMain() }
    }

    /**
     * The Git probe is several journaled Git reads, about half a second each. The session list and a
     * session switch show the last known answer instead of waiting for it; a switch refreshes it behind.
     */
    @Test fun sessionListAndSwitchDoNotWaitForGitProbe() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val answers = Channel<WorktreeAvailability>()
        var probes = 0
        var service: DefaultCodingService? = null
        try {
            val f = ModelSettingsFixture()
            val repo = JsonCodingProjectRepository(f.kv, f.json)
            repo.save(project); repo.saveSession(session)
            val port = object : TaskWorkspace by Workspace() {
                override suspend fun availability(project: CodingProject): WorktreeAvailability { probes++; return answers.receive() }
            }
            val events = InMemoryEventJournal()
            val projects = journalCodingProjects(f.kv, f.json, journal = events, checkpoints = repo)
            val worktrees = testTaskWorktreeService(projects, port, LocalPlanningWorkspace(), events, f.kv)
            val opening = async { f.prepareCoding(Runtime(worktrees), projects, taskWorktrees = worktrees) }
            runCurrent()
            assertTrue(opening.isCompleted, "the list and the opened session are published before Git answers")
            val opened = opening.await().also { service = it }
            assertEquals(WorktreeAvailability(false, "Проверка Git…"), opened.state.value.coding.sessions.single().worktreeAvailability)
            opened.activate("p", "s"); runCurrent()
            assertEquals(1, probes, "a switch while the probe runs joins it instead of starting another")

            answers.send(WorktreeAvailability(true)); runCurrent()
            assertTrue(opened.state.value.coding.sessions.single().worktreeAvailability.available)
            opened.activate("p", "s"); runCurrent()
            assertEquals(2, probes, "a later switch refreshes the answer")
            assertTrue(opened.state.value.coding.sessions.single().worktreeAvailability.available,
                "the switch shows the last known answer while the refresh runs")

            answers.send(WorktreeAvailability(false, "Выберите Git-ветку")); runCurrent()
            assertEquals(WorktreeAvailability(false, "Выберите Git-ветку"), opened.state.value.coding.sessions.single().worktreeAvailability)
        } finally { answers.close(); service?.close(); Dispatchers.resetMain() }
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

    /**
     * The checks are the agent's own, so their failure goes back to it instead of ending the session: the repair is
     * told what failed, fixes the command it handed off, and its second handoff is the one verified and delivered.
     */
    @Test fun failedChecksAreReturnedToTheAgentAndItsRepairIsDelivered() = runTest { fixture { service, runtime, port, repo ->
        val report = "Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение\n" +
            "Команда проверки не найдена: gradlew.bat"
        port.verificationError = report
        runtime.checks = listOf(listOf("gradlew.bat", "test"))
        runtime.reply = { call -> listOf("Finished implementation", "Fixed the check command")[call - 1] }
        runtime.onRun = { call -> if (call == 2) { runtime.checks = listOf(listOf("./gradlew.bat", "test")); port.verificationError = null } }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(2, runtime.calls.size, "the first run and one repair")
        assertContains(runtime.prompts[1], report, message = "the repair is told what the checks reported")
        val delivered = repo.sessions("p").single()
        assertEquals(delivered.taskWorktree?.taskId, runtime.calls[1].second.pendingRun?.workspaceTaskId, "the repair continues the same task")
        assertEquals(TaskWorktreePhase.COMPLETE, delivered.taskWorktree?.phase)
        assertEquals(listOf(listOf("./gradlew.bat", "test")), delivered.taskWorktree?.checks, "the repaired handoff is the one verified")
        assertEquals(1, port.deliveries)
        assertNull(delivered.pendingRun)
        val answer = repo.messages("p", "s").last { it.role == CodingRole.AGENT && !it.systemNotice }
        assertFalse(answer.failed)
        assertEquals("Fixed the check command", answer.text)
        val returned = AppLog.history().last { it.component == "coding.worktree" && it.event == "verification.returned" }
        assertEquals("1", returned.fields["attempt"])
    } }

    /**
     * Checks that keep failing are returned to the agent a bounded number of times, and the last refusal stops the run
     * with the saved result kept. Once the cause is gone outside the task — the check passes again — an explicit retry
     * delivers that result without another agent run.
     */
    @Test fun checksThatKeepFailingStopAfterBoundedRepairsAndAnExplicitRetryDeliversWithoutTheAgent() = runTest { fixture { service, runtime, port, repo ->
        port.verificationError = "Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение\nFAILED: SuiteTest"
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(4, runtime.calls.size, "the first run and three repairs")
        val failed = repo.sessions("p").single()
        val task = failed.taskWorktree!!
        assertEquals(TaskWorktreePhase.MERGING, task.phase)
        assertEquals(port.verificationError, task.error, "the user sees what the checks reported")
        assertEquals(ExecutionIntent.STOP, failed.pendingRun?.intent)
        assertEquals(0, port.deliveries)
        val response = assertNotNull(task.executionResponse)
        port.verificationError = null
        val recovery = service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        service.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", selected = listOf("retry")))); runCurrent()
        val completed = repo.sessions("p").single()
        assertEquals(4, runtime.calls.size)
        assertEquals(1, port.opens)
        assertEquals(1, port.deliveries)
        assertEquals(task.taskId, completed.taskWorktree?.taskId)
        assertEquals(task.branch, completed.taskWorktree?.branch)
        assertEquals(TaskWorktreePhase.COMPLETE, completed.taskWorktree?.phase)
        assertNull(completed.pendingRun)
        assertEquals(1, repo.messages("p", "s").count { it.id == response.id })
    } }

    /**
     * An explicit retry verifies the saved result again without the agent, and a refusal there is returned to the agent
     * like the first one: the continuation delivers only after its repair.
     */
    @Test fun aRetriedVerificationThatFailsAgainIsReturnedToTheAgent() = runTest { fixture { service, runtime, port, repo ->
        port.verificationError = "Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение\nFAILED: SuiteTest"
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(4, runtime.calls.size)
        runtime.onRun = { call -> if (call == 5) port.verificationError = null }
        val recovery = service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        service.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
        assertEquals(5, runtime.calls.size, "the retried verification failed, and only its repair ran the agent")
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertEquals(1, port.deliveries)
    } }

    /**
     * A repair that ends its answer without handing off again is refused with the reason the task keeps, and the task
     * stays with the agent: continuing runs it on the same copy, and that handoff is delivered.
     */
    @Test fun aChecksRepairWithoutANewHandoffStopsWithItsReasonAndContinuesTheSameTask() = runTest { fixture { service, runtime, port, repo ->
        port.verificationError = "Проверка результата завершилась с ошибкой. Исправьте изменения и повторите продолжение\nFAILED: SuiteTest"
        runtime.onRun = { call -> runtime.handoff = call != 2; if (call == 3) port.verificationError = null }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(2, runtime.calls.size)
        val stopped = repo.sessions("p").single()
        val task = stopped.taskWorktree!!
        assertEquals(TaskWorktreePhase.RUNNING, task.phase, "the task stays returned to the agent")
        assertEquals("Агент завершил ответ, не передав результат задачи, поэтому изменения не влиты. Уточните запрос и продолжите", task.error)
        assertEquals(0, port.deliveries)
        val recovery = service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        service.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
        assertEquals(3, runtime.calls.size, "continuing runs the agent on the returned task")
        val delivered = repo.sessions("p").single()
        assertEquals(task.taskId, delivered.taskWorktree?.taskId)
        assertEquals(TaskWorktreePhase.COMPLETE, delivered.taskWorktree?.phase)
        assertEquals(1, port.deliveries)
    } }

    /**
     * Seatbelt refused `python3` and `gradlew` their program start; the agent could not fix that, handed off BLOCKED and
     * the session stopped. The refusal now asks the user, and the run waits for the answer instead of failing.
     */
    private fun spawnRefusal(answer: String, block: suspend TestScope.(DefaultCodingService, Runtime, Workspace, CodingProjectOwner) -> Unit) =
        runTest { fixture(questions = DefaultRuntimeQuestionnaireService(InMemoryEventJournal(), "test")) { service, runtime, port, repo ->
            port.refusesSpawn = true
            runtime.checks = listOf(listOf("python3", "tools/verify/verify-module-architecture.py", "--self-test"))
            service.sendCodingPromptTo("s", "Task"); runCurrent()
            val question = service.state.value.coding.interactions.single { it.kind == InteractionKind.RUNTIME }
            assertEquals("s", question.sessionId)
            assertContains(question.questions.single().title, "python3 tools/verify/verify-module-architecture.py --self-test")
            assertEquals(listOf("allow", "skip", "agent"), question.questions.single().options.map { it.id })
            assertNotEquals(ExecutionIntent.STOP, repo.sessions("p").single().pendingRun?.intent, "the run waits for the user")
            assertEquals(0, port.deliveries)
            service.submitQuestionnaire(question.id, listOf(PlanningAnswer("check-spawn", selected = listOf(answer)))); runCurrent()
            block(service, runtime, port, repo)
        } }

    @Test fun checkRefusedProgramStartRerunsItWhenTheUserAllowsIt() = spawnRefusal("allow") { _, runtime, port, repo ->
        assertEquals(1, runtime.calls.size, "the agent is not asked to fix what only the user can grant")
        assertEquals(setOf(runtime.checks.single()), port.grants.last().spawning)
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertEquals(1, port.deliveries)
        assertEquals("allow", AppLog.history().last { it.component == "coding.worktree" && it.event == "check.decided" }.fields["result"])
    }

    @Test fun checkRefusedProgramStartIsWaivedWhenTheUserSkipsIt() = spawnRefusal("skip") { _, runtime, port, repo ->
        assertEquals(1, runtime.calls.size)
        assertEquals(setOf(runtime.checks.single()), port.grants.last().skipped)
        assertTrue(port.grants.last().spawning.isEmpty(), "a skip grants nothing")
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertEquals(1, port.deliveries)
    }

    @Test fun checkRefusedProgramStartGoesToTheAgentWhenTheUserReturnsIt() = spawnRefusal("agent") { service, runtime, port, repo ->
        assertEquals(2, runtime.calls.size, "the agent may change the command")
        assertContains(runtime.prompts[1], "Песочница проверок не дала команде запустить программу")
        // The repair handed off the same command, so the containment refuses it again and the user is asked again.
        assertEquals(1, service.state.value.coding.interactions.count { it.kind == InteractionKind.RUNTIME })
        assertEquals(0, port.deliveries)
        assertNotEquals(ExecutionIntent.STOP, repo.sessions("p").single().pendingRun?.intent)
    }

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
     * A repair that ends without a handoff fails the run, and the user continues it: the continued delivery repairs
     * again. Each repair is a fresh request of the same task, so each is offered the handoff, and what each agent
     * said stays in the history instead of the task's first answer.
     */
    @Test fun continuedConflictRepairIsHandedOffAndEveryRepairKeepsItsTranscript() = runTest { fixture { service, runtime, port, repo ->
        port.conflict = true
        runtime.reply = { call -> listOf("Finished implementation", "Staged without handoff", "Conflict resolved")[call - 1] }
        runtime.onRun = { call -> runtime.handoff = call != 2; if (call == 3) port.conflict = false }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(TaskWorktreePhase.CONFLICT, repo.sessions("p").single().taskWorktree?.phase)
        val unfinished = repo.messages("p", "s").last { it.role == CodingRole.AGENT && !it.systemNotice }
        assertTrue(unfinished.failed)
        assertEquals("Staged without handoff", unfinished.text)

        val recovery = service.state.value.coding.interactions.single { it.kind == InteractionKind.RECOVER_RUN }
        service.submitQuestionnaire(recovery.id, listOf(PlanningAnswer("decision", listOf("retry")))); runCurrent()
        assertEquals(3, runtime.calls.size, "the continued delivery launches only its repair")
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertEquals(1, port.deliveries)
        val history = repo.messages("p", "s")
        val delivered = history.last { it.role == CodingRole.AGENT && !it.systemNotice }
        assertFalse(delivered.failed)
        assertEquals("Conflict resolved", delivered.text)
        assertTrue(unfinished in history, "the failed repair stays in the history")
    } }

    /**
     * A repair stopped midway leaves its outcome unknown, and continuing is the user's decision about it. That decision
     * settles the attempt for the next run of the session, as the native journal admits it: the reconciliations before
     * the relaunch, the delivery and after the repair refused it forever, so every continuation failed the same way.
     */
    @Test fun repairStoppedMidTurnContinuesOnceTheUserDecidedItsUnknownOutcome() = runTest { fixture { service, runtime, port, repo ->
        port.conflict = true
        runtime.cancelledOutcome = NativeRunOutcome.UNKNOWN
        runtime.onRun = { call -> runtime.gate = if (call == 2) CompletableDeferred() else null; if (call == 3) port.conflict = false }
        service.sendCodingPromptTo("s", "Task"); runCurrent()
        assertEquals(2, runtime.calls.size)
        service.abortCodingSession("s"); runCurrent()
        assertEquals(CodingMachine.Phase.UNKNOWN, service.state.value.coding.currentSession!!.runPhase)

        service.resumeCodingSession("s"); runCurrent()
        assertEquals(3, runtime.calls.size, "the continued delivery relaunches only its repair")
        assertNotNull(runtime.bindings.last()?.acknowledgement, "the repair carries the user's decision to the native journal")
        assertEquals(TaskWorktreePhase.COMPLETE, repo.sessions("p").single().taskWorktree?.phase)
        assertEquals(1, port.deliveries)
    } }

    @Test fun refusedTaskFailureNoteLeavesTheRepairTranscriptInTheHistory() = runTest {
        fixture(sessions = ::RefusingFailureNotes) { service, runtime, port, repo ->
            port.conflict = true
            runtime.reply = { call -> if (call == 1) "Finished implementation" else "Staged without handoff" }
            runtime.onRun = { call -> runtime.handoff = call != 2 }
            service.sendCodingPromptTo("s", "Task"); runCurrent()
            val failed = repo.messages("p", "s").last { it.role == CodingRole.AGENT && !it.systemNotice }
            assertTrue(failed.failed)
            assertEquals("Staged without handoff", failed.text)
            val refused = AppLog.history().last { it.component == "coding" && it.event == "run.task-note.failed" }
            assertEquals(listOf("CodingCommandRejected"), refused.causeTypes)
            assertNotNull(service.state.value.notice)
            assertFalse(service.state.value.coding.sessions.single().running)
        }
    }

    /** Refuses the note of a failed run, as the parent refused one from a task bound to the launch before the repair. */
    private class RefusingFailureNotes(private val delegate: TaskWorktreeSessionAccess) : TaskWorktreeSessionAccess by delegate {
        override suspend fun publish(projection: TaskWorktreeProjection) {
            if (projection.task?.error == "Конфликт требует продолжения") throw CodingCommandRejected("Сессия удалена или её запуск изменился")
            delegate.publish(projection)
        }
    }

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
