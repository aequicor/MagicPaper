package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestrationToolsTest {
    private class Fixture(val scope: TestScope) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val host = ToolHost(MemoryToolReceiptStore())
        val profile = LlmProfile("model", "Planner", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
        val project = CodingProject("p", "Project", "/project", 0)
        val parent = CodingSession("parent", "p", "Parent", 0, engine = CodingEngine.PI, planningMode = true,
            role = CodingSessionRole.ORCHESTRATOR, modelSelection = ModelSelection("model", "m"))
        val worker = CodingSession("worker", "p", "Worker", 0, engine = CodingEngine.PI, role = CodingSessionRole.WORKER,
            parentSessionId = parent.id, planId = "plan", stageId = "stage")
        val assignment = StageAssignment("model", "m")
        val attempt = StageAttempt("attempt", "worker", assignment, phase = AttemptPhase.EXECUTING, path = "/project", engine = CodingEngine.PI)
        val verificationReports = mutableListOf<String>()
        val workerCalls = mutableListOf<ToolExecutionContext>()
        var workerRun: suspend (ToolSession) -> String = { error("Unexpected worker run") }
        var planningRun: suspend (ToolSession) -> String = { error("Unexpected model run") }
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                val tools = currentCoroutineContext()[ToolSession] ?: error("Missing worker tools")
                workerCalls += tools.context
                emit(CodingEvent.SessionStarted("native-${session.id}"))
                emit(CodingEvent.ToolStarted("read", "source.kt", "read"))
                emit(CodingEvent.ToolFinished("read", false, "read", "source content"))
                emit(CodingEvent.FinalText(workerRun(tools)))
                emit(CodingEvent.Finished)
            }
            override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile) = flow {
                emit(CodingEvent.MessageStarted)
                val tools = currentCoroutineContext()[ToolSession] ?: error("Missing tools")
                assertEquals(session.id, tools.context.sessionId)
                assertEquals(session.parentSessionId, tools.context.ownerSessionId)
                assertNotEquals(session.id, tools.context.ownerSessionId)
                emit(CodingEvent.FinalText(planningRun(tools)))
                emit(CodingEvent.Finished)
            }
        }
        val gateway = object : LlmGateway { override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Text-only gateway must not run") }
        val composer = PlanComposer(gateway, planningGateway = RuntimePlanningGateway(runtime), projectLookup = { project }, toolHost = host)
        val execution = PlanningExecutionService(store, ToolEnabledCodingRuntime(runtime, host), projects, profiles, settings,
            object : MilestoneVerifier { override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                verificationReports += report
                return Verdict(true, "Checked")
            } },
            workspaces = object : PlanningWorkspace by LocalPlanningWorkspace() {
                override suspend fun verificationSnapshot(path: String) = "fixture-snapshot"
            },
            scope = scope.backgroundScope)
        val service = OrchestrationService(store, execution, projects, profiles, settings, composer, gateway, scope.backgroundScope,
            workerDispatcher = StandardTestDispatcher(scope.testScheduler), toolHost = host)
        suspend fun init() {
            projects.save(project); profiles.save(profile); settings.save(AppSettings(activeLlmProfileId = profile.id))
            projects.saveSession(parent); projects.saveSession(worker)
            store.save(Plan("plan", "p", "Goal", parentSessionId = parent.id, sessionId = parent.id, engine = CodingEngine.PI,
                confirmedRevision = 0, runId = "run", sharedWorkspace = true, plannerSelection = parent.modelSelection,
                tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("node")), DecisionNode("node", "Stage", DecisionKind.STAGE, stageId = "stage")),
                milestones = listOf(Milestone("stage", "Stage", description = "Task", acceptance = "Verified", assignment = assignment, attempts = listOf(attempt)))))
        }
        fun orchestrator(request: String = "request", source: OrchestrationInput? = null) = host.session(ToolExecutionContext("p", "parent", "parent", request,
            ToolRole.ORCHESTRATOR, CodingInteractionMode.PLANNING, "plan", "run", sourceInput = source))
        fun args(text: String) = Json.parseToJsonElement(text).jsonObject
    }

    @Test fun commandsUseExistingSessionDeliveryAndRevisionGuards() = runTest {
        val f = Fixture(this); f.init()
        val tools = f.orchestrator()
        tools.call("send", "stage.send", f.args("""{"stageId":"stage","message":"Check the update"}"""))
        val delivery = f.store.planFor("plan")!!.deliveries.single()
        assertEquals("stage", delivery.targetStageId)
        assertEquals(DeliveryState.QUEUED, delivery.state)
        tools.call("rename", "session.manage", f.args("""{"kind":"RENAME","stageId":"stage","name":"New name"}"""))
        assertEquals("New name", f.projects.sessions("p").first { it.id == "worker" }.name)
        assertFailsWith<IllegalArgumentException> { tools.call("archive", "session.manage", f.args("""{"kind":"ARCHIVE","stageId":"stage"}""")) }
        assertFailsWith<IllegalArgumentException> { tools.call("stale", "plan.control", f.args("""{"action":"pause","revision":-1}""")) }
        assertEquals(ExecutionIntent.STOP, f.store.planFor("plan")!!.intent)
        assertFailsWith<IllegalArgumentException> { tools.call("foreign", "stage.send", f.args("""{"stageId":"other","message":"No"}""")) }
        assertEquals(1, f.store.planFor("plan")!!.deliveries.size)
    }

    @Test fun selectivePauseIncludesDependantsAndLeavesIndependentStagesAvailable() = runTest {
        val f = Fixture(this); f.init()
        f.store.update("plan") { p -> p.copy(milestones = p.milestones + listOf(
            Milestone("dependent", "Dependent", dependsOn = listOf("stage")), Milestone("independent", "Independent")),
            tree = p.tree.map { if (it.id == "root") it.copy(children = it.children + listOf("d", "i")) else it } + listOf(
                DecisionNode("d", "Dependent", DecisionKind.STAGE, stageId = "dependent"),
                DecisionNode("i", "Independent", DecisionKind.STAGE, stageId = "independent"))) }
        f.orchestrator().call("pause", "stage.pause", f.args("""{"stageIds":["stage"],"reason":"Clarify requirements"}"""))
        val saved = f.projects.orchestration("parent")!!
        assertEquals(setOf("stage", "dependent"), saved.pausedStages(f.store.planFor("plan")!!))
        assertEquals(listOf("stage"), saved.workPauses["request"]!!.stageIds)
    }

    @Test fun savedLegacyDecisionUsesCommandRecoveryAndHistoricalMessagesAreNotExecuted() = runTest {
        val f = Fixture(this); f.init()
        val decision = f.json.decodeFromString<UserTurnDecision>("""{"intent":"INSTRUCT","stageId":"stage"}""")
        val input = OrchestrationInput("legacy", "Legacy instruction", 0, decision = decision)
        f.projects.saveOrchestration(OrchestrationState("parent", "p", activePlanId = "plan", inputs = listOf(input)))
        f.projects.saveMessages("p", "parent", listOf(CodingMessage("old-text", CodingRole.AGENT,
            """{"intent":"INSTRUCT","stageId":"stage","reply":"Never execute this history"}""", createdAt = 0)))
        f.service.bootstrap(); runCurrent()
        assertEquals("legacy-instruction", f.store.planFor("plan")!!.deliveries.single().id)
        assertEquals("Legacy instruction", f.store.planFor("plan")!!.deliveries.single().text)
        assertTrue(f.host.receipts.forRequest("p/parent/legacy").any { it.toolId == "stage.send" && it.phase == ToolPhase.SUCCEEDED })
        assertEquals(OrchestrationInputStatus.DONE, f.projects.orchestration("parent")!!.inputs.single().status)
        f.service.bootstrap(); runCurrent()
        assertEquals(1, f.store.planFor("plan")!!.deliveries.size)
        f.service.shutdown()
    }

    @Test fun handoffIsDurableAndCannotTargetAnotherAttempt() = runTest {
        val f = Fixture(this); f.init()
        val context = f.host.prepareWorker(f.worker)
        assertEquals(ToolRole.WORKER, context.role)
        val tools = f.host.session(context)
        val args = f.args("""{"kind":"RESULT","text":"Tests passed; implementation complete","changedFiles":["source.kt"]}""")
        tools.call("handoff", "stage.handoff", args)
        val saved = f.store.planFor("plan")!!.coordination.single()
        assertEquals("attempt-turn-0", saved.id)
        assertEquals("worker", saved.sourceSessionId)
        assertEquals(StageReplyKind.RESULT, saved.reply.kind)
        assertEquals(MilestoneStatus.PENDING, f.store.planFor("plan")!!.milestones.single().status)
        f.host.session(context).call("handoff", "stage.handoff", args)
        assertEquals(1, f.store.planFor("plan")!!.coordination.size)
        assertFailsWith<IllegalArgumentException> { f.host.session(context.copy(attemptId = "old")).call("foreign", "stage.handoff", args) }
    }

    @Test fun relativeHandoffWaitBecomesAFixedDeadlineBeforeRecovery() = runTest {
        val f = Fixture(this); f.init()
        val context = f.host.prepareWorker(f.worker)
        val args = f.args("""{"kind":"WAIT","text":"Wait for the next check","waitFor":{"kind":"AT_TIME","afterMillis":5000},"resumeMessage":"Check result"}""")
        f.host.session(context).call("wait", "stage.handoff", args)
        val saved = f.store.planFor("plan")!!.coordination.single().reply.waitFor!!
        assertNull(saved.afterMillis)
        assertNotNull(saved.at)
        f.host.session(context).call("wait", "stage.handoff", args)
        assertEquals(saved, f.store.planFor("plan")!!.coordination.single().reply.waitFor)
    }

    @Test fun automaticInputCannotConfirmOrResumeAStoppedPlan() = runTest {
        val f = Fixture(this); f.init()
        val tools = f.orchestrator(source = OrchestrationInput("auto", "resume", 0, scheduledRuleId = "rule"))
        for (action in listOf("confirm", "resume")) assertFailsWith<IllegalArgumentException> {
            tools.call(action, "plan.control", f.args("""{"action":"$action","revision":0}"""))
        }
        assertEquals(ExecutionIntent.STOP, f.store.planFor("plan")!!.intent)
    }

    @Test fun scheduleCommandsRetainDeliveryReceiptsAcrossToolSessionRecovery() = runTest {
        val f = Fixture(this); f.init()
        val args = f.args("""{"commands":[{"operation":"CREATE","trigger":{"kind":"AT_TIME","at":4102444800000},"text":"Check status"}]}""")
        val first = f.orchestrator().call("schedule", "schedule.manage", args)
        assertEquals(first, f.orchestrator().call("schedule", "schedule.manage", args))
        assertEquals(1, f.store.planFor("plan")!!.scheduledMessages.size)
    }

    @Test fun plannerUsesProposalToolAndRejectsTextPretendingToApplyAPlan() = runTest {
        val f = Fixture(this); f.init()
        val plan = f.store.planFor("plan")!!
        val observed = mutableListOf<CodingStep>()
        f.planningRun = { tools ->
            assertEquals(ToolRole.PLANNER, tools.context.role)
            tools.call("propose", "plan.propose", f.args("""{"reply":"Existing plan remains","tree":[],"milestones":[]}"""))
            "Human explanation"
        }
        val structured = f.composer.completePlanning(plan, f.profile, listOf(LlmMessage(LlmChatRole.USER, "Review")), observed::add)
        assertContains(structured, "Existing plan remains")
        assertFalse(structured.contains("Human explanation"))
        assertTrue(observed.any { it.tool == "plan.propose" && !it.running && it.ok })
        f.planningRun = { """{"reply":"Pretend plan","tree":[],"milestones":[]}""" }
        assertFailsWith<IllegalStateException> { f.composer.completePlanning(plan, f.profile, listOf(LlmMessage(LlmChatRole.USER, "Review"))) {} }
    }

    @Test fun coordinatorContinueQueuesConcreteWorkAndVerifyCannotAcceptItEarly() = runTest {
        val f = Fixture(this); f.init()
        f.host.session(f.host.prepareWorker(f.worker)).call("result", "stage.handoff", f.args("""{"kind":"RESULT","text":"Partial work"}"""))
        val context = ToolExecutionContext("p", "parent", "parent", "review", ToolRole.ORCHESTRATOR, CodingInteractionMode.PLANNING,
            "plan", "run", "stage", "attempt", 0)
        val tools = f.host.session(context)
        assertFailsWith<IllegalArgumentException> { tools.call("empty", "stage.resolve", f.args("""{"action":"CONTINUE"}""")) }
        tools.call("continue", "stage.resolve", f.args("""{"action":"CONTINUE","reason":"Add the missing failure test"}"""))
        assertEquals("Add the missing failure test", f.store.planFor("plan")!!.deliveries.single().text)
        assertEquals(MilestoneStatus.PENDING, f.store.planFor("plan")!!.milestones.single().status)
    }
    @Test fun committedDeliveryIsRecoveredWhenTheFinalToolReceiptWasNotSaved() = runTest {
        val f = Fixture(this); f.init()
        val args = f.args("""{"stageId":"stage","message":"Once"}""")
        val expected = f.orchestrator().call("send", "stage.send", args)
        val receipt = f.host.receipts.forRequest("p/parent/request").single()
        f.host.receipts.save(receipt.copy(phase = ToolPhase.STARTED, result = JsonNull))
        assertEquals(expected, f.orchestrator().call("send", "stage.send", args))
        assertEquals(1, f.store.planFor("plan")!!.deliveries.size)
        assertEquals(ToolPhase.SUCCEEDED, f.host.receipts.get(receipt.id)!!.phase)
    }

    @Test fun proposalConfirmationWorkerHandoffAndVerificationShareToolsAndSeparateHistories() = runTest {
        val f = Fixture(this); f.init()
        f.store.update("plan") { it.copy(confirmedRevision = null,
            milestones = it.milestones.map { stage -> stage.copy(attempts = emptyList()) }) }
        val roles = mutableListOf<ToolRole>()
        f.planningRun = { tools ->
            roles += tools.context.role
            if (tools.context.role == ToolRole.ORCHESTRATOR) {
                tools.call("context", "context.get", JsonObject(emptyMap()))
                tools.call("refine", "plan.refine", f.args("""{"message":"Create a plan","requiresConfirmation":true}"""))
                "План подготовлен"
            } else {
                tools.call("proposal", "plan.propose", f.args("""{"reply":"Предлагаю выполнить этап","tree":[{"id":"root","title":"Goal","kind":"GOAL","children":["node"]},{"id":"node","title":"Stage","kind":"STAGE","stageId":"stage"}],"milestones":[{"id":"stage","title":"Stage","description":"Implement the change","acceptance":"Tests pass"}]}"""))
                "Предложение готово"
            }
        }
        f.service.bootstrap(); runCurrent()
        f.service.send(f.parent, "Составь план изменения")
        runCurrent()
        assertEquals(listOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER), roles)
        val saved = f.store.planFor("plan")!!
        assertNull(saved.confirmedRevision)
        assertEquals(ExecutionIntent.STOP, saved.intent)
        assertEquals("Implement the change", saved.milestones.first { it.id == "stage" }.description)
        assertTrue(saved.milestones.all { it.attempts.isEmpty() })
        val history = f.projects.messages("p", "parent")
        assertTrue(history.any { it.text.contains("План подготовлен") }, history.toString())
        assertTrue(history.flatMap { it.steps }.any { it.tool == "plan.refine" && !it.running && it.ok })
        f.workerRun = { tools ->
            if (tools.context.role == ToolRole.CHAT) {
                assertNotNull(tools.context.planId, "Internal verification retains the plan context")
                tools.call("context", "context.get", JsonObject(emptyMap()))
                "Detailed evidence: checks PASS"
            } else {
            tools.call("handoff", "stage.handoff", f.args("""{"kind":"RESULT","text":"Detailed evidence: checks PASS","changedFiles":["source.kt"]}"""))
            "Краткий ответ исполнителя"
            }
        }
        f.planningRun = { tools ->
            assertEquals(ToolRole.ORCHESTRATOR, tools.context.role)
            val record = f.store.planFor("plan")!!.coordination.single { it.attemptId == tools.context.attemptId }
            assertNotNull(record.toolCallId, "Handoff must be durable before coordination starts")
            tools.call("resolve", "stage.resolve", f.args("""{"action":"VERIFY"}"""))
            "Передано на проверку"
        }
        f.service.confirm("plan"); advanceTimeBy(1000); runCurrent()
        val completed = f.store.planFor("plan")!!
        assertEquals(PlanStatus.DONE, completed.status, completed.issue.toString())
        assertEquals(1, f.workerCalls.count { it.stageId == "stage" })
        assertTrue(completed.coordination.all { it.verification?.passed == true })
        assertTrue(f.verificationReports.all { "Detailed evidence: checks PASS" in it })
        val ownerHistory = f.projects.messages("p", "parent")
        val workerHistory = f.projects.messages("p", f.workerCalls.single { it.stageId == "stage" }.ownerSessionId)
        assertTrue(ownerHistory.flatMap { it.steps }.none { it.tool == "stage.handoff" })
        assertTrue(ownerHistory.flatMap { it.steps }.any { it.tool == "stage.resolve" && it.ok })
        assertTrue(workerHistory.flatMap { it.steps }.any { it.tool == "file.read" })
        assertTrue(workerHistory.flatMap { it.steps }.any { it.tool == "stage.handoff" && it.ok })
        f.service.shutdown()
    }

}
