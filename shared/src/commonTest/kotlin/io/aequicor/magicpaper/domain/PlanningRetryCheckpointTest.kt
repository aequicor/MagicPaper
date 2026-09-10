package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningRetryCheckpointTest {
    private val issue = PlanningIssue(IssueKind.UNCERTAIN, "Previous native run must be reconciled", requiresUser = true)
    private val parent = CodingSession("parent", "project", "Plan", 1, planningMode = true, role = CodingSessionRole.ORCHESTRATOR)
    private val attempt = StageAttempt("attempt", "worker", StageAssignment("model", "m"), phase = AttemptPhase.EXECUTING,
        error = issue, sessionGeneration = 1, turnIndex = 0)
    private val stage = Milestone("stage", "Stage", attempts = listOf(attempt))
    private val plan = Plan("plan", "project", "Goal", parentSessionId = parent.id, runId = "run", confirmedRevision = 1,
        intent = ExecutionIntent.RUN, phase = ExecutionPhase.WAITING, issue = issue, milestones = listOf(stage))

    private inner class Fixture(scope: TestScope) {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        private val delegate = JsonCodingProjectRepository(kv, json)
        var onMessages: suspend (List<CodingMessage>) -> Unit = {}
        val projects = object : CodingProjectRepository by delegate {
            override suspend fun saveMessages(projectId: String, sessionId: String, messages: List<CodingMessage>) {
                delegate.saveMessages(projectId, sessionId, messages)
                onMessages(messages)
            }
        }
        private val profiles = JsonLlmProfileRepository(kv, json)
        private val settings = JsonSettingsRepository(kv, json)
        private val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("No model work expected")
        }
        val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
            object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict = error("No verification expected")
            }, scope = scope.backgroundScope)
        val service = OrchestrationService(store, execution, projects, profiles, settings, PlanComposer(gateway), gateway,
            scope.backgroundScope, workerDispatcher = StandardTestDispatcher(scope.testScheduler))
        var authorizations = 0
        suspend fun init() {
            projects.save(CodingProject("project", "Project", "/fake", 1))
            projects.saveSession(parent)
            projects.saveSession(CodingSession("worker", "project", "Worker", 1, planId = plan.id, parentSessionId = parent.id, stageId = stage.id))
            store.save(plan)
            execution.authorizeRetry = { _, _, _ -> authorizations++; null }
            // Assert the durable retry boundary without dispatching a worker in this fixture.
            execution.shutdown()
        }
        suspend fun request(): UserInteractionRequest {
            val saved = store.planFor(plan.id)!!
            val ids = saved.blockingIssues(emptyList()).map { it.messageId }.sorted().joinToString(":")
            return UserInteractionRequest("blocker:$ids", plan.projectId, parent.id, InteractionKind.RECOVER_PLAN,
                listOf(PlanningQuestion("decision", "Retry?", QuestionKind.SINGLE, listOf(QuestionOption("retry", "Retry")), canSkip = false)), planId = plan.id)
        }
    }

    @Test fun customAnswerCannotQueueInstructionsIntoANewerAttemptAfterHistoryPublicationSuspends() = runTest {
        val f = Fixture(this); f.init(); val request = f.request()
        var newer: Plan? = null
        f.onMessages = { messages ->
            if (newer == null && messages.any { it.id == request.id + "-instructions" }) {
                newer = f.store.update(plan.id) { current -> current.copy(milestones = current.milestones.map {
                    it.copy(attempts = it.attempts.map { a -> a.copy(sessionGeneration = 2, turnIndex = 1) })
                }) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            f.service.submitInteraction(request, listOf(PlanningAnswer("decision", text = "Check the repaired file")))
        }
        assertNotNull(newer)
        assertEquals(newer, f.store.planFor(plan.id))
        assertEquals(0, f.authorizations)
        assertTrue(newer!!.deliveries.isEmpty())
        assertEquals(issue, newer!!.milestones.single().attempts.single().error)
    }

    @Test fun retryFenceRejectsANewGenerationArrivingAfterCustomInstructionsWereQueued() = runTest {
        val f = Fixture(this); f.init(); val request = f.request()
        var newer: Plan? = null
        f.onMessages = { messages ->
            if (newer == null && messages.any { it.deliveryId == request.id + "-instructions-stage" }) {
                newer = f.store.update(plan.id) { current -> current.copy(intent = ExecutionIntent.STOP, issue = issue,
                    milestones = current.milestones.map { it.copy(attempts = it.attempts.map { a -> a.copy(sessionGeneration = 2, error = issue) }) }) }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            f.service.submitInteraction(request, listOf(PlanningAnswer("decision", text = "Check the repaired file")))
        }
        assertNotNull(newer)
        assertEquals(newer, f.store.planFor(plan.id))
        assertEquals(0, f.authorizations)
        assertEquals(ExecutionIntent.STOP, newer!!.intent)
    }

    @Test fun matchingCustomAnswerPreservesInstructionsAndStillAuthorizesTheOriginallyFailedAttempt() = runTest {
        val f = Fixture(this); f.init(); val request = f.request()
        f.service.submitInteraction(request, listOf(PlanningAnswer("decision", text = "Check the repaired file")))
        val resumed = f.store.planFor(plan.id)!!
        assertEquals(1, f.authorizations)
        assertEquals(ExecutionIntent.RUN, resumed.intent)
        assertEquals(ExecutionPhase.RECOVERING, resumed.phase)
        assertEquals("Check the repaired file", resumed.deliveries.single().text)
        assertEquals(DeliveryState.QUEUED, resumed.deliveries.single().state)
        assertNull(resumed.issue)
    }

    @Test fun staleRunTurnGenerationAndStopHistoryAreRejectedBeforeAnyRetryAuthorization() = runTest {
        for (change in listOf<(Plan) -> Plan>(
            { it.copy(runId = "new-run") },
            { it.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(turnIndex = 1)))) ) },
            { it.copy(milestones = listOf(stage.copy(attempts = listOf(attempt.copy(sessionGeneration = 2)))) ) },
            { it.copy(journal = it.journal + PlanJournalEntry("new-stop", 2, operation = "stop-intent")) },
        )) {
            val f = Fixture(this); f.init()
            val expected = f.store.planFor(plan.id)!!
            val current = f.store.update(plan.id, change = change)
            assertFailsWith<IllegalArgumentException> { f.execution.retry(plan.id, expected) }
            assertEquals(0, f.authorizations)
            assertEquals(current, f.store.planFor(plan.id))
        }
    }

    @Test fun authorizationSuspensionStillRequiresTheExactPlanSnapshotAtCommit() = runTest {
        val f = Fixture(this); f.init()
        val expected = f.store.planFor(plan.id)!!
        var changed: Plan? = null
        f.execution.authorizeRetry = { _, _, _ ->
            changed = f.store.update(plan.id) { it.copy(issue = issue.copy(message = "A new failure")) }
            null
        }
        assertFailsWith<IllegalStateException> { f.execution.retry(plan.id, expected) }
        assertEquals(changed, f.store.planFor(plan.id))
    }
}
