package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningPeerContextTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = "shared/PaperChatComponents.kt"
    private val profile = LlmProfile("model", "Model", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
    private fun owner(plan: Plan, archived: Boolean = false) = CodingSession(plan.parentSessionId, plan.projectId, plan.goal, 1,
        archived = archived, planningMode = true, role = CodingSessionRole.ORCHESTRATOR, modelSelection = ModelSelection(profile.id, "m"))
    private fun sample(id: String, text: String = "Checked source"): Plan {
        val reply = StageReply(StageReplyKind.RESULT, text, changedFiles = listOf(file))
        val attempt = StageAttempt("$id-attempt", "$id-worker", StageAssignment(profile.id, "m"), phase = AttemptPhase.EXECUTING,
            path = "/shared", report = json.encodeToString(StageReply.serializer(), reply))
        val stage = Milestone("$id-stage", "$id stage", description = "Change source", acceptance = "Checks pass", attempts = listOf(attempt))
        return Plan(id, "project", "$id goal", parentSessionId = "$id-parent", runId = "$id-run", confirmedRevision = 1,
            sharedWorkspace = true, intent = ExecutionIntent.RUN, phase = ExecutionPhase.EXECUTING, status = PlanStatus.RUNNING,
            milestones = listOf(stage), coordination = listOf(CoordinationRecord("${attempt.id}-turn-0", stage.id, reply,
                runId = "$id-run", attemptId = attempt.id, sourceSessionId = attempt.sessionId)))
    }
    private fun overlap(target: Plan, source: Plan, record: CoordinationRecord = source.coordination.single()): PlanDelivery =
        PlanDelivery("overlap-${target.milestones.first().id}-${record.id}", source.parentSessionId, target.milestones.first().id,
            "Обнаружено пересечение файлов с планом ${source.goal}: [$file]. Перечитай фактическое состояние, согласуй изменения без отката чужих файлов и повтори проверки. Не повторяй уже выполненные правки.")

    private inner class Fixture(scope: TestScope) {
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = """{"reply":"Передаю результат на проверку","resultAction":"VERIFY"}"""
        }
        val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
            object : MilestoneVerifier { override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked") },
            scope = scope.backgroundScope)
        val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway,
            scope.backgroundScope, workerDispatcher = StandardTestDispatcher(scope.testScheduler))
        suspend fun initialize(vararg plans: Plan) {
            projects.save(CodingProject("project", "Project", "/shared", 1)); profiles.save(profile)
            settings.save(AppSettings(activeLlmProfileId = profile.id))
            for (plan in plans) {
                projects.saveSession(owner(plan)); store.save(plan)
                for (stage in plan.milestones) projects.saveSession(CodingSession(stage.attempts.firstOrNull()?.sessionId ?: "plan-${plan.id}-stage-${stage.id}",
                    plan.projectId, stage.title, 1, planId = plan.id, parentSessionId = plan.parentSessionId, stageId = stage.id, role = CodingSessionRole.WORKER))
            }
        }
        suspend fun finish(planId: String): StageTurnDecision {
            val plan = store.planFor(planId)!!
            return service.finished(plan, plan.milestones.first(), plan.milestones.first().attempts.last())
        }
    }

    @Test fun onlyActiveOwnersSharingTheWorkingFolderExchangeReports() {
        val source = sample("source"); val peer = sample("peer")
        assertTrue(source.sharesPeerWorkspace(peer))
        assertTrue(peer.acceptsPeerContext(listOf(owner(peer))))
        assertFalse(peer.acceptsPeerContext(emptyList()))
        assertFalse(peer.acceptsPeerContext(listOf(owner(peer, archived = true))))
        for (blocked in listOf(peer.copy(intent = ExecutionIntent.STOP), peer.copy(intent = ExecutionIntent.PAUSE),
            peer.copy(stopping = true), peer.copy(confirmedRevision = null), peer.copy(phase = ExecutionPhase.COMPLETE)))
            assertFalse(blocked.acceptsPeerContext(listOf(owner(peer))))
        assertFalse(source.sharesPeerWorkspace(peer.copy(sharedWorkspace = false)))
        assertFalse(source.copy(workspace = PlanWorkspace("/shared", "/one")).sharesPeerWorkspace(peer.copy(workspace = PlanWorkspace("/shared", "/two"))))
    }

    @Test fun currentContextContainsOneLatestResultPerCurrentAttemptAndRun() {
        val base = sample("source")
        val current = base.coordination.single().copy(id = "source-attempt-turn-1", turnIndex = 1, reply = StageReply(StageReplyKind.RESULT, "Latest", changedFiles = listOf(file)))
        val plan = base.copy(milestones = base.milestones.map { it.copy(attempts = it.attempts.map { a -> a.copy(turnIndex = 1) }) },
            coordination = base.coordination + current.copy(id = "old-turn-0", runId = "old", attemptId = "old") + current)
        assertEquals(listOf(current), plan.currentPeerResults())
    }

    @Test fun repeatedPeerResultsStayContextAndNeverReopenWorkOrMultiplyMessages() = runTest {
        val source = sample("source")
        val issue = PlanningIssue(IssueKind.UNCERTAIN, "Await explicit recovery", requiresUser = true)
        val peer = sample("peer").copy(issue = issue, finalAttempt = StageAttempt("final", "final-worker", StageAssignment(profile.id, "m"), phase = AttemptPhase.VERIFYING))
        val f = Fixture(this); f.initialize(source, peer)
        val peerBefore = f.store.planFor(peer.id)!!
        repeat(5) { turn ->
            f.store.update(source.id) { p -> p.copy(milestones = p.milestones.map { stage -> stage.copy(attempts = stage.attempts.map { a -> a.copy(
                turnIndex = turn, report = json.encodeToString(StageReply.serializer(), StageReply(StageReplyKind.RESULT, "Checked revision $turn", changedFiles = listOf(file)))) }) }) }
            assertEquals(StageTurnAction.VERIFY, f.finish(source.id).action)
            assertTrue(f.store.planFor(source.id)!!.deliveries.isEmpty())
            assertEquals(peerBefore, f.store.planFor(peer.id))
        }
        val observations = f.projects.messages(peer.projectId, peer.parentSessionId).filter { it.id.startsWith("peer-context:") }
        assertEquals(1, observations.size)
        assertContains(observations.single().text, "Checked revision 4")
        assertNull(observations.single().deliveryId)
        assertEquals(StageTurnAction.VERIFY, f.finish(peer.id).action)
        assertTrue(f.store.plans().all { it.deliveries.isEmpty() && it.milestones.size == 1 })
    }

    @Test fun archivedSourceOrRecipientNeverSendsOrReceivesAutomaticContext() = runTest {
        for (archiveSource in listOf(false, true)) {
            val source = sample("source"); val peer = sample("peer")
            val f = Fixture(this); f.initialize(source, peer)
            f.projects.saveSession(owner(if (archiveSource) source else peer, archived = true))
            assertEquals(StageTurnAction.VERIFY, f.finish(source.id).action)
            assertTrue(f.projects.messages(peer.projectId, peer.parentSessionId).none { it.id.startsWith("peer-context:") })
            assertTrue(f.store.plans().all { it.deliveries.isEmpty() })
        }
    }

    @Test fun queuedHistoricalOverlapCommandsAreCancelledWithoutTouchingExplicitOrDeliveredWork() {
        val target = sample("target"); val source = sample("source")
        val generated = (0..12).map { i -> source.coordination.single().copy(id = "source-attempt-turn-$i") }
        val peer = source.copy(coordination = generated)
        val queued = generated.map { overlap(target, peer, it) }
        val explicit = PlanDelivery("user-command", target.parentSessionId, target.milestones.single().id, "Check that file again")
        val delivered = overlap(target, peer, generated.last().copy(id = "source-attempt-turn-14")).copy(state = DeliveryState.DELIVERED)
        val changed = queued.first().copy(id = "overlap-${target.milestones.single().id}-fake", text = "User instruction")
        val plan = target.copy(deliveries = queued + listOf(explicit, delivered, changed))
        val migrated = plan.cancelLegacyPeerCommands(listOf(peer))
        assertEquals(13, migrated.deliveries.count { it.state == DeliveryState.CANCELLED })
        assertEquals(listOf(explicit, delivered, changed), migrated.deliveries.takeLast(3))
        assertEquals(plan.coordination, migrated.coordination)
        assertEquals(plan.milestones, migrated.milestones)
        assertEquals(migrated, migrated.cancelLegacyPeerCommands(listOf(peer)))
    }

    @Test fun untouchedSyntheticFollowupIsRemovedButEditedOrStartedWorkRemains() {
        for (edit in 0..2) {
            val target = sample("target"); val source = sample("source")
            val base = target.milestones.single()
            val command = overlap(target, source)
            val id = "${base.id}-followup-${command.id}"
            val generated = base.copy(id = id, continuationOf = base.id, description = command.text, attempts = emptyList(), dependsOn = listOf(base.id),
                report = "", checkNote = "", displayNumber = 2).let { if (edit == 1) it.copy(description = "User changed this task") else if (edit == 2) it.copy(attempts = base.attempts) else it }
            val tree = listOf(DecisionNode("root", "Root", DecisionKind.GOAL, listOf(base.id, id)),
                DecisionNode(base.id, base.title, DecisionKind.STAGE, stageId = base.id), DecisionNode(id, generated.title, DecisionKind.STAGE, stageId = id))
            val plan = target.copy(milestones = listOf(base, generated), tree = tree, deliveries = listOf(command.copy(targetStageId = id)))
            val migrated = plan.cancelLegacyPeerCommands(listOf(source))
            assertEquals(DeliveryState.CANCELLED, migrated.deliveries.single().state)
            assertEquals(if (edit == 0) listOf(base) else plan.milestones, migrated.milestones)
            assertTrue(DecisionCompiler.compile(migrated).valid)
        }
    }

    @Test fun oldInformationalPeerCommandsRequireExactSourceRecordAndPayload() {
        val source = sample("source"); val target = sample("target")
        val report = source.coordination.single(); val sourceStage = source.milestones.single(); val stage = target.milestones.single()
        val info = "План «${source.goal}», этап «${sourceStage.title}»: ${report.reply.text}\nИзменённые файлы: ${report.reply.changedFiles.joinToString()}"
        val delivery = PlanDelivery("${source.id}-${sourceStage.id}-${report.reply.hashCode()}-peer-${stage.id}", source.parentSessionId, stage.id,
            "Сведения соседнего плана. Перед продолжением перечитай затронутые файлы, не перезаписывай чужие изменения. $info")
        assertEquals(DeliveryState.CANCELLED, target.copy(deliveries = listOf(delivery)).cancelLegacyPeerCommands(listOf(source)).deliveries.single().state)
        for (changed in listOf(delivery.copy(text = delivery.text + " Explicit followup"), delivery.copy(sourceRunId = source.runId),
            delivery.copy(sourceSessionId = "user"), delivery.copy(state = DeliveryState.DELIVERED))) {
            assertEquals(changed, target.copy(deliveries = listOf(changed)).cancelLegacyPeerCommands(listOf(source)).deliveries.single())
        }
    }

    private fun syntheticContinuation(): Pair<Plan, Plan> {
        val target = sample("target"); val source = sample("source")
        val old = target.coordination.single()
        val issue = PlanningIssue(IssueKind.UNCERTAIN, "Native ownership must be reconciled", requiresUser = true)
        val stage = target.milestones.single()
        val attempt = stage.attempts.single().copy(turnIndex = 1, report = old.reply.text, sessionGeneration = 1,
            error = issue, interrupted = true, verificationSnapshot = "snapshot", coordinationPending = false,
            chatTurns = listOf(StageChatTurn(0, 1, 2), StageChatTurn(0, 3)))
        val record = old.copy(status = HandoffStatus.RESOLVED, nextStep = "Исполнителю назначено продолжение",
            decision = CoordinatorReply("Verify result", resultAction = CoordinatorResultAction.VERIFY, toolsApplied = true))
        return target.copy(milestones = listOf(stage.copy(attempts = listOf(attempt))), coordination = listOf(record),
            deliveries = listOf(overlap(target, source)), issue = issue) to source
    }

    @Test fun onlyUnadmittedSyntheticContinuationRestoresSavedVerificationWithoutClearingRecoveryAuthority() {
        val (target, source) = syntheticContinuation()
        val stage = target.milestones.single(); val attempt = stage.attempts.single()
        val binding = SessionLegacyAttempt(target.id, target.runId, stage.id, attempt.id, 0, 1)
        val cancelled = target.cancelLegacyPeerCommands(listOf(source))
        assertEquals(attempt, cancelled.milestones.single().attempts.single(), "Queue cleanup alone cannot prove native admission")
        val restored = cancelled.cancelLegacyPeerCommands(listOf(source), mapOf(attempt.sessionId to binding))
        assertEquals(attempt.copy(phase = AttemptPhase.VERIFYING), restored.milestones.single().attempts.single())
        assertEquals(target.issue, restored.issue)
        assertEquals(target.coordination, restored.coordination)
        assertEquals(DeliveryState.CANCELLED, restored.deliveries.single().state)
        assertEquals(restored, restored.cancelLegacyPeerCommands(listOf(source), mapOf(attempt.sessionId to binding)))
    }

    @Test fun realWorkOrUnprovenResultNeverRestoresVerificationFromAnOldOverlapCommand() {
        val (target, source) = syntheticContinuation()
        val stage = target.milestones.single(); val attempt = stage.attempts.single(); val record = target.coordination.single()
        val binding = SessionLegacyAttempt(target.id, target.runId, stage.id, attempt.id, 0, 1)
        fun changedAttempt(change: (StageAttempt) -> StageAttempt) = target.copy(milestones = listOf(stage.copy(attempts = listOf(change(attempt)))))
        val cases = listOf(
            target to binding.copy(turnIndex = 1), target to binding.copy(generation = 2), target to binding.copy(runId = "old-run"),
            target.copy(deliveries = target.deliveries + PlanDelivery("user", "user", stage.id, "Make another change")) to binding,
            target.copy(deliveries = target.deliveries + PlanDelivery("model", target.parentSessionId, stage.id, "Do work", DeliveryState.DELIVERED, attempt.id, 1)) to binding,
            target.copy(coordination = listOf(record.copy(status = HandoffStatus.FAILED))) to binding,
            target.copy(coordination = listOf(record.copy(decision = record.decision!!.copy(resultAction = CoordinatorResultAction.CONTINUE)))) to binding,
            target.copy(coordination = listOf(record.copy(decision = record.decision!!.copy(actions = listOf(CoordinatorAction(stage.id, "Repair")))))) to binding,
            changedAttempt { it.copy(report = "New native output") } to binding,
            changedAttempt { it.copy(pendingTool = "running", pendingToolExternal = true) } to binding,
            changedAttempt { it.copy(chatTurns = it.chatTurns.dropLast(1) + it.chatTurns.last().copy(completedAt = 4)) } to binding,
            changedAttempt { it.copy(steps = listOf(CodingStep(CodingStepKind.EXEC, "New command", result = "output"))) } to binding,
        )
        for ((plan, admitted) in cases) {
            val migrated = plan.cancelLegacyPeerCommands(listOf(source), mapOf(attempt.sessionId to admitted))
            assertEquals(plan.milestones, migrated.milestones)
            assertEquals(DeliveryState.CANCELLED, migrated.deliveries.first().state)
        }
    }

    @Test fun bootAndInstructionsClearSyntheticQueueWithoutErasingHistoricalCards() = runTest {
        val source = sample("source"); val target = sample("target")
        val delivery = overlap(target, source)
        val f = Fixture(this); f.initialize(source, target.copy(deliveries = listOf(delivery)))
        f.projects.saveSession(owner(source, archived = true))
        val workerId = target.milestones.single().attempts.single().sessionId
        val card = CodingMessage(delivery.id, CodingRole.USER, delivery.text, createdAt = 1, deliveryId = delivery.id, pendingDelivery = true)
        f.projects.saveMessages(target.projectId, workerId, listOf(card))
        f.service.bootstrap(); f.service.awaitReady(); runCurrent()
        val saved = f.store.planFor(target.id)!!
        assertEquals(DeliveryState.CANCELLED, saved.deliveries.single().state)
        assertEquals(card.copy(pendingDelivery = false), f.projects.messages(target.projectId, workerId).single { it.id == card.id })
        val prompt = f.service.instructions(saved, saved.milestones.single(), saved.milestones.single().attempts.single())
        assertFalse(prompt.contains("Обнаружено пересечение файлов"))
        assertEquals(1, saved.milestones.size)
    }
}
