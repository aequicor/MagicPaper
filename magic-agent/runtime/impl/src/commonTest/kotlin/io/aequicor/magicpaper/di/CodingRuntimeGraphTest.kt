package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.storage.JsonUsageRepository
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CodingRuntimeGraphTest {
    @Test fun realGraphBootstrapsBeforeItsDeferredPlanningPortIsUsedWithoutStartingAnAgent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var graph: CodingRuntimeGraph? = null
        try {
            val fixture = SessionOrganismTestFixture()
            fixture.initialize()
            var nativeRuns = 0
            var dispatches = 0
            val native = object : CodingRuntime by NoopCodingRuntime {
                override fun run(project: CodingProject, session: CodingSession, prompt: String,
                    profile: LlmProfile?, attachments: List<Attachment>) = flow {
                    nativeRuns++
                    emit(CodingEvent.Finished)
                }
            }
            graph = graph(fixture, native, orchestrationFactory = { actions ->
                object : CustomOrchestration {
                    override suspend fun execute(context: ToolExecutionContext, operationId: String, tool: String,
                        arguments: JsonObject): JsonElement {
                        dispatches++
                        return actions.execute(context, operationId, tool, arguments)
                    }
                }
            })
            assertNotNull(graph.runtime)
            graph.start()
            assertEquals(0, nativeRuns)
            assertEquals(0, dispatches)
            val owner = assertNotNull(graph.planningChat)
            assertSame(owner, graph.planningChat)
            val context = owner.prepareWorker(fixture.root)
            val result = graph.toolSessions.session(context).call("context", "context.get", JsonObject(emptyMap())).jsonObject
            assertEquals(fixture.root.id, result.getValue("sessionId").jsonPrimitive.content)
            assertEquals(ToolPhase.SUCCEEDED,
                graph.toolReceipts.forRequest("${context.projectId}/${context.ownerSessionId}/${context.requestId}").single().phase)
            assertEquals(0, nativeRuns)
        } finally {
            graph?.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun workspaceResetFailureStillResumesPlanningAndItsForms() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var graph: CodingRuntimeGraph? = null
        try {
            val fixture = SessionOrganismTestFixture(limits = OrganismLimits()); fixture.initialize()
            val pauseFailure = IllegalStateException("workspace pause failed")
            val resumeFailure = IllegalStateException("workspace resume failed")
            var resumes = 0
            val workspace = object : TaskWorktreeOwner by testTaskWorktreeOwner(UnavailableTaskWorkspace, fixture.journal, fixture.storage) {
                override suspend fun prepareForReset() { throw pauseFailure }
                override suspend fun resumeAfterReset() { resumes++; throw resumeFailure }
            }
            val active = graph(fixture, NoopCodingRuntime, workspace).also { graph = it }
            active.start()
            val planning = assertNotNull(active.planningChat)
            val original = planning.forms.rename(fixture.root)
            assertTrue(original.state.value.available)
            val paused = assertFailsWith<IllegalStateException> { active.pauseForReset() }
            assertTrue(generateSequence<Throwable>(paused) { it.cause }.any { it === pauseFailure })
            assertFalse(original.state.value.available)
            val resumed = assertFailsWith<IllegalStateException> { active.resumeAfterReset() }
            assertTrue(generateSequence<Throwable>(resumed) { it.cause }.any { it === resumeFailure })
            assertEquals(1, resumes)
            assertTrue(planning.forms.rename(fixture.root).state.value.available,
                "Planning must resume after an earlier child owner fails to resume")
            var entered = false
            assertNotNull(active.sessionTree).withScope(fixture.root) { entered = true }
            assertTrue(entered, "The session scope must be usable after rollback")
        } finally { graph?.close(); Dispatchers.resetMain() }
    }

    // A Git operation with no recorded outcome refuses a reset, unless the user confirmed an erase that forgets it:
    // the pause then finishes every step after the workspace owner instead of stopping there.
    @Test fun unconfirmedWorkspaceOperationRefusesResetUnlessTheUserConsentedToForgetIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var graph: CodingRuntimeGraph? = null
        try {
            val fixture = SessionOrganismTestFixture(limits = OrganismLimits()); fixture.initialize()
            var prepares = 0
            val workspace = object : TaskWorktreeOwner by testTaskWorktreeOwner(UnavailableTaskWorkspace, fixture.journal, fixture.storage) {
                override suspend fun prepareForReset() { prepares++; throw TaskWorktreeResetUnconfirmed() }
            }
            val active = graph(fixture, NoopCodingRuntime, workspace).also { graph = it }
            active.start()
            assertFailsWith<TaskWorktreeResetUnconfirmed> { active.pauseForReset() }
            active.resumeAfterReset()
            active.pauseForReset(discardUnresolvable = true)
            assertEquals(2, prepares)
            active.resumeAfterReset()
            var entered = false
            assertNotNull(active.sessionTree).withScope(fixture.root) { entered = true }
            assertTrue(entered, "the consented pause resumes like any other")
        } finally { graph?.close(); Dispatchers.resetMain() }
    }

    private fun graph(fixture: SessionOrganismTestFixture, native: CodingRuntime,
        workspaces: TaskWorktreeOwner = testTaskWorktreeOwner(UnavailableTaskWorkspace, fixture.journal, fixture.storage),
        orchestrationFactory: (OrchestrationActions) -> CustomOrchestration = { actions ->
            object : CustomOrchestration {
                override suspend fun execute(context: ToolExecutionContext, operationId: String, tool: String,
                    arguments: JsonObject): JsonElement = actions.execute(context, operationId, tool, arguments)
            }
        }): CodingRuntimeGraph {
            val gateway = object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
                    error("Bootstrap must not request a model")
            }
            val search = object : SearchEngine {
                override val provider = SearchProvider.AUTO
                override val displayName = "Test search"
                override fun isConfigured(settings: AppSettings) = true
                override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> =
                    error("Bootstrap must not perform a search")
            }
        return CodingRuntimeGraph(fixture.storage, Json, fixture.settings, fixture.profiles,
                fixture.projects, native, LocalPlanningWorkspace(), null,
                UsageLedger(JsonUsageRepository(fixture.storage, Json), fixture.journal, fixture.storage, Json), gateway, search,
                events = fixture.journal,
                settingsCommands = object : SettingsCommands {
                    override suspend fun selectDefaultCodingEngine(engine: CodingEngine): AppSettings = error("Unexpected preference write")
                    override suspend fun runtimePolicy() = SettingsRuntimePolicy.Confirmed(fixture.settings.load())
                },
                taskWorktreeOwner = workspaces,
                planningStoreFactory = io.aequicor.magicpaper.data.planning.PlanningStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.planning.DefaultPlanningStore(io.aequicor.magicpaper.data.planning.JsonPlanningRepository(fixture.storage, Json), fixture.journal, secrets)
                },
                organismStoreFactory = io.aequicor.magicpaper.data.coding.SessionOrganismStoreFactory { secrets ->
                    io.aequicor.magicpaper.data.coding.DefaultSessionOrganismStore(fixture.storage, fixture.journal, secrets)
                },
                toolQuestions = io.aequicor.magicpaper.domain.testQuestionnaires(),
                toolReceipts = MemoryToolReceiptStore(), toolSessionFactory = DefaultToolSessionFactory(),
                mediaToolFactory = DefaultMediaToolCommandsFactory(),
                mediaToolReceipts = DefaultMediaToolReceiptOwner(MemoryToolReceiptStore()) { null }, questionnaireToolFactory = DefaultQuestionnaireToolCommandsFactory(), orchestrationFactory = orchestrationFactory, modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers())
    }
}
