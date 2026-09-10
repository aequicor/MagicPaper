package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.coding.SessionOrganismStore
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
    private class Fixture(val scope: TestScope, receipts: ToolReceiptStore = MemoryToolReceiptStore(), managed: Boolean = false) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val host = ToolHost(receipts)
        val organisms = if (managed) SessionOrganismService(SessionOrganismStore(kv), projects, settings) else null
        val profile = LlmProfile("model", "Planner", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
        val project = CodingProject("p", "Project", "/project", 0)
        val planningPrompts = mutableListOf<String>()
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
                planningPrompts += prompt
                emit(CodingEvent.SessionStarted("native-${session.id}"))
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
            workerDispatcher = StandardTestDispatcher(scope.testScheduler), toolHost = host, organisms = organisms)
        suspend fun init() {
            projects.save(project); profiles.save(profile); settings.save(AppSettings(activeLlmProfileId = profile.id))
            projects.saveSession(parent); projects.saveSession(worker)
            store.save(Plan("plan", "p", "Goal", parentSessionId = parent.id, sessionId = parent.id, engine = CodingEngine.PI,
                confirmedRevision = 0, runId = "run", sharedWorkspace = true, plannerSelection = parent.modelSelection,
                tree = listOf(DecisionNode("root", "Goal", DecisionKind.GOAL, listOf("node")), DecisionNode("node", "Stage", DecisionKind.STAGE, stageId = "stage")),
                milestones = listOf(Milestone("stage", "Stage", description = "Task", acceptance = "Verified", assignment = assignment, attempts = listOf(attempt)))))
            organisms?.ensure(parent)
        }
        fun orchestrator(request: String = "request", source: OrchestrationInput? = null) = host.session(ToolExecutionContext("p", "parent", "parent", request,
            ToolRole.ORCHESTRATOR, CodingInteractionMode.PLANNING, "plan", "run", sourceInput = source))
        fun args(text: String) = Json.parseToJsonElement(text).jsonObject
        suspend fun blockOnStaleAcceptance(): Plan {
            return store.update("plan") { plan ->
                val criteria = plan.milestones.single().criteria()
                val record = AcceptanceRecord("run", "attempt", "old-snapshot", criteria,
                    criteria.map { AcceptanceFinding(it.id, CheckStatus.STALE, it.description, "Files changed") },
                    status = AcceptanceStatus.STALE)
                val issue = PlanningIssue(IssueKind.VERIFICATION, record.summary(), requiresUser = true)
                plan.copy(intent = ExecutionIntent.RUN, phase = ExecutionPhase.WAITING, issue = issue, status = PlanStatus.FAILED,
                    milestones = plan.milestones.map { it.copy(status = MilestoneStatus.FAILED,
                        attempts = listOf(attempt.copy(phase = AttemptPhase.VERIFYING, error = issue,
                            verificationSnapshot = "old-snapshot", acceptanceRecord = record))) })
            }
        }
    }

    @Test fun statusQuestionsIncludingLegacyResumeInputsDoNotRetryAcceptance() = runTest {
        val f = Fixture(this); f.init()
        val blocked = f.blockOnStaleAcceptance()
        f.planningRun = { tools ->
            tools.call("context", "context.get", JsonObject(emptyMap()))
            "Результат сохранён, проверка устарела."
        }
        f.service.bootstrap(); runCurrent()
        // Reproduce a persisted input from the old composer and then its current entry point.
        f.service.send(f.parent, "Почему требуется проверка?", resumeAfter = true); runCurrent()
        f.service.resume(f.parent, "Подготовь описание проблемы"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        val saved = f.store.planFor("plan")!!
        assertEquals(blocked.issue, saved.issue)
        assertEquals(ExecutionPhase.WAITING, saved.phase)
        assertEquals(blocked.milestones.single().attempts, saved.milestones.single().attempts)
        assertTrue(f.verificationReports.isEmpty())
        assertTrue(f.workerCalls.isEmpty())
        assertEquals(2, f.service.states.value["parent"]!!.inputs.count { it.status == OrchestrationInputStatus.DONE })
    }

    @Test fun leaveRecoveryPersistsStopBeforePublishingTheAnswer() = runTest {
        for (skip in listOf(false, true)) {
            val f = Fixture(this); f.init()
            val blocked = f.blockOnStaleAcceptance()
            val blockers = blocked.blockingIssues(emptyList())
            val request = UserInteractionRequest("blocker:${blockers.map { it.messageId }.sorted().joinToString(":")}",
                "p", "parent", InteractionKind.RECOVER_PLAN,
                listOf(PlanningQuestion("decision", "Как продолжить?", QuestionKind.SINGLE,
                    listOf(QuestionOption("leave", "Оставить остановленной")), canSkip = true)), planId = "plan")
            f.service.submitInteraction(request, listOf(PlanningAnswer("decision", if (skip) emptyList() else listOf("leave"), skipped = skip)))
            val persisted = JsonPlanningRepository(f.kv, f.json).plans().single()
            assertEquals(ExecutionIntent.STOP, persisted.intent)
            assertEquals(PlanStatus.STOPPED, persisted.status)
            assertEquals(blocked.milestones.single().attempts, persisted.milestones.single().attempts)
            assertTrue(f.projects.messages("p", "parent").any { it.id == request.id + "-left" })
            f.execution.bootstrap(); advanceTimeBy(6_000); runCurrent()
            assertTrue(f.verificationReports.isEmpty())
            assertTrue(f.workerCalls.isEmpty())
        }
    }

    @Test fun lateDiscussionCannotOverrideStopEvenWithLegacyResumeFlag() = runTest {
        val f = Fixture(this); f.init(); f.blockOnStaleAcceptance()
        val reply = CompletableDeferred<Unit>()
        f.planningRun = { tools ->
            tools.call("context", "context.get", JsonObject(emptyMap()))
            reply.await()
            "Только объяснение."
        }
        f.service.bootstrap(); runCurrent()
        f.service.send(f.parent, "Объясни состояние", resumeAfter = true); runCurrent()
        f.execution.stopAndJoin("plan")
        reply.complete(Unit); runCurrent(); advanceTimeBy(6_000); runCurrent()
        assertEquals(ExecutionIntent.STOP, f.store.planFor("plan")!!.intent)
        assertEquals(PlanStatus.STOPPED, f.store.planFor("plan")!!.status)
        assertTrue(f.verificationReports.isEmpty())
        assertTrue(f.workerCalls.isEmpty())
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

    @Test fun legacyTargetsCannotReuseAStageWhoseSessionBelongsToAnotherParent() = runTest {
        val f = Fixture(this); f.init()
        f.projects.saveSession(f.worker.copy(parentSessionId = "other-parent"))
        val tools = f.orchestrator()
        assertFailsWith<ToolArgumentRejection> { tools.call("send", "stage.send", f.args("""{"stageId":"stage","message":"Cross branch"}""")) }
        assertFailsWith<ToolArgumentRejection> { tools.call("manage", "session.manage", f.args("""{"kind":"RENAME","stageId":"stage","name":"Taken over"}""")) }
        assertFailsWith<ToolArgumentRejection> { tools.call("pause", "stage.pause", f.args("""{"stageIds":["stage"],"reason":"Cross branch"}""")) }
        assertFailsWith<ToolArgumentRejection> { tools.call("schedule", "schedule.manage", f.args("""{"commands":[{"targetTaskId":"stage","text":"Cross branch","trigger":{"kind":"AT_TIME","at":1000}}]}""")) }
        assertTrue(f.store.planFor("plan")!!.deliveries.isEmpty())
        assertTrue(f.store.planFor("plan")!!.scheduledMessages.isEmpty())
        assertEquals("Worker", f.projects.sessions("p").first { it.id == "worker" }.name)
        assertTrue(f.host.receipts.forRequest("p/parent/request").all { it.phase == ToolPhase.FAILED })
    }

    @Test fun workerCannotGainSiblingControlByUsingTheLegacyCoordinatorAdapter() = runTest {
        val f = Fixture(this); f.init()
        val tools = f.host.session(ToolExecutionContext("p", "worker", "worker", "forged-coordinator",
            ToolRole.ORCHESTRATOR, CodingInteractionMode.PLANNING, "plan", "run"))
        assertFailsWith<ToolArgumentRejection> { tools.call("send", "stage.send", f.args("""{"stageId":"stage","message":"Cross branch"}""")) }
        assertFailsWith<ToolArgumentRejection> { tools.call("control", "plan.control", f.args("""{"action":"pause","revision":0}""")) }
        assertTrue(f.store.planFor("plan")!!.deliveries.isEmpty())
    }

    @Test fun mergeRuntimeKeepsHistoryOwnershipWithoutAcquiringNormalSessionControl() = runTest {
        val f = Fixture(this); f.init()
        val context = f.host.prepareWorker(f.worker.copy(id = "worker-merge"))
        assertEquals("worker", context.ownerSessionId)
        assertTrue(context.auxiliaryExecution)
        assertEquals(ToolRole.CHAT, context.role)
        val tools = f.host.session(context)
        tools.call("context", "context.get", JsonObject(emptyMap()))
        assertFailsWith<IllegalArgumentException> {
            tools.call("create", "session.create", f.args("""{"name":"Sibling","task":"No","acceptance":"No","tokens":1000}"""))
        }
        assertFalse(f.host.prepareWorker(f.worker).auxiliaryExecution)
    }

    @Test fun managedGenerationIsCheckedAfterEffectAndBeforeNativeCompletionPersistence() = runTest {
        val f = Fixture(this, managed = true); f.init()
        val organisms = f.organisms!!
        val aggregate = organisms.ensure(f.parent)
        val context = f.orchestrator().context.copy(organismId = aggregate.id)
        val tools = f.host.session(context, mapOf("stage.send" to { _, _, _ ->
            organisms.store.beginRun(aggregate.id, "parent")
            JsonPrimitive("late external result")
        }))
        val event = ToolEvent("p", "parent", "request", "p/parent/request/native/native-call", "file.read",
            ToolCategory.READ, ToolPhase.STARTED, "source.kt")
        tools.executor.recordNative(context, event)
        assertFailsWith<IllegalArgumentException> { tools.call("late", "stage.send", f.args("""{"stageId":"stage","message":"Late effect"}""")) }
        assertTrue(tools.calls.value.isEmpty())
        val late = f.host.receipts.forRequest("p/parent/request").single { it.toolId == "stage.send" }
        assertEquals(ToolPhase.UNKNOWN, late.phase)
        assertFailsWith<IllegalArgumentException> { tools.executor.recordNative(context, event.copy(phase = ToolPhase.SUCCEEDED, result = "late content")) }
        assertEquals(ToolPhase.STARTED, f.host.receipts.get(event.callId)?.phase)
    }

    @Test fun managedLegacyMutationsRetainAnExplicitRejectedIntentWithoutChangingTheAggregate() = runTest {
        val f = Fixture(this, managed = true); f.init()
        val before = f.organisms!!.ensure(f.parent)
        val tools = f.orchestrator()
        for (kind in listOf("RENAME", "ARCHIVE", "RESTORE")) {
            val error = assertFailsWith<ToolArgumentRejection> {
                tools.call(kind, "session.manage", f.args("""{"kind":"$kind","stageId":"stage","name":"Changed"}"""))
            }
            assertTrue(error.message.orEmpty().contains("session.control"))
        }
        assertEquals(before, f.organisms.store.get(before.id))
        val worker = f.projects.sessions("p").first { it.id == "worker" }
        assertEquals("Worker", worker.name)
        assertFalse(worker.archived)
        val recorded = f.projects.orchestration("parent")!!.sessionCommands
        assertEquals(3, recorded.size)
        assertTrue(recorded.all { !it.applied && it.error.contains("session.control") })
        assertEquals(3, f.projects.messages("p", "parent").count { it.id.endsWith("-rejected") && it.origin == MessageOrigin.TOOL })
        assertTrue(f.host.receipts.forRequest("p/parent/request").all { it.phase == ToolPhase.FAILED })
    }

    @Test fun savedManagedLegacyCommandCannotMintCurrentGenerationAuthorityOnReplay() = runTest {
        val f = Fixture(this, managed = true); f.init()
        val before = f.organisms!!.ensure(f.parent)
        val oldTools = f.orchestrator()
        val generation = f.organisms.store.beginRun(before.id, "parent").generation
        assertFailsWith<IllegalArgumentException> { oldTools.call("stale", "session.manage", f.args("""{"kind":"RENAME","stageId":"stage","name":"Stale"}""")) }
        f.projects.saveOrchestration(OrchestrationState("parent", "p", activePlanId = "plan",
            sessionCommands = listOf(SessionCommand("persisted", SessionCommandKind.RESTORE, "worker", "plan", "stage"))))
        f.service.bootstrap(); runCurrent()
        val failed = f.projects.orchestration("parent")!!.sessionCommands.single()
        assertEquals("persisted", failed.id)
        assertFalse(failed.applied)
        assertTrue(failed.error.contains("session.control"))
        assertEquals(generation, f.organisms.store.get(before.id).sessions.getValue("parent").generation)
        assertEquals(before.sessions.getValue("worker").generation, f.organisms.store.get(before.id).sessions.getValue("worker").generation)
        assertEquals(1, f.projects.messages("p", "parent").count { it.id == "persisted-rejected" })
        f.service.shutdown()
    }

    @Test fun userRenameUpdatesManagedAggregateAndProjectionWithoutChangingRuntimeAuthority() = runTest {
        val f = Fixture(this, managed = true); f.init()
        val before = f.organisms!!.ensure(f.parent)
        val workerBefore = before.sessions.getValue("worker")
        f.service.renameSession("worker", "  Human name  "); runCurrent()
        val renamed = f.organisms.store.get(before.id)
        val node = renamed.sessions.getValue("worker")
        assertEquals("Human name", node.name)
        assertTrue(node.nameManuallySet)
        assertEquals(workerBefore.generation, node.generation)
        assertEquals(workerBefore.desired, node.desired)
        assertEquals(workerBefore.remainingTokens, node.remainingTokens)
        assertTrue(renamed.audit.any { it.actor == "USER" && it.action == "RENAME" && it.affected == setOf("worker") })
        f.organisms.project(before) // A delayed projection must read the authoritative current name.
        val projected = f.projects.sessions("p").first { it.id == "worker" }
        assertEquals(node.name, projected.name)
        assertTrue(projected.nameManuallySet)
        assertEquals("Human name", f.store.planFor("plan")!!.milestones.single().displayName)
    }

    @Test fun workerContextCannotReadSiblingMutableReportOutsideTheParentRoute() = runTest {
        val f = Fixture(this); f.init()
        f.store.update("plan") { plan -> plan.copy(milestones = plan.milestones +
            Milestone("sibling", "Sibling", description = "Unrouted description", report = "Unrouted live draft"),
            tree = plan.tree.map { if (it.id == "root") it.copy(children = it.children + "sibling-node") else it } +
                DecisionNode("sibling-node", "Sibling", DecisionKind.STAGE, stageId = "sibling")) }
        val workerTools = f.host.session(f.host.prepareWorker(f.worker))
        val worker = workerTools.call("context", "context.get", JsonObject(emptyMap())).jsonObject
        assertEquals(listOf("stage"), worker.getValue("stages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse(worker.toString().contains("Unrouted description"))
        assertFalse(worker.toString().contains("Unrouted live draft"))
        val mergeTools = f.host.session(f.host.prepareWorker(f.worker.copy(id = "worker-merge")))
        val merge = mergeTools.call("context", "context.get", JsonObject(emptyMap()))
        assertFalse(merge.toString().contains("Unrouted live draft"))
        val parent = f.orchestrator().call("context", "context.get", JsonObject(emptyMap()))
        assertTrue(parent.toString().contains("Unrouted live draft"))
    }

    @Test fun resultReferencesSurviveCancelledDeliveryAndStayBoundedToOwnedResults() = runTest {
        val f = Fixture(this, managed = true); f.init()
        val organisms = f.organisms!!
        val initial = organisms.ensure(f.parent)
        val parentTools = f.orchestrator()
        val authority = organisms.authority(parentTools.context, initial)
        for (id in listOf("a", "b")) organisms.store.command(authority, id, OrganismCommand(OrganismAction.CREATE,
            name = "Child $id", tokens = 1_000, task = SessionTask("Investigate", "parent", "Verified findings")))
        for (index in 0 until 30) organisms.store.recordResult(initial.id,
            SessionResult("bulk-$index", "session-a", 1, "parent", "Private result body $index", sourceVersion = "source", commitSha = "sha"))
        organisms.store.recordResult(initial.id, SessionResult("cancelled-result", "session-a", 1, "parent",
            "Private accepted body", evidence = listOf("Private checks"), sourceVersion = "source", commitSha = "sha"))
        organisms.store.recordResult(initial.id, SessionResult("sibling-result", "session-b", 1, "parent",
            "Private sibling body", sourceVersion = "source-b", commitSha = "sha-b"))
        organisms.store.observe(initial.id, "session-a", 1, SessionObservedState.COMPLETED)
        organisms.store.command(authority, "accept", OrganismCommand(OrganismAction.REVIEW_RESULT, "session-a",
            reason = "Verified", resultId = "cancelled-result", accepted = true, sourceVersion = "source", checks = listOf("Passed")))
        organisms.project(organisms.store.quarantine(initial.id, "session-a", 1, "unknown", "Reconcile unrelated native effect"))
        val saved = organisms.store.get(initial.id)
        assertEquals(SessionDeliveryState.CANCELLED, saved.outbox.single { it.packet.resultIds == listOf("cancelled-result") }.state)
        assertEquals(SessionDeliveryState.ACCEPTED, saved.outbox.single { it.packet.resultIds == listOf("sibling-result") }.state)
        assertTrue(f.projects.messages("p", "parent").isEmpty())
        val parent = parentTools.call("references", "context.get", JsonObject(emptyMap())).jsonObject
        val references = parent.getValue("resultReferences").jsonArray
        assertEquals(30, references.size)
        assertEquals(JsonPrimitive(2), parent["resultReferencesOmitted"])
        val accepted = references.single { it.jsonObject["id"] == JsonPrimitive("cancelled-result") }.jsonObject
        assertEquals(setOf("id", "sourceSessionId", "generation", "accepted", "sourceVersion", "commitSha"), accepted.keys)
        assertEquals(JsonPrimitive(true), accepted["accepted"])
        assertTrue(references.any { it.jsonObject["id"] == JsonPrimitive("sibling-result") })
        assertFalse(parent.toString().contains("Private"))
        val child = f.projects.sessions("p").first { it.id == "session-a" }
        val childContext = f.host.session(ToolExecutionContext.worker(child)).call("references", "context.get", JsonObject(emptyMap())).jsonObject
        assertEquals(JsonPrimitive(1), childContext["resultReferencesOmitted"])
        assertTrue(childContext.getValue("resultReferences").jsonArray.any { it.jsonObject["id"] == JsonPrimitive("cancelled-result") })
        assertFalse(childContext.toString().contains("sibling-result"))
        val immunity = f.projects.sessions("p").first { it.id == initial.immunityId }
        val supervisory = f.host.session(ToolExecutionContext.worker(immunity)).call("references", "context.get", JsonObject(emptyMap())).jsonObject
        assertEquals(references, supervisory["resultReferences"])
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

    @Test fun failedRevisionKeepsSelectivePauseUntilAnExplicitUserResume() = runTest {
        val f = Fixture(this); f.init()
        f.store.update("plan") { it.copy(intent = ExecutionIntent.RUN) }
        f.planningRun = { tools ->
            if (tools.context.role == ToolRole.PLANNER) error("Model connection lost")
            tools.call("pause", "stage.pause", f.args("""{"stageIds":["stage"],"reason":"Change requirements"}"""))
            assertFailsWith<IllegalStateException> {
                tools.call("refine", "plan.refine", f.args("""{"message":"Revise the plan","requiresConfirmation":true}"""))
            }
            "Не удалось подготовить предложение. Этап остаётся на паузе."
        }
        f.service.bootstrap(); runCurrent()
        f.service.send(f.parent, "Приостанови этап и измени план"); runCurrent()
        val paused = withContext(Dispatchers.Default) { withTimeout(5000) {
            f.service.states.first { it["parent"]?.inputs?.lastOrNull()?.status == OrchestrationInputStatus.DONE }.getValue("parent")
        } }
        assertEquals(OrchestrationInputStatus.DONE, paused.inputs.last().status)
        assertTrue(paused.workPauses.values.single().requiresUser)
        assertEquals(setOf("stage"), paused.pausedStages(f.store.planFor("plan")!!))
        assertTrue(f.workerCalls.isEmpty(), "A handled planning error must not restart workers")
        assertTrue(f.json.decodeFromString<OrchestrationState>(f.json.encodeToString(paused)).workPauses.values.single().requiresUser)
        f.workerRun = { awaitCancellation() }
        val latest = f.store.planFor("plan")!!
        val automatic = f.orchestrator("auto", OrchestrationInput("auto", "Resume", 0, scheduledRuleId = "rule"))
        assertFailsWith<IllegalArgumentException> {
            automatic.call("resume", "plan.control", f.args("""{"action":"resume","revision":${latest.revision}}"""))
        }
        assertTrue(f.projects.orchestration("parent")!!.workPauses.values.single().requiresUser)
        f.orchestrator("resume", OrchestrationInput("resume", "Continue", 0)).call("resume", "plan.control",
            f.args("""{"action":"resume","revision":${latest.revision}}"""))
        runCurrent()
        assertTrue(f.projects.orchestration("parent")!!.workPauses.isEmpty())
        assertEquals(1, f.workerCalls.size)
        f.execution.shutdown(); f.service.shutdown()
    }

    @Test fun aNewProposalRetainsEarlierFailedRevisionPausesUntilConfirmation() {
        val plan = Plan("plan", "p", "Goal", proposal = PlanProposal("new-proposal", "run", emptyList(), emptyList(), emptyList(), emptyList(), "Revision"))
        val state = OrchestrationState("parent", "p", workPauses = mapOf(
            "failed" to OrchestrationPause("plan", listOf("stage"), requiresUser = true),
            "current" to OrchestrationPause("plan", listOf("dependent")),
            "other" to OrchestrationPause("other-plan", listOf("other-stage"), requiresUser = true)))
        val saved = state.finishWorkPause(plan, "current")
        assertEquals("new-proposal", saved.workPauses["failed"]?.proposalId)
        assertFalse(saved.workPauses["failed"]!!.requiresUser)
        assertEquals("new-proposal", saved.workPauses["current"]?.proposalId)
        assertEquals(state.workPauses["other"], saved.workPauses["other"])
        assertEquals(OrchestrationPause("plan", listOf("stage")), Json.decodeFromString<OrchestrationPause>("""{"planId":"plan","stageIds":["stage"]}"""))
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

    @Test fun prematurePlannerFinalKeepsQuestionAliveAndContinuesAfterConfirmedAnswer() = runTest {
        val f = Fixture(this); f.init()
        val contexts = mutableListOf<ToolExecutionContext>()
        f.planningRun = { tools ->
            contexts += tools.context
            if (contexts.size == 1) {
                coroutineScope {
                    val call = launch { tools.call("2", "questionnaire", f.args("""{"questions":[{"id":"source","title":"Источник?","kind":"TEXT"}]}""")) }
                    f.host.questions.requests.first { it.isNotEmpty() }
                    // The native turn closes a still-running exec cell and its MCP request.
                    call.cancelAndJoin()
                }
                "После ответа передам план"
            } else {
                tools.call("2", "plan.propose", f.args("""{"reply":"Ответ учтён","tree":[],"milestones":[]}"""))
                "Готово"
            }
        }
        val result = async { f.composer.completePlanning(f.store.planFor("plan")!!, f.profile,
            listOf(LlmMessage(LlmChatRole.USER, "Review"))) {} }
        runCurrent()
        val question = f.host.questions.requests.value.single()
        advanceTimeBy(3_600_000); runCurrent()
        assertTrue(result.isActive)
        assertEquals(question, f.host.questions.requests.value.single())
        assertEquals(1, contexts.size)
        f.host.questions.respond(question.id, listOf(PlanningAnswer("source", text = "Существующее подключение")))
        assertContains(result.await(), "Ответ учтён")
        assertEquals(2, contexts.size)
        assertContains(f.planningPrompts.last(), "Существующее подключение")
        assertContains(f.planningPrompts.last(), "Источник?")
        assertNotEquals(contexts[0].requestId, contexts[1].requestId)
        assertTrue(f.host.questions.requests.value.isEmpty())
    }

    @Test fun plannerCannotProposeBeforeAnswerAndNormalQuestionNeedsNoRestart() = runTest {
        val f = Fixture(this); f.init()
        var turns = 0
        f.planningRun = { tools ->
            turns++
            coroutineScope {
                val answer = async { tools.call("ask", "questionnaire", f.args("""{"questions":[{"id":"q","title":"Источник?","kind":"TEXT"}]}""")) }
                f.host.questions.requests.first { it.isNotEmpty() }
                assertFailsWith<IllegalStateException> {
                    tools.call("early", "plan.propose", f.args("""{"reply":"Слишком рано"}"""))
                }
                assertContains(answer.await().toString(), "Мой ответ")
                tools.call("ready", "plan.propose", f.args("""{"reply":"Ответ учтён"}"""))
            }
            "Готово"
        }
        val result = async { f.composer.completePlanning(f.store.planFor("plan")!!, f.profile,
            listOf(LlmMessage(LlmChatRole.USER, "Review"))) {} }
        runCurrent()
        f.host.questions.respond(f.host.questions.requests.value.single().id, listOf(PlanningAnswer("q", text = "Мой ответ")))
        assertContains(result.await(), "Ответ учтён")
        assertEquals(1, turns)
        assertTrue(f.host.questions.requests.value.isEmpty())
    }

    @Test fun stoppingPlanningStillCancelsQuestionAfterPrematureNativeFinal() = runTest {
        val f = Fixture(this); f.init()
        f.planningRun = { tools ->
            coroutineScope {
                val call = launch { tools.call("ask", "questionnaire", f.args("""{"questions":[{"id":"q","title":"Источник?","kind":"TEXT"}]}""")) }
                f.host.questions.requests.first { it.isNotEmpty() }
                call.cancelAndJoin()
            }
            "Жду ответ"
        }
        val result = launch { f.composer.completePlanning(f.store.planFor("plan")!!, f.profile,
            listOf(LlmMessage(LlmChatRole.USER, "Review"))) {} }
        runCurrent()
        assertEquals(1, f.host.questions.requests.value.size)
        result.cancelAndJoin()
        assertTrue(f.host.questions.requests.value.isEmpty())
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
        val backing = MemoryToolReceiptStore()
        var failCompletion = true
        val receipts = object : ToolReceiptStore by backing {
            override suspend fun save(receipt: ToolReceipt) {
                if (receipt.phase == ToolPhase.SUCCEEDED && failCompletion) {
                    failCompletion = false
                    error("Injected final receipt persistence failure")
                }
                backing.save(receipt)
            }
        }
        val f = Fixture(this, receipts); f.init()
        val args = f.args("""{"stageId":"stage","message":"Once"}""")
        assertFailsWith<IllegalStateException> { f.orchestrator().call("send", "stage.send", args) }
        val receipt = f.host.receipts.forRequest("p/parent/request").single()
        assertEquals(ToolPhase.UNKNOWN, receipt.phase)
        val expected = buildJsonObject { put("status", "queued"); put("deliveryId", f.store.planFor("plan")!!.deliveries.single().id) }
        assertEquals(expected, f.orchestrator().call("send", "stage.send", args))
        assertEquals(1, f.store.planFor("plan")!!.deliveries.size)
        assertEquals(ToolPhase.SUCCEEDED, f.host.receipts.get(receipt.id)!!.phase)
    }

    @Test fun proposalConfirmationWorkerHandoffAndVerificationShareToolsAndSeparateHistories() = runTest {
        val f = Fixture(this); f.init()
        f.store.update("plan") { it.copy(confirmedRevision = null,
            milestones = it.milestones.map { stage -> stage.copy(attempts = emptyList()) }) }
        val roles = mutableListOf<ToolRole>()
        val proposalGate = CompletableDeferred<Unit>()
        f.planningRun = { tools ->
            roles += tools.context.role
            if (tools.context.role == ToolRole.ORCHESTRATOR) {
                tools.call("context", "context.get", JsonObject(emptyMap()))
                tools.call("refine", "plan.refine", f.args("""{"message":"Create a plan","requiresConfirmation":true}"""))
                "План подготовлен"
            } else {
                tools.call("read-context", "context.get", JsonObject(emptyMap()))
                proposalGate.await()
                tools.call("proposal", "plan.propose", f.args("""{"reply":"Предлагаю выполнить этап","tree":[{"id":"root","title":"Goal","kind":"GOAL","children":["node"]},{"id":"node","title":"Stage","kind":"STAGE","stageId":"stage"}],"milestones":[{"id":"stage","title":"Stage","description":"Implement the change","acceptance":"Tests pass"}]}"""))
                "Предложение готово"
            }
        }
        f.service.bootstrap(); runCurrent()
        f.service.send(f.parent, "Составь план изменения")
        runCurrent()
        assertEquals(listOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER), roles)
        val liveSteps = f.service.drafts.value["parent"]!!.steps
        assertTrue(liveSteps.any { it.tool == "plan.refine" && it.running }, "Nested planning must retain the parent command card")
        assertEquals(2, liveSteps.count { it.tool == "context.get" })
        val liveCalls = liveSteps.filter { it.toolCategory != null }.map { it.callId }
        advanceTimeBy(125_000); runCurrent()
        assertTrue(f.service.drafts.value["parent"]!!.steps.any { it.tool == "plan.refine" && it.running })
        proposalGate.complete(Unit); runCurrent()
        val saved = f.store.planFor("plan")!!
        assertNull(saved.confirmedRevision)
        assertEquals(ExecutionIntent.STOP, saved.intent)
        assertEquals("Implement the change", saved.milestones.first { it.id == "stage" }.description)
        assertTrue(saved.milestones.all { it.attempts.isEmpty() })
        val history = f.projects.messages("p", "parent")
        assertTrue(history.any { it.text.contains("План подготовлен") }, history.toString())
        assertTrue(history.flatMap { it.steps }.any { it.tool == "plan.refine" && !it.running && it.ok })
        val savedCalls = history.flatMap { it.steps }.filter { it.toolCategory != null }.map { it.callId }
        assertTrue(savedCalls.containsAll(liveCalls), "Saving nested planning must preserve tool identities")
        assertEquals(savedCalls.distinct(), savedCalls)
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
