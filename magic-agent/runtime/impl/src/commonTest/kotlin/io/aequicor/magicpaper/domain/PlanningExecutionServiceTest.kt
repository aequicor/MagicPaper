package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.domain.planning.*
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningExecutionServiceTest {
    @Test fun unknownRuntimePolicyCannotAdmitAPlanOrCreateAnExternalIntent() = runTest {
        val (store, service, runtime, ports) = fixture()
        store.save(plan(stage("one")))
        ports.runtimePolicyReady = false
        assertFailsWith<IllegalStateException> { service.start("plan") }
        assertNull(store.currentAdmission("plan"))
        assertTrue(store.unsettled("plan").isEmpty())
        assertTrue(runtime.calls.isEmpty())
        service.shutdown()
    }

    @Test fun policyBecomingUnknownDuringPreparationCannotProduceANativeDispatch() = runTest {
        val (store, service, runtime, ports) = fixture()
        store.save(plan(stage("one")))
        ports.onPrepareAttempt = { _, _, attempt -> ports.runtimePolicyReady = false; attempt }
        service.start("plan"); advanceTimeBy(2_000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertTrue(store.unsettled("plan").none { PlanJournalOperation.of(it.operation) == PlanJournalOperation.AGENT_INTENT })
        service.shutdown()
    }

    @Test fun unavailableTaskWorktreeRetainsTheResolvedSharedWorkspaceMode() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("one")).copy(worktreeEnabled = true, sharedWorkspace = false))
        service.start("plan"); advanceTimeBy(2_000); runCurrent()
        val completed = checkNotNull(store.planFor("plan"))
        assertEquals(PlanStatus.DONE, completed.status, completed.issue?.message)
        assertEquals(false, completed.worktreeEnabled)
        assertTrue(completed.sharedWorkspace)
        assertTrue(runtime.executionPaths.isNotEmpty())
        assertTrue(runtime.executionPaths.all { it == project.path })
        service.shutdown()
        store.recover()
        assertEquals(false, store.planFor("plan")!!.worktreeEnabled)
        assertTrue(store.planFor("plan")!!.sharedWorkspace)
    }

    @Test fun worktreePlanDeliversOnlyAfterAcceptanceAndUsesIsolatedSource() = runTest {
        val prepared = mutableListOf<String>()
        val applied = mutableListOf<String>()
        var delivered = 0
        var accepted = 0
        val leases = LocalPlanningWorkspace()
        val workspace = object : PlanningWorkspace by leases {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                prepared += project.path
                return PlanWorkspace("/integration", "/integration")
            }
            override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) =
                leases.stage(project.copy(path = workspace.integrationPath), workspace, attempt, operation)
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace {
                applied += project.path
                return workspace.copy(applied = true)
            }
        }
        val task = object : TaskWorkspace {
            override suspend fun availability(project: CodingProject) = WorktreeAvailability(true)
            override suspend fun describe(project: CodingProject, sessionId: String, taskId: String, label: String) =
                TaskWorktree(taskId, project.path, "main", "base", "/task", "task", label = label)
            override suspend fun open(record: TaskWorktree, operation: TaskWorkspaceOperation, previous: TaskWorktree?) = Unit
            override suspend fun reconcile(record: TaskWorktree) = Unit
            override suspend fun capture(record: TaskWorktree, operation: TaskWorkspaceOperation) = "result"
            override suspend fun target(record: TaskWorktree) = "base"
            override suspend fun refresh(record: TaskWorktree, operation: TaskWorkspaceOperation) = TaskWorktreeRefresh()
            override suspend fun integrate(record: TaskWorktree, operation: TaskWorkspaceOperation) = "result"
            override suspend fun verify(record: TaskWorktree, operation: TaskWorkspaceOperation) = Unit
            override suspend fun delivered(record: TaskWorktree) = delivered > 0
            override suspend fun deliver(record: TaskWorktree, operation: TaskWorkspaceOperation) { assertTrue(accepted > 0); delivered++ }
        }
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                accepted++
                return Verdict(true, "checked")
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace, verifier = verifier, taskWorkspace = task)
        store.save(plan(stage("one")).copy(parentSessionId = "parent", worktreeEnabled = true))
        service.start(project.id); advanceTimeBy(2_000); runCurrent()
        assertEquals(PlanStatus.DONE, store.planFor("plan")?.status, store.planFor("plan")?.issue?.message)
        assertEquals(listOf("/task"), prepared)
        assertEquals(listOf("/task"), applied)
        assertFalse("/fake" in runtime.executionPaths)
        assertEquals(1, delivered)
        // The judge's review of the merged result is a verification step of its own, logged beside the checks'.
        val review = AppLog.history().filter { it.component == "planning.execution" && it.event.startsWith("verification.review") }.takeLast(2)
        assertEquals(listOf("verification.review.started", "verification.review.finished"), review.map { it.event })
        assertEquals("model_review", review.first().fields["mode"])
        assertEquals(listOf("passed", "none"), listOf(review.last().fields["result"], review.last().fields["reason"]))
        assertEquals("plan", AppLog.history().last { it.component == "coding.worktree" && it.event == "merge.accepted" }.fields["mode"])
        service.shutdown()
    }

    @Test fun resumedIsolatedPlanDoesNotAcquireOrReleaseAnOrdinarySessionsSourceLease() = runTest {
        val leases = LocalPlanningWorkspace()
        val workspace = object : PlanningWorkspace by leases {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
            override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) =
                leases.stage(project.copy(path = workspace.integrationPath), workspace, attempt, operation)
        }
        val ordinary = project.copy(id = "ordinary-session")
        val heldWorkspace1 = assertNotNull(leases.acquire(ordinary, "lease-request-1"))
        val (store, service, runtime) = fixture(Runtime(CompletableDeferred()), workspace)
        store.save(plan(stage("work")).copy(workspace = PlanWorkspace("/isolated", "/isolated", git = true), runId = "existing-run"))
        service.start(project.id); runCurrent()
        assertEquals(1, runtime.calls.size)
        service.stop(project.id); runCurrent()
        assertNull(leases.acquire(project.copy(id = "intruder"), "lease-request-2"))
        leases.release(heldWorkspace1)
        val heldWorkspace3 = assertNotNull(leases.acquire(ordinary, "lease-request-3"))
        leases.release(heldWorkspace3)
    }

    @Test fun isolatedPlanReleasesSourceDuringExecutionAndReacquiresItBeforeApply() = runTest {
        val leases = LocalPlanningWorkspace()
        var applied = 0
        val workspace = object : PlanningWorkspace by leases {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation) = PlanWorkspace("/isolated", "/isolated", git = true)
            override suspend fun stage(project: CodingProject, workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) =
                leases.stage(project.copy(path = workspace.integrationPath), workspace, attempt, operation)
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace {
                assertNull(leases.acquire(project.copy(id = "competing-writer"), "lease-request-4"), "Applying must own the source checkout")
                applied++
                return workspace.copy(applied = true)
            }
        }
        val gate = CompletableDeferred<Unit>()
        val (store, service, _) = fixture(Runtime(gate), workspace)
        store.save(plan(stage("work")))
        service.start(project.id); runCurrent()
        val ordinary = project.copy(id = "ordinary-session")
        val heldWorkspace5 = assertNotNull(leases.acquire(ordinary, "lease-request-5"), "An isolated plan must not reserve the user's checkout")
        assertNull(leases.acquire(project.copy(id = "intruder", path = "/isolated"), "lease-request-6"))
        gate.complete(Unit); advanceTimeBy(1_000); runCurrent()
        assertEquals(0, applied)
        assertEquals(IssueKind.TRANSIENT, store.planFor(project.id)!!.issue?.kind)
        leases.release(heldWorkspace5)
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, applied)
        assertEquals(ExecutionPhase.COMPLETE, store.planFor(project.id)!!.phase)
        val heldWorkspace7 = assertNotNull(leases.acquire(ordinary, "lease-request-7"))
        leases.release(heldWorkspace7)
    }

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
        private val command: String = "./gradlew :feature:session:impl:jvmTest",
        private val cleanupGate: CompletableDeferred<Unit>? = null,
        private val terminateAfterOutput: (suspend () -> Unit)? = null,
    ) : CodingRuntime {
        val engines = mutableListOf<CodingEngine?>()
        val sessions = mutableListOf<CodingSession>()
        val executionPaths = mutableListOf<String>()
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
            executionPaths += project.path
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
        override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
        var applied = 0
        var released = 0
        override suspend fun release(lease: WorkspaceLease) { released++; base.release(lease) }
        override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation) = PlanWorkspace("/fake", "/fake", git = parallel)
        override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace { applied++; return workspace.copy(applied = true) }
    }
    private data class ExecutionFixture(val store: TestPlanningStore, val service: PlanningExecutionService, val runtime: Runtime, val ports: TestPlanningExecutionPorts)
    private suspend fun TestScope.fixture(runtime: Runtime = Runtime(), workspace: PlanningWorkspace = Workspaces(), verifier: MilestoneVerifier = pass,
        acceptanceChecks: AcceptanceChecks = AcceptanceChecks(), retryLimit: Int? = 3, taskWorkspace: TaskWorkspace? = null, events: EventJournal = InMemoryEventJournal(), strategyGateway: LlmGateway? = null,
        roster: List<LlmProfile> = listOf(profile), nativeModels: NativeModelSnapshots = NativeModelSnapshots.None): ExecutionFixture {
        val kv = InMemoryKeyValueStore()
        val store = TestPlanningStore(JsonPlanningRepository(kv, json), events)
        val profiles = JsonLlmProfileRepository(kv, json).also { repository -> roster.forEach { repository.save(it) } }
        val projects = journalCodingProjects(kv, json).also { it.createTestProject(project) }
        if (taskWorkspace != null) projects.createTestSession(CodingSession("parent", project.id, "Task", 1, planningMode = true))
        val taskWorktrees = taskWorkspace?.let { testTaskWorktreeService(projects, it, workspace, events, kv) }
        val settings = JsonSettingsRepository(kv, json)
        settings.save(AppSettings(agentLimits = OrganismLimits(retries = retryLimit)))
        val ports = TestPlanningExecutionPorts()
        return ExecutionFixture(store, PlanningExecutionService(store, runtime, projects, profiles, settings, verifier, workspace, backgroundScope,
            outputClock = { testScheduler.currentTime }, retryClock = { testScheduler.currentTime }, acceptanceChecks = acceptanceChecks, taskWorktrees = taskWorktrees,
            strategyClassifier = strategyGateway?.let { PlanStrategyClassifier(store, it, profiles, settings) }, nativeModels = nativeModels,
            attemptAuthority = ports, chatHooksProvider = { ports.chatHooks }), runtime, ports)
    }
    private fun plan(vararg stages: Milestone) = Plan("plan", "project", "Goal", milestones = stages.toList())
    private fun stage(id: String, depends: List<String> = emptyList()) = Milestone(id, id, description = "Check result", agentProfileId = "agent", dependsOn = depends)

    private val astra = CodingModel("openai", "gpt-6-astra", "GPT-6 Astra", levels = listOf("low", "medium", "max", "ultra"), defaultLevel = "medium")
    private val codexSnapshot = CodingModelSnapshot(CodingEngine.CODEX, listOf(astra), 1)
    private val subscription = LlmProfile("chatgpt", "ChatGPT", provider = ProviderType.OPENAI_SUBSCRIPTION, modelId = "profile-default", modelLibraryVersion = 1)
    private fun nativeStage(model: CodingModel, level: String?) = stage("one").copy(agentProfileId = subscription.id,
        assignment = nativeStageAssignment(subscription, CodingEngine.CODEX, model, level))

    @Test fun nativeStageRunsItsCatalogChoiceInTheWorkerSession() = runTest {
        val (store, service, runtime) = fixture(roster = listOf(subscription), nativeModels = NativeModelSnapshots { codexSnapshot })
        store.save(plan(nativeStage(astra, "ultra")).copy(engine = CodingEngine.CODEX))
        service.start("plan"); advanceTimeBy(2_000); runCurrent()
        val completed = checkNotNull(store.planFor("plan"))
        assertEquals(PlanStatus.DONE, completed.status, completed.issue?.message)
        val choice = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6-astra", "ultra")
        assertEquals(choice, runtime.sessions.single { it.stageId == "one" }.codingModel, "the worker session carries the choice verbatim, ultra included")
        assertTrue(runtime.sessions.size > 1, "the plan also runs a final verification session")
        assertTrue(runtime.sessions.all { it.codingModel == choice }, "every session run from the assignment must carry it, or the engine falls back to the app ladder")
        service.shutdown()
    }

    @Test fun nativeStageWhoseModelLeftTheCatalogIsRefusedByNameBeforeAnyWorkerStarts() = runTest {
        val gone = astra.copy(id = "gpt-gone", name = "Gone")
        val (store, service, runtime) = fixture(roster = listOf(subscription), nativeModels = NativeModelSnapshots { codexSnapshot })
        store.save(plan(nativeStage(gone, "low")).copy(engine = CodingEngine.CODEX))
        service.start("plan"); advanceTimeBy(2_000); runCurrent()
        val blocked = checkNotNull(store.planFor("plan"))
        val message = blocked.issue?.message.orEmpty()
        assertContains(message, "gpt-gone")
        assertContains(message, "переназначьте этап")
        assertNotEquals(PlanStatus.DONE, blocked.status)
        assertTrue(runtime.calls.isEmpty(), "no worker may start on a model the engine no longer offers")
        service.shutdown()
    }

    @Test fun nativeStageIsNotJudgedWhenNoCatalogIsKnownSoAnOfflineStartStillRuns() = runTest {
        val (store, service, runtime) = fixture(roster = listOf(subscription))
        store.save(plan(nativeStage(astra, "max")).copy(engine = CodingEngine.CODEX))
        service.start("plan"); advanceTimeBy(2_000); runCurrent()
        assertEquals(PlanStatus.DONE, checkNotNull(store.planFor("plan")).status)
        assertTrue(runtime.sessions.isNotEmpty() && runtime.sessions.all { it.codingModel?.level == "max" })
        service.shutdown()
    }

    @Test fun classifierPausePrecedesEveryWorkspaceAndWorkerEffect() = runTest {
        val events = InMemoryEventJournal()
        var prepared = 0
        var classifications = 0
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                prepared++
                return PlanWorkspace("/fake", "/fake")
            }
        }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                classifications++
                return """{"cause":"UNKNOWN"}"""
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace, events = events, strategyGateway = gateway)
        store.save(plan(stage("work")).copy(runId = "run", intent = ExecutionIntent.RUN,
            issue = PlanningIssue(IssueKind.TRANSIENT, "Network failure", retries = 2)))
        val diagnostic = checkNotNull(store.planFor("plan")).issue
        store.command("plan", PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp(Id.new(), Id.now())))
        store.command("plan", PlanningMachine.Fact.IssueObserved(checkNotNull(store.currentAdmission("plan")), diagnostic,
            stamp = PlanningMachine.Stamp(Id.new(), Id.now())))
        repeat(2) { store.withJournaledIntent("plan", PlanJournalOperation.AGENT_INTENT, "work", "failed") { reject() } }
        service.start("plan"); runCurrent()
        assertEquals(1, classifications)
        assertEquals(0, prepared)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(ExecutionIntent.PAUSE, store.planFor("plan")!!.intent)
        assertTrue(store.planFor("plan")!!.issue!!.requiresUser)
        assertEquals(1, events.read("plan").count { it.operation == PlanJournalOperation.STRATEGY_SELECTED.wire })
        service.shutdown()
    }

    @Test fun classifiedBackoffRunsThroughExistingAcceptanceAndRecordsTheActualOutcome() = runTest {
        val events = InMemoryEventJournal()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = """{"cause":"TRANSIENT_TRANSPORT"}"""
        }
        val (store, service, runtime) = fixture(events = events, strategyGateway = gateway)
        store.save(plan(stage("work")).copy(runId = "run", intent = ExecutionIntent.RUN,
            issue = PlanningIssue(IssueKind.TRANSIENT, "Network failure", retries = 2)))
        val diagnostic = checkNotNull(store.planFor("plan")).issue
        store.command("plan", PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(), PlanningMachine.Stamp(Id.new(), Id.now())))
        store.command("plan", PlanningMachine.Fact.IssueObserved(checkNotNull(store.currentAdmission("plan")), diagnostic,
            stamp = PlanningMachine.Stamp(Id.new(), Id.now())))
        repeat(2) { store.withJournaledIntent("plan", PlanJournalOperation.AGENT_INTENT, "work", "failed") { reject() } }
        service.start("plan"); advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.calls.isNotEmpty())
        assertEquals(ExecutionPhase.COMPLETE, store.planFor("plan")!!.phase)
        val records = events.read("plan").map { it.planEvidence() }
        val selection = records.single { it.operation == PlanJournalOperation.STRATEGY_SELECTED.wire }
        val result = records.filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
            .map { PlanIntentOutcome.decode(it.detail) }.single { it.strategySeq == selection.seq }
        assertEquals(PlanIntentStatus.COMPLETED, result.status)
        assertEquals(PlanJournalOperation.AGENT_INTENT.wire, records.single { it.seq == result.intentSeq }.operation)
        assertTrue(store.unsettled("plan").isEmpty())
        service.shutdown()
    }

    @Test fun restoredJournalIntentBlocksExecutionBeforeAnyWorkspaceOrWorkerEffect() = runTest {
        val events = InMemoryEventJournal()
        var prepared = 0
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                prepared++
                return PlanWorkspace("/fake", "/fake")
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace, events = events)
        store.save(plan(stage("work")))
        val original = events.append("plan", PlanJournalOperation.PREPARE_INTENT.wire, 1)
        service.start(project.id); advanceTimeBy(60_000); runCurrent()
        assertEquals(0, prepared)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(IssueKind.UNCERTAIN, store.planFor("plan")!!.issue?.kind)
        assertEquals(listOf(original), store.unsettled("plan"))
        service.retry(project.id); advanceTimeBy(60_000); runCurrent()
        assertEquals(0, prepared, "A retry click is not confirmation of an unknown effect")
        assertTrue(runtime.calls.isEmpty())
        service.shutdown()
    }

    @Test fun journalQuarantinePreservesAnIndependentNonRetryableBlocker() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, runtime) = fixture(events = events)
        val original = PlanningIssue(IssueKind.VERIFICATION, "Owner action required", requiresUser = true, retryBlocked = true)
        store.save(plan(stage("work")).copy(issue = original))
        events.append("plan", PlanJournalOperation.CAPTURE_INTENT.wire, 1)
        service.recoverJournalQuarantines()
        service.start(project.id)
        assertEquals(original, store.planFor("plan")!!.issue)
        assertEquals(1, store.unsettled("plan").size)
        assertTrue(runtime.calls.isEmpty())
        service.shutdown()
    }

    @Test fun restoreInspectsEvenCompletedPlansWithoutReexecutingOrDiscardingCompletion() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, runtime) = fixture(events = events)
        store.save(plan(stage("work")).copy(phase = ExecutionPhase.COMPLETE, status = PlanStatus.DONE))
        events.append("plan", PlanJournalOperation.APPLY_INTENT.wire, 1)
        service.recoverJournalQuarantines()
        assertEquals(ExecutionPhase.COMPLETE, store.planFor("plan")!!.phase)
        assertEquals(IssueKind.UNCERTAIN, store.planFor("plan")!!.issue?.kind)
        assertTrue(runtime.calls.isEmpty())
        service.shutdown()
    }

    @Test fun successfulExecutionSettlesEveryEffectWithAnExactOutcome() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, _) = fixture(events = events)
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertEquals(ExecutionPhase.COMPLETE, store.planFor("plan")!!.phase)
        val records = events.read("plan").map { it.planEvidence() }
        val intents = records.filter { PlanJournalOperation.of(it.operation)?.kind == JournalEntryKind.INTENT }
        assertEquals(setOf(PlanJournalOperation.PREPARE_INTENT, PlanJournalOperation.STAGE_WORKSPACE_INTENT,
            PlanJournalOperation.AGENT_INTENT, PlanJournalOperation.CAPTURE_INTENT, PlanJournalOperation.MERGE_INTENT,
            PlanJournalOperation.FINAL_VERIFICATION_INTENT, PlanJournalOperation.APPLY_INTENT),
            intents.map { PlanJournalOperation.of(it.operation) }.toSet())
        val outcomes = records.filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
            .map { PlanIntentOutcome.decode(it.detail) }
        assertEquals(intents.map { it.seq }.toSet(), outcomes.map { it.intentSeq }.toSet())
        assertEquals(intents.size, outcomes.size)
        assertTrue(outcomes.all { it.status == PlanIntentStatus.COMPLETED })
        service.shutdown()
    }

    @Test fun admissionRejectionSettlesIntentWithoutStartingTheWorker() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, runtime, ports) = fixture(events = events)
        ports.onPrepareAttempt = { _, _, _ -> error("admission denied") }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        val records = events.read("plan").map { it.planEvidence() }
        val intent = records.single { it.operation == PlanJournalOperation.AGENT_INTENT.wire }
        assertEquals(PlanIntentStatus.REJECTED, records.filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
            .map { PlanIntentOutcome.decode(it.detail) }.single { it.intentSeq == intent.seq }.status)
        assertNotNull(store.planFor("plan")!!.issue)
        service.shutdown()
    }

    @Test fun stopRetainsUnknownWorkerOutcomeWithoutInventingInterruptionProof() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, _) = fixture(Runtime(CompletableDeferred()), events = events)
        store.save(plan(stage("work")))
        service.start(project.id); runCurrent()
        service.stop(project.id); runCurrent()
        val records = events.read("plan").map { it.planEvidence() }
        val intent = records.single { it.operation == PlanJournalOperation.AGENT_INTENT.wire }
        assertFalse(records.filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
            .map { PlanIntentOutcome.decode(it.detail) }.any { it.intentSeq == intent.seq })
        assertTrue(store.unsettled("plan").any { it.seq == intent.seq })
        assertNull(store.currentAdmission("plan"))
        service.shutdown()
    }

    @Test fun cancelledApplicationAdmissionIsKnownNotDispatched() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, runtime, ports) = fixture(events = events)
        ports.onPrepareAttempt = { _, _, _ ->
            service.pause(project.id)
            currentCoroutineContext().cancel(CancellationException("Admission cancelled"))
            currentCoroutineContext().ensureActive()
            error("Cancelled admission cannot return")
        }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        val records = events.read("plan").map { it.planEvidence() }
        val intent = records.single { it.operation == PlanJournalOperation.AGENT_INTENT.wire }
        assertEquals(PlanIntentStatus.REJECTED, records.filter { it.operation == PlanJournalOperation.INTENT_OUTCOME.wire }
            .map { PlanIntentOutcome.decode(it.detail) }.single { it.intentSeq == intent.seq }.status)
        service.shutdown()
    }

    @Test fun thrownTransportAfterWorkerInvocationRemainsUnknown() = runTest {
        val events = InMemoryEventJournal()
        val (store, service, runtime) = fixture(events = events)
        runtime.onRun = { error("transport lost response") }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertEquals(1, runtime.calls.size)
        val intent = events.read("plan").map { it.planEvidence() }.single { it.operation == PlanJournalOperation.AGENT_INTENT.wire }
        assertTrue(store.unsettled("plan").any { it.seq == intent.seq })
        assertNull(store.currentAdmission("plan"))
        service.shutdown()
    }

    @Test fun admittedGenerationIsSavedBeforeNativeRunAndRetainedInCompletionCheckpoint() = runTest {
        val (store, service, runtime, ports) = fixture()
        val checkpoints = mutableListOf<StageAttempt>()
        ports.onPrepareAttempt = { plan, _, attempt ->
            assertTrue(plan.journal.any { it.operation == "agent-intent" && it.attemptId == attempt.id })
            assertTrue(runtime.calls.isEmpty())
            attempt.copy(sessionGeneration = 41)
        }
        ports.onAttemptCheckpoint = { plan, stageId, attempt ->
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
        val (store, service, runtime, ports) = fixture()
        ports.onPrepareAttempt = { _, _, _ -> error("Admission refused") }
        store.save(plan(stage("work")))
        service.start(project.id); advanceTimeBy(1_000); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertNotNull(store.planFor(project.id)!!.issue)
    }

    @Test fun stopProjectionWaitsForNativeCleanupAndSuccessfulReconciliation() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val (store, service, runtime, ports) = fixture(Runtime(CompletableDeferred(), cleanupGate = cleanup))
        var projected = 0
        ports.onStoppedCheckpoint = { plan ->
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
        val (store, service, runtime, ports) = fixture()
        var available = false
        ports.onPrepareAttempt = { _, _, attempt -> attempt.copy(sessionGeneration = 7) }
        ports.onAttemptCheckpoint = { _, _, attempt ->
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

    @Test fun continuingWhilePausedWorkerIsStillRunningPreservesItsAdmission() = runTest {
        val worker = CompletableDeferred<Unit>()
        val (store, service, runtime) = fixture(Runtime(worker))
        store.save(plan(stage("work")))
        service.start(project.id); runCurrent()
        val ref = checkNotNull(store.currentAdmission("plan"))
        assertEquals(1, runtime.calls.size)
        service.pause(project.id); runCurrent()
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); runCurrent()
        assertEquals(ref, store.currentAdmission("plan"))
        assertEquals(1, runtime.calls.size)
        worker.complete(Unit); advanceTimeBy(2_000); runCurrent()
        assertEquals(PlanStatus.DONE, store.planFor("plan")?.status, store.planFor("plan")?.issue?.message)
        assertTrue(store.unsettled("plan").isEmpty())
        service.shutdown()
    }

    @Test fun questionInterruptsOnlyAffectedWorkerAndResumesSameAttempt() = runTest {
        val (store, service, runtime, ports) = fixture(Runtime(CompletableDeferred(), trailingDelta = "Saved progress"))
        var blocked = emptySet<String>()
        ports.chatHooks = object : PlanningExecutionHooks {
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
        assertFailsWith<IllegalArgumentException> { service.pause(project.id) }
        blocked = emptySet()
        advanceTimeBy(200); runCurrent()
        assertEquals(calls, runtime.calls.size)
        assertEquals(PlanningMachine.RunPhase.UNKNOWN, store.machineStates.value.getValue("plan").run?.phase)
        service.stopAndJoin("plan")
        service.start(project.id); advanceTimeBy(200); runCurrent()
        assertEquals(calls, runtime.calls.size, "Cancellation does not prove an external worker outcome")
        assertTrue(store.unsettled("plan").isNotEmpty())
        for(attempt in listOf(before, research)) {
            val session = CodingSession(attempt.sessionId, project.id, "Worker", 1, planId = "plan")
            assertEquals(QuarantineRecoveryOutcome.NEEDS_CONFIRMATION, service.reconcileJournalQuarantine(session, confirmed = false))
            assertEquals(QuarantineRecoveryOutcome.RESOLVED, service.reconcileJournalQuarantine(session, confirmed = true))
        }
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
        val (store, service, _, ports) = fixture(runtime)
        var blocked = emptySet<String>()
        ports.chatHooks = object : PlanningExecutionHooks {
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
        assertContains(running.title, "./gradlew :feature:session:impl:jvmTest")
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
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "files-after-${runtime.calls.count { !it.contains("-final") }}"
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
    @Test fun restoredVerificationRequiresExplicitStartWithoutRerunningWorker() = runTest {
        val (store, service, runtime) = fixture()
        val attempt = StageAttempt("attempt", "session", StageAssignment("agent", "m"), phase = AttemptPhase.VERIFYING, path = "/fake", report = "Already done")
        store.save(plan(stage("a").copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))).copy(intent = ExecutionIntent.RUN, runId = "run"))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertTrue(runtime.calls.single().endsWith("-final-session"))
        assertEquals(PlanStatus.DONE, store.planFor(project.id)?.status)
    }
    @Test fun bootstrapLeavesUserPauseAndStopAlone() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("a")).copy(intent = ExecutionIntent.PAUSE)); service.bootstrap(); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        store.seed(project.id) { it.copy(intent = ExecutionIntent.STOP) }; advanceTimeBy(6000); runCurrent()
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
        assertContains(saved.issue!!.message, reason)
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
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); runCurrent()
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
            // Advance the real persisted deadline and the scheduler's next poll, retaining the
            // original explicit admission; restoring or editing a checkpoint is not a clock.
            assertNotNull(store.currentAdmission(waiting.id))
            advanceTimeBy((waiting.issue!!.retryAt - testScheduler.currentTime).coerceAtLeast(0) + 5_100)
            runCurrent()
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
            override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = false
            override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = ++resolutions >= 4
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
            override suspend fun integrate(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = false
            override suspend fun finishConflict(workspace: PlanWorkspace, attempt: StageAttempt, operation: WorkspaceOperation) = false
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
        val (store, service, _, ports) = fixture(Runtime(CompletableDeferred()))
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
        ports.onAuthorizeRetry = { snapshot, stageId, attempt ->
            assertEquals(before, snapshot)
            authorized += stageId to attempt.id
            permission
        }
        ports.onPrepareAttempt = { _, stageId, attempt ->
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

    @Test fun retryRejectsNewGlobalIssueOrFinalAttemptWhileAuthorizationIsPending() = runTest {
        for (changeFinal in listOf(false, true)) {
            val (store, service, runtime, ports) = fixture()
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
            var accepted: Plan? = null
            ports.onAuthorizeRetry = { _, _, _ ->
                store.save(changed)
                accepted = store.planFor(before.id)
                assertEquals(before.revision + 1, accepted!!.revision)
                assertEquals(changed, accepted!!.copy(revision = changed.revision, updatedAt = changed.updatedAt))
                PlanAttemptRetryAuthorization("retry", SessionLegacyAttempt(before.id, before.runId,
                    "a", failed.id, failed.turnIndex, failed.sessionGeneration), 10)
            }
            assertFailsWith<IllegalArgumentException> { service.retry(before.id) }
            runCurrent()
            assertEquals(accepted, store.planFor(before.id))
            assertTrue(runtime.calls.isEmpty())
        }
    }

    @Test fun transportLimitSurvivesExplicitContinuation() = runTest {
        val (store, service, runtime) = fixture(Runtime(failure = "429 rate limit"))
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(200); runCurrent()
        repeat(3) {
            store.seed(project.id) { p -> p.copy(milestones = p.milestones.map { m -> m.copy(attempts = m.attempts.map { a -> a.copy(error = a.error?.copy(retryAt = 0)) }) }) }
            service.retry(project.id); advanceTimeBy(200); runCurrent()
        }
        assertEquals(4, runtime.calls.size)
        val saved = store.planFor(project.id)!!
        assertEquals(3, saved.milestones.single().attempts.single().transportRetries)
        assertTrue(saved.issue!!.requiresUser)
    }

    @Test fun planningProposalCanRebaseOverTelemetryButNotUserEdits() = runTest {
        val (store) = fixture()
        store.save(plan(stage("a"), stage("b")))
        store.command(project.id, PlanningMachine.Intent.Start("run", PlanningRulesSettings().snapshot(),
            PlanningMachine.Stamp(Id.new(), Id.now())))
        val ref = store.currentAdmission(store.planFor(project.id)!!.id)!!
        val base = store.planFor(project.id)!!
        val request = store.beginRefinement(base)
        val proposed = base.copy(milestones = base.milestones.map { if (it.id == "b") it.copy(description = "new task") else it })
        val created = store.command(base.id, PlanningMachine.Fact.StageCreated(ref, "a", "attempt", "worker",
            StageAssignment("agent", "m"), 1, PlanningMachine.Stamp(Id.new(), Id.now()))).milestones.first().attempts.single()
        store.command(base.id, PlanningMachine.Fact.StageProgressObserved(ref, "a", PlanningMachine.AttemptRef.from(created),
            StageProgress.from(created.copy(report = "live")), PlanningMachine.Stamp(Id.new(), Id.now())))
        store.completeRefinement(request, proposed)
        assertEquals("live", store.planFor(project.id)!!.milestones.first().report)
        assertEquals("new task", store.planFor(project.id)!!.milestones.last().description)
        assertFailsWith<IllegalArgumentException> { store.completeRefinement(request, proposed) }
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
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        assertEquals(0, checked, "Restore cannot invoke the verifier")
        service.start(project.id); runCurrent(); advanceTimeBy(500); runCurrent()
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
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); runCurrent(); advanceTimeBy(500); runCurrent()
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
            store.seed(project.id) { p -> p.copy(milestones = p.milestones.map { m ->
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

    @Test fun workspaceTransportExceptionRemainsUnknownAcrossRestoreAndExplicitRetry() = runTest {
        var preparations = 0
        val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                preparations++
                throw IllegalStateException("HTTP 500: unavailable")
            }
        }
        val (store, service, runtime) = fixture(workspace = workspace)
        store.save(plan(stage("a")))
        service.start(project.id); advanceTimeBy(200); runCurrent()
        val unresolved = store.unsettled("plan")
        assertEquals(listOf(PlanJournalOperation.PREPARE_INTENT.wire), unresolved.map { it.operation })
        assertEquals(PlanningMachine.RunPhase.UNKNOWN, store.machineStates.value["plan"]?.run?.phase)
        assertNull(store.currentAdmission("plan"))
        store.recover()
        service.bootstrap(); advanceTimeBy(60_000); runCurrent()
        repeat(3) {
            service.retry(project.id); advanceTimeBy(200); runCurrent()
        }
        assertEquals(1, preparations, "A transport exception cannot prove that workspace preparation had no effect")
        assertTrue(runtime.calls.isEmpty())
        assertEquals(unresolved, store.unsettled("plan"))
        assertNull(store.currentAdmission("plan"))
        assertEquals(IssueKind.UNCERTAIN, store.planFor(project.id)!!.issue!!.kind)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
        service.shutdown()
    }

    @Test fun finalVerificationUnknownExternalCommandRequiresAcknowledgement() = runTest {
        val (store, service, runtime) = fixture()
        val attempt = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.EXECUTING,
            pendingTool = "curl https://example.com/action", pendingToolExternal = true)
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(
            intent = ExecutionIntent.RUN, runId = "run", finalAttempt = attempt))
        service.bootstrap(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); advanceTimeBy(500); runCurrent()
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

    private suspend fun PlanningStore.beginRefinement(base: Plan): RefinementRef {
        val result = dispatch(base.id, PlanningMachine.Intent.BeginRefinement(PlanningMessage(Id.new(), "user", "Refine"),
            null, false, base.plannerSelection, base.searchProvider, PlanningMachine.Stamp(Id.new(), Id.now())))
        assertNull(result.rejection)
        return checkNotNull(result.state.refinement).ref
    }

    private suspend fun PlanningStore.completeRefinement(ref: RefinementRef, result: Plan): Plan = command(ref.planId,
        PlanningMachine.Fact.RefinementCompleted(ref, RefinementResult(PlanSpecification.from(result),
            PlanningMessage("${ref.requestId}-reply", "assistant", result.dialogue.lastOrNull { it.role == "assistant" }?.text ?: "Updated")),
            PlanningMachine.Stamp(Id.new(), Id.now())))

    @Test fun rejectedFinalCanBeExtendedAndRecheckedWithoutRepeatingCompletedWork() = runTest {
        val (store, service, runtime) = fixture()
        store.save(rejectedFinalPlan())
        val base = store.planFor(project.id)!!
        val revised = store.completeRefinement(store.beginRefinement(base), withFollowup(base))
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
        val discussed = store.completeRefinement(store.beginRefinement(base),
            base.copy(dialogue = listOf(PlanningMessage("reply", "assistant", "Уточните требования"))))
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
        val request = store.beginRefinement(base)
        store.seed(base.id) { it.copy(phase = ExecutionPhase.VERIFYING, issue = null) }
        assertFailsWith<IllegalArgumentException> { store.completeRefinement(request, withFollowup(base)) }
        assertEquals(base.finalAttempt, store.planFor(project.id)!!.finalAttempt)
    }

    @Test fun extensionCannotClearUncertainCommandsOrAnAppliedWorkspace() = runTest {
        val rejected = rejectedFinalPlan()
        for (blocked in listOf(
            rejected.copy(finalAttempt = rejected.finalAttempt!!.copy(pendingTool = "deploy", pendingToolExternal = true)),
            rejected.copy(issue = PlanningIssue(IssueKind.UNCERTAIN, "Unknown result", requiresUser = true)),
            rejected.copy(workspace = PlanWorkspace("/fake", "/fake", applied = true)),
        )) {
            val (store) = fixture()
            store.save(rejected)
            val base = store.planFor(project.id)!!
            val request = store.beginRefinement(base)
            store.seed(base.id) { it.copy(finalAttempt = blocked.finalAttempt, issue = blocked.issue, workspace = blocked.workspace) }
            val current = store.planFor(project.id)!!
            assertFailsWith<IllegalArgumentException> { store.completeRefinement(request, withFollowup(base)) }
            assertEquals(current, store.planFor(project.id))
        }
    }

    @Test fun finalVerifierThrownErrorRetainsAttemptAndRetryBudget() = runTest {
        var verificationCalls = 0
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                verificationCalls++
                throw IllegalStateException("503 network unavailable")
            }
        }
        val (store, service, runtime) = fixture(verifier = verifier)
        val attempt = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.VERIFYING,
            report = "Completed checks", transportRetries = 3)
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(
            intent = ExecutionIntent.RUN, runId = "run", finalAttempt = attempt))
        service.bootstrap(); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        assertEquals(0, verificationCalls)
        service.start(project.id); advanceTimeBy(500); runCurrent()
        assertTrue(runtime.calls.isEmpty())
        assertEquals(1, verificationCalls)
        assertEquals(AttemptPhase.VERIFYING, store.planFor(project.id)!!.finalAttempt!!.phase)
        assertEquals(3, store.planFor(project.id)!!.finalAttempt!!.transportRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun committedDeliveryResumesVerificationBeforeTransfer() = runTest {
        var finished = false
        val port = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
            override suspend fun finishDeliveryConflict(path: String, operation: WorkspaceOperation): Boolean { finished = true; return true }
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace {
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
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        assertFalse(finished, "Restore cannot complete a transfer")
        service.start(project.id); runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(finished, "Saved issue: ${store.planFor(project.id)?.issue}; service error: ${service.error.value}")
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
        assertTrue(blocked.blockingIssues(emptyList()).single().canSkipVerification)
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(1, runtime.calls.size)
        assertEquals(0, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
    }

    @Test fun sourceChangeDuringFinalReviewInvalidatesAcceptanceAndPreventsApply() = runTest {
        var snapshot = "before"
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = snapshot
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace = error("Must not apply stale result")
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
        val proofs = blocked.blockingIssues(emptyList()).map { checkNotNull(it.verificationProof) }.toSet()
        assertFailsWith<IllegalArgumentException> { service.continueWithoutVerification(blocked.id, setOf("stale"), proofs) }
        service.continueWithoutVerification(blocked.id, ids, proofs); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(1, runtime.calls.size, "Neither file changes nor explicitly skipped final checks should run again")
        assertEquals(1, workspace.applied)
        assertEquals(1, saved.acceptanceWaivers.size)
        assertEquals(CheckStatus.SKIPPED, saved.finalAttempt!!.acceptanceRecord!!.findings.single().status)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt!!.acceptanceRecord!!.status)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.milestones.single().attempts.single().acceptanceRecord!!.status)
        assertContains(saved.milestones.single().checkNote, "по решению пользователя")
        val restored = json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), saved))
        assertEquals(saved.acceptanceWaivers, restored.acceptanceWaivers)
        assertFailsWith<IllegalArgumentException> { service.continueWithoutVerification(saved.id, ids, proofs) }
    }

    @Test fun skippingOneStageDoesNotSkipOtherStageChecks() = runTest {
        val (store, service, runtime) = fixture()
        store.save(plan(stage("a").copy(acceptanceCriteria = listOf(
            AcceptanceCriterion("manual", "Inspect layout", environment = EvidenceEnvironment.MANUAL))), stage("b", listOf("a"))))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val blocked = store.planFor(project.id)!!
        service.continueWithoutVerification(blocked.id, blocked.blockingIssues(emptyList()).map { it.messageId }.toSet(),
            blocked.blockingIssues(emptyList()).map { checkNotNull(it.verificationProof) }.toSet())
        advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(3, runtime.calls.size, "Other stage and its final verification must still run")
        assertEquals(CheckStatus.PASS, saved.finalAttempt!!.acceptanceRecord!!.findings.single { it.criterionId == "b/result" }.status)
        assertEquals(CheckStatus.SKIPPED, saved.finalAttempt!!.acceptanceRecord!!.findings.single { it.criterionId == "a/manual" }.status)
    }

    @Test fun skipCoversPreviouslyPassedCriteriaAndDoesNotReadSnapshotAgain() = runTest {
        var skippedByUser = false
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?): String {
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
        service.continueWithoutVerification(blocked.id, blocked.blockingIssues(emptyList()).map { it.messageId }.toSet(),
            blocked.blockingIssues(emptyList()).map { checkNotNull(it.verificationProof) }.toSet())
        advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(1, runtime.calls.size)
        assertEquals(2, saved.acceptanceWaivers.size)
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt!!.acceptanceRecord!!.status)
        assertTrue(saved.finalAttempt!!.acceptanceRecord!!.findings.all { it.status == CheckStatus.SKIPPED })
    }

    @Test fun explicitStartRestoresLegacySkipBlockedByStaleSnapshot() = runTest {
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?): String = error("Skipped verification must not fingerprint the project")
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
        assertTrue(runtime.calls.isEmpty(), "Restoring a saved RUN does not authorize a native call")
        assertNull(store.currentAdmission("plan"))
        service.start(project.id); advanceTimeBy(1000); runCurrent()
        val saved = store.planFor(project.id)!!
        assertEquals(PlanStatus.DONE, saved.status)
        assertEquals(criteria.toSet(), saved.acceptanceWaivers.map { it.criterion }.toSet())
        assertTrue(runtime.calls.isEmpty(), "The already completed worker turn must not execute again")
        assertEquals(AcceptanceStatus.ACCEPTED_WITH_SKIPS, saved.finalAttempt!!.acceptanceRecord!!.status)
    }

    @Test fun pauseDuringFinalReviewCannotApplyOrCompletePlan() = runTest {
        lateinit var store: TestPlanningStore
        var applied = false
        val workspace = object : PlanningWorkspace by Workspaces() {
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace, operation: WorkspaceOperation): PlanWorkspace { applied = true; return workspace.copy(applied = true) }
        }
        val judge = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                if (milestone.id == "final") store.seed("plan") { it.copy(intent = ExecutionIntent.PAUSE) }
                return Verdict(true, "Checked")
            }
        }
        val fixture = fixture(workspace = workspace, verifier = judge); store = fixture.store
        store.save(plan(stage("a")))
        fixture.service.start(project.id); advanceTimeBy(500); runCurrent()
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
