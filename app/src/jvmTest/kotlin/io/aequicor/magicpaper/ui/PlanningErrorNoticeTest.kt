package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningErrorNoticeTest {
    @Test fun asynchronousPlanningFailureIsVisibleWithoutOpeningThePlanningPanel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var model: DefaultCodingService? = null
        var planning: OrchestrationService? = null
        try {
            val fixture = ModelSettingsFixture()
            val projects = journalCodingProjects(fixture.kv, fixture.json)
            val project = CodingProject("project", "Project", "/fixture", 1)
            projects.createTestProject(project)
            projects.createTestSession(CodingSession("session", project.id, "Task", 1, engine = CodingEngine.PI))
            val delegate = TestPlanningStore(JsonPlanningRepository(fixture.kv, fixture.json))
            var fail = false
            val store = object : PlanningStore by delegate {
                override suspend fun planFor(projectId: String): Plan? {
                    if (fail) error("Private transport payload must not appear")
                    return delegate.planFor(projectId)
                }
            }
            val ports = TestPlanningExecutionPorts()
            val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, fixture.profiles, fixture.settings,
                object : MilestoneVerifier {
                    override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
                }, workspaces = LocalPlanningWorkspace(), scope = backgroundScope, attemptAuthority = ports, chatHooksProvider = { ports.chatHooks })
            val service = OrchestrationService(store, execution, projects, fixture.profiles, fixture.settings,
                textPlanComposer(fixture.gateway), fixture.gateway, backgroundScope,
                workerDispatcher = StandardTestDispatcher(testScheduler), modelDossiers = io.aequicor.magicpaper.domain.testModelDossiers())
            planning = service
            ports.chatHooks = service
            model = fixture.prepareCoding(NoopCodingRuntime, projects, planningChat = service)
            runCurrent()
            fail = true
            service.control("plan", "resume")
            runCurrent()
            assertNotNull(service.error.value)
            assertEquals("Не удалось выполнить действие планирования. Проверьте состояние плана.", model.state.value.notice)
            assertFalse(model.state.value.notice.orEmpty().contains("Private"))
            val firstEvent = assertNotNull(service.failureEvents.value)
            val firstMessage = service.error.value
            model.dismissNotice(); runCurrent()
            assertNull(model.state.value.notice)
            service.control("plan", "resume"); runCurrent()
            assertEquals(firstMessage, service.error.value)
            assertNotEquals(firstEvent.id, assertNotNull(service.failureEvents.value).id)
            assertEquals("Не удалось выполнить действие планирования. Проверьте состояние плана.", model.state.value.notice)
            assertTrue(fixture.calls.isEmpty())
        } finally {
            model?.close()
            planning?.shutdown()
            Dispatchers.resetMain()
        }
    }
}
