package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Model selection is exercised through the application plan owner, including its durable admission. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlanningExecutionModelSelectionTest {
    @Test fun stageRunsItsSelectedFavoriteInsteadOfTheProfileDefault() = runTest {
        assertStageModel(selectedModel = "favorite", expectedModel = "favorite")
    }

    @Test fun stageWithoutAnOverrideUsesTheAvailableProfileDefault() = runTest {
        assertStageModel(selectedModel = "", expectedModel = "default")
    }

    private suspend fun TestScope.assertStageModel(selectedModel: String, expectedModel: String) {
        val storage = InMemoryKeyValueStore()
        val json = Json { encodeDefaults = true }
        val store = TestPlanningStore(JsonPlanningRepository(storage, json))
        val profile = LlmProfile("agent", "Agent", baseUrl = "http://test/v1", modelId = "default",
            favoriteModels = listOf("default", "favorite"), modelLibraryVersion = 1)
        val profiles = JsonLlmProfileRepository(storage, json).also { it.save(profile) }
        val project = CodingProject("project", "Project", "/fake", 1)
        val projects = journalCodingProjects(storage, json).also { it.createTestProject(project) }
        val calls = mutableListOf<Pair<String?, String?>>()
        val runtime = object : CodingRuntime {
            override val supported = true
            override val rootPath = "/fake"
            override suspend fun status() = RuntimeStatus(RuntimePhase.READY)
            override fun ensureReady() = flowOf(RuntimeStatus(RuntimePhase.READY))
            override fun abort(sessionId: String) = Unit
            override fun abortAll() = Unit
            override suspend fun uninstall() = Unit
            override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>) = flow {
                calls += session.stageId to profile?.modelId
                emit(CodingEvent.FinalText("Verified result"))
                emit(CodingEvent.Finished)
            }
        }
        val verifier = object : MilestoneVerifier {
            override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
        }
        val workspace = object : PlanningWorkspace by LocalPlanningWorkspace() {
            override suspend fun verificationSnapshot(path: String, operation: WorkspaceOperation?) = "fixture-snapshot"
        }
        val executionPorts = TestPlanningExecutionPorts()
        val service = PlanningExecutionService(store, runtime, projects, profiles, JsonSettingsRepository(storage, json),
            verifier, workspace, backgroundScope,
            attemptAuthority = executionPorts, chatHooksProvider = { executionPorts.chatHooks })
        store.save(Plan("plan", project.id, "Goal", milestones = listOf(Milestone("stage", "Stage",
            description = "Verify result", agentProfileId = profile.id, agentModelId = selectedModel))))
        service.start("plan")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(expectedModel, calls.single { it.first == "stage" }.second)
        val saved = checkNotNull(store.planFor("plan"))
        assertEquals(expectedModel, saved.milestones.single().attempts.single().assignment.modelId)
        assertEquals(PlanStatus.DONE, saved.status, saved.issue?.message)
        service.shutdown()
    }
}
