package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
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
        var gate: CompletableDeferred<Unit>? = null
        var overrideReply: String? = null
        var timeout = false
        var coordinator = """{"reply":"Результат принят","actions":[]}"""
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
            lastMessages = messages
            if (timeout) withTimeout(10) { awaitCancellation() }
            gate?.await()
            overrideReply?.let { return it }
            if (messages.first().content.contains("Ты координатор")) return coordinator
            return """{"reply":"Уточним результат","questions":[{"id":"single","title":"Формат?","kind":"SINGLE","options":[{"id":"pdf","label":"PDF"},{"id":"doc","label":"DOC"}]},{"id":"multi","title":"Возможности?","kind":"MULTIPLE","options":[{"id":"read","label":"Чтение"},{"id":"write","label":"Запись"}]},{"id":"text","title":"Критерии?","kind":"TEXT"}]}"""
        }
    }
    private class Runtime(val gate: CompletableDeferred<Unit> = CompletableDeferred()) : CodingRuntime {
        val calls = mutableListOf<Pair<CodingSession, String>>()
        val paths = mutableListOf<String>()
        override val supported = true
        override val rootPath = "/shared"
        override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
        override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
        override fun abort(sessionId: String) = Unit
        override fun abortAll() = Unit
        override suspend fun uninstall() = Unit
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
            calls += session to prompt; paths += project.path
            emit(CodingEvent.SessionStarted("engine-${session.id}"))
            gate.await()
            emit(CodingEvent.FinalText("""{"kind":"RESULT","text":"Проверки выполнены","changedFiles":[]}"""))
            emit(CodingEvent.Finished)
        }
    }
    private inner class Fixture(scope: TestScope) {
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val gateway = Gateway()
        val runtime = Runtime()
        val execution = PlanningExecutionService(store, runtime, projects, profiles, settings, object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
        }, scope = scope.backgroundScope)
        val service = PlanningChatService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway, scope.backgroundScope)
        suspend fun initialize() {
            projects.save(project); profiles.save(profile); settings.save(AppSettings(activeLlmProfileId = profile.id))
            service.bootstrap()
        }
        suspend fun session(id: String): CodingSession = CodingSession(id, project.id, id, 1, planningMode = true,
            modelSelection = ModelSelection(profile.id, "m")).also { projects.saveSession(it) }
        suspend fun readyPlan(id: String, parent: CodingSession) = Plan(id, project.id, "Goal $id", parentSessionId = parent.id, sharedWorkspace = true,
            plannerSelection = parent.modelSelection, milestones = listOf(Milestone("stage", "Stage", description = "Change files", acceptance = "Checks pass", assignment = StageAssignment(profile.id, "m"))),
            tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("stage")), DecisionNode("stage", "Stage", DecisionKind.STAGE, stageId = "stage"))).also { store.save(it) }
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
        assertTrue(f.projects.messages(project.id, "parent").any { it.text.contains("очередь этапа") && it.text.contains("Проверь экспорт") })
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
                    yield()
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
        assertEquals(completed, updated.milestones.first { it.id == "stage" })
        assertTrue(updated.milestones.any { it.id == "extra" })
        assertTrue(f.projects.sessions(project.id).any { it.stageId == "extra" })
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
}
