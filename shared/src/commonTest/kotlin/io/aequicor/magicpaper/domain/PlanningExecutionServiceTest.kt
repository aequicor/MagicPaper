package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningExecutionServiceTest {
    @Test fun sharedWorkspacePlansUseOneWriterLeaseAndReleaseItAfterStop() = runTest {
        val workspace = Workspaces()
        val (store, service, runtime) = fixture(Runtime(CompletableDeferred()), workspace)
        val first = plan(stage("first")).copy(id = "first-plan", sharedWorkspace = true)
        val second = plan(stage("second")).copy(id = "second-plan", sharedWorkspace = true)
        store.save(first); store.save(second)
        service.start(first.id); runCurrent()
        val originalCall = runtime.calls.single()
        service.start(second.id); runCurrent()
        assertEquals(listOf(originalCall), runtime.calls)
        assertTrue(store.planFor(second.id)!!.milestones.single().attempts.isEmpty())
        service.stop(first.id); runCurrent()
        assertEquals(1, workspace.released)
        service.start(second.id); runCurrent()
        assertEquals(2, runtime.calls.size)
        assertNotEquals(originalCall, runtime.calls.last())
        service.stop(second.id); runCurrent()
        assertEquals(2, workspace.released)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profile = LlmProfile("agent", "Agent", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
    private val project = CodingProject("project", "Project", "/fake", 1)
    private val pass = object : MilestoneVerifier {
        override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "checked")
    }
    private class Runtime(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: String? = null,
        private val failuresBeforeSuccess: Int = Int.MAX_VALUE,
        private val commandGate: CompletableDeferred<Unit>? = null,
        private val trailingDelta: String? = null,
        private val report: String = "Verified result",
        private val command: String = "./gradlew :shared:jvmTest",
        private val cleanupGate: CompletableDeferred<Unit>? = null,
        private val terminateAfterOutput: (suspend () -> Unit)? = null,
    ) : CodingRuntime {
        val engines = mutableListOf<CodingEngine?>()
        val sessions = mutableListOf<CodingSession>()
        val calls = mutableListOf<String>(); val aborted = mutableListOf<String>()
        var onRun: suspend (CodingSession) -> Unit = {}
        var reconciliationFailure: String? = null
        override suspend fun reconcile(sessionId: String) { reconciliationFailure?.let { error(it) } }
        override val supported = true; override val rootPath = "/fake"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun abort(sessionId: String) { aborted += sessionId }
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += session.id
            sessions += session
            onRun(session)
            engines += session.engine
            emit(CodingEvent.SessionStarted("engine-${session.id}"))
            emit(CodingEvent.ThinkingDelta("Проверяю критерии"))
            emit(CodingEvent.ToolStarted("read", "Чтение проекта", "read-1"))
            emit(CodingEvent.ToolFinished("read", false, "read-1", "Файлы прочитаны"))
            if (commandGate != null) {
                emit(CodingEvent.ToolStarted("command", command, "command-1", isExec = true))
                commandGate.await()
                emit(CodingEvent.ToolFinished("command", false, "command-1", "BUILD SUCCESSFUL"))
            }
            trailingDelta?.let { emit(CodingEvent.TextDelta(it)) }
            terminateAfterOutput?.invoke()
            gate?.await()
            if (failure != null && calls.size <= failuresBeforeSuccess) emit(CodingEvent.Failed(failure)) else emit(CodingEvent.FinalText(report))
            emit(CodingEvent.Finished)
        }.onCompletion { if (cleanupGate != null) withContext(NonCancellable) { cleanupGate.await() } }
    }
    private class Workspaces(private val parallel: Boolean = true, private val base: PlanningWorkspace = LocalPlanningWorkspace()) : PlanningWorkspace by base {
        override suspend fun verificationSnapshot(path: String) = "fixture-snapshot"
        var applied = 0
        var released = 0
        override suspend fun release(project: CodingProject) { released++; base.release(project) }
        override suspend fun prepare(project: CodingProject, runId: String) = PlanWorkspace("/fake", "/fake", git = parallel)
        override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace { applied++; return workspace.copy(applied = true) }
    }
    private suspend fun TestScope.fixture(runtime: Runtime = Runtime(), workspace: PlanningWorkspace = Workspaces(), verifier: MilestoneVerifier = pass,
        acceptanceChecks: AcceptanceChecks = AcceptanceChecks(), retryLimit: Int? = 3): Triple<PlanningStore, PlanningExecutionService, Runtime> {
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val profiles = JsonLlmProfileRepository(kv, json).also { it.save(profile) }
        val projects = JsonCodingProjectRepository(kv, json).also { it.save(project) }
        val settings = JsonSettingsRepository(kv, json)
        settings.save(AppSettings(agentLimits = OrganismLimits(retries = retryLimit)))
        return Triple(store, PlanningExecutionService(store, runtime, projects, profiles, settings, verifier, workspace, backgroundScope,
            outputClock = { testScheduler.currentTime }, acceptanceChecks = acceptanceChecks), runtime)
    }
    private fun plan(vararg stages: Milestone) = Plan("plan", "project", "Goal", milestones = stages.toList())
    private fun stage(id: String, depends: List<String> = emptyList()) = Milestone(id, id, description = "Check result", agentProfileId = "agent", dependsOn = depends)

    @Test fun admittedGenerationIsSavedBeforeNativeRunAndRetainedInCompletionCheckpoint() = runTest {
        val (store, service, runtime) = fixture()
        val checkpoints = mutableListOf<StageAttempt>()
        service.prepareAttempt = { plan, _, attempt ->
            assertTrue(plan.journal.any { it.operation == "agent-intent" && it.attemptId == attempt.id })
            assertTrue(runtime.calls.isEmpty())
            attempt.copy(sessionGeneration = 41)
        }
        service.attemptCheckpoint = { plan, stageId, attempt ->
            assertEquals(attempt, store.planFor(plan.id)!!.milestones.first { it.id == stageId }.attempts.first { it.id == attempt.id })
            checkpoints += attempt
        }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        val completed = checkpoints.first { it.phase == AttemptPhase.COMPLETE }
        assertEquals(41L, completed.sessionGeneration)
        assertEquals(41L, runtime.sessions.first { it.id == completed.sessionId }.runtimeGeneration)
        assertEquals(41L, store.planFor(project.id)!!.milestones.single().attempts.single().sessionGeneration)
    }

    @Test fun rejectedApplicationAdmissionPreventsNativeStartup() = runTest {
        val (store, service, runtime) = fixture()
        service.prepareAttempt = { _, _, _ -> error("Admission refused") }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertNotNull(store.planFor(project.id)!!.issue)
    }

    @Test fun stopProjectionWaitsForNativeCleanupAndSuccessfulReconciliation() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val (store, service, runtime) = fixture(Runtime(CompletableDeferred(), cleanupGate = cleanup))
        var projected = 0
        service.stoppedCheckpoint = { plan ->
            assertFalse(plan.stopping)
            assertEquals(PlanStatus.STOPPED, store.planFor(plan.id)!!.status)
            projected++
        }
        store.save(plan(stage("work")))
        service.start(project.id); runCurrent()
        runtime.reconciliationFailure = "native still owns writer"
        service.stop(project.id); runCurrent()
        assertEquals(0, projected)
        cleanup.complete(Unit); advanceTimeBy(1_000); runCurrent()
        assertEquals(0, projected)
        assertTrue(store.planFor(project.id)!!.stopping)
        runtime.reconciliationFailure = null
        service.stop(project.id); runCurrent()
        assertEquals(1, projected)
    }

    @Test fun projectionFailureAfterAcceptedPlanCommitNeverDowngradesOrReexecutesTheStage() = runTest {
        val (store, service, runtime) = fixture()
        var available = false
        service.prepareAttempt = { _, _, attempt -> attempt.copy(sessionGeneration = 7) }
        service.attemptCheckpoint = { _, _, attempt ->
            if (attempt.phase == AttemptPhase.COMPLETE) check(available) { "Organism projection unavailable" }
        }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        val committed = store.planFor(project.id)!!
        assertEquals(AttemptPhase.COMPLETE, committed.milestones.single().attempts.single().phase)
        assertEquals(MilestoneStatus.DONE, committed.milestones.single().status)
        assertEquals(1, committed.pendingSessionProjections.size)
        assertEquals(1, committed.journal.count { it.operation == "session-projection-pending" })
        val calls = runtime.calls.toList()
        service.synchronizeSessionProjections(committed.id)
        assertEquals(1, store.planFor(project.id)!!.journal.count { it.operation == "session-projection-pending" })
        available = true
        service.synchronizeSessionProjections(committed.id)
        val synchronized = store.planFor(project.id)!!
        assertTrue(synchronized.pendingSessionProjections.isEmpty())
        assertEquals(AttemptPhase.COMPLETE, synchronized.milestones.single().attempts.single().phase)
        assertEquals(calls, runtime.calls)
    }

    @Test fun controllerCallbackRequestsStopWithoutJoiningItsOwnAncestor() = runTest {
        val (store, service, runtime) = fixture()
        val confirmed = CompletableDeferred<Boolean>()
        runtime.onRun = {
            val caller = currentCoroutineContext().job
            withContext(NonCancellable) { confirmed.complete(service.stopController("plan", caller)) }
        }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertFalse(confirmed.await())
        assertFalse(store.planFor(project.id)!!.stopping)
        assertEquals(PlanStatus.STOPPED, store.planFor(project.id)!!.status)
    }

    @Test fun stoppingAnAlreadyCompletedControllerPreservesItsAcceptedPlan() = runTest {
        val (store, service, _) = fixture()
        val completed = plan(stage("work").copy(status = MilestoneStatus.DONE)).copy(phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE)
        store.save(completed)
        val saved = store.planFor(completed.id)
        assertTrue(service.stopController(completed.id, currentCoroutineContext().job))
        assertEquals(saved, store.planFor(completed.id))
    }

    @Test fun questionInterruptsOnlyAffectedWorkerAndResumesSameAttempt() = runTest {
        val (store, service, runtime) = fixture(Runtime(CompletableDeferred(), trailingDelta = "Saved progress"))
        var blocked = emptySet<String>()
        service.chatHooks = object : PlanningExecutionHooks {
            override suspend fun blockedStages(plan: Plan) = blocked
            override suspend fun prepareSessions(plan: Plan) = Unit
            override suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt) = ""
            override suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt) = StageTurnDecision(StageTurnAction.VERIFY, attempt.report)
        }
        store.save(plan(stage("research"), stage("design")).copy(parallelism = 2))
        service.start(project.id); advanceTimeBy(200); runCurrent()
        val before = store.planFor(project.id)!!.milestones.first { it.id == "design" }.attempts.single()
        val research = store.planFor(project.id)!!.milestones.first { it.id == "research" }.attempts.single()
        blocked = setOf("design")
        advanceTimeBy(200); runCurrent()
        assertContains(runtime.aborted, before.sessionId)
        assertFalse(research.sessionId in runtime.aborted)
        val paused = store.planFor(project.id)!!.milestones.first { it.id == "design" }.attempts.single()
        assertEquals("Saved progress", paused.report)
        assertTrue(paused.interrupted)
        val pausedPlan = store.planFor(project.id)!!
        assertFalse(pausedPlan.isStageWorking(pausedPlan.milestones.first { it.id == "design" }))
        assertTrue(pausedPlan.isStageWorking(pausedPlan.milestones.first { it.id == "research" }))
        assertTrue(paused.chatTurns.all { it.completedAt != 0L })
        assertTrue(json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), pausedPlan)).milestones.first { it.id == "design" }.attempts.single().interrupted)
        assertNull(paused.error)
        assertEquals(before.transportRetries, paused.transportRetries)
        val calls = runtime.calls.size
        advanceTimeBy(1000); runCurrent()
        assertEquals(calls, runtime.calls.size)
        service.pause(project.id)
        blocked = emptySet()
        advanceTimeBy(200); runCurrent()
        assertEquals(calls, runtime.calls.size)
        assertEquals(ExecutionIntent.PAUSE, store.planFor(project.id)!!.intent)
        service.stopAndJoin("plan")
        service.start(project.id); advanceTimeBy(200); runCurrent()
        val resumed = store.planFor(project.id)!!.milestones.first { it.id == "design" }.attempts.single()
        assertEquals(before.id, resumed.id)
        assertEquals(paused.engineSessionId, resumed.engineSessionId)
        assertFalse(resumed.interrupted)
        assertTrue(store.planFor(project.id)!!.isStageWorking(store.planFor(project.id)!!.milestones.first { it.id == "design" }))
        service.shutdown()
    }

    @Test fun selectivePauseClosesToolCardsAndRetainsUncertainEffectsBeforeResume() = runTest {
        val runtime = Runtime(commandGate = CompletableDeferred(), command = "publish-release")
        val (store, service) = fixture(runtime)
        var blocked = emptySet<String>()
        service.chatHooks = object : PlanningExecutionHooks {
            override suspend fun blockedStages(plan: Plan) = blocked
            override suspend fun prepareSessions(plan: Plan) = Unit
            override suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt) = ""
            override suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt) = StageTurnDecision(StageTurnAction.VERIFY, attempt.report)
        }
        store.save(plan(stage("a"))); service.start(project.id); runCurrent()
        blocked = setOf("a")
        service.interruptStages("plan", blocked); runCurrent()
        val paused = store.planFor("plan")!!.milestones.single().attempts.single()
        val command = paused.steps.single { it.callId == "command-1" }
        assertFalse(command.running)
        assertEquals(io.aequicor.magicpaper.domain.tools.ToolPhase.UNKNOWN, command.toolPhase)
        assertEquals("publish-release", paused.pendingTool)
        assertTrue(paused.pendingToolExternal)
        assertTrue(paused.interrupted)
        blocked = emptySet()
        advanceTimeBy(200); runCurrent(); service.start(project.id); runCurrent()
        assertEquals(1, runtime.calls.size, "An interrupted external effect must not run again automatically")
        assertEquals(IssueKind.UNCERTAIN, store.planFor("plan")!!.issue?.kind)
        service.shutdown()
    }

    @Test fun interruptionDuringVerificationPreservesTheCheckpointAndDoesNotRepeatWorkerChanges() = runTest {
        val gate = CompletableDeferred<Unit>()
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                gate.await(); return Verdict(true, "Checked")
            }
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        store.save(plan(stage("a"))); service.start(project.id); runCurrent()
        assertEquals(AttemptPhase.VERIFYING, store.planFor("plan")!!.milestones.single().attempts.single().phase)
        service.stopAndJoin("plan")
        val interrupted = store.planFor("plan")!!.milestones.single().attempts.single()
        assertEquals(AttemptPhase.VERIFYING, interrupted.phase)
        assertTrue(interrupted.interrupted)
        gate.complete(Unit); service.start(project.id); advanceTimeBy(500); runCurrent()
        assertEquals(1, runtime.calls.count { it == interrupted.sessionId })
        val completed = store.planFor("plan")!!.milestones.single().attempts.single()
        assertEquals(AttemptPhase.COMPLETE, completed.phase)
        assertFalse(completed.interrupted)
        service.shutdown()
    }

    @Test fun chosenEngineIsInheritedByWorkersAndFinalVerification() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("a")).copy(engine = CodingEngine.CODEX))
        service.start(project.id); advanceTimeBy(500); runCurrent()
        assertEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
        assertTrue(runtime.engines.size >= 2)
        assertTrue(runtime.engines.all { it == CodingEngine.CODEX })
        assertEquals(CodingEngine.CODEX, store.planFor(project.id)!!.milestones.single().attempts.single().engine)
        assertEquals(CodingEngine.CODEX, store.planFor(project.id)!!.finalAttempt!!.engine)
    }

    @Test fun commandAppearsInLiveMessageBeforeOutputAndUpdatesOnCompletion() = runTest {
        val commandGate = CompletableDeferred<Unit>()
        val (store, service) = fixture(Runtime(gate = CompletableDeferred(), commandGate = commandGate))
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        val attemptId = store.planFor(project.id)!!.milestones.single().attempts.single().id
        val running = service.live.value[attemptId]!!.steps.single { it.kind == CodingStepKind.EXEC }
        assertTrue(running.running)
        assertContains(running.title, "./gradlew :shared:jvmTest")
        assertEquals("", running.result)

        commandGate.complete(Unit); runCurrent()
        val finished = service.live.value[attemptId]!!.steps.single { it.kind == CodingStepKind.EXEC }
        assertEquals(running.callId, finished.callId)
        assertFalse(finished.running)
        assertTrue(finished.ok)
        assertEquals("BUILD SUCCESSFUL", finished.result)
        service.stop(project.id); runCurrent()
    }

    @Test fun parallelStagesStartTogetherAndJoinWaits() = runTest {
        val gate = CompletableDeferred<Unit>(); val runtime = Runtime(gate)
        val (store, service) = fixture(runtime)
        store.save(plan(stage("a"), stage("b"), stage("join", listOf("a", "b"))))
        service.start(project.id); runCurrent()
        assertEquals(2, runtime.calls.size)
        gate.complete(Unit); advanceTimeBy(500); runCurrent()
        assertEquals(4, runtime.calls.size) // includes the combined-project verification
        assertEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
        assertContains(store.planFor(project.id)!!.milestones.first().attempts.first().prompt, "Общая цель: Goal")
        val timeline = store.planFor(project.id)!!.milestones.first().attempts.first().steps
        assertTrue(timeline.any { it.kind == CodingStepKind.THINKING && it.title == "Проверяю критерии" })
        assertTrue(timeline.any { it.kind == CodingStepKind.TOOL && it.result == "Файлы прочитаны" })
    }
    @Test fun sharedFolderKeepsNextWorkerOutUntilAcceptanceCompletes() = runTest {
        val reviewing = CompletableDeferred<Unit>()
        val runtime = Runtime()
        val workspace = object : PlanningWorkspace by Workspaces() {
            // Every worker changes the shared project, including unrelated stages.
            override suspend fun verificationSnapshot(path: String) = "files-after-${runtime.calls.count { !it.contains("-final") }}"
        }
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (milestone.id == "a") reviewing.await()
                return Verdict(true, "checked")
            }
        }
        val (store, service) = fixture(runtime, workspace, judge)
        store.save(plan(stage("a"), stage("b")).copy(sharedWorkspace = true, parallelism = 8))
        service.start(project.id); advanceTimeBy(500); runCurrent()
        assertEquals(1, runtime.calls.size, "Another writer would invalidate the acceptance snapshot")
        reviewing.complete(Unit); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(3, runtime.calls.size)
        assertTrue(saved.milestones.all { it.attempts.single().repairRetries == 0 })
        assertTrue(saved.milestones.all { it.attempts.single().acceptanceRecord?.status == AcceptanceStatus.ACCEPTED })
    }

    @Test fun silentRunningStageDoesNotAccumulateEmptyActivity() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (store, service) = fixture(Runtime(gate))
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        advanceTimeBy(2500); runCurrent()
        val saved = store.planFor(project.id)!!
        val live = service.live.value
        advanceTimeBy(5000); runCurrent()
        assertSame(saved, store.planFor(project.id), "Silent flush ticks must not rewrite the plan or invalidate saved history")
        assertSame(live, service.live.value, "Silence must not allocate and publish new activity snapshots")
        val attempt = store.planFor(project.id)!!.milestones.single().attempts.single()
        assertTrue(attempt.steps.isNotEmpty())
        assertTrue(attempt.steps.none { it.kind == CodingStepKind.INFO && it.title.isBlank() })
        service.stop(project.id); runCurrent()
    }

    @Test fun trailingOutputIsDisplayedAndCheckpointedOnceWhileAgentIsSilent() = runTest {
        val (store, service) = fixture(Runtime(CompletableDeferred(), trailingDelta = "Partial answer"))
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        advanceTimeBy(150); runCurrent()
        assertEquals("Partial answer", service.live.value.values.single().report)
        advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals("Partial answer", saved.milestones.single().attempts.single().report)
        advanceTimeBy(5000); runCurrent()
        assertSame(saved, store.planFor(project.id))
        service.stop(project.id); runCurrent()
    }

    @Test fun longReasoningPauseDoesNotAbortAndCanFinishNormally() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime(gate)
        val (store, service) = fixture(runtime)
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        assertTrue(runtime.aborted.isEmpty())
        assertEquals(ExecutionIntent.RUN, store.planFor(project.id)!!.intent)
        assertNull(store.planFor(project.id)!!.issue)
        gate.complete(Unit); advanceTimeBy(500); runCurrent()
        assertEquals(PlanStatus.DONE, store.planFor(project.id)!!.status)
        val notices = store.planFor(project.id)!!.milestones.single().attempts.single().steps.filter { it.kind == CodingStepKind.INFO }
        assertEquals(1, notices.size)
        assertContains(notices.single().title, "Ожидание новых событий")
    }

    @Test fun silentAgentStillRespondsToExplicitStop() = runTest {
        val runtime = Runtime(CompletableDeferred())
        val (store, service) = fixture(runtime)
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        advanceTimeBy(180_000); runCurrent()
        service.stop(project.id); runCurrent()
        assertTrue(runtime.aborted.isNotEmpty())
        assertEquals(ExecutionIntent.STOP, store.planFor(project.id)!!.intent)
    }

    @Test fun nonGitOnlyStartsOneStage() = runTest {
        val gate = CompletableDeferred<Unit>(); val runtime = Runtime(gate)
        val (store, service) = fixture(runtime, Workspaces(false))
        store.save(plan(stage("a"), stage("b"))); service.start(project.id); runCurrent()
        assertEquals(1, runtime.calls.size)
    }
    @Test fun bootstrapAutomaticallyResumesVerificationWithoutRerunningAgent() = runTest {
        val (store, service, runtime) = fixture()
        val attempt = StageAttempt("attempt", "session", StageAssignment("agent", "m"), phase = AttemptPhase.VERIFYING, path = "/fake", report = "Already done")
        store.save(plan(stage("a").copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))).copy(intent = ExecutionIntent.RUN, runId = "run"))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertTrue(runtime.calls.single().endsWith("-final-session"))
        assertEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
    }
    @Test fun bootstrapLeavesUserPauseAndStopAlone() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("a")).copy(intent = ExecutionIntent.PAUSE)); service.bootstrap(); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        store.update(project.id) { it.copy(intent = ExecutionIntent.STOP) }; advanceTimeBy(6000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
    }
    @Test fun engineFailureCannotPassPositiveVerifier() = runTest {
        val (store, service) = fixture(Runtime(failure = "fatal execution failure"))
        store.save(plan(stage("a"))); service.start(project.id); runCurrent(); advanceTimeBy(200); runCurrent()
        assertNotEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
        assertTrue(store.planFor(project.id)?.issue?.requiresUser == true)
    }

    @Test fun childBudgetCancellationRetainsItsReasonAndCompletedEvidenceWithoutRetry() = runTest {
        assertChildCancellationWaitsForUser("Бюджет сессии исчерпан") {
            throw CancellationException("Бюджет сессии исчерпан")
        }
    }

    @Test fun childDeadlineCancellationRetainsItsReasonAndCompletedEvidenceWithoutRetry() = runTest {
        assertChildCancellationWaitsForUser("Timed out") {
            withTimeout(50) { awaitCancellation() }
        }
    }

    private suspend fun TestScope.assertChildCancellationWaitsForUser(reason: String, terminate: suspend () -> Unit) {
        val partialReport = "Начинаю аудит: файлы прочитаны, проверка результата ещё не завершена."
        val runtime = Runtime(trailingDelta = partialReport, terminateAfterOutput = terminate)
        var verifications = 0
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                verifications++
                return Verdict(true, "Must not accept a cancelled worker")
            }
        }
        val (store, service) = fixture(runtime, verifier = verifier)
        store.save(plan(stage("a"), stage("b", depends = listOf("a"))))
        service.start(project.id)
        advanceTimeBy(1_000); runCurrent()

        val saved = store.planFor(project.id)!!
        val attempt = saved.milestones.first().attempts.single()
        assertEquals(ExecutionPhase.WAITING, saved.phase)
        assertEquals(PlanStatus.FAILED, saved.status)
        assertTrue(saved.issue!!.requiresUser)
        assertContains(saved.issue.message, reason)
        assertEquals(saved.issue, attempt.error)
        assertEquals(AttemptPhase.FAILED, attempt.phase)
        assertEquals(partialReport, attempt.report)
        val read = attempt.steps.single { it.callId == "read-1" }
        assertEquals("Файлы прочитаны", read.result)
        assertFalse(read.running)
        assertTrue(read.ok)
        assertEquals(io.aequicor.magicpaper.domain.tools.ToolPhase.SUCCEEDED, read.toolPhase)
        assertEquals("", attempt.pendingTool)
        assertNull(attempt.acceptanceRecord)
        assertNull(saved.finalAttempt)
        assertEquals(0, verifications)
        assertEquals(0, attempt.transportRetries)
        assertEquals(0, attempt.repairRetries)
        assertTrue(saved.milestones.last().attempts.isEmpty())

        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(attempt.sessionId), runtime.calls, "An internally cancelled worker must await the user's decision")
        assertEquals(ExecutionPhase.WAITING, store.planFor(project.id)!!.phase)
        assertEquals(attempt, store.planFor(project.id)!!.milestones.first().attempts.single())
        assertEquals(0, verifications)
    }

    @Test fun verificationUnavailableDoesNotRerunImplementation() = runTest {
        val unavailable = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) =
                Verdict(false, "unavailable", PlanningIssue(IssueKind.INVALID_RESPONSE, "unavailable", requiresUser = true))
        }
        val (store, service, runtime) = fixture(verifier = unavailable)
        store.save(plan(stage("a"))); service.start(project.id); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(AttemptPhase.VERIFYING, store.planFor(project.id)!!.milestones.single().attempts.single().phase)
    }
    @Test fun retryCountIsDurable() = runTest {
        val (store, service) = fixture(Runtime(failure = "429 rate limit"))
        store.save(plan(stage("a"))); service.start(project.id); runCurrent(); advanceTimeBy(100); runCurrent()
        val attempt = store.planFor(project.id)!!.milestones.single().attempts.single()
        assertEquals(1, attempt.transportRetries)
        assertTrue(attempt.error!!.retryAt > 0)
    }

    @Test fun pauseAllowsCurrentWorkToFinishButDoesNotDispatchNext() = runTest {
        val gate = CompletableDeferred<Unit>(); val runtime = Runtime(gate)
        val (store, service) = fixture(runtime, Workspaces(false))
        store.save(plan(stage("a"), stage("b"))); service.start(project.id); runCurrent()
        service.pause(project.id); gate.complete(Unit); advanceTimeBy(500); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(ExecutionIntent.PAUSE, store.planFor(project.id)!!.intent)
        assertEquals(MilestoneStatus.DONE, store.planFor(project.id)!!.milestones.first().status)
        assertEquals(MilestoneStatus.PENDING, store.planFor(project.id)!!.milestones.last().status)
    }

    @Test fun stopAbortsSessionAndReleasesOwner() = runTest {
        val runtime = Runtime(CompletableDeferred()); val workspace = Workspaces()
        val (store, service) = fixture(runtime, workspace)
        store.save(plan(stage("a"))); service.start(project.id); runCurrent()
        service.stop(project.id); runCurrent()
        assertTrue(runtime.calls.single() in runtime.aborted)
        assertEquals(1, workspace.released)
        assertEquals(ExecutionIntent.STOP, store.planFor(project.id)!!.intent)
        assertFalse(store.planFor(project.id)!!.stopping)
        assertEquals(PlanStatus.STOPPED, store.planFor(project.id)!!.status)
    }

    @Test fun stopDoesNotCompleteOrAllowReplacementBeforeChildCleanupConfirmsExit() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val runtime = Runtime(CompletableDeferred(), cleanupGate = cleanup)
        val workspace = Workspaces()
        val (store, service) = fixture(runtime, workspace)
        store.save(plan(stage("a"))); service.start(project.id); runCurrent()
        service.stop(project.id); runCurrent()
        assertTrue(store.planFor(project.id)!!.stopping)
        assertNotEquals(PlanStatus.STOPPED, store.planFor(project.id)!!.status)
        assertEquals(0, workspace.released)
        assertFailsWith<IllegalStateException> { service.start(project.id) }
        assertEquals(1, runtime.calls.size)
        cleanup.complete(Unit); runCurrent()
        assertFalse(store.planFor(project.id)!!.stopping)
        assertEquals(PlanStatus.STOPPED, store.planFor(project.id)!!.status)
        assertEquals(1, workspace.released)
    }

    @Test fun missingRuntimeStopAcknowledgementPersistsUncertaintyAndBlocksRetry() = runTest {
        val runtime = Runtime(CompletableDeferred())
        val (store, service) = fixture(runtime)
        store.save(plan(stage("a"))); service.start(project.id); runCurrent()
        runtime.reconciliationFailure = "Injected missing termination acknowledgement"
        service.stop(project.id); runCurrent()
        val saved = store.planFor(project.id)!!
        assertTrue(saved.stopping)
        assertEquals(IssueKind.UNCERTAIN, saved.issue?.kind)
        assertNotEquals(PlanStatus.STOPPED, saved.status)
        assertFailsWith<IllegalStateException> { service.retry(project.id) }
        assertEquals(1, runtime.calls.size)
        runtime.reconciliationFailure = null
        service.stopAndJoin(saved.id)
        assertFalse(store.planFor(saved.id)!!.stopping)
        assertEquals(PlanStatus.STOPPED, store.planFor(saved.id)!!.status)
    }

    @Test fun unknownExternalCommandWaitsBeforeStartingAnotherExecutor() = runTest {
        val (store, service, runtime) = fixture()
        val attempt = StageAttempt("a", "session", StageAssignment("agent", "m"), phase = AttemptPhase.EXECUTING,
            path = "/fake", pendingTool = "publish", pendingToolExternal = true)
        store.save(plan(stage("a").copy(attempts = listOf(attempt))).copy(intent = ExecutionIntent.RUN, runId = "run"))
        service.bootstrap(); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertEquals(IssueKind.UNCERTAIN, store.planFor(project.id)!!.issue?.kind)
    }

    @Test fun defaultPolicyCompletesAfterMoreThanThreeTransportRetries() = runTest {
        val (store, service, runtime) = fixture(Runtime(failure = "HTTP 503 network unavailable", failuresBeforeSuccess = 4), retryLimit = null)
        store.save(plan(stage("a")))
        service.bootstrap(); service.start(project.id); advanceTimeBy(200); runCurrent()
        repeat(4) { index ->
            val waiting = store.planFor(project.id)!!
            assertEquals(index + 1, waiting.milestones.single().attempts.single().transportRetries)
            assertFalse(waiting.issue!!.requiresUser)
            // Make the persisted backoff due; bootstrap, rather than a user retry, resumes it.
            store.update(project.id) { p -> p.copy(issue = p.issue?.copy(retryAt = 0),
                milestones = p.milestones.map { m -> m.copy(attempts = m.attempts.map { a -> a.copy(error = a.error?.copy(retryAt = 0)) }) }) }
            advanceTimeBy(5_100); runCurrent()
        }
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(4, saved.milestones.single().attempts.single().transportRetries)
        assertEquals(5, runtime.calls.count { it == saved.milestones.single().attempts.single().sessionId })
    }

    @Test fun defaultPolicyCompletesAfterMoreThanTwoWorkerRepairs() = runTest {
        var failures = 0
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) =
                if (milestone.id == "a" && failures++ < 4) Verdict(false, "Another scenario $failures remains") else Verdict(true, "All scenarios checked")
        }
        val (store, service, runtime) = fixture(verifier = verifier, retryLimit = null)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1_000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(4, saved.milestones.single().attempts.single().repairRetries)
        assertEquals(5, runtime.calls.count { it == saved.milestones.single().attempts.single().sessionId })
    }

    @Test fun defaultPolicyCompletesAfterMoreThanTwoMergeRepairs() = runTest {
        var resolutions = 0
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt) = false
            override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt) = ++resolutions >= 4
        }
        val (store, service, runtime) = fixture(workspace = workspace, retryLimit = null)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(30_000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(4, saved.milestones.single().attempts.single().mergeRetries)
        assertEquals(4, runtime.calls.count { it.endsWith("-merge") })
    }

    @Test fun explicitMergeRetryLimitPreservesTheUnresolvedWorkspace() = runTest {
        val base = Workspaces()
        val workspace = object : PlanningWorkspace by base {
            override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt) = false
            override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt) = false
        }
        val (store, service, runtime) = fixture(workspace = workspace, retryLimit = 2)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(10_000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(IssueKind.CONFLICT, saved.issue?.kind)
        assertTrue(saved.issue!!.requiresUser)
        assertEquals(2, runtime.calls.count { it.endsWith("-merge") })
        assertEquals(0, base.applied)
        assertFalse(saved.milestones.single().completed)
    }

    @Test fun independentWorktreesCanUseMoreThanEightRequestedSlots() = runTest {
        val (store, service, runtime) = fixture(Runtime(CompletableDeferred()), retryLimit = null)
        store.save(plan(*(1..10).map { stage("task-$it") }.toTypedArray()).copy(parallelism = 10))
        service.start(project.id); runCurrent()
        assertEquals(10, runtime.calls.size)
        service.stop(project.id); runCurrent()
    }

    @Test fun explicitlyDisabledRetriesStopAfterTheFirstFailure() = runTest {
        val (store, service, runtime) = fixture(Runtime(failure = "HTTP 503 network unavailable"), retryLimit = 0)
        store.save(plan(stage("a"))); service.bootstrap(); service.start(project.id); advanceTimeBy(30_000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertTrue(saved.issue!!.requiresUser)
        assertEquals(0, saved.milestones.single().attempts.single().transportRetries)
        assertEquals(1, runtime.calls.size)
    }

    @Test fun identicalFailureStopsAfterOneRepair() = runTest {
        val fail = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(false, "Test failed")
        }
        val (store, service, runtime) = fixture(verifier = fail, retryLimit = 2)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(2, runtime.calls.size)
        assertEquals(1, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun missingReviewEvidenceReturnsToWorkerBeforeAskingUser() = runTest {
        var reviews = 0
        val verifier = object : MilestoneVerifier by pass {
            override suspend fun review(milestone: Milestone, criteria: List<AcceptanceCriterion>, goal: String, report: String, profile: LlmProfile?): AcceptanceReview {
                val status = if (milestone.id == "a" && reviews++ == 0) CheckStatus.NOT_RUN else CheckStatus.PASS
                return AcceptanceReview(criteria.map { AcceptanceFinding(it.id, status, it.description,
                    if (status == CheckStatus.NOT_RUN) "Provide the layout source and check output" else "Source and checks reviewed", recovery = AcceptanceRecovery.EVIDENCE) })
            }
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(AcceptanceCriterion("layout", "Button precedes the project menu")))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        val attempt = saved.milestones.single().attempts.single()
        assertEquals(2, runtime.calls.count { it == attempt.sessionId })
        assertEquals(1, attempt.repairRetries)
        assertContains(attempt.prompt, "Provide the layout source and check output")
        assertContains(attempt.prompt, "Button precedes the project menu")
        assertEquals(AcceptanceStatus.ACCEPTED, attempt.acceptanceRecord?.status)
    }

    @Test fun repeatedMissingEvidenceStopsWithoutAcceptingTheStage() = runTest {
        val verifier = object : MilestoneVerifier by pass {
            override suspend fun review(milestone: Milestone, criteria: List<AcceptanceCriterion>, goal: String, report: String, profile: LlmProfile?) =
                AcceptanceReview(criteria.map { AcceptanceFinding(it.id, CheckStatus.NOT_RUN, it.description, "No source evidence", recovery = AcceptanceRecovery.EVIDENCE) })
        }
        val (store, service, runtime) = fixture(verifier = verifier, retryLimit = 2)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(2, runtime.calls.size)
        assertEquals(1, saved.milestones.single().attempts.single().repairRetries)
        assertEquals(AcceptanceStatus.PARTIAL, saved.milestones.single().attempts.single().acceptanceRecord?.status)
        assertTrue(saved.issue!!.requiresUser)
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(3, runtime.calls.size)
        assertEquals(1, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
    }

    @Test fun registeredCheckFailureCanBeRepairedByTheWorker() = runTest {
        var checks = 0
        val registry = AcceptanceChecks(mapOf("layout" to RegisteredAcceptanceCheck(EvidenceEnvironment.LOCAL_TEST) {
            AcceptanceCheckResult(if (checks++ == 0) CheckStatus.FAIL else CheckStatus.PASS, "Button position", listOf("layout-test.log"))
        }))
        val (store, service, runtime) = fixture(acceptanceChecks = registry)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("layout", "Button precedes menu", environment = EvidenceEnvironment.LOCAL_TEST, checkId = "layout")))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        val attempt = saved.milestones.single().attempts.single()
        assertEquals(1, attempt.repairRetries)
        assertEquals(2, runtime.calls.count { it == attempt.sessionId })
    }

    @Test fun explicitRepairRetryRunsOneAdditionalTurnWithoutResettingAutomaticLimit() = runTest {
        val fail = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(false, "Restore permissions")
        }
        val (store, service, runtime) = fixture(verifier = fail, retryLimit = 2)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(2, runtime.calls.size)
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(3, runtime.calls.size)
        val attempt = store.planFor(project.id)!!.milestones.single().attempts.single()
        assertContains(attempt.prompt, "Restore permissions")
        assertEquals(1, attempt.repairRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun explicitRetryAuthorizesOnlyCurrentFailedAttemptAndPersistsPermissionBeforeAdmission() = runTest {
        val (store, service) = fixture(Runtime(CompletableDeferred()))
        val issue = PlanningIssue(IssueKind.UNCERTAIN, "Бюджет сессии исчерпан", requiresUser = true)
        val assignment = StageAssignment("agent", "m")
        val older = StageAttempt("old", "old-worker", assignment, phase = AttemptPhase.FAILED, error = issue, sessionGeneration = 2)
        val failed = StageAttempt("current", "worker", assignment, phase = AttemptPhase.FAILED, path = "/fake/stage", error = issue, sessionGeneration = 7)
        val completed = StageAttempt("completed", "completed-worker", assignment, phase = AttemptPhase.COMPLETE)
        val prepared = StageAttempt("prepared", "prepared-worker", assignment, phase = AttemptPhase.PREPARED)
        val initial = plan(
            stage("failed").copy(status = MilestoneStatus.FAILED, attempts = listOf(older, failed)),
            stage("completed").copy(status = MilestoneStatus.DONE, attempts = listOf(completed)),
            stage("prepared").copy(attempts = listOf(prepared)),
        ).copy(runId = "run", confirmedRevision = 1, intent = ExecutionIntent.STOP, phase = ExecutionPhase.WAITING,
            status = PlanStatus.STOPPED, issue = issue)
        store.save(initial)
        val before = store.planFor(initial.id)!!
        val permission = PlanAttemptRetryAuthorization("explicit-retry", SessionLegacyAttempt(before.id, before.runId,
            "failed", failed.id, failed.turnIndex, failed.sessionGeneration), 12)
        val authorized = mutableListOf<Pair<String, String>>()
        val admitted = mutableListOf<Pair<String, PlanAttemptRetryAuthorization?>>()
        service.authorizeRetry = { snapshot, stageId, attempt ->
            assertEquals(before, snapshot)
            authorized += stageId to attempt.id
            permission
        }
        service.prepareAttempt = { _, stageId, attempt ->
            val persisted = store.planFor(before.id)!!.milestones.first { it.id == stageId }.attempts.last()
            assertEquals(persisted.retryAuthorization, attempt.retryAuthorization)
            admitted += stageId to attempt.retryAuthorization
            attempt
        }
        service.retry(before.id); runCurrent()
        assertEquals(listOf("failed" to failed.id), authorized)
        assertEquals(permission, admitted.single { it.first == "failed" }.second)
        val saved = store.planFor(before.id)!!
        assertNull(saved.milestones.first { it.id == "failed" }.attempts.first().retryAuthorization)
        assertNull(saved.milestones.first { it.id == "completed" }.attempts.last().retryAuthorization)
        assertNull(saved.milestones.first { it.id == "prepared" }.attempts.last().retryAuthorization)
        service.stop(before.id); runCurrent()
    }

    @Test fun retryRejectsNewGlobalIssueOrFinalAttemptEvenWhenRevisionAndStageSnapshotsMatch() = runTest {
        for (changeFinal in listOf(false, true)) {
            val (store, service, runtime) = fixture()
            val issue = PlanningIssue(IssueKind.UNCERTAIN, "Original stopped run", requiresUser = true)
            val assignment = StageAssignment("agent", "m")
            val failed = StageAttempt("current", "worker", assignment, phase = AttemptPhase.FAILED, error = issue, sessionGeneration = 3)
            val initial = plan(stage("a").copy(status = MilestoneStatus.FAILED, attempts = listOf(failed)))
                .copy(runId = "run", confirmedRevision = 1, intent = ExecutionIntent.STOP, phase = ExecutionPhase.WAITING,
                    status = PlanStatus.STOPPED, issue = issue)
            store.save(initial)
            val before = store.planFor(initial.id)!!
            val changed = if (changeFinal) before.copy(finalAttempt = StageAttempt("new-final", "final-worker", assignment,
                phase = AttemptPhase.FAILED, pendingTool = "publish", pendingToolExternal = true, error = issue))
                else before.copy(issue = issue.copy(message = "New unconfirmed external operation"))
            service.authorizeRetry = { _, _, _ ->
                store.save(changed) // A projection can replace the snapshot without incrementing its revision.
                PlanAttemptRetryAuthorization("retry", SessionLegacyAttempt(before.id, before.runId,
                    "a", failed.id, failed.turnIndex, failed.sessionGeneration), 10)
            }
            assertFailsWith<IllegalStateException> { service.retry(before.id) }
            runCurrent()
            assertEquals(changed, store.planFor(before.id))
            assertTrue(runtime.calls.isEmpty())
        }
    }

    @Test fun transportLimitSurvivesExplicitContinuation() = runTest {
        val (store, service, runtime) = fixture(Runtime(failure = "429 rate limit"))
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(200); runCurrent()
        repeat(3) {
            store.update(project.id) { p -> p.copy(milestones = p.milestones.map { m -> m.copy(attempts = m.attempts.map { a -> a.copy(error = a.error?.copy(retryAt = 0)) }) }) }
            service.retry(project.id); advanceTimeBy(200); runCurrent()
        }
        assertEquals(4, runtime.calls.size)
        val saved = store.planFor(project.id)!!
        assertEquals(3, saved.milestones.single().attempts.single().transportRetries)
        assertTrue(saved.issue!!.requiresUser)
    }

    @Test fun planningProposalCanRebaseOverTelemetryButNotUserEdits() = runTest {
        val (store, service) = fixture()
        store.save(plan(stage("a"), stage("b")))
        val base = store.planFor(project.id)!!
        val proposed = base.copy(milestones = base.milestones.map { if (it.id == "b") it.copy(description = "new task") else it })
        store.update(project.id) { it.copy(milestones = it.milestones.map { m -> if (m.id == "a") m.copy(status = MilestoneStatus.ACTIVE, report = "live") else m }) }
        service.applyProposal(base, proposed)
        assertEquals("live", store.planFor(project.id)!!.milestones.first().report)
        assertEquals("new task", store.planFor(project.id)!!.milestones.last().description)
        assertFailsWith<IllegalArgumentException> { service.applyProposal(base, proposed) }
    }

    @Test fun committedMergeStillResumesItsPendingVerification() = runTest {
        var checked = 0
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (report == "merged report") checked++
                return Verdict(true, "checked")
            }
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        val a = StageAttempt("attempt", "session", StageAssignment("agent", "m"), phase = AttemptPhase.INTEGRATING, path = "/fake",
            mergePhase = AttemptPhase.VERIFYING, mergeRetries = 1, mergeReport = "merged report")
        store.save(plan(stage("a").copy(status = MilestoneStatus.ACTIVE, attempts = listOf(a))).copy(intent = ExecutionIntent.RUN, runId = "run"))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, checked)
        assertEquals(listOf("run-final-session"), runtime.calls)
        assertEquals(PlanStatus.DONE, store.planFor(project.id)!!.status)
    }

    @Test fun savedRetryDeadlineIsRespectedEvenWithoutTheRunIssue() = runTest {
        val (store, service, runtime) = fixture()
        val a = StageAttempt("attempt", "session", StageAssignment("agent", "m"), phase = AttemptPhase.FAILED, path = "/fake",
            transportRetries = 1, error = PlanningIssue(IssueKind.TRANSIENT, "429", retryAt = Long.MAX_VALUE, retries = 1))
        store.save(plan(stage("a").copy(status = MilestoneStatus.ACTIVE, attempts = listOf(a))).copy(intent = ExecutionIntent.RUN, runId = "run"))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertEquals(Long.MAX_VALUE, store.planFor(project.id)!!.issue?.retryAt)
    }

    @Test fun thrownVerificationErrorsPreservePhaseAndExhaustDurableRetries() = runTest {
        var checks = 0
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                checks++
                throw IllegalStateException("503 network unavailable")
            }
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        store.save(plan(stage("a")))
        service.start(project.id); advanceTimeBy(200); runCurrent()
        repeat(3) {
            val saved = store.planFor(project.id)!!.milestones.single().attempts.single()
            assertEquals(it + 1, saved.transportRetries)
            assertTrue(saved.error!!.retryAt > 0)
            assertEquals(AttemptPhase.VERIFYING, saved.phase)
            store.update(project.id) { p -> p.copy(milestones = p.milestones.map { m ->
                m.copy(attempts = m.attempts.map { a -> a.copy(error = a.error?.copy(retryAt = 0)) })
            }) }
            service.retry(project.id); advanceTimeBy(200); runCurrent()
        }
        assertEquals(4, checks)
        assertEquals(1, runtime.calls.size)
        val saved = store.planFor(project.id)!!
        assertEquals(3, saved.milestones.single().attempts.single().transportRetries)
        assertTrue(saved.issue!!.requiresUser)
    }

    @Test fun workspaceTransportExceptionsHaveDurableBoundedRetries() = runTest {
        var preparations = 0
        val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun prepare(project: CodingProject, runId: String): PlanWorkspace {
                preparations++
                throw IllegalStateException("HTTP 500: unavailable")
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace)
        store.save(plan(stage("a")))
        service.start(project.id); advanceTimeBy(200); runCurrent()
        repeat(3) {
            assertEquals(it + 1, store.planFor(project.id)!!.transportRetries)
            assertTrue(store.planFor(project.id)!!.issue!!.retryAt > 0)
            service.retry(project.id); advanceTimeBy(200); runCurrent()
        }
        assertEquals(4, preparations)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(3, store.planFor(project.id)!!.transportRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun finalVerificationUnknownExternalCommandRequiresAcknowledgement() = runTest {
        val (store, service, runtime) = fixture()
        val attempt = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.EXECUTING,
            pendingTool = "curl https://example.com/action", pendingToolExternal = true)
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(
            intent = ExecutionIntent.RUN, runId = "run", finalAttempt = attempt))
        service.bootstrap(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertEquals(IssueKind.UNCERTAIN, store.planFor(project.id)!!.issue!!.kind)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    private fun rejectedFinalPlan(): Plan {
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Integration is missing", requiresUser = true)
        val done = stage("a").copy(status = MilestoneStatus.DONE, report = "Original work",
            attempts = listOf(StageAttempt("done", "worker", StageAssignment("agent", "m"), phase = AttemptPhase.COMPLETE)))
        return DecisionCompiler.migrate(plan(done)).copy(intent = ExecutionIntent.RUN, runId = "run",
            phase = ExecutionPhase.WAITING, status = PlanStatus.FAILED, issue = issue,
            finalAttempt = StageAttempt("run-final", "run-final-session", StageAssignment("agent", "m"),
                phase = AttemptPhase.VERIFYING, report = "Original verification", error = issue, transportRetries = 2))
    }

    private fun withFollowup(plan: Plan): Plan = plan.copy(
        milestones = plan.milestones + stage("followup", listOf("a")),
        tree = plan.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + "followup") else it } +
            DecisionNode("followup", "Followup", DecisionKind.STAGE, stageId = "followup"))

    @Test fun rejectedFinalCanBeExtendedAndRecheckedWithoutRepeatingCompletedWork() = runTest {
        val (store, service, runtime) = fixture()
        store.save(rejectedFinalPlan())
        val base = store.planFor(project.id)!!
        val revised = service.applyProposal(base, withFollowup(base))
        assertNull(revised.issue)
        assertNull(revised.finalAttempt)
        assertEquals(listOf(base.finalAttempt), revised.finalAttemptHistory)
        assertEquals(base.milestones.single(), revised.milestones.first())
        // A reload must retain the previous report and its retry budget.
        assertEquals(revised, json.decodeFromString<Plan>(json.encodeToString(revised)))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val complete = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, complete.status)
        assertEquals(2, runtime.calls.size)
        assertFalse("worker" in runtime.calls)
        assertEquals("run-final-2-session", runtime.calls.last())
        assertEquals(base.finalAttempt, complete.finalAttemptHistory.single())
    }

    @Test fun manualExtensionPreservesPauseAndCompletedStageHistory() = runTest {
        val (store, service, runtime) = fixture()
        store.save(rejectedFinalPlan().copy(intent = ExecutionIntent.PAUSE))
        val base = store.planFor(project.id)!!
        val revised = service.edit(base.id, base.revision, ::withFollowup)
        assertEquals(ExecutionIntent.PAUSE, revised.intent)
        assertNull(revised.finalAttempt)
        assertEquals(base.milestones.single(), revised.milestones.first())
        service.bootstrap(); advanceTimeBy(6000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun dialogueOrInactiveChangesDoNotDismissFailedFinalVerification() = runTest {
        val (store, service) = fixture()
        store.save(rejectedFinalPlan())
        val base = store.planFor(project.id)!!
        val discussed = service.applyProposal(base, base.copy(dialogue = listOf(PlanningMessage("reply", "assistant", "Уточните требования"))))
        assertEquals(base.finalAttempt, discussed.finalAttempt)
        assertEquals(base.issue, discussed.issue)
        val relabelled = service.edit(discussed.id, discussed.revision) { p ->
            p.copy(tree = p.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(title = "New label") else it })
        }
        assertEquals(base.finalAttempt, relabelled.finalAttempt)
        assertEquals(base.issue, relabelled.issue)
    }

    @Test fun extensionCannotRewriteCompletedWorkOrRaceActiveFinalVerification() = runTest {
        val (store, service) = fixture()
        store.save(rejectedFinalPlan())
        val base = store.planFor(project.id)!!
        assertFailsWith<IllegalArgumentException> {
            service.edit(base.id, base.revision) { p -> withFollowup(p).let { it.copy(milestones = it.milestones.map { m -> m.copy(report = "Rewritten") }) } }
        }
        assertEquals(base, store.planFor(project.id))
        // The LLM started while blocked, but a check resumed before its reply arrived.
        store.update(base.id) { it.copy(phase = ExecutionPhase.VERIFYING, issue = null) }
        assertFailsWith<IllegalArgumentException> { service.applyProposal(base, withFollowup(base)) }
        assertEquals(base.finalAttempt, store.planFor(project.id)!!.finalAttempt)
    }

    @Test fun extensionCannotClearUncertainCommandsOrAnAppliedWorkspace() = runTest {
        val (store, service) = fixture()
        val rejected = rejectedFinalPlan()
        for (blocked in listOf(
            rejected.copy(finalAttempt = rejected.finalAttempt!!.copy(pendingTool = "deploy", pendingToolExternal = true)),
            rejected.copy(issue = PlanningIssue(IssueKind.UNCERTAIN, "Unknown result", requiresUser = true)),
            rejected.copy(workspace = PlanWorkspace("/fake", "/fake", applied = true)),
        )) {
            store.save(blocked)
            val base = store.planFor(project.id)!!
            assertFailsWith<IllegalArgumentException> { service.applyProposal(base, withFollowup(base)) }
            assertEquals(base, store.planFor(project.id))
        }
    }

    @Test fun finalVerifierThrownErrorRetainsAttemptAndRetryBudget() = runTest {
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict =
                throw IllegalStateException("503 network unavailable")
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        val attempt = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.VERIFYING,
            report = "Completed checks", transportRetries = 3)
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(
            intent = ExecutionIntent.RUN, runId = "run", finalAttempt = attempt))
        service.bootstrap(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertEquals(AttemptPhase.VERIFYING, store.planFor(project.id)!!.finalAttempt!!.phase)
        assertEquals(3, store.planFor(project.id)!!.finalAttempt!!.transportRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun committedDeliveryResumesVerificationBeforeTransfer() = runTest {
        var finished = false
        val port = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun verificationSnapshot(path: String) = "fixture-snapshot"
            override suspend fun finishDeliveryConflict(path: String): Boolean { finished = true; return true }
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace {
                assertTrue(finished, "Transfer must wait for the recorded verification")
                return workspace.copy(applied = true)
            }
        }
        val (store, service, runtime) = fixture(workspace = port)
        val a = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.COMPLETE,
            acceptanceRecord = AcceptanceRecord("run", "final", "fixture-snapshot", plan(stage("a")).acceptanceCriteria(),
                plan(stage("a")).acceptanceCriteria().map { AcceptanceFinding(it.id, CheckStatus.PASS, it.description, "Checked") }, status = AcceptanceStatus.ACCEPTED),
            mergePhase = AttemptPhase.VERIFYING, mergePath = "/fake/delivery", mergeRetries = 1, mergeReport = "checked merge")
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(intent = ExecutionIntent.RUN, runId = "run", finalAttempt = a))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(finished)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(PlanStatus.DONE, store.planFor(project.id)!!.status)
    }
    @Test fun modelSuccessCannotAcceptRequiredLiveCheckWithoutHostReceipt() = runTest {
        val workspace = Workspaces()
        val (store, service, runtime) = fixture(workspace = workspace)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("live", "Real backend receives payload", environment = EvidenceEnvironment.REAL_BACKEND)))))
        service.start(project.id); advanceTimeBy(500); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.FAILED, saved.status)
        assertEquals(AcceptanceStatus.PARTIAL, saved.milestones.single().attempts.single().acceptanceRecord?.status)
        assertEquals(0, workspace.applied)
        val calls = runtime.calls.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(calls, runtime.calls.size)
        assertTrue(saved.messageEvents.none { it.kind == MessageEventKind.RUN_COMPLETED })
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(calls, runtime.calls.size, "Retrying an unavailable host check must not rerun file changes")
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
        assertEquals(0, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
    }

    @Test fun unavailableCollectorNeverAsksModelToInventAWorkerRepair() = runTest {
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict =
                error("No model review can supply an unregistered collector")
        }
        val (store, service, runtime) = fixture(verifier = judge)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("layout", "Check layout", environment = EvidenceEnvironment.LOCAL_TEST, checkId = "invented-tool")))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val blocked = store.planFor(project.id)!!
        val acceptance = blocked.milestones.single().attempts.single().acceptanceRecord!!
        assertEquals(AcceptanceStatus.PARTIAL, acceptance.status)
        assertFalse(acceptance.canRetryWithWorker)
        assertTrue(acceptance.canSkipByUser)
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(0, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
    }

    @Test fun sourceChangeDuringFinalReviewInvalidatesAcceptanceAndPreventsApply() = runTest {
        var snapshot = "before"
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String) = snapshot
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace = error("Must not apply stale result")
        }
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (milestone.id == "final") snapshot = "after"
                return Verdict(true, "All accepted")
            }
        }
        val (store, service) = fixture(workspace = workspace, verifier = judge)
        store.save(plan(stage("a")))
        service.start(project.id); advanceTimeBy(500); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(AcceptanceStatus.STALE, saved.finalAttempt?.acceptanceRecord?.status)
        assertEquals(PlanStatus.FAILED, saved.status)
        assertEquals("before", saved.finalAttempt?.acceptanceRecord?.snapshotId)
    }

    @Test fun explicitSkipContinuesTheStageAndFinalAcceptanceWithoutRerunningImplementation() = runTest {
        val workspace = Workspaces()
        val (store, service, runtime) = fixture(workspace = workspace)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("layout", "Button precedes menu", environment = EvidenceEnvironment.MANUAL)))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val blocked = store.planFor(project.id)!!
        assertEquals(1, runtime.calls.size)
        val ids = blocked.blockingIssues(emptyList()).map { it.messageId }.toSet()
        assertFailsWith<IllegalArgumentException> { service.continueWithoutVerification(blocked.id, setOf("stale")) }
        service.continueWithoutVerification(blocked.id, ids); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(1, runtime.calls.size, "Neither file changes nor explicitly skipped final checks should run again")
        assertEquals(1, workspace.applied)
        assertEquals(1, saved.acceptanceWaivers.size)
        assertEquals(CheckStatus.SKIPPED, saved.finalAttempt!!.acceptanceRecord!!.findings.single().status)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt.acceptanceRecord!!.status)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.milestones.single().attempts.single().acceptanceRecord!!.status)
        assertContains(saved.milestones.single().checkNote, "по решению пользователя")
        val restored = json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), saved))
        assertEquals(saved.acceptanceWaivers, restored.acceptanceWaivers)
        assertFailsWith<IllegalArgumentException> { service.continueWithoutVerification(saved.id, ids) }
    }

    @Test fun skippingOneStageDoesNotSkipOtherStageChecks() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("manual", "Inspect layout", environment = EvidenceEnvironment.MANUAL))), stage("b", listOf("a"))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val blocked = store.planFor(project.id)!!
        service.continueWithoutVerification(blocked.id, blocked.blockingIssues(emptyList()).map { it.messageId }.toSet())
        advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(3, runtime.calls.size, "Other stage and its final verification must still run")
        assertEquals(CheckStatus.PASS, saved.finalAttempt!!.acceptanceRecord!!.findings.single { it.criterionId == "b/result" }.status)
        assertEquals(CheckStatus.SKIPPED, saved.finalAttempt.acceptanceRecord!!.findings.single { it.criterionId == "a/manual" }.status)
    }

    @Test fun skipCoversPreviouslyPassedCriteriaAndDoesNotReadSnapshotAgain() = runTest {
        var skippedByUser = false
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String): String {
                check(!skippedByUser) { "Files are changing; skipped verification must not read them again" }
                return "before"
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace)
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("source", "Old button row removed"),
            AcceptanceCriterion("layout", "New button precedes menu", environment = EvidenceEnvironment.MANUAL)))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val blocked = store.planFor(project.id)!!
        assertEquals(listOf(CheckStatus.PASS, CheckStatus.NOT_RUN), blocked.milestones.single().attempts.single().acceptanceRecord!!.findings.map { it.status })
        skippedByUser = true
        service.continueWithoutVerification(blocked.id, blocked.blockingIssues(emptyList()).map { it.messageId }.toSet())
        advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(1, runtime.calls.size)
        assertEquals(2, saved.acceptanceWaivers.size)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt!!.acceptanceRecord!!.status)
        assertTrue(saved.finalAttempt.acceptanceRecord!!.findings.all { it.status == CheckStatus.SKIPPED })
    }

    @Test fun bootstrapRestoresLegacySkipBlockedByStaleSnapshot() = runTest {
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String): String = error("Skipped verification must not fingerprint the project")
        }
        val (store, service, runtime) = fixture(workspace = workspace)
        val stage = stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("source", "Old row removed"),
            AcceptanceCriterion("layout", "Button position", environment = EvidenceEnvironment.MANUAL)))
        val criteria = stage.criteria()
        val waiver = AcceptanceWaiver("run", criteria.last(), "attempt", "old-snapshot", 1)
        val record = AcceptanceRecord("run", "attempt", "changed-snapshot", criteria,
            criteria.map { AcceptanceFinding(it.id, CheckStatus.STALE, it.description, "Files changed") },
            status = AcceptanceStatus.STALE, waivers = listOf(waiver))
        val issue = PlanningIssue(IssueKind.VERIFICATION, record.summary(), requiresUser = true)
        val attempt = StageAttempt("attempt", "worker", StageAssignment("agent", "m"), phase = AttemptPhase.VERIFYING,
            path = "/fake", report = "Button relocated", error = issue, acceptanceRecord = record)
        val blocked = plan(stage.copy(attempts = listOf(attempt))).copy(runId = "run", intent = ExecutionIntent.RUN,
            phase = ExecutionPhase.WAITING, status = PlanStatus.FAILED, issue = issue, acceptanceWaivers = listOf(waiver),
            journal = listOf(PlanJournalEntry("skip", 1, operation = "user-skip-verification")))
        store.save(json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), blocked)))
        service.bootstrap(); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(criteria.toSet(), saved.acceptanceWaivers.map { it.criterion }.toSet())
        assertTrue(runtime.calls.isEmpty(), "The already completed worker turn must not execute again")
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt!!.acceptanceRecord!!.status)
    }

    @Test fun pauseDuringFinalReviewCannotApplyOrCompletePlan() = runTest {
        lateinit var store: PlanningStore
        var applied = false
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace { applied = true; return workspace.copy(applied = true) }
        }
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (milestone.id == "final") store.update("plan") { it.copy(intent = ExecutionIntent.PAUSE) }
                return Verdict(true, "Checked")
            }
        }
        val fixture = fixture(workspace = workspace, verifier = judge); store = fixture.first
        store.save(plan(stage("a")))
        fixture.second.start(project.id); advanceTimeBy(500); runCurrent()
        assertFalse(applied)
        assertEquals(ExecutionIntent.PAUSE, store.planFor(project.id)?.intent)
        assertNotEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
    }

    @Test fun finalReviewGetsEveryApprovedCriterionAndKeepsExactFindings() = runTest {
        val reviewed = mutableListOf<String>()
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (milestone.id == "final") reviewed += milestone.acceptance
                return Verdict(true, "Checked ${milestone.acceptance}")
            }
        }
        val (store, service) = fixture(verifier = judge)
        store.save(plan(stage("a").copy(acceptance = "Exact criterion A"), stage("b").copy(acceptance = "Exact criterion B")))
        service.start(project.id); advanceTimeBy(500); runCurrent()
        assertEquals(setOf("Exact criterion A", "Exact criterion B"), reviewed.toSet())
        val saved = store.planFor(project.id)!!
        assertEquals(AcceptanceStatus.ACCEPTED, saved.finalAttempt?.acceptanceRecord?.status)
        assertEquals(2, PlanningRequestContext.from(saved).acceptance?.findings?.size)
    }

    @Test fun finishedBackendWithoutReportCannotCompleteStage() = runTest {
        val (store, service) = fixture(Runtime(report = ""))
        store.save(plan(stage("a")))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertNotEquals(PlanStatus.DONE, saved.status)
        assertNotEquals(MilestoneStatus.DONE, saved.milestones.single().status)
        assertNull(saved.finalAttempt)
    }

}
