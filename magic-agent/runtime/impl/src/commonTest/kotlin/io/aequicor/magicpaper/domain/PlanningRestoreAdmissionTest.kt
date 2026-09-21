package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningRestoreAdmissionTest {
    @Test fun restoredRunStopAndDueMessagesPerformNoNativeModelWorkspaceOrDeliveryCalls() = runTest {
        val storage = InMemoryKeyValueStore(); val repo = JsonPlanningRepository(storage, Json)
        val legacy = Plan("running", "project", "Goal", parentSessionId = "parent", intent = ExecutionIntent.RUN, runId = "saved-run", confirmedRevision = 0,
            milestones = listOf(Milestone("stage", "Stage", description = "Done", agentProfileId = "agent")))
            .applyScheduleCommands(listOf(ScheduleCommand(trigger = MessageTrigger(MessageTriggerKind.AT_TIME, at = 1), text = "Due")),
                "scheduled", "orchestrator", emptySet(), 0).advanceScheduledMessages(2)
        assertEquals(ScheduledMessageStatus.READY, legacy.scheduledMessages.single().status)
        repo.save(legacy)
        repo.save(legacy.copy(id = "stopping", stopping = true, intent = ExecutionIntent.STOP, scheduledMessages = emptyList(),
            milestones = listOf(Milestone("stage", "Stage", attempts = listOf(StageAttempt("old-attempt", "old-worker", StageAssignment("agent", "model"), phase = AttemptPhase.EXECUTING))))))
        val owner = DefaultPlanningStore(repo, InMemoryEventJournal())
        var native = 0; var model = 0; var workspace = 0; var delivery = 0
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override suspend fun reconcile(sessionId: String) { native++ }
            override suspend fun preflight(engine: CodingEngine, profile: LlmProfile) { native++ }
        }
        val workspaces = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun prepare(project: CodingProject, runId: String, operation: WorkspaceOperation): PlanWorkspace {
                workspace++; return PlanWorkspace(project.path, project.path)
            }
        }
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                model++; return Verdict(true, "Checked")
            }
        }
        val projects = journalCodingProjects(storage, Json).also { it.createTestProject(CodingProject("project", "Project", "/fake", 1)) }
        val profiles = JsonLlmProfileRepository(storage, Json).also { it.save(LlmProfile("agent", "Agent", baseUrl = "http://test", modelId = "model")) }
        val executionPorts = TestPlanningExecutionPorts()
        val execution = PlanningExecutionService(owner, runtime, projects, profiles,
            JsonSettingsRepository(storage, Json), verifier, workspaces, backgroundScope,
            attemptAuthority = executionPorts, chatHooksProvider = { executionPorts.chatHooks })
        val messages = MessageScheduler(owner, backgroundScope, { _, _ -> delivery++; true }, { error(it) })
        execution.bootstrap(); messages.bootstrap()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(0, native); assertEquals(0, model); assertEquals(0, workspace); assertEquals(0, delivery)
        assertNull(owner.currentAdmission("running")); assertNull(owner.currentAdmission("stopping"))
        assertTrue(checkNotNull(owner.planFor("stopping")).stopping)
        execution.shutdown(); messages.pauseForReset()
    }
}
