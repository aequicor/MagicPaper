package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.interactionCandidates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningChatServiceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profile = LlmProfile("model", "Planner", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
    private val project = CodingProject("project", "Project", "/shared", 1)
    private class Gateway : LlmGateway {
        var lastMessages = emptyList<LlmMessage>()
        val requests = mutableListOf<List<LlmMessage>>()
        var gate: CompletableDeferred<Unit>? = null
        var overrideReply: String? = null
        var timeout = false
        var failure: String? = null
        var userDecision = """{"intent":"REFINE"}"""
        var coordinator = """{"reply":"Результат принят","actions":[]}"""
        val coordinatorReplies = mutableListOf<String>()
        val coordinatorCallbacks = mutableListOf<(CodingStep) -> Unit>()
        val coordinatorGates = mutableListOf<CompletableDeferred<Unit>>()
        var requestCallback: ((CodingStep) -> Unit)? = null
        override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
            if (messages.first().content.contains("Ты координатор")) {
                val index = coordinatorCallbacks.size
                coordinatorCallbacks += onActivity
                onActivity(CodingStep(CodingStepKind.THINKING, "Разбираю результат ${index + 1}", callId = "thinking"))
                coordinatorGates.getOrNull(index)?.await()
                return complete(profile, messages)
            }
            requestCallback = onActivity
            return super.completeWithActivity(profile, messages, onActivity)
        }
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            lastMessages = messages
            requests += messages
            if (timeout) withTimeout(10) { awaitCancellation() }
            gate?.await()
            failure?.let { error(it) }
            if (messages.first().content.contains("Ты оркестратор диалога")) return userDecision
            overrideReply?.let { return it }
            if (messages.first().content.contains("Ты координатор"))
                return if (coordinatorReplies.isEmpty()) coordinator else coordinatorReplies.removeAt(0)
            return """{"reply":"Уточним результат","questions":[{"id":"single","title":"Формат?","kind":"SINGLE","options":[{"id":"pdf","label":"PDF"},{"id":"doc","label":"DOC"}]},{"id":"multi","title":"Возможности?","kind":"MULTIPLE","options":[{"id":"read","label":"Чтение"},{"id":"write","label":"Запись"}]},{"id":"text","title":"Критерии?","kind":"TEXT"}]}"""
        }
    }
    private class Runtime(val gate: CompletableDeferred<Unit> = CompletableDeferred()) : CodingRuntime {
        val calls = mutableListOf<Pair<CodingSession, String>>()
        val paths = mutableListOf<String>()
        val turnGates = mutableListOf<CompletableDeferred<Unit>>()
        val turnReplies = mutableListOf<String>()
        override val supported = true
        override val rootPath = "/shared"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            val index = calls.size
            calls += session to prompt; paths += project.path
            emit(CodingEvent.SessionStarted("engine-${session.id}"))
            gate.await()
            turnGates.getOrNull(index)?.await()
            emit(CodingEvent.FinalText(turnReplies.getOrNull(index) ?: """{"kind":"RESULT","text":"Проверки выполнены","changedFiles":[]}"""))
            emit(CodingEvent.Finished)
        }
    }
    private inner class Fixture(scope: TestScope, val kv: KeyValueStore = InMemoryKeyValueStore()) {
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val gateway = Gateway()
        val runtime = Runtime()
        var verdict = Verdict(true, "Checked")
        val execution = PlanningExecutionService(store, runtime, projects, profiles, settings, object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = verdict
        }, scope = scope.backgroundScope)
        val service = PlanningChatService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway, scope.backgroundScope)
        suspend fun initialize() {
            projects.save(project); profiles.save(profile); settings.save(AppSettings(activeLlmProfileId = profile.id))
            service.bootstrap()
        }
        suspend fun session(id: String, engine: CodingEngine? = null): CodingSession = CodingSession(id, project.id, id, 1, planningMode = true,
            modelSelection = ModelSelection(profile.id, "m"), engine = engine).also { projects.saveSession(it) }
        suspend fun readyPlan(id: String, parent: CodingSession) = Plan(id, project.id, "Goal $id", parentSessionId = parent.id, sharedWorkspace = true,
            plannerSelection = parent.modelSelection, milestones = listOf(Milestone("stage", "Stage", description = "Change files", acceptance = "Checks pass", assignment = StageAssignment(profile.id, "m"))),
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("stage")), DecisionNode("stage", "Stage", DecisionKind.STAGE, stageId = "stage"))).also { store.save(it) }
    }
    private suspend fun Fixture.status(item: CodingSessionUi): CodingSessionStatus {
        val sessions = projects.sessions(project.id).map { s ->
            if (s.id == item.session.id) item else CodingSessionUi(s, projects.messages(project.id, s.id), plan = store.plans.value.firstOrNull { it.id == s.planId })
        }
        val requests = interactionCandidates(CodingUi(sessions = sessions), store.plans.value, service.states.value, service.persistenceErrors.value)
        return item.copy(interactions = requests.filter { it.affects(item.session) }).status
    }

    private suspend fun Fixture.interactions(parent: CodingSession) = interactionCandidates(
        CodingUi(sessions = listOf(CodingSessionUi(parent, projects.messages(project.id, parent.id)))),
        store.plans.value, service.states.value, service.persistenceErrors.value)

    @Test fun confirmedAllSkippedQuestionnaireClosesOriginalRequestAndPersistsExplicitSkips() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.service.send(parent, "Подготовь план"); runCurrent()
        val request = f.interactions(parent).single { it.kind == InteractionKind.QUESTION }
        val original = f.projects.orchestration(parent.id)!!.questions.single { it.id == request.sourceId }
        assertTrue(original.partialAnswers.isEmpty())
        f.gateway.overrideReply = """{"reply":"Пропуски учтены","questions":[]}"""
        f.service.submitInteraction(request, request.questions.map { PlanningAnswer(it.id, skipped = true) }); runCurrent()
        val state = f.projects.orchestration(parent.id)!!
        assertEquals(UserRequestStatus.ANSWERED, state.questions.single { it.id == request.sourceId }.status)
        assertTrue(state.inputs.single { it.replyTo == request.sourceId }.answers.all { it.skipped })
        assertContains(state.inputs.single { it.replyTo == request.sourceId }.text, "Пропущено пользователем")
        assertFailsWith<IllegalStateException> { f.service.submitInteraction(request, request.questions.map { PlanningAnswer(it.id, skipped = true) }) }
    }

    @Test fun confirmationRejectsStaleRevisionAndNoKeepsPlanWithoutStarting() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent"); val plan = f.readyPlan("p", parent)
        val request = f.interactions(parent).single { it.kind == InteractionKind.CONFIRM_PLAN }
        f.store.update(plan.id) { it.copy(goal = "Уточнённая цель") }
        assertFailsWith<IllegalArgumentException> {
            f.service.submitInteraction(request, listOf(PlanningAnswer("decision", listOf("yes"))))
        }
        assertTrue(f.runtime.calls.isEmpty()); assertNull(f.store.planFor(plan.id)!!.confirmedRevision)
        val current = f.interactions(parent).single { it.kind == InteractionKind.CONFIRM_PLAN }
        f.service.submitInteraction(current, listOf(PlanningAnswer("decision", listOf("no")))); runCurrent()
        assertNull(f.store.planFor(plan.id)!!.confirmedRevision); assertTrue(f.runtime.calls.isEmpty())
        assertTrue(f.projects.messages(project.id, parent.id).any { it.id == current.id + "-declined" })
    }

    @Test fun restartRecoversUnavailableAssignmentsAndCoordinatesCheckpointBeforeStartingAnotherWorkerTurn() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val missing = StageAssignment("removed", "m")
        val attempt = StageAttempt("a", "plan-p-stage-stage", missing, phase = AttemptPhase.EXECUTING,
            path = "/shared", report = """{"kind":"RESULT","text":"Saved result","changedFiles":[]}""", awaitingPlanner = true)
        val completed = Milestone("done", "Finished", status = MilestoneStatus.DONE, assignment = missing,
            attempts = listOf(attempt.copy(id = "done-attempt", phase = AttemptPhase.COMPLETE)))
        f.store.save(base.copy(confirmedRevision = 1, runId = "existing-run", intent = ExecutionIntent.RUN,
            phase = ExecutionPhase.WAITING, status = PlanStatus.RUNNING,
            plannerSelection = ModelSelection("removed", "m"),
            issue = PlanningIssue(IssueKind.CONFIGURATION, "Источник этапа недоступен: removed"),
            milestones = listOf(completed, base.milestones.single().copy(status = MilestoneStatus.ACTIVE,
                assignment = missing, attempts = listOf(attempt)))))
        f.execution.shutdown(); runCurrent()
        val restored = Fixture(this, f.kv)
        restored.service.bootstrap(); restored.execution.bootstrap()
        restored.runtime.gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        val result = restored.store.planFor(base.id)!!
        assertEquals(PlanStatus.DONE, result.status)
        assertEquals("existing-run", result.runId)
        assertEquals(parent.modelSelection, result.plannerSelection)
        assertEquals(missing, result.milestones.first { it.id == "done" }.assignment)
        assertEquals(missing, result.milestones.first { it.id == "done" }.attempts.single().assignment)
        assertEquals(profile.id, result.milestones.first { it.id == "stage" }.attempts.single().assignment.profileId)
        assertTrue(restored.runtime.calls.none { it.first.id == attempt.sessionId })
        assertEquals(1, restored.gateway.coordinatorCallbacks.size)
        restored.execution.shutdown()
    }

    @Test fun unavailableModelIsVisibleAndRecoveryDoesNotSubstituteAnotherModel() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val base = f.readyPlan("p", f.session("parent"))
        f.store.save(base.copy(confirmedRevision = 1, milestones = base.milestones.map {
            it.copy(assignment = StageAssignment("removed", "unavailable-model"))
        }))
        f.service.resume(f.projects.sessions(project.id).first { it.id == "parent" }); runCurrent()
        val result = f.store.planFor(base.id)!!
        assertTrue(result.issue?.requiresUser == true)
        assertEquals(IssueKind.CONFIGURATION, result.issue?.kind)
        assertEquals("unavailable-model", result.milestones.single().assignment?.modelId)
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun shutdownRequeuesOrchestratorInputWhileExplicitCancelDoesNot() = runTest {
        for (stop in listOf(false, true)) {
            val f = Fixture(this); f.initialize(); runCurrent()
            val parent = f.session("parent")
            f.gateway.gate = CompletableDeferred()
            f.service.send(parent, "Prepare a plan"); runCurrent()
            assertEquals(OrchestrationInputStatus.PROCESSING, f.service.states.value[parent.id]!!.inputs.single().status)
            if (stop) { f.service.cancelRequest(parent.id); runCurrent() }
            f.execution.shutdown(); runCurrent()
            val restored = Fixture(this, f.kv)
            restored.service.bootstrap(); runCurrent()
            assertEquals(if (stop) OrchestrationInputStatus.CANCELLED else OrchestrationInputStatus.DONE,
                restored.service.states.value[parent.id]!!.inputs.single().status)
            assertEquals(1, restored.projects.messages(project.id, parent.id).count { it.role == CodingRole.USER })
            restored.execution.shutdown()
        }
    }

    @Test fun continueCanResumeCancelledOrchestratorBeforePlanConfirmation() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.gateway.gate = CompletableDeferred()
        f.service.send(parent, "Prepare a plan"); runCurrent()
        f.service.cancelRequest(parent.id); runCurrent()
        assertEquals(OrchestrationInputStatus.CANCELLED, f.service.states.value[parent.id]!!.inputs.single().status)
        f.gateway.gate!!.complete(Unit)
        f.service.resume(parent); runCurrent()
        assertEquals(OrchestrationInputStatus.DONE, f.service.states.value[parent.id]!!.inputs.single().status)
        assertNotNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun textEnteredWithContinueReachesWorkerBeforeStoppedPlanResumes() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent"); val base = f.readyPlan("p", parent)
        f.store.save(base.copy(confirmedRevision = 1, intent = ExecutionIntent.STOP, runId = "same-run"))
        f.gateway.userDecision = """{"intent":"INSTRUCT","stageId":"stage"}"""
        f.service.resume(parent, "Сохрани существующий формат"); runCurrent()
        val call = f.runtime.calls.single()
        assertContains(call.second, "Сохрани существующий формат")
        assertEquals(ExecutionIntent.RUN, f.store.planFor(base.id)!!.intent)
        assertEquals("same-run", f.store.planFor(base.id)!!.runId)
        assertEquals(OrchestrationInputStatus.DONE, f.service.states.value[parent.id]!!.inputs.single().status)
        f.execution.stop(base.id)
    }

    @Test fun resumeRestartsStoppedPlanInSameRunAndDoesNothingAfterCompletion() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent"); val base = f.readyPlan("p", parent)
        f.service.confirm(base.id); runCurrent()
        val runId = f.store.planFor(base.id)!!.runId
        f.execution.stop(base.id); runCurrent()
        f.runtime.gate.complete(Unit)
        f.service.resume(parent); advanceTimeBy(1000); runCurrent()
        assertEquals(PlanStatus.DONE, f.store.planFor(base.id)!!.status)
        assertEquals(runId, f.store.planFor(base.id)!!.runId)
        assertEquals(1, f.store.planFor(base.id)!!.milestones.single().attempts.size)
        val count = f.runtime.calls.size
        f.service.resume(parent); advanceTimeBy(1000); runCurrent()
        assertEquals(count, f.runtime.calls.size)
    }

    @Test fun deletingAllSessionsStopsPlansAndPreservesOtherProjects() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("plan", parent)
        val other = project.copy(id = "other")
        f.projects.save(other)
        f.projects.saveSession(CodingSession("other-session", other.id, "Keep", 1))
        f.store.save(Plan("other-plan", other.id, "Keep"))
        f.service.confirm(plan.id); runCurrent()
        assertEquals(1, f.runtime.calls.size)
        val deletedSessions = f.projects.sessions(project.id)
        f.service.deleteProjectSessions(project.id)
        f.runtime.gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertTrue(f.projects.sessions(project.id).isEmpty())
        assertTrue(JsonCodingProjectRepository(f.kv, json).sessions(project.id).isEmpty())
        assertTrue(f.store.plans.value.none { it.projectId == project.id })
        assertTrue(deletedSessions.all { f.projects.messages(project.id, it.id).isEmpty() })
        assertNotNull(f.projects.all().firstOrNull { it.id == project.id })
        assertEquals(1, f.projects.sessions(other.id).size)
        assertNotNull(f.store.planFor("other-plan"))
        f.service.deleteProjectSessions(project.id)
        assertEquals(1, f.runtime.calls.size)
    }

    @Test fun planningModeCannotBeRevertedEvenWithStaleSession() = runTest {
        val f = Fixture(this); f.initialize()
        val ordinary = CodingSession("locked", project.id, "Chat", 1)
        f.projects.saveSession(ordinary)
        f.service.configure(ordinary, planning = true)
        assertFailsWith<IllegalArgumentException> { f.service.configure(ordinary, planning = false) }
        val saved = f.projects.sessions(project.id).first { it.id == ordinary.id }
        assertTrue(saved.planningMode)
        f.service.configure(saved, search = SearchProvider.AUTO)
        assertTrue(f.projects.sessions(project.id).first { it.id == ordinary.id }.planningMode)
    }

    @Test fun handoffShowsLivePlannerActivityAndGreenWorkerThenUserQuestion() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.runtime.gate.complete(Unit)
        val coordinatorGate = CompletableDeferred<Unit>()
        f.gateway.coordinatorGates += coordinatorGate
        f.gateway.coordinator = """{"reply":"Уточните формат","askUser":true}"""
        f.service.confirm(plan.id); runCurrent()
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        val handedOff = f.store.planFor(plan.id)!!
        assertTrue(handedOff.milestones.single().attempts.last().awaitingPlanner)
        assertFalse(handedOff.isStageWorking(handedOff.milestones.single()))
        assertEquals(CodingSessionStatus.IDLE, CodingSessionUi(worker, plan = handedOff).status)
        val initial = assertNotNull(f.service.drafts.value[parent.id])
        assertTrue(initial.active)
        assertEquals(CodingSessionStatus.WORKING, CodingSessionUi(parent, draft = initial, running = initial.active, plan = handedOff).status)
        assertTrue(initial.steps.any { it.kind == CodingStepKind.THINKING })

        val callback = f.gateway.coordinatorCallbacks.single()
        callback(CodingStep(CodingStepKind.THINKING, "Сопоставляю результаты этапов", callId = "thinking"))
        callback(CodingStep(CodingStepKind.TOOL, "Проверка этапов", callId = "check", running = true))
        runCurrent()
        assertEquals("Сопоставляю результаты этапов", f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.THINKING }.title)
        assertTrue(f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.TOOL }.running)
        callback(CodingStep(CodingStepKind.TOOL, "Проверка этапов", callId = "check", result = "Проверено"))
        callback(CodingStep(CodingStepKind.ANSWER, "{\"askUser\":true}"))
        runCurrent()
        assertFalse(f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.TOOL }.running)
        assertTrue(f.service.drafts.value[parent.id]!!.steps.filter { it.kind == CodingStepKind.ANSWER }.all { it.title.isBlank() })

        coordinatorGate.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertNull(f.service.drafts.value[parent.id])
        val waiting = f.store.planFor(plan.id)!!
        val history = f.projects.messages(project.id, parent.id)
        assertEquals(CodingSessionStatus.WAITING, f.status(CodingSessionUi(parent, history, plan = waiting)))
        assertEquals(CodingSessionStatus.WAITING, f.status(CodingSessionUi(worker, plan = waiting)))
        val message = assertNotNull(history.pendingPlanningQuestion())
        assertTrue(message.steps.any { it.title == "Сопоставляю результаты этапов" })
        assertTrue(message.steps.none { it.running })
        val restored = json.decodeFromString<Plan>(json.encodeToString(Plan.serializer(), waiting))
        assertTrue(restored.milestones.single().attempts.last().awaitingPlanner)
        assertTrue(restored.coordination.single().activity.any { it.title == "Сопоставляю результаты этапов" })

        f.gateway.coordinator = """{"reply":"Результат принят"}"""
        f.service.send(parent, "PDF", replyTo = message.id); advanceTimeBy(1000); runCurrent()
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
        assertFalse(f.store.planFor(plan.id)!!.milestones.single().attempts.last().awaitingPlanner)
    }

    @Test fun exhaustedVerificationPublishesReasonWithoutInventingQuestionAndRetryRunsRepair() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        val reason = "Не подтверждены удаление индексов, блокировка ухудшений и восстановление разрешений."
        f.verdict = Verdict(false, reason)
        f.runtime.gate.complete(Unit)
        f.service.confirm(plan.id); advanceTimeBy(1000); runCurrent()
        val blocked = f.store.planFor(plan.id)!!
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        assertEquals(3, f.runtime.calls.size)
        assertEquals(2, blocked.milestones.single().attempts.single().repairRetries)
        val history = f.projects.messages(project.id, parent.id)
        assertNull(history.pendingPlanningQuestion())
        assertEquals(CodingSessionStatus.WAITING, f.status(CodingSessionUi(parent, history, plan = blocked)))
        val notice = history.single { it.failed }
        assertContains(notice.text, reason)
        assertContains(notice.text, "исчерпаны (2)")
        assertEquals(notice, f.projects.messages(project.id, worker.id).single { it.id == notice.id })
        val restored = JsonPlanningRepository(f.kv, json).planFor(plan.id)!!
        assertEquals(reason, restored.blockingIssues(history).single().issue.message)
        f.store.update(plan.id) { it }; runCurrent()
        assertEquals(history, f.projects.messages(project.id, parent.id))

        f.verdict = Verdict(true, "Исправления проверены")
        f.service.control(plan.id, "retry"); advanceTimeBy(1000); runCurrent()
        assertEquals(4, f.runtime.calls.count { it.first.id == worker.id })
        assertContains(f.runtime.calls.last { it.first.id == worker.id }.second, reason)
        val completed = f.store.planFor(plan.id)!!
        assertEquals(PlanStatus.DONE, completed.status)
        assertEquals(1, completed.milestones.size)
        assertEquals(2, completed.milestones.single().attempts.single().repairRetries)
        assertTrue(completed.blockingIssues(f.projects.messages(project.id, parent.id)).isEmpty())
    }

    @Test fun instructionAfterFailedVerificationReturnsToOriginalWorkerInsteadOfBlockedFollowup() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.verdict = Verdict(false, "Нужно проверить откат разрешений")
        f.runtime.gate.complete(Unit)
        f.service.confirm(plan.id); advanceTimeBy(1000); runCurrent()
        val workerId = f.projects.sessions(project.id).single { it.stageId != null }.id
        f.verdict = Verdict(true, "Откат проверен")
        f.gateway.userDecision = """{"intent":"INSTRUCT","stageId":"stage"}"""
        f.service.send(parent, "Добавь проверку восстановления разрешений"); advanceTimeBy(1000); runCurrent()
        val completed = f.store.planFor(plan.id)!!
        assertEquals(PlanStatus.DONE, completed.status)
        assertEquals(1, completed.milestones.size)
        assertEquals("stage", completed.deliveries.single().targetStageId)
        assertEquals(4, f.runtime.calls.count { it.first.id == workerId })
        assertContains(f.runtime.calls.last { it.first.id == workerId }.second, "Добавь проверку восстановления разрешений")
        assertContains(f.runtime.calls.last { it.first.id == workerId }.second, "Нужно проверить откат разрешений")
    }

    private suspend fun Fixture.rejectedFinal(parent: CodingSession): Plan {
        val draft = readyPlan("p", parent)
        val issue = PlanningIssue(IssueKind.VERIFICATION, "Coding integration is missing", requiresUser = true)
        val blocked = draft.copy(confirmedRevision = 1, runId = "run", intent = ExecutionIntent.RUN,
            status = PlanStatus.FAILED, phase = ExecutionPhase.WAITING, issue = issue,
            milestones = draft.milestones.map { it.copy(status = MilestoneStatus.DONE, report = "Completed work") },
            dialogue = listOf(PlanningMessage("last-answer", "user", "Нужны каталог и ссылка на репозиторий")),
            finalAttempt = StageAttempt("run-final", "run-final-session", StageAssignment(profile.id, "m"),
                phase = AttemptPhase.VERIFYING, report = "Previous checks", error = issue))
        store.save(blocked)
        service.prepareSessions(blocked)
        return store.planFor(blocked.id)!!
    }

    @Test fun failedFinalAcceptsUserContinuationOrRepairButtonAndResumesInSameSession() = runTest {
        for (useRetry in listOf(false, true)) {
            val f = Fixture(this); f.initialize(); runCurrent()
            val parent = f.session("parent")
            val blocked = f.rejectedFinal(parent)
            val followup = blocked.milestones.single().copy(id = "followup", title = "Coding integration",
                status = MilestoneStatus.PENDING, report = "", dependsOn = listOf("stage"))
            val proposal = blocked.copy(milestones = blocked.milestones + followup,
                tree = blocked.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + "followup") else it } +
                    DecisionNode("followup", "Coding integration", DecisionKind.STAGE, stageId = "followup"))
            f.gateway.overrideReply = "{\"reply\":\"Добавлены этапы продолжения\"," + json.encodeToString(Plan.serializer(), proposal).drop(1)
            if (useRetry) f.service.control(blocked.id, "retry") else f.service.send(parent, "Добавь подключение к coding-агенту")
            runCurrent()
            val resumed = f.store.planFor(blocked.id)!!
            assertEquals(blocked.id, resumed.id)
            assertEquals(blocked.parentSessionId, resumed.parentSessionId)
            assertNull(resumed.issue)
            assertNull(resumed.finalAttempt)
            assertEquals(blocked.finalAttempt, resumed.finalAttemptHistory.single())
            assertEquals(blocked.milestones.single(), resumed.milestones.first())
            assertNotEquals("followup", resumed.milestones.last().id)
            assertEquals("plan-${blocked.id}-stage-${resumed.milestones.last().id}", f.runtime.calls.single().first.id)
            assertContains(f.gateway.lastMessages.last().content, "Нужны каталог и ссылка на репозиторий")
            if (useRetry) assertContains(f.gateway.lastMessages.last().content, "Coding integration is missing")
            assertFalse(f.projects.messages(project.id, parent.id).any { it.text == "Итоговая проверка уже начата" })
            f.gateway.overrideReply = null
            f.runtime.gate.complete(Unit); advanceTimeBy(1000); runCurrent()
            assertEquals(PlanStatus.DONE, f.store.planFor(blocked.id)!!.status)
            assertEquals(2, f.runtime.calls.size)
            assertEquals("run-final-2-session", f.runtime.calls.last().first.id)
        }
    }

    @Test fun repairClarificationKeepsFinalBlockerUntilActualWorkIsAdded() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val blocked = f.rejectedFinal(parent)
        f.service.control(blocked.id, "retry"); runCurrent()
        val discussed = f.store.planFor(blocked.id)!!
        assertEquals(blocked.finalAttempt, discussed.finalAttempt)
        assertEquals(blocked.issue, discussed.issue)
        assertTrue(discussed.finalAttemptHistory.isEmpty())
        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(3, f.projects.messages(project.id, parent.id).pendingPlanningQuestion()!!.planning!!.questions.size)
    }

    @Test fun repeatedHandoffKeepsEachReplyAfterItsIncomingMessageAndSurvivesReload() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        val secondTurn = CompletableDeferred<Unit>()
        f.runtime.gate.complete(Unit)
        f.runtime.turnGates += listOf(CompletableDeferred(Unit), secondTurn)
        f.runtime.turnReplies += listOf(
            """{"kind":"QUESTION","text":"Какой формат?"}""",
            """{"kind":"RESULT","text":"Экспорт PDF проверен"}""",
        )
        f.gateway.coordinator = """{"reply":"Уточните формат","askUser":true}"""
        f.service.confirm(plan.id); runCurrent()
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        val first = f.projects.messages(project.id, worker.id).last { it.role == CodingRole.AGENT && it.handoff == null }
        assertEquals("Какой формат?", first.text)
        val question = f.projects.messages(project.id, parent.id).pendingPlanningQuestion()!!
        f.service.send(parent, "PDF", replyTo = question.id); advanceTimeBy(1000); runCurrent()
        assertEquals(2, f.runtime.calls.size)
        assertEquals(first, f.projects.messages(project.id, worker.id).single { it.role == CodingRole.AGENT && it.handoff == null })
        val coordinatorGate = CompletableDeferred<Unit>()
        f.gateway.coordinatorGates += listOf(CompletableDeferred(Unit), coordinatorGate)
        secondTurn.complete(Unit); runCurrent()
        val saved = f.store.planFor(plan.id)!!.milestones.single().attempts.single()
        assertTrue(saved.awaitingPlanner)
        val history = f.projects.messages(project.id, worker.id)
        val replies = history.filter { it.role == CodingRole.AGENT && it.handoff == null }
        assertEquals(listOf("Какой формат?", "Экспорт PDF проверен"), replies.map { it.text })
        assertEquals(first, replies.first())
        val answerIndex = history.indexOfFirst { it.deliveryId != null && "PDF" in it.text }
        assertTrue(answerIndex > history.indexOf(first))
        assertEquals(replies.last(), history.last { it.handoff == null })
        assertEquals(2, history.count { it.handoff != null })
        assertEquals(HandoffStatus.PROCESSING, history.last { it.handoff != null }.handoff!!.status)
        assertTrue(history.indexOf(replies.last()) > answerIndex)
        assertEquals(2, saved.chatTurns.size)
        assertTrue(saved.chatTurns.all { it.completedAt > 0 })
        assertEquals(history, JsonCodingProjectRepository(f.kv, json).messages(project.id, worker.id))
        assertEquals(saved.chatTurns, JsonPlanningRepository(f.kv, json).planFor(plan.id)!!.milestones.single().attempts.single().chatTurns)
        f.store.update(plan.id) { it.copy(updatedAt = it.updatedAt + 1) }; runCurrent()
        assertEquals(history, f.projects.messages(project.id, worker.id))
    }

    @Test fun legacyCombinedReplyIsSplitAroundTheUserAnswerWithoutLosingActivity() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val workerId = "plan-p-stage-stage"
        val steps = listOf(
            CodingStep(CodingStepKind.TOOL, "Чтение", result = "Прочитано"),
            CodingStep(CodingStepKind.ANSWER, """{"kind":"QUESTION","text":"Какой формат?"}"""),
            CodingStep(CodingStepKind.THINKING, "Проверяю PDF"),
            CodingStep(CodingStepKind.ANSWER, "```json\n{\"kind\":\"RESULT\",\"text\":\"PDF готов\"}\n```"),
        )
        val attempt = StageAttempt("a", workerId, StageAssignment(profile.id, "m"), steps = steps,
            report = "PDF готов", turnIndex = 2, startedAt = 10, awaitingPlanner = true)
        val plan = base.copy(confirmedRevision = 1, milestones = listOf(base.milestones.single().copy(attempts = listOf(attempt))))
        f.service.prepareSessions(plan)
        f.projects.saveMessages(project.id, workerId, listOf(
            CodingMessage("a-response", CodingRole.AGENT, "PDF готов", steps = readableStageActivity(steps), createdAt = 11),
            CodingMessage("answer", CodingRole.USER, "PDF", createdAt = 30),
        ))
        f.projects.saveMessages(project.id, parent.id, listOf(
            CodingMessage("a-turn-0", CodingRole.AGENT, "Какой формат?", createdAt = 20),
            CodingMessage("a-turn-1", CodingRole.AGENT, "PDF готов", createdAt = 40),
        ))
        f.store.save(plan); runCurrent()
        val history = f.projects.messages(project.id, workerId)
        assertEquals(listOf("a-prompt", "a-response", "answer", "a-response-1"), history.map { it.id })
        assertEquals(listOf("Какой формат?", "PDF готов"), history.filter { it.role == CodingRole.AGENT }.map { it.text })
        assertEquals(readableStageActivity(steps), history.flatMap { it.steps })
        f.store.update(plan.id) { it }; runCurrent()
        assertEquals(history, f.projects.messages(project.id, workerId))
    }

    @Test fun concurrentCoordinatorTurnsKeepRemainingActivityVisible() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val other = base.milestones.single().copy(id = "other", title = "Другой этап")
        f.store.save(base.copy(milestones = base.milestones + other,
            tree = base.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + other.id) else it } +
                DecisionNode(other.id, other.title, DecisionKind.STAGE, stageId = other.id)))
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        f.gateway.coordinatorGates += listOf(first, second)
        f.runtime.gate.complete(Unit)
        f.service.confirm(base.id); runCurrent()
        assertEquals(2, f.gateway.coordinatorCallbacks.size)
        assertEquals(2, f.service.drafts.value[parent.id]!!.steps.count { it.kind == CodingStepKind.THINKING })
        first.complete(Unit); runCurrent()
        val remaining = assertNotNull(f.service.drafts.value[parent.id])
        assertTrue(remaining.active)
        assertEquals("Разбираю результат 2", remaining.steps.single { it.kind == CodingStepKind.THINKING }.title)
        second.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertNull(f.service.drafts.value[parent.id])
        assertEquals(PlanStatus.DONE, f.store.planFor(base.id)!!.status)
    }

    @Test fun coordinatorReplyStreamsBeforeCompletionWithoutPrematureActionsOrDuplicateFinalText() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.runtime.gate.complete(Unit)
        val gate = CompletableDeferred<Unit>()
        f.gateway.coordinatorGates += gate
        f.service.confirm(plan.id); runCurrent()
        val callback = f.gateway.coordinatorCallbacks.single()
        callback(CodingStep(CodingStepKind.ANSWER, """{"reply":"Проверки""", callId = "answer", running = true))
        runCurrent()
        assertEquals("Проверки", f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.ANSWER }.title)
        callback(CodingStep(CodingStepKind.INFO, "Получен фрагмент", callId = "progress", running = true))
        val full = """{"reply":"Проверки приняты","actions":[]}"""
        callback(CodingStep(CodingStepKind.ANSWER, full, callId = "answer"))
        runCurrent()
        assertEquals("Проверки приняты", f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.ANSWER }.title)
        assertTrue(f.store.planFor(plan.id)!!.deliveries.isEmpty())
        assertNull(f.store.planFor(plan.id)!!.coordination.single().decision)
        assertTrue(f.projects.messages(project.id, parent.id).none { it.text == "Оркестратор: Проверки приняты" })
        f.gateway.coordinator = full
        gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertNull(f.service.drafts.value[parent.id])
        val final = f.projects.messages(project.id, parent.id).single { it.text == "Оркестратор: Проверки приняты" }
        assertEquals(listOf("Оркестратор: Проверки приняты"), final.steps.filter { it.kind == CodingStepKind.ANSWER }.map { it.title })
        assertTrue(f.store.planFor(plan.id)!!.coordination.single().activity.none { it.kind == CodingStepKind.ANSWER })
    }

    @Test fun planningQuestionsStreamReadableTextBeforeWizardAppears() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val gate = CompletableDeferred<Unit>()
        f.gateway.gate = gate
        f.service.send(parent, "Создай план"); runCurrent()
        val callback = assertNotNull(f.gateway.requestCallback)
        callback(CodingStep(CodingStepKind.ANSWER, """{"reply":"Уточним""", callId = "response", running = true))
        runCurrent()
        assertEquals("Уточним", f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.ANSWER }.title)
        callback(CodingStep(CodingStepKind.ANSWER, """{"reply":"Уточним результат","questions":['""", callId = "response", running = true))
        runCurrent()
        assertEquals("Уточним результат", f.service.drafts.value[parent.id]!!.steps.single { it.kind == CodingStepKind.ANSWER }.title)
        assertNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
        gate.complete(Unit); runCurrent()
        val question = assertNotNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
        assertEquals(listOf("Уточним результат"), question.steps.filter { it.kind == CodingStepKind.ANSWER }.map { it.title })
        assertNull(f.service.drafts.value[parent.id])
    }

    @Test fun routingThinkingUpdatesKeepTheStreamedAnswerAndItsIdentity() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.readyPlan("p", parent).also { plan -> f.store.save(plan.copy(dialogue = listOf(PlanningMessage("old", "assistant", "Готово")))) }
        val gate = CompletableDeferred<Unit>()
        f.gateway.gate = gate
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Ответ пользователю"}"""
        f.service.send(parent, "Объясни результат"); runCurrent()
        val callback = assertNotNull(f.gateway.requestCallback)
        callback(CodingStep(CodingStepKind.ANSWER, """{"reply":"Ответ пользователю""", callId = "response", running = true))
        runCurrent()
        val answer = f.service.drafts.value.getValue(parent.id).steps.single { it.kind == CodingStepKind.ANSWER }
        assertEquals("Ответ пользователю", answer.title)
        assertTrue(answer.id.isNotBlank())
        repeat(4) {
            callback(CodingStep(CodingStepKind.THINKING, "Мысль $it", callId = "thought"))
            runCurrent()
            assertEquals(answer, f.service.drafts.value.getValue(parent.id).steps.single { it.kind == CodingStepKind.ANSWER })
        }
        val identity = assertNotNull(f.service.drafts.value.getValue(parent.id).timelineId)
        gate.complete(Unit); runCurrent()
        val saved = f.projects.messages(project.id, parent.id).single { it.id == identity }
        assertEquals(identity, saved.timelineId)
        assertEquals(answer.id, saved.steps.last { it.kind == CodingStepKind.ANSWER }.id)
    }

    @Test fun stoppingCoordinatorClearsLiveActivityAndPreservesItsMessages() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.runtime.gate.complete(Unit)
        f.gateway.coordinatorGates += CompletableDeferred<Unit>()
        f.service.confirm(plan.id); runCurrent()
        assertTrue(f.service.drafts.value[parent.id]!!.active)
        f.service.cancelRequest(parent.id); runCurrent()
        assertNull(f.service.drafts.value[parent.id])
        assertEquals(ExecutionIntent.STOP, f.store.planFor(plan.id)!!.intent)
        assertTrue(f.projects.messages(project.id, parent.id).any { it.text == "Работа оркестратора остановлена." && it.steps.any { step -> step.kind == CodingStepKind.THINKING } })
    }

    @Test fun coordinatorTimeoutClearsActivityAndKeepsTheErrorInHistory() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.runtime.gate.complete(Unit)
        f.gateway.timeout = true
        f.service.confirm(plan.id); runCurrent()
        assertTrue(f.service.drafts.value[parent.id]!!.active)
        advanceTimeBy(11); runCurrent()
        assertNull(f.service.drafts.value[parent.id])
        val failure = f.projects.messages(project.id, parent.id).last { it.failed }
        assertTrue(failure.text.startsWith("Ошибка оркестратора:"))
        assertTrue(failure.steps.any { it.kind == CodingStepKind.THINKING })
        assertEquals(CodingStepKind.ERROR, failure.steps.last().kind)
    }

    @Test fun invalidCoordinatorRouteIsRepairedBeforeAnyActionIsDelivered() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        val peer = f.readyPlan("peer", f.session("peer-parent"))
        f.gateway.coordinatorReplies += listOf(
            """{"reply":"Передаю вопрос","actions":[{"stageId":"stage","message":"Не доставлять частично"},{"stageId":"peer","message":"Согласуйте файлы"}]}""",
            """{"reply":"Соседний план не запущен","actions":[{"stageId":"stage","message":"Продолжить проверку фактических файлов"}]}""",
        )
        val attempt = StageAttempt("a", "worker", StageAssignment("model", "m"),
            report = """{"kind":"QUESTION","text":"Нужно согласование","targetStageId":"peer"}""")
        assertEquals(StageTurnAction.CONTINUE, f.service.finished(plan, plan.milestones.single(), attempt).action)
        val saved = f.store.planFor(plan.id)!!
        assertEquals("Продолжить проверку фактических файлов", saved.deliveries.single().text)
        assertTrue(f.store.planFor(peer.id)!!.deliveries.isEmpty())
        assertEquals(2, f.gateway.coordinatorCallbacks.size)
        assertTrue(f.gateway.lastMessages.last().content.contains("Недопустимые адресаты actions: peer"))
        assertTrue(f.gateway.lastMessages.any { it.content.contains("intent=STOP; phase=IDLE; status=DRAFT") })
        assertTrue(f.gateway.lastMessages.any { it.content.contains("нет сохранённых отчётов") })
        val history = f.projects.messages(project.id, "parent")
        assertTrue(history.any { it.id == "a-turn-0-repair-0" })
        assertFalse(history.any { it.failed })
        assertTrue(saved.coordination.single().activity.any { it.callId == "coordinator-repair-0" })
        f.service.finished(saved, saved.milestones.single(), attempt)
        assertEquals(2, f.gateway.coordinatorCallbacks.size)
        assertEquals(1, f.store.planFor(plan.id)!!.deliveries.size)
    }

    @Test fun malformedAndBlankCoordinatorMessagesAreRepairedWithoutRepeatingWorker() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        f.gateway.coordinatorReplies += listOf("invalid JSON",
            """{"reply":"Продолжить","actions":[{"stageId":"stage","message":" "}]}""",
            """{"reply":"Результат принят"}""")
        f.runtime.gate.complete(Unit)
        f.service.confirm(plan.id); advanceTimeBy(1000); runCurrent()
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
        assertEquals(3, f.gateway.coordinatorCallbacks.size)
        assertEquals(1, f.runtime.calls.count { it.first.id == "plan-p-stage-stage" })
        assertTrue(f.store.planFor(plan.id)!!.deliveries.isEmpty())
    }

    @Test fun repeatedlyInvalidCoordinatorResponseOpensAnswerableQuestionAndCanResume() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.gateway.coordinator = """{"reply":"Согласовать","actions":[{"stageId":"missing","message":"Вопрос"}]}"""
        f.runtime.gate.complete(Unit)
        f.service.confirm(plan.id); advanceTimeBy(1000); runCurrent()
        val saved = f.store.planFor(plan.id)!!
        assertEquals(3, f.gateway.coordinatorCallbacks.size)
        assertEquals(ExecutionPhase.WAITING, saved.phase)
        assertNull(saved.issue)
        assertNotNull(saved.milestones.single().attempts.last().waitingForUser)
        assertTrue(saved.deliveries.isEmpty())
        assertNull(f.service.drafts.value[parent.id])
        val history = f.projects.messages(project.id, parent.id)
        val question = assertNotNull(history.pendingPlanningQuestion())
        assertEquals("stage", question.planning!!.sourceStageId)
        assertTrue(question.text.contains("за три попытки"))
        assertEquals(CodingSessionStatus.WAITING, f.status(CodingSessionUi(parent, history, plan = saved)))
        f.gateway.coordinator = """{"reply":"Результат принят"}"""
        f.service.send(parent, "Продолжить с сохранённого результата", replyTo = question.id)
        advanceTimeBy(1000); runCurrent()
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
        assertNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
    }

    @Test fun retryOfSavedCoordinatorRoutingFailureDoesNotRepeatCompletedWorkerTurn() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val base = f.readyPlan("p", f.session("parent"))
        val error = PlanningIssue(IssueKind.UNCERTAIN, "Некорректное сообщение координатора", requiresUser = true)
        val attempt = StageAttempt("a", "plan-p-stage-stage", StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING,
            path = "/shared", report = "Saved result", turnIndex = 3, awaitingPlanner = true, error = error)
        f.store.save(base.copy(confirmedRevision = 1, intent = ExecutionIntent.RUN, phase = ExecutionPhase.WAITING,
            status = PlanStatus.FAILED, issue = error,
            milestones = listOf(base.milestones.single().copy(status = MilestoneStatus.FAILED, attempts = listOf(attempt))),
            coordination = listOf(CoordinationRecord("a-turn-3", "stage", StageReply(StageReplyKind.RESULT, "Saved result")))))
        f.runtime.gate.complete(Unit)
        f.execution.retry(base.id); advanceTimeBy(1000); runCurrent()
        assertEquals(PlanStatus.DONE, f.store.planFor(base.id)!!.status)
        assertTrue(f.runtime.calls.none { it.first.id == attempt.sessionId })
        assertEquals(1, f.gateway.coordinatorCallbacks.size)
        assertEquals(1, f.store.planFor(base.id)!!.coordination.size)
        assertEquals(4, f.store.planFor(base.id)!!.milestones.single().attempts.last().turnIndex)
    }

    @Test fun questionsUseHistoryAndAnswersAreStoredOnce() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        f.projects.saveMessages(project.id, session.id, listOf(CodingMessage("history", CodingRole.USER, "Existing context", createdAt = 1)))
        f.service.send(session, "Make an editor"); runCurrent()
        val plan = f.store.plans.value.single()
        assertTrue(plan.sharedWorkspace)
        assertTrue(plan.dialogue.any { it.text == "Existing context" })
        val question = plan.dialogue.last()
        assertEquals(listOf(QuestionKind.SINGLE, QuestionKind.MULTIPLE, QuestionKind.TEXT), question.questions.map { it.kind })
        val answers = listOf(PlanningAnswer("single", listOf("pdf")), PlanningAnswer("multi", listOf("read", "write")), PlanningAnswer("text", text = "Tests"))
        f.service.send(session, "PDF, read and write, tests", answers, question.id); runCurrent()
        f.service.send(session, "PDF, read and write, tests", answers, question.id); runCurrent()
        assertEquals(1, f.projects.messages(project.id, session.id).count { it.planning?.replyTo == question.id })
    }
    @Test fun timeoutAfterAnswersPublishesErrorAndAllowsRetry() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        f.service.send(session, "Make an editor"); runCurrent()
        val original = f.store.plans.value.single()
        f.gateway.timeout = true
        f.service.send(session, "PDF, read and write, tests"); runCurrent()
        advanceTimeBy(11); runCurrent()

        val failed = f.projects.messages(project.id, session.id).last()
        assertEquals(CodingRole.AGENT, failed.role)
        assertTrue(failed.failed)
        assertTrue(failed.text.contains("время"))
        assertEquals(failed.text, failed.steps.last().title)
        val saved = f.store.planFor(original.id)!!
        assertEquals("", saved.pendingRequest)
        assertEquals("", saved.requestId)
        assertEquals(original.tree, saved.tree)
        assertTrue(f.service.drafts.value.isEmpty())

        f.gateway.timeout = false
        f.gateway.overrideReply = """{"reply":"План готов","tree":[{"id":"root","title":"Goal","kind":"GOAL","children":["stage"]},{"id":"stage","title":"Stage","kind":"STAGE","stageId":"stage"}],"milestones":[{"id":"stage","title":"Stage","acceptance":"Checks pass"}]}"""
        f.service.send(session, "Повтори планирование"); runCurrent()
        val history = f.projects.messages(project.id, session.id)
        assertTrue(history.any { it.text == "План готов" && it.role == CodingRole.AGENT })
        assertEquals(1, history.count { it.planning?.graph == true })
        assertEquals("", f.store.planFor(original.id)!!.pendingRequest)
    }

    @Test fun inputSizeFailureClearsLiveProgressAndCanRetryTheSavedMessage() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        f.gateway.gate = CompletableDeferred()
        f.gateway.failure = "Input exceeds the maximum length of 1048576 characters."
        f.service.send(session, "Change models"); runCurrent()
        f.gateway.requestCallback!!(CodingStep(CodingStepKind.THINKING, "Checking plan", running = true))
        runCurrent()
        assertTrue(f.service.drafts.value[session.id]?.active == true)
        f.gateway.gate!!.complete(Unit); runCurrent()
        val input = f.service.states.value.getValue(session.id).inputs.single()
        assertEquals(OrchestrationInputStatus.FAILED, input.status)
        assertContains(input.error, "Контекст запроса")
        assertTrue(f.service.drafts.value.isEmpty())
        val history = f.projects.messages(project.id, session.id)
        val failed = history.single { it.failed }
        assertTrue(failed.steps.any { it.kind == CodingStepKind.THINKING })
        assertTrue(failed.steps.none { it.running })
        assertEquals("", f.store.plans.value.single().pendingRequest)
        val notice = CodingMessage("worker-notice", CodingRole.AGENT, "Worker continues", createdAt = 2)
        assertEquals(CodingSessionStatus.WAITING, f.status(CodingSessionUi(session, history + notice,
            plan = f.store.plans.value.single())))

        f.gateway.failure = null
        f.service.retryInput(session.id, input.id); runCurrent()
        assertEquals(OrchestrationInputStatus.DONE, f.service.states.value.getValue(session.id).inputs.single().status)
        assertTrue(f.service.drafts.value.isEmpty())
        val retried = f.projects.messages(project.id, session.id)
        assertEquals(1, retried.count { it.role == CodingRole.USER })
        assertNotEquals(CodingSessionStatus.BLOCKED, CodingSessionUi(session, retried,
            plan = f.store.plans.value.single()).status)
    }

    @Test fun invalidModelResponsePublishesError() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        f.gateway.overrideReply = "invalid response"
        f.service.send(session, "Goal"); runCurrent()
        assertTrue(f.projects.messages(project.id, session.id).last().failed)
        assertEquals("", f.store.plans.value.single().pendingRequest)
        assertTrue(f.service.drafts.value.isEmpty())
    }

    @Test fun confirmationIsIdempotentAndTwoPlansShareCurrentFolder() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val one = f.readyPlan("one", f.session("s1")); val two = f.readyPlan("two", f.session("s2"))
        f.service.confirm(one.id); f.service.confirm(one.id); f.service.confirm(two.id); runCurrent()
        assertEquals(2, f.runtime.calls.size)
        assertEquals(listOf("/shared", "/shared"), f.runtime.paths)
        assertEquals(2, f.projects.sessions(project.id).count { it.stageId != null })
        assertEquals(2, f.store.plans.value.size)
        assertNotNull(f.store.planFor(one.id)?.confirmedRevision)
        assertFailsWith<IllegalArgumentException> { f.store.planFor(project.id) }
    }
    @Test fun userMessageIsQueuedAndConsumedAfterCurrentTurn() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        f.service.confirm(plan.id); runCurrent()
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.service.send(worker, "Also check export"); runCurrent()
        assertEquals(1, f.runtime.calls.size)
        assertTrue(f.projects.messages(project.id, worker.id).any { it.pendingDelivery })
        f.runtime.gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertTrue(f.runtime.calls.drop(1).any { "Also check export" in it.second })
        assertTrue(f.store.planFor(plan.id)!!.deliveries.all { it.state == DeliveryState.ANSWERED })
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
        assertEquals(1, f.projects.messages(project.id, "parent").count { it.planning?.graph == true })
    }
    @Test fun queuedMessageDoesNotResumePausedPlan() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        f.service.confirm(plan.id); runCurrent()
        f.execution.pause(plan.id)
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.service.send(worker, "После возобновления проверь экспорт"); runCurrent()
        assertEquals(ExecutionIntent.PAUSE, f.store.planFor(plan.id)!!.intent)
        assertEquals(DeliveryState.QUEUED, f.store.planFor(plan.id)!!.deliveries.single().state)
    }

    @Test fun coordinationRoutesToOtherStageAndRejectsInvalidResult() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val base = f.readyPlan("p", f.session("parent"))
        val plan = base.copy(milestones = base.milestones + Milestone("other", "Other", description = "Read info", acceptance = "Checked"),
            tree = base.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + "other") else it } + DecisionNode("other", "Other", DecisionKind.STAGE, stageId = "other"))
        f.store.save(plan)
        f.gateway.coordinator = """{"reply":"Передаю вопрос","actions":[{"stageId":"other","message":"Уточни формат"},{"stageId":"stage","message":"Сначала проверь документацию"}]}"""
        val attempt = StageAttempt("a", "worker", StageAssignment("model", "m"), report = """{"kind":"QUESTION","text":"Какой формат?","targetStageId":"other"}""")
        val response = f.service.finished(plan, plan.milestones.first(), attempt)
        assertEquals(StageTurnAction.CONTINUE, response.action)
        assertTrue(f.store.planFor(plan.id)!!.deliveries.any { it.targetStageId == "other" })
        val count = f.store.planFor(plan.id)!!.deliveries.size
        f.service.finished(f.store.planFor(plan.id)!!, plan.milestones.first(), attempt)
        assertEquals(count, f.store.planFor(plan.id)!!.deliveries.size)
        f.gateway.coordinator = """{"reply":"Нужен правильный формат","actions":[]}"""
        var latest = f.store.planFor(plan.id)!!
        for (turn in 1..3) {
            val decision = f.service.finished(latest, latest.milestones.first(), attempt.copy(turnIndex = turn, report = "not a result"))
            assertNotEquals(StageTurnAction.VERIFY, decision.action)
            latest = f.store.planFor(plan.id)!!
        }
    }
    @Test fun plannerCancellationPreservesSavedPlanAndClearsPendingRequest() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        f.gateway.gate = CompletableDeferred()
        f.service.send(session, "Goal"); runCurrent()
        assertTrue(f.store.plans.value.single().pendingRequest.isNotBlank())
        f.service.cancelRequest(session.id); runCurrent()
        assertEquals("", f.store.plans.value.single().pendingRequest)
        assertTrue(f.service.drafts.value.isEmpty())
        assertTrue(f.store.plans.value.single().milestones.isEmpty())
    }

    @Test fun coordinatorQuestionsUseWizardAndAnswerOnlyTheirSourceStageOnce() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val waiting = PlanningIssue(IssueKind.CONFIGURATION, "Нужен ответ", requiresUser = true)
        val attempt = StageAttempt("a", "plan-p-stage-stage", StageAssignment("model", "m"),
            phase = AttemptPhase.EXECUTING, error = waiting,
            report = """{"kind":"QUESTION","text":"Какой формат?"}""")
        val stage = base.milestones.single().copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))
        val other = Milestone("other", "Other", status = MilestoneStatus.ACTIVE, acceptance = "Checked",
            attempts = listOf(attempt.copy(id = "b", sessionId = "other-worker")))
        val plan = base.copy(confirmedRevision = 1, milestones = listOf(stage, other),
            tree = base.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + "other") else it } + DecisionNode("other", "Other", DecisionKind.STAGE, stageId = "other"))
        f.store.save(plan)
        f.gateway.coordinator = """{"reply":"Выберите формат","askUser":true,"questions":[{"id":"format","title":"Формат экспорта","kind":"SINGLE","options":[{"id":"pdf","label":"PDF"},{"id":"docx","label":"DOCX"}]}]}"""
        assertEquals(StageTurnAction.WAIT, f.service.finished(plan, stage, attempt).action)
        val history = f.projects.messages(project.id, parent.id)
        val question = assertNotNull(history.pendingPlanningQuestion())
        assertEquals(stage.id, question.planning!!.sourceStageId)
        assertEquals(QuestionKind.SINGLE, question.planning.questions.single().kind)
        f.service.finished(f.store.planFor(plan.id)!!, stage, attempt)
        assertEquals(1, f.projects.messages(project.id, parent.id).count { it.id == question.id })

        val answers = listOf(PlanningAnswer("format", listOf("pdf")))
        f.service.send(parent, "PDF", answers, question.id); runCurrent()
        f.service.send(parent, "PDF", answers, question.id); runCurrent()
        val saved = f.store.planFor(plan.id)!!
        assertEquals(listOf(stage.id), saved.deliveries.map { it.targetStageId })
        assertTrue(saved.milestones.first { it.id == other.id }.attempts.last().error!!.requiresUser)
        assertNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
        assertEquals(1, f.projects.messages(project.id, parent.id).count { it.planning?.replyTo == question.id })
    }

    @Test fun plainCoordinatorQuestionFallsBackToTextWizard() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        f.gateway.coordinator = """{"reply":"Уточните требования","askUser":true}"""
        val attempt = StageAttempt("a", "worker", StageAssignment("model", "m"), report = """{"kind":"QUESTION","text":"Нужны требования"}""")
        assertEquals(StageTurnAction.WAIT, f.service.finished(plan, plan.milestones.single(), attempt).action)
        val question = assertNotNull(f.projects.messages(project.id, "parent").pendingPlanningQuestion())
        assertEquals(QuestionKind.TEXT, question.planning!!.questions.single().kind)
        assertTrue(question.planning.questions.single().title.contains("Уточните требования"))
    }

    @Test fun orchestrationMessagesTrackAssignmentDeliveryAndCompletionWithoutDuplicates() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val plan = f.readyPlan("p", f.session("parent"))
        f.service.confirm(plan.id); runCurrent()
        val running = f.store.planFor(plan.id)!!
        val stage = running.milestones.single()
        val attempt = stage.attempts.last()
        f.service.instructions(running, stage, attempt)
        f.service.instructions(running, stage, attempt)
        assertEquals(1, f.projects.messages(project.id, "parent").count { it.id == "${attempt.id}-turn-0-started" })
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.service.send(worker, "Проверь экспорт"); runCurrent()
        assertTrue(f.projects.messages(project.id, "parent").any { it.route?.deliveryId != null && it.route.target.name == "Stage" && it.text.contains("Проверь экспорт") })
        f.runtime.gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        val messages = f.projects.messages(project.id, "parent")
        assertTrue(messages.any { it.text.contains("Передано сообщений: 1") })
        assertEquals(1, messages.count { it.id == "p-stage-completed" })
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
    }

    @Test fun answerArrivingBeforeWorkerSavesWaitingStateResumesTheStage() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val plan = f.readyPlan("p", parent)
        f.gateway.coordinator = """{"reply":"Уточните формат","askUser":true}"""
        var answered = false
        f.execution.chatHooks = object : PlanningExecutionHooks by f.service {
            override suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision {
                val decision = f.service.finished(plan, stage, attempt)
                if (!answered) {
                    answered = true
                    val question = f.projects.messages(project.id, parent.id).pendingPlanningQuestion()!!
                    f.service.send(parent, "Экспортировать в PDF", listOf(PlanningAnswer(question.planning!!.questions.single().id, text = "PDF")), question.id)
                    repeat(8) { if (f.store.planFor(plan.id)!!.deliveries.none { it.replyTo == question.id }) yield() }
                    assertTrue(f.store.planFor(plan.id)!!.deliveries.any { it.replyTo == question.id })
                    f.gateway.coordinator = """{"reply":"Результат принят"}"""
                }
                return decision
            }
        }
        f.runtime.gate.complete(Unit)
        f.service.confirm(plan.id); advanceTimeBy(1000); runCurrent()
        assertTrue(f.runtime.calls.any { it.second.contains("Экспортировать в PDF") })
        assertEquals(PlanStatus.DONE, f.store.planFor(plan.id)!!.status)
        assertNull(f.projects.messages(project.id, parent.id).pendingPlanningQuestion())
    }
    @Test fun automaticReplanningKeepsCompletedStageAndAddsNewSession() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val session = f.session("parent")
        val base = f.readyPlan("p", session)
        val completed = base.milestones.single().copy(status = MilestoneStatus.DONE,
            attempts = listOf(StageAttempt("done", "plan-p-stage-stage", StageAssignment("model", "m"), phase = AttemptPhase.COMPLETE)))
        f.store.save(base.copy(confirmedRevision = 1, milestones = listOf(completed)))
        f.gateway.overrideReply = """{"reply":"Добавлен этап проверки","tree":[{"id":"root","title":"Goal","kind":"GOAL","children":["stage","extra"]},{"id":"stage","title":"Stage","kind":"STAGE","stageId":"stage"},{"id":"extra","title":"Extra","kind":"STAGE","stageId":"extra"}],"milestones":[{"id":"stage","title":"Stage","acceptance":"Checks pass"},{"id":"extra","title":"Extra","description":"Additional check","acceptance":"Checked","dependsOn":["stage"]}]}"""
        f.service.send(session, "Добавь проверку"); runCurrent()
        val updated = f.store.planFor("p")!!
        assertEquals(completed, updated.milestones.first { it.id == "stage" }.copy(displayNumber = completed.displayNumber))
        val extra = updated.milestones.single { it.title == "Extra" }
        assertNotEquals("extra", extra.id)
        assertTrue(f.projects.sessions(project.id).any { it.stageId == extra.id })
        assertTrue(updated.versions.isNotEmpty())
    }

    @Test fun recordedTurnResumesCoordinatorWithoutRepeatingWorkerOperations() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val attempt = StageAttempt("a", "plan-p-stage-stage", StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING,
            path = "/shared", report = "Saved result")
        f.store.save(base.copy(confirmedRevision = 1, milestones = listOf(base.milestones.single().copy(status = MilestoneStatus.ACTIVE, attempts = listOf(attempt))),
            coordination = listOf(CoordinationRecord("a-turn-0", "stage", StageReply(StageReplyKind.RESULT, "Saved result"), CoordinatorReply("Accepted")))))
        f.runtime.gate.complete(Unit)
        f.service.confirm("p"); advanceTimeBy(1000); runCurrent()
        assertTrue(f.runtime.calls.none { it.first.id == attempt.sessionId })
        assertEquals(PlanStatus.DONE, f.store.planFor("p")!!.status)
        assertEquals(1, f.store.planFor("p")!!.coordination.size)
    }

    @Test fun legacyMigrationDoesNotStartRuntimeOrDuplicateMessages() = runTest {
        val f = Fixture(this)
        f.projects.save(project); f.profiles.save(profile)
        f.store.save(Plan("old", project.id, "Old goal", dialogue = listOf(PlanningMessage("q", "assistant", "Question?"))))
        f.service.bootstrap(); runCurrent()
        assertEquals("planning-old", f.store.planFor("old")!!.parentSessionId)
        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(1, f.projects.messages(project.id, "planning-old").count { it.id == "q" })
        assertFalse(f.store.planFor("old")!!.sharedWorkspace)
    }
    @Test fun sessionNamesAndNumbersSurviveReorderingArchivalAndRenaming() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val extra = base.milestones.single().copy(id = "extra", title = "Stage")
        f.store.save(base.copy(confirmedRevision = 1, milestones = base.milestones + extra,
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("stage", "extra"))) +
                listOf("stage", "extra").map { DecisionNode(it, "Stage", DecisionKind.STAGE, stageId = it) }))
        f.service.prepareSessions(f.store.planFor("p")!!)
        val sessions = f.projects.sessions(project.id).filter { it.stageId != null }
        assertEquals(listOf("Stage", "Stage"), sessions.map { it.name })
        assertEquals(setOf(1, 2), sessions.map { it.stageNumber }.toSet())
        f.store.update("p") { it.copy(milestones = it.milestones.reversed()) }
        f.service.prepareSessions(f.store.planFor("p")!!)
        assertEquals(sessions.map { it.id to it.stageNumber }, f.projects.sessions(project.id).filter { it.stageId != null }.map { it.id to it.stageNumber })
        val first = sessions.first()
        f.service.renameSession(first.id, "Вход по email"); runCurrent()
        assertEquals("Вход по email", f.projects.sessions(project.id).first { it.id == first.id }.name)
        assertContains(f.store.planFor("p")!!.milestones.first { it.id == first.stageId }.stageLabel(), "Вход по email")
        f.store.update("p") { it.copy(milestones = it.milestones.map { stage -> stage.copy(status = MilestoneStatus.DONE) }) }
        f.service.archiveSession(first.id); runCurrent()
        assertTrue(f.projects.sessions(project.id).first { it.id == first.id }.archived)
        val history = f.projects.messages(project.id, first.id)
        f.service.prepareSessions(f.store.planFor("p")!!)
        assertTrue(f.projects.sessions(project.id).first { it.id == first.id }.archived)
        assertEquals(history, f.projects.messages(project.id, first.id))
        f.service.restoreSession(first.id); runCurrent()
        assertFalse(f.projects.sessions(project.id).first { it.id == first.id }.archived)
        assertEquals(first.stageNumber, f.projects.sessions(project.id).first { it.id == first.id }.stageNumber)
    }

    @Test fun archiveDoesNotRemoveAnUnfinishedSession() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val base = f.readyPlan("p", f.session("parent"))
        f.store.save(base.copy(confirmedRevision = 1)); f.service.prepareSessions(f.store.planFor("p")!!)
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.service.archiveSession(worker.id); runCurrent()
        assertFalse(f.projects.sessions(project.id).first { it.id == worker.id }.archived)
        assertContains(f.service.error.value.orEmpty(), "незавершённого")
        assertTrue(f.projects.orchestration("parent")!!.sessionCommands.none { it.kind == SessionCommandKind.ARCHIVE })
    }

    @Test fun discussionDuringAQuestionKeepsTheOriginalRequestOpen() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.service.send(parent, "Сделай редактор"); runCurrent()
        val question = f.projects.messages(project.id, parent.id).pendingPlanningQuestion()!!
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"PDF сохраняет расположение элементов, DOC подходит для редактирования."}"""
        f.service.send(parent, "Чем отличаются PDF и DOC?"); runCurrent()
        assertEquals(question.id, f.projects.messages(project.id, parent.id).pendingPlanningQuestion()?.id)
        assertTrue(f.projects.messages(project.id, parent.id).none { it.planning?.replyTo == question.id })
        assertContains(f.projects.messages(project.id, parent.id).last().text, "PDF сохраняет")
        assertEquals(1, f.store.plans.value.size)
    }

    @Test fun partialStructuredAnswersStayOpenUntilEveryQuestionHasAnAnswer() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.service.send(parent, "Сделай редактор"); runCurrent()
        val question = f.projects.messages(project.id, parent.id).pendingPlanningQuestion()!!
        f.service.send(parent, "PDF", listOf(PlanningAnswer("single", listOf("pdf"))), question.id); runCurrent()
        assertEquals(question.id, f.projects.messages(project.id, parent.id).pendingPlanningQuestion()?.id)
        val restored = f.projects.orchestration(parent.id)!!
        assertEquals(listOf("pdf"), restored.questions.first { it.id == question.id }.partialAnswers.single().selected)
        f.service.send(parent, "Чтение и тесты", listOf(PlanningAnswer("multi", listOf("read")), PlanningAnswer("text", text = "Tests")), question.id); runCurrent()
        val answered = f.projects.orchestration(parent.id)!!.questions.first { it.id == question.id }
        assertEquals(UserRequestStatus.ANSWERED, answered.status)
        assertEquals(3, answered.partialAnswers.size)
        assertContains(f.gateway.lastMessages.last().content, "Формат?: PDF")
        assertContains(f.gateway.lastMessages.last().content, "Возможности?: Чтение")
    }

    @Test fun messagesArrivingDuringAModelTurnAreDurableAndProcessedInOrder() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val gate = CompletableDeferred<Unit>(); f.gateway.gate = gate
        f.service.send(parent, "Сделай редактор"); runCurrent()
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Объяснение форматов"}"""
        f.service.send(parent, "Объясни форматы"); runCurrent()
        val queued = f.projects.orchestration(parent.id)!!.inputs
        assertEquals(listOf(OrchestrationInputStatus.PROCESSING, OrchestrationInputStatus.QUEUED), queued.map { it.status })
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(OrchestrationInputStatus.DONE, OrchestrationInputStatus.DONE), f.projects.orchestration(parent.id)!!.inputs.map { it.status })
        assertEquals(listOf("Сделай редактор", "Объясни форматы"), f.projects.messages(project.id, parent.id).filter { it.role == CodingRole.USER }.map { it.text })
    }

    @Test fun withdrawingAMiddleMessagePreservesTheActiveTurnAndRemainingQueue() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val gate = CompletableDeferred<Unit>(); f.gateway.gate = gate
        f.service.send(parent, "Сделай редактор"); runCurrent()
        f.service.send(parent, "Отменённое поручение про экспорт"); runCurrent()
        f.service.send(parent, "Объясни форматы"); runCurrent()
        val inputs = f.projects.orchestration(parent.id)!!.inputs

        f.service.cancelQueuedInput(parent.id, inputs.first().id) // Already processing.
        f.service.cancelQueuedInput(parent.id, inputs[1].id)
        f.service.cancelQueuedInput(parent.id, inputs[1].id) // Double click is harmless.
        f.service.cancelQueuedInput(parent.id, "missing")
        runCurrent()
        assertEquals(listOf(OrchestrationInputStatus.PROCESSING, OrchestrationInputStatus.WITHDRAWN,
            OrchestrationInputStatus.QUEUED), f.projects.orchestration(parent.id)!!.inputs.map { it.status })
        assertTrue(f.service.drafts.value[parent.id]?.active == true)
        val cancelled = f.projects.messages(project.id, parent.id).single { it.id == inputs[1].id }
        assertEquals(OrchestrationInputStatus.WITHDRAWN, cancelled.inputStatus)
        assertEquals(inputs[1].text, cancelled.text)

        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Объяснение форматов"}"""
        gate.complete(Unit); runCurrent()
        f.service.cancelQueuedInput(parent.id, inputs.last().id); runCurrent() // Already done.
        assertEquals(listOf(OrchestrationInputStatus.DONE, OrchestrationInputStatus.WITHDRAWN,
            OrchestrationInputStatus.DONE), f.projects.orchestration(parent.id)!!.inputs.map { it.status })
        val history = f.projects.messages(project.id, parent.id)
        assertEquals(3, history.count { it.role == CodingRole.USER })
        assertTrue(history.none { it.id == "${inputs[1].id}-reply" })
        assertTrue(history.any { it.id == "${inputs.last().id}-reply" })
        assertTrue(f.gateway.requests.flatten().none { inputs[1].text in it.content })
        assertTrue(f.service.persistenceErrors.value.isEmpty())
        assertNull(f.service.error.value)
    }

    @Test fun withdrawnInputSurvivesRestartAndIsExcludedFromTheFirstPlanContext() = runTest {
        val f = Fixture(this)
        val parent = f.session("parent")
        val input = OrchestrationInput("withdrawn", "Поручение, которое нельзя отправить", 2,
            status = OrchestrationInputStatus.WITHDRAWN, resumeAfter = true)
        f.projects.saveOrchestration(OrchestrationState(parent.id, project.id, inputs = listOf(input)))
        // Simulate stopping after the inbox was saved but before its chat projection was updated.
        f.projects.saveMessages(project.id, parent.id, listOf(CodingMessage(input.id, CodingRole.USER,
            input.text, createdAt = input.createdAt, inputStatus = OrchestrationInputStatus.QUEUED)))
        f.initialize(); runCurrent()
        assertEquals(OrchestrationInputStatus.WITHDRAWN,
            f.projects.messages(project.id, parent.id).single().inputStatus)
        f.service.retryInput(parent.id, input.id)
        f.service.resume(parent)
        runCurrent()
        assertTrue(f.gateway.requests.isEmpty())
        assertTrue(f.store.plans.value.isEmpty())

        f.service.send(parent, "Создай новый редактор"); runCurrent()
        assertEquals(OrchestrationInputStatus.WITHDRAWN, f.projects.orchestration(parent.id)!!.inputs.first().status)
        assertTrue(f.store.plans.value.single().dialogue.none { input.text in it.text })
        assertTrue(f.gateway.requests.flatten().none { input.text in it.content })
    }

    @Test fun withdrawingAQueuedAnswerDoesNotAnswerTheQuestionOrConfirmAPlan() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        f.service.send(parent, "Сделай редактор"); runCurrent()
        val question = f.projects.orchestration(parent.id)!!.openQuestions().single()
        val gate = CompletableDeferred<Unit>(); f.gateway.gate = gate
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Объяснение форматов"}"""
        f.service.send(parent, "Какие есть варианты?"); runCurrent()
        f.service.send(parent, "PDF", listOf(PlanningAnswer("single", listOf("pdf"))), question.id,
            resumeAfter = true); runCurrent()
        val answer = f.projects.orchestration(parent.id)!!.inputs.last()
        f.service.cancelQueuedInput(parent.id, answer.id); runCurrent()
        gate.complete(Unit); runCurrent()

        val saved = f.projects.orchestration(parent.id)!!
        assertEquals(OrchestrationInputStatus.WITHDRAWN, saved.inputs.last().status)
        assertEquals(question, saved.openQuestions().single())
        assertTrue(saved.messageEvents.none { it.kind == MessageEventKind.QUESTION_ANSWERED })
        assertNull(f.store.plans.value.single().confirmedRevision)
        assertTrue(f.store.plans.value.single().deliveries.isEmpty())
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun failedWithdrawalSaveDoesNotPretendTheMessageWasCancelled() = runTest {
        val backing = InMemoryKeyValueStore()
        var fail = false
        val disk = object : KeyValueStore by backing {
            override fun write(key: String, value: String) {
                if (fail && key.startsWith("coding-orchestration-")) error("Disk unavailable")
                backing.write(key, value)
            }
        }
        val f = Fixture(this, disk); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val gate = CompletableDeferred<Unit>(); f.gateway.gate = gate
        f.service.send(parent, "Сделай редактор"); runCurrent()
        f.service.send(parent, "Отменить этот запрос"); runCurrent()
        val input = f.projects.orchestration(parent.id)!!.inputs.last()
        fail = true
        f.service.cancelQueuedInput(parent.id, input.id); runCurrent()
        assertTrue(f.service.persistenceErrors.value.containsKey(parent.id))
        assertEquals(OrchestrationInputStatus.QUEUED, f.projects.orchestration(parent.id)!!.inputs.last().status)
        assertEquals(OrchestrationInputStatus.QUEUED,
            f.projects.messages(project.id, parent.id).single { it.id == input.id }.inputStatus)
        fail = false
        f.service.cancelQueuedInput(parent.id, input.id); runCurrent()
        assertEquals(OrchestrationInputStatus.WITHDRAWN, f.projects.orchestration(parent.id)!!.inputs.last().status)
        gate.complete(Unit); runCurrent()
        assertTrue(f.gateway.requests.flatten().none { input.text in it.content })
    }

    @Test fun completedPlanCanBeDiscussedAndExtendedOnlyAfterConfirmingTheProposal() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        f.store.save(base.copy(confirmedRevision = 1, status = PlanStatus.DONE, phase = ExecutionPhase.COMPLETE, runId = "first-run",
            milestones = base.milestones.map { it.copy(status = MilestoneStatus.DONE, report = "Old result") }))
        f.service.prepareSessions(f.store.planFor("p")!!); runCurrent()
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Готов предыдущий результат."}"""
        f.service.send(parent, "Что сделано?"); runCurrent()
        assertEquals(ExecutionPhase.COMPLETE, f.store.planFor("p")!!.phase)
        assertNull(f.store.planFor("p")!!.proposal)
        assertEquals(1, f.store.plans.value.size)
        f.gateway.userDecision = """{"intent":"REFINE"}"""
        f.gateway.overrideReply = """{"reply":"Предлагаю обработку ошибок","tree":[{"id":"root","title":"Goal","kind":"GOAL","children":["stage","errors"]},{"id":"stage","title":"Stage","kind":"STAGE","stageId":"stage"},{"id":"errors","title":"Обработка ошибок","kind":"STAGE","stageId":"errors"}],"milestones":[{"id":"stage","title":"Stage","acceptance":"Checks pass"},{"id":"errors","title":"Обработка ошибок","acceptance":"Error checks pass","description":"Handle errors","dependsOn":["stage"],"continuationOf":"stage"}]}"""
        f.service.send(parent, "Добавь обработку ошибок"); runCurrent()
        val pending = f.store.planFor("p")!!
        val proposal = assertNotNull(pending.proposal)
        assertEquals(ExecutionPhase.COMPLETE, pending.phase)
        assertEquals(1, pending.milestones.size)
        assertTrue(f.runtime.calls.isEmpty())
        f.gateway.overrideReply = null
        f.runtime.gate.complete(Unit)
        f.service.confirm("p", proposal.id); advanceTimeBy(1000); runCurrent()
        val done = f.store.planFor("p")!!
        assertEquals(ExecutionPhase.COMPLETE, done.phase)
        assertEquals(2, done.milestones.size)
        assertEquals("Old result", done.milestones.first { it.id == "stage" }.report)
        assertEquals("first-run", done.runHistory.single().runId)
        assertNotEquals("first-run", done.runId)
        assertTrue(f.runtime.calls.none { it.first.stageId == "stage" })
        val calls = f.runtime.calls.size
        f.service.confirm("p", proposal.id); runCurrent()
        assertEquals(calls, f.runtime.calls.size)
        assertTrue(f.projects.sessions(project.id).first { it.stageId == "stage" }.archived)
        val continuationTask = done.milestones.single { it.title == "Обработка ошибок" }
        assertNotEquals("errors", continuationTask.id)
        val continuation = f.projects.sessions(project.id).first { it.stageId == continuationTask.id }
        assertEquals("Обработка ошибок", continuation.name)
        assertEquals(1, continuation.continuationOfNumber)
        assertEquals(2, continuation.stageNumber)
    }

    @Test fun preparingAPromptDoesNotClaimDeliveryBeforeTheRuntimeStarts() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val base = f.readyPlan("p", f.session("parent"))
        f.store.save(base.copy(confirmedRevision = 1, intent = ExecutionIntent.PAUSE))
        f.service.prepareSessions(f.store.planFor("p")!!)
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.service.send(worker, "Учти экспорт"); runCurrent()
        val stage = f.store.planFor("p")!!.milestones.single()
        val attempt = StageAttempt("a", worker.id, StageAssignment(profile.id, "m"))
        f.service.instructions(f.store.planFor("p")!!, stage, attempt)
        assertEquals(DeliveryState.QUEUED, f.store.planFor("p")!!.deliveries.single().state)
        f.service.started(f.store.planFor("p")!!, stage, attempt)
        assertEquals(DeliveryState.DELIVERED, f.store.planFor("p")!!.deliveries.single().state)
        val route = f.projects.messages(project.id, "parent").first { it.route?.deliveryId != null }.route!!
        assertEquals("Stage", route.target.name)
        assertContains(route.target.subtitle, "Этап 1")
        assertEquals(worker.id, route.target.sessionId)
    }

    @Test fun anAnswerDoesNotResumeAnExplicitlyPausedPlan() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        f.store.save(base.copy(confirmedRevision = 1, intent = ExecutionIntent.PAUSE))
        f.service.prepareSessions(f.store.planFor("p")!!)
        val plan = f.store.planFor("p")!!
        val worker = f.projects.sessions(project.id).single { it.stageId != null }
        f.gateway.coordinator = """{"reply":"Какой формат?","askUser":true}"""
        f.service.finished(plan, plan.milestones.single(), StageAttempt("a", worker.id, StageAssignment(profile.id, "m"),
            report = """{"kind":"QUESTION","text":"Формат?"}"""))
        val question = f.projects.orchestration(parent.id)!!.openQuestions().single()
        f.service.send(parent, "PDF", replyTo = question.id); runCurrent()
        assertEquals(ExecutionIntent.PAUSE, f.store.planFor("p")!!.intent)
        assertTrue(f.runtime.calls.isEmpty())
        assertEquals(DeliveryState.QUEUED, f.store.planFor("p")!!.deliveries.single().state)
    }

    @Test fun questionScopeAndOriginArePersistedForEachStage() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val second = base.milestones.single().copy(id = "second", title = "Проверка экспорта")
        f.store.save(base.copy(confirmedRevision = 1, milestones = base.milestones + second,
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("stage", "second"))) +
                listOf("stage", "second").map { DecisionNode(it, it, DecisionKind.STAGE, stageId = it) }))
        f.service.prepareSessions(f.store.planFor("p")!!)
        val plan = f.store.planFor("p")!!
        f.gateway.coordinator = """{"reply":"Уточните формат","askUser":true}"""
        for (stage in plan.milestones) {
            val worker = f.projects.sessions(project.id).first { it.stageId == stage.id }
            f.service.finished(plan, stage, StageAttempt("attempt-${stage.id}", worker.id, StageAssignment(profile.id, "m"),
                report = """{"kind":"QUESTION","text":"Нужен формат"}"""))
        }
        val requests = f.projects.orchestration(parent.id)!!.openQuestions()
        assertEquals(2, requests.size)
        assertEquals(setOf("stage", "second"), requests.flatMap { it.stageIds }.toSet())
        assertTrue(requests.all { it.scopeLabel.contains("Этап") && it.sourceSessionId != parent.id })
        f.service.send(parent, "PDF", replyTo = requests.first().id); runCurrent()
        assertEquals(listOf(requests.last().id), f.projects.orchestration(parent.id)!!.openQuestions().map { it.id })
    }

    @Test fun localQuestionAllowsIndependentWorkButGlobalQuestionHoldsIt() = runTest {
        for (global in listOf(false, true)) {
            val f = Fixture(this); f.initialize(); runCurrent()
            val parent = f.session("parent")
            val base = f.readyPlan("p", parent)
            val extra = base.milestones.single().copy(id = "extra", title = "Independent")
            f.store.save(base.copy(parallelism = 1, milestones = base.milestones + extra,
                tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("stage", "extra"))) +
                    listOf("stage", "extra").map { DecisionNode(it, it, DecisionKind.STAGE, stageId = it) }))
            f.gateway.coordinatorReplies += if (global) """{"reply":"Уточните общую цель","askUser":true,"questionStageIds":[]}"""
                else """{"reply":"Уточните первый этап","askUser":true}"""
            f.runtime.gate.complete(Unit)
            f.service.confirm("p"); advanceTimeBy(1000); runCurrent()
            val saved = f.store.planFor("p")!!
            assertEquals(ExecutionPhase.WAITING, saved.phase)
            assertNull(saved.issue)
            assertEquals(if (global) 1 else 2, f.runtime.calls.size)
            assertEquals(if (global) MilestoneStatus.PENDING else MilestoneStatus.DONE, saved.milestones.first { it.id == "extra" }.status)
        }
    }

    @Test fun bootReplaysStoredInboxWithoutRepeatingAnAlreadyAppliedReply() = runTest {
        val f = Fixture(this)
        f.projects.save(project); f.profiles.save(profile); f.settings.save(AppSettings(activeLlmProfileId = profile.id))
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        f.store.save(base.copy(dialogue = listOf(PlanningMessage("applied-reply", "assistant", "Уже сохранено"))))
        f.projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = "p", inputs = listOf(
            OrchestrationInput("applied", "Первый вопрос", 1, status = OrchestrationInputStatus.PROCESSING),
            OrchestrationInput("queued", "Следующий вопрос", 2, decision = UserTurnDecision(UserTurnIntent.DISCUSS, "Сохранённое решение")))))
        f.service.bootstrap(); runCurrent()
        assertEquals(listOf(OrchestrationInputStatus.DONE, OrchestrationInputStatus.DONE), f.projects.orchestration(parent.id)!!.inputs.map { it.status })
        assertTrue(f.gateway.lastMessages.isEmpty())
        assertEquals(1, f.projects.messages(project.id, parent.id).count { it.id == "queued-reply" })
        assertEquals(1, f.store.planFor("p")!!.dialogue.count { it.id == "applied-reply" })
    }

    @Test fun fullAnswerForwardsEverySavedPartToTheAffectedWorker() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        f.store.save(base.copy(confirmedRevision = base.revision, intent = ExecutionIntent.PAUSE))
        f.projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = "p", questions = listOf(
            OrchestrationQuestion("question", "p", "Формат и срок", listOf(PlanningQuestion("format", "Формат?"),
                PlanningQuestion("duration", "Срок?")), parent.id, listOf("stage")))))
        f.service.send(parent, "Нужен PDF", listOf(PlanningAnswer("format", text = "PDF")), "question"); runCurrent()
        assertTrue(f.store.planFor("p")!!.deliveries.isEmpty())
        f.service.send(parent, "Пять минут", listOf(PlanningAnswer("duration", text = "5 минут")), "question"); runCurrent()
        val delivery = f.store.planFor("p")!!.deliveries.single()
        assertContains(delivery.text, "Формат?: PDF")
        assertContains(delivery.text, "Срок?: 5 минут")
        assertEquals("stage", delivery.targetStageId)
        assertEquals(ExecutionIntent.PAUSE, f.store.planFor("p")!!.intent)
    }

    @Test fun bootReconstructsMissingDeliveryCardsOnlyOnce() = runTest {
        val f = Fixture(this)
        f.projects.save(project); f.profiles.save(profile); f.settings.save(AppSettings(activeLlmProfileId = profile.id))
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        f.store.save(base.copy(confirmedRevision = base.revision, engine = CodingEngine.CODEX, deliveries = listOf(PlanDelivery("saved", parent.id, "stage", "Сохранённое поручение"))))
        f.service.bootstrap(); runCurrent()
        val worker = f.projects.sessions(project.id).first { it.stageId == "stage" }
        assertEquals(CodingEngine.CODEX, worker.engine)
        val routed = f.projects.messages(project.id, parent.id).single { it.id == "saved-routed" }
        assertEquals(worker.id, routed.route?.target?.sessionId)
        assertEquals("Этап 1 · Stage", routed.route?.stageLabel)
        assertEquals(1, f.projects.messages(project.id, worker.id).count { it.id == "saved" })
        f.store.update("p") { it.copy(goal = "Новое имя цели") }; runCurrent()
        assertEquals(routed, f.projects.messages(project.id, parent.id).single { it.id == "saved-routed" })
    }

    @Test fun failedInitialSaveKeepsTextVisibleAndRecoveryProcessesIt() = runTest {
        val backing = InMemoryKeyValueStore()
        var fail = false
        val disk = object : KeyValueStore by backing {
            override fun write(key: String, value: String) {
                if (fail && key.startsWith("coding-orchestration-")) error("Disk unavailable")
                backing.write(key, value)
            }
        }
        val f = Fixture(this, disk); f.initialize(); runCurrent()
        val parent = f.session("parent")
        fail = true
        f.service.send(parent, "Сделай редактор"); runCurrent()
        assertTrue(f.service.persistenceErrors.value.containsKey(parent.id))
        assertEquals("Сделай редактор", f.service.unsavedInputs.value[parent.id]!!.single().text)
        assertTrue(f.gateway.lastMessages.isEmpty())
        fail = false
        f.service.recoverOrchestration(parent.id); runCurrent()
        assertTrue(f.service.persistenceErrors.value.isEmpty())
        assertTrue(f.service.unsavedInputs.value.isEmpty())
        assertEquals(OrchestrationInputStatus.DONE, f.projects.orchestration(parent.id)!!.inputs.single().status)
        assertEquals(1, f.projects.messages(project.id, parent.id).count { it.role == CodingRole.USER })
    }

    @Test fun damagedOrchestratorDoesNotPreventAnotherFromRestoringItsInbox() = runTest {
        val f = Fixture(this)
        f.projects.save(project); f.profiles.save(profile); f.settings.save(AppSettings(activeLlmProfileId = profile.id))
        val damaged = f.session("damaged"); f.readyPlan("bad", damaged)
        val healthy = f.session("healthy")
        val plan = f.readyPlan("good", healthy)
        f.store.save(plan.copy(dialogue = listOf(PlanningMessage("ready", "assistant", "Готово"))))
        f.kv.write("coding-orchestration-damaged", "{broken")
        f.projects.saveOrchestration(OrchestrationState(healthy.id, project.id, activePlanId = "good", inputs = listOf(
            OrchestrationInput("queued", "Расскажи", 2, decision = UserTurnDecision(UserTurnIntent.DISCUSS, "Ответ")))))
        f.service.bootstrap(); runCurrent()
        assertTrue(f.service.persistenceErrors.value.containsKey(damaged.id))
        assertEquals(OrchestrationInputStatus.DONE, f.projects.orchestration(healthy.id)!!.inputs.single().status)
        assertEquals(1, f.projects.messages(project.id, healthy.id).count { it.id == "queued-reply" })
    }

    @Test fun plannerQuestionKeepsItsAffectedStageAndStaysOpenDuringDiscussion() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent"); f.readyPlan("p", parent)
        f.gateway.overrideReply = """{"reply":"Уточните срок","questionStageIds":["stage"],"questions":[{"id":"duration","title":"Сколько минут?","kind":"TEXT"}]}"""
        f.service.send(parent, "Уточни требования"); runCurrent()
        val question = f.projects.orchestration(parent.id)!!.openQuestions("p").single()
        assertEquals(listOf("stage"), question.stageIds)
        assertEquals("Этап 1 · Stage", question.scopeLabel)
        assertEquals(parent.id, question.sourceSessionId)
        assertTrue(question.forPlanning)
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Срок определяет время действия кода."}"""
        f.service.send(parent, "Зачем задавать срок?"); runCurrent()
        assertEquals(question.id, f.projects.orchestration(parent.id)!!.openQuestions("p").single().id)
        assertContains(f.gateway.lastMessages.last().content, "stage: Этап 1 · Stage")
    }

    @Test fun orchestratorPreservesTheChosenEngineWhenCreatingAPlan() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent", engine = CodingEngine.CODEX)
        f.service.send(parent, "Сделай редактор"); runCurrent()
        assertEquals(CodingEngine.CODEX, f.store.plans.value.single().engine)
    }

    @Test fun eventWaitReleasesSlotAndWakesWorkerOnceWithoutStatusModelCalls() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val other = base.milestones.single().copy(id = "other", title = "Independent task")
        val p = base.copy(parallelism = 1, milestones = base.milestones + other,
            tree = base.tree.map { if (it.kind == DecisionKind.GOAL) it.copy(children = it.children + "other") else it } +
                DecisionNode("other", "Independent task", DecisionKind.STAGE, stageId = "other"))
        f.store.save(p)
        val independent = CompletableDeferred<Unit>()
        f.runtime.turnGates += listOf(CompletableDeferred(Unit), independent)
        f.runtime.turnReplies += listOf(
            """{"kind":"WAIT","text":"Нужен результат соседней задачи","waitFor":{"kind":"EVENT","event":"TASK_SUCCEEDED","taskId":"other"},"resumeMessage":"Проверь результат"}""",
            """{"kind":"RESULT","text":"Independent verified result"}""",
            """{"kind":"RESULT","text":"Review done"}""",
        )
        f.gateway.coordinatorReplies += listOf(
            """{"reply":"Ожидаем результат без опросов","schedules":[{"trigger":{"kind":"EVENT","event":"TASK_SUCCEEDED","taskId":"other"},"targetTaskId":"stage","waitTaskId":"stage","text":"Проверь результат соседней задачи"}]}""",
            """{"reply":"Результат принят"}""",
            """{"reply":"Проверка принята"}""",
        )
        f.runtime.gate.complete(Unit)
        f.service.confirm("p"); advanceTimeBy(1000); runCurrent()
        var current = f.store.planFor("p")!!
        val rule = current.scheduledMessages.single()
        assertEquals(rule.id, current.milestones.first { it.id == "stage" }.attempts.last().waitingForEvent)
        assertEquals(listOf("plan-p-stage-stage", "plan-p-stage-other"), f.runtime.calls.map { it.first.id })
        assertEquals(1, f.gateway.coordinatorCallbacks.size)
        val worker = f.projects.sessions(project.id).first { it.stageId == "stage" }
        assertEquals(CodingSessionStatus.SCHEDULED, CodingSessionUi(worker, plan = current).status)
        assertContains(f.runtime.calls.first().second, "taskId=stage")
        assertContains(f.runtime.calls.first().second, "taskId=other")
        assertContains(f.runtime.calls.first().second, "runId=${current.runId}")
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(2, f.runtime.calls.size)
        assertEquals(1, f.gateway.coordinatorCallbacks.size)
        independent.complete(Unit); advanceTimeBy(10_000); runCurrent()
        current = f.store.planFor("p")!!
        val workerCalls = f.runtime.calls.filter { it.first.id.startsWith("plan-p-stage-") }
        assertEquals(listOf("plan-p-stage-stage", "plan-p-stage-other", "plan-p-stage-stage"), workerCalls.map { it.first.id })
        assertEquals(1, f.runtime.calls.count { it.first.name == "Итоговая проверка" })
        assertEquals(3, f.gateway.coordinatorCallbacks.size)
        assertContains(workerCalls.last().second, "Independent verified result")
        assertEquals(PlanStatus.DONE, current.status)
        assertEquals(ScheduledMessageStatus.QUEUED, current.scheduledMessages.single().status)
        assertEquals(1, current.deliveries.count { it.id == rule.deliveryId })
        val childTransfers = f.projects.messages(project.id, worker.id).filter { it.handoff != null }
        assertEquals(2, childTransfers.size)
        val parentTransfers = f.projects.messages(project.id, parent.id).filter { it.handoff?.taskId == "stage" }
        assertEquals(childTransfers.map { it.handoff?.eventId }.toSet(), parentTransfers.map { it.handoff?.eventId }.toSet())
        assertTrue(childTransfers.all { it.handoff?.status == HandoffStatus.RESOLVED })
    }

    @Test fun timedOutWaitCallsOnlyOrchestratorAndDoesNotResumeWorker() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val attempt = StageAttempt("attempt", "worker", StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING)
        val p = base.copy(runId = "run", confirmedRevision = 1, intent = ExecutionIntent.RUN,
            milestones = base.milestones.map { it.copy(attempts = listOf(attempt), status = MilestoneStatus.ACTIVE) })
        f.store.save(p); f.service.prepareSessions(p); runCurrent()
        f.gateway.userDecision = """{"intent":"DISCUSS","reply":"Срок истёк; продолжение требует решения."}"""
        f.service.messageScheduler.apply("p", listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.EVENT,
            event = MessageEventKind.RESULT_RETURNED, taskId = "stage", deadline = 1), targetTaskId = "stage", waitTaskId = "stage", text = "Продолжить после результата")),
            "wait", parent.id, emptySet(), "stage")
        runCurrent()
        val current = f.store.planFor("p")!!
        val rule = current.scheduledMessages.single()
        assertTrue(rule.timeout)
        assertTrue(f.runtime.calls.isEmpty())
        assertNotNull(current.milestones.single().attempts.single().waitingForEvent)
        val input = f.projects.orchestration(parent.id)!!.inputs.single { it.scheduledRuleId == rule.id }
        assertEquals(OrchestrationInputStatus.DONE, input.status)
        assertTrue(current.deliveries.isEmpty())
        repeat(3) { f.service.messageScheduler.tick(); runCurrent() }
        assertEquals(1, f.projects.orchestration(parent.id)!!.inputs.count { it.scheduledRuleId == rule.id })
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun userCreatesRuleThroughChatAndCancellationUsesNoModel() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val base = f.readyPlan("p", parent)
        val p = base.copy(runId = "run", confirmedRevision = 1, intent = ExecutionIntent.PAUSE)
        f.store.save(p)
        f.gateway.userDecision = """{"intent":"SCHEDULE","reply":"Дождусь результата","schedules":[{"trigger":{"kind":"EVENT","event":"TASK_SUCCEEDED","taskId":"stage"},"text":"Объясни итог"}]}"""
        f.service.send(parent, "Когда этап завершится, объясни итог"); runCurrent()
        val rule = f.store.planFor("p")!!.scheduledMessages.single()
        assertEquals("run", rule.runId)
        assertEquals(parent.id, rule.targetSessionId)
        assertContains(f.gateway.lastMessages.last().content, "taskId=stage")
        f.gateway.failure = "Cancel must not call the model"
        f.service.cancelScheduledMessage("p", rule.id); runCurrent()
        assertEquals(ScheduledMessageStatus.CANCELLED, f.store.planFor("p")!!.scheduledMessages.single().status)
        assertTrue(f.runtime.calls.isEmpty())
    }

    @Test fun fullAnswerProducesOneDurableEventAndPartialAnswerDoesNot() = runTest {
        val f = Fixture(this); f.initialize(); runCurrent()
        val parent = f.session("parent")
        val p = f.readyPlan("p", parent).copy(runId = "run", confirmedRevision = 1, intent = ExecutionIntent.PAUSE)
        f.store.save(p)
        val q = OrchestrationQuestion("question", p.id, "Two details", listOf(
            PlanningQuestion("first", "First", QuestionKind.TEXT), PlanningQuestion("second", "Second", QuestionKind.TEXT)), parent.id)
        f.projects.saveOrchestration(OrchestrationState(parent.id, project.id, activePlanId = p.id, questions = listOf(q)))
        f.service.recoverOrchestration(parent.id); runCurrent()
        f.service.messageScheduler.apply(p.id, listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.EVENT,
            event = MessageEventKind.QUESTION_ANSWERED, questionId = q.id), text = "Summarize the answer")), "subscription", parent.id, setOf(q.id))
        f.service.send(parent, "One", listOf(PlanningAnswer("first", text = "One")), replyTo = q.id); runCurrent()
        assertTrue(f.store.planFor(p.id)!!.messageEvents.none { it.kind == MessageEventKind.QUESTION_ANSWERED })
        assertEquals(ScheduledMessageStatus.WAITING, f.store.planFor(p.id)!!.scheduledMessages.single().status)
        f.service.send(parent, "Two", listOf(PlanningAnswer("second", text = "Two")), replyTo = q.id); runCurrent()
        val events = f.store.planFor(p.id)!!.messageEvents.filter { it.kind == MessageEventKind.QUESTION_ANSWERED }
        assertEquals(1, events.size)
        assertEquals(q.id, events.single().questionId)
        assertEquals("run", events.single().runId)
        assertEquals(ScheduledMessageStatus.READY, f.store.planFor(p.id)!!.scheduledMessages.single().status)
        f.service.recoverOrchestration(parent.id); runCurrent(); f.service.messageScheduler.tick()
        assertEquals(events, f.store.planFor(p.id)!!.messageEvents.filter { it.kind == MessageEventKind.QUESTION_ANSWERED })
        assertTrue(f.runtime.calls.isEmpty())
    }

}
