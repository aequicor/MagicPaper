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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profile = LlmProfile("agent", "Agent", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
    private val project = CodingProject("project", "Project", "/fake", 1)
    private val pass = object : MilestoneVerifier {
        override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "checked")
    }
    private class Runtime(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: String? = null,
        private val commandGate: CompletableDeferred<Unit>? = null,
    ) : CodingRuntime {
        val calls = mutableListOf<String>(); val aborted = mutableListOf<String>()
        override val supported = true; override val rootPath = "/fake"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun abort(sessionId: String) { aborted += sessionId }
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += session.id
            emit(CodingEvent.SessionStarted("engine-${session.id}"))
            emit(CodingEvent.ThinkingDelta("Проверяю критерии"))
            emit(CodingEvent.ToolStarted("read", "Чтение проекта", "read-1"))
            emit(CodingEvent.ToolFinished("read", false, "read-1", "Файлы прочитаны"))
            if (commandGate != null) {
                emit(CodingEvent.ToolStarted("command", "./gradlew :shared:jvmTest", "command-1", isExec = true))
                commandGate.await()
                emit(CodingEvent.ToolFinished("command", false, "command-1", "BUILD SUCCESSFUL"))
            }
            gate?.await()
            if (failure != null) emit(CodingEvent.Failed(failure)) else emit(CodingEvent.FinalText("Verified result"))
            emit(CodingEvent.Finished)
        }
    }
    private class Workspaces(private val parallel: Boolean = true, private val base: PlanningWorkspace = LocalPlanningWorkspace()) : PlanningWorkspace by base {
        var applied = 0
        var released = 0
        override suspend fun release(project: CodingProject) { released++; base.release(project) }
        override suspend fun prepare(project: CodingProject, runId: String) = PlanWorkspace("/fake", "/fake", git = parallel)
        override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace { applied++; return workspace.copy(applied = true) }
    }
    private suspend fun TestScope.fixture(runtime: Runtime = Runtime(), workspace: PlanningWorkspace = Workspaces(), verifier: MilestoneVerifier = pass): Triple<PlanningStore, PlanningExecutionService, Runtime> {
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val profiles = JsonLlmProfileRepository(kv, json).also { it.save(profile) }
        val projects = JsonCodingProjectRepository(kv, json).also { it.save(project) }
        val settings = JsonSettingsRepository(kv, json)
        return Triple(store, PlanningExecutionService(store, runtime, projects, profiles, settings, verifier, workspace, backgroundScope), runtime)
    }
    private fun plan(vararg stages: Milestone) = Plan("plan", "project", "Goal", milestones = stages.toList())
    private fun stage(id: String, depends: List<String> = emptyList()) = Milestone(id, id, description = "Check result", agentProfileId = "agent", dependsOn = depends)

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
    @Test fun silentRunningStageDoesNotAccumulateEmptyActivity() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (store, service) = fixture(Runtime(gate))
        store.save(plan(stage("a")))
        service.start(project.id); runCurrent()
        advanceTimeBy(2500); runCurrent()
        val attempt = store.planFor(project.id)!!.milestones.single().attempts.single()
        assertTrue(attempt.steps.isNotEmpty())
        assertTrue(attempt.steps.none { it.kind == CodingStepKind.INFO && it.title.isBlank() })
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

    @Test fun failedChecksHaveOnlyTwoRepairs() = runTest {
        val fail = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(false, "Test failed")
        }
        val (store, service, runtime) = fixture(verifier = fail)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(3, runtime.calls.size)
        assertEquals(2, store.planFor(project.id)!!.milestones.single().attempts.single().repairRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
    }

    @Test fun explicitRepairRetryRunsOneAdditionalTurnWithoutResettingAutomaticLimit() = runTest {
        val fail = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(false, "Restore permissions")
        }
        val (store, service, runtime) = fixture(verifier = fail)
        store.save(plan(stage("a"))); service.start(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(3, runtime.calls.size)
        service.retry(project.id); advanceTimeBy(1000); runCurrent()
        assertEquals(4, runtime.calls.size)
        val attempt = store.planFor(project.id)!!.milestones.single().attempts.single()
        assertContains(attempt.prompt, "Restore permissions")
        assertEquals(2, attempt.repairRetries)
        assertTrue(store.planFor(project.id)!!.issue!!.requiresUser)
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
            override suspend fun finishDeliveryConflict(path: String): Boolean { finished = true; return true }
            override suspend fun apply(project: CodingProject, workspace: PlanWorkspace): PlanWorkspace {
                assertTrue(finished, "Transfer must wait for the recorded verification")
                return workspace.copy(applied = true)
            }
        }
        val (store, service, runtime) = fixture(workspace = port)
        val a = StageAttempt("final", "session", StageAssignment("agent", "m"), phase = AttemptPhase.COMPLETE,
            mergePhase = AttemptPhase.VERIFYING, mergePath = "/fake/delivery", mergeRetries = 1, mergeReport = "checked merge")
        store.save(plan(stage("a").copy(status = MilestoneStatus.DONE)).copy(intent = ExecutionIntent.RUN, runId = "run", finalAttempt = a))
        service.bootstrap(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertTrue(finished)
        assertTrue(runtime.calls.isEmpty())
        assertEquals(PlanStatus.DONE, store.planFor(project.id)!!.status)
    }
}
