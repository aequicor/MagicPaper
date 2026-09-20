package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.JsonPlanningRepository
import io.aequicor.magicpaper.data.planning.PlanningStore
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.data.storage.JsonLlmProfileRepository
import io.aequicor.magicpaper.data.storage.JsonSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningCheckpointReloadTest {
    @Test fun verificationRestoredWhilePreparingInstructionsNeverReplaysTheWorker() = runTest {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val profile = LlmProfile("agent", "Agent", baseUrl = "http://test/v1", modelId = "m", favoriteModels = listOf("m"), modelLibraryVersion = 1)
        val profiles = JsonLlmProfileRepository(kv, json).also { it.save(profile) }
        val projects = JsonCodingProjectRepository(kv, json).also { it.save(CodingProject("project", "Project", "/fake", 1)) }
        val nativeCalls = mutableListOf<String>()
        val runtime = object : CodingRuntime by NoopCodingRuntime {
            override val supported = true
            override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
            override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) =
                flowOf(CodingEvent.Failed("Unexpected worker replay")).also { nativeCalls += session.id }
        }
        val verification = CompletableDeferred<String>()
        val holdVerification = CompletableDeferred<Unit>()
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?): Verdict {
                verification.complete(report)
                holdVerification.await()
                return Verdict(true, "Checked")
            }
        }
        val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun verificationSnapshot(path: String) = "snapshot"
        }
        val service = PlanningExecutionService(store, runtime, projects, profiles, JsonSettingsRepository(kv, json), verifier, workspace, backgroundScope)
        var admissions = 0
        service.prepareAttempt = { _, _, attempt -> admissions++; attempt }
        service.chatHooks = object : PlanningExecutionHooks {
            override suspend fun prepareSessions(plan: Plan) = Unit
            override suspend fun instructions(plan: Plan, stage: Milestone, attempt: StageAttempt): String {
                store.update(plan.id) { current -> current.copy(milestones = current.milestones.map { milestone ->
                    if (milestone.id != stage.id) milestone else milestone.copy(attempts = milestone.attempts.map {
                        if (it.id == attempt.id) it.copy(phase = AttemptPhase.VERIFYING) else it
                    })
                }) }
                return "Obsolete synthetic continuation removed"
            }
            override suspend fun finished(plan: Plan, stage: Milestone, attempt: StageAttempt): StageTurnDecision = error("No worker replay expected")
        }
        val attempt = StageAttempt("attempt", "worker", StageAssignment(profile.id, "m"), phase = AttemptPhase.EXECUTING,
            path = "/fake", turnIndex = 1, report = "Completed worker result", sessionGeneration = 1,
            verificationSnapshot = "snapshot", coordinationPending = false, chatTurns = listOf(StageChatTurn(0, 1, 2)))
        val stage = Milestone("stage", "Stage", description = "Check result", agentProfileId = profile.id, attempts = listOf(attempt))
        val plan = Plan("plan", "project", "Goal", milestones = listOf(stage), runId = "run", intent = ExecutionIntent.RUN,
            confirmedRevision = 1, phase = ExecutionPhase.EXECUTING, workspace = PlanWorkspace("/fake", "/fake"))
        store.save(plan)
        service.start(plan.id); advanceTimeBy(1_000); runCurrent()
        val saved = store.planFor(plan.id)!!.milestones.single().attempts.single()
        assertTrue(verification.isCompleted, "The restored result must reach verification")
        assertContains(verification.await(), attempt.report)
        assertEquals(AttemptPhase.VERIFYING, saved.phase)
        assertEquals(attempt.chatTurns, saved.chatTurns)
        assertEquals(attempt.turnIndex, saved.turnIndex)
        assertEquals(attempt.sessionGeneration, saved.sessionGeneration)
        assertEquals(0, admissions)
        assertTrue(nativeCalls.isEmpty())
        assertTrue(store.planFor(plan.id)!!.journal.none { it.operation == "agent-intent" })
        service.shutdown()
    }
}
