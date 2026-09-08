package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningGatewayTest {
    private val project = CodingProject("project", "Project", "/selected/project", 1)
    private val profile = LlmProfile("model", "Model", baseUrl = "http://test/v1", modelId = "planner", codingModelId = "executor",
        advanced = AdvancedLlmOptions(timeoutSeconds = 1))
    private val messages = listOf(LlmMessage(LlmChatRole.USER, "Изучи изменения и составь план"))
    private class Runtime(val events: Flow<CodingEvent>) : CodingRuntime by NoopCodingRuntime {
        override val supported = true
        val calls = mutableListOf<Pair<CodingProject, CodingSession>>()
        val profiles = mutableListOf<LlmProfile>()
        val aborted = mutableListOf<String>()
        override fun run(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment>): Flow<CodingEvent> = error("Execution must not be used")
        override fun runPlanning(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile): Flow<CodingEvent> {
            calls += project to session; profiles += profile; return events
        }
        override fun abort(sessionId: String) { aborted += sessionId }
    }

    @Test fun toolsReturnBeforeThePlanAndContextsStayIndependentOfProvider() = runTest {
        val runtime = Runtime(flow {
            emit(CodingEvent.ToolStarted("read", "source.kt", "read")); delay(3000)
            emit(CodingEvent.ToolFinished("read", false, "read", "observed content"))
            emit(CodingEvent.MessageStarted)
            emit(CodingEvent.TextDelta("{\"reply\":\"Нашёл реализацию\","))
            emit(CodingEvent.FinalText("{\"reply\":\"Нашёл реализацию\",\"tree\":[]}"))
            emit(CodingEvent.Finished)
        })
        val gateway = RuntimePlanningGateway(runtime)
        val activity = mutableListOf<CodingStep>()
        for (engine in CodingEngine.entries) gateway.completeWithActivity(project, engine, "request", profile, messages, activity::add)
        assertEquals(CodingEngine.entries, runtime.calls.map { it.second.engine })
        assertTrue(runtime.calls.all { it.first == project && it.second.piSessionId.isEmpty() })
        assertEquals(2, runtime.calls.map { it.second.id }.distinct().size)
        assertTrue(runtime.profiles.all { it.modelId == "planner" && it.codingModelId == "executor" })
        assertTrue(activity.any { it.tool == "read" && it.result == "observed content" && !it.running })
        assertTrue(activity.any { it.kind == CodingStepKind.ANSWER && it.title == "Нашёл реализацию" })
        assertTrue(activity.none { it.kind == CodingStepKind.ANSWER && it.title.contains("\"tree\"") })
    }

    @Test fun inactivityAndCancellationStopOnlyTheirOwnRequest() = runTest {
        val runtime = Runtime(flow { emit(CodingEvent.Notice("Подготовка")); awaitCancellation() })
        val gateway = RuntimePlanningGateway(runtime)
        assertFailsWith<IllegalStateException> { gateway.completeWithActivity(project, CodingEngine.PI, "timeout", profile, messages) {} }
        assertEquals(listOf(runtime.calls.single().second.id), runtime.aborted)
        val request = launch { gateway.completeWithActivity(project, CodingEngine.CODEX, "cancel",
            profile.copy(advanced = profile.advanced.copy(timeoutSeconds = 0)), messages) {} }
        runCurrent(); request.cancelAndJoin()
        assertEquals(2, runtime.aborted.distinct().size)
    }

    @Test fun missingRuntimeAndFailedOrIncompleteTurnsNeverBecomePlans() = runTest {
        assertFailsWith<IllegalStateException> { RuntimePlanningGateway(NoopCodingRuntime).completeWithActivity(project, CodingEngine.PI, "r", profile, messages) {} }
        for (events in listOf(flowOf(CodingEvent.FinalText("partial")), flowOf(CodingEvent.Failed("Папка недоступна"), CodingEvent.Finished))) {
            assertFailsWith<IllegalStateException> { RuntimePlanningGateway(Runtime(events)).completeWithActivity(project, CodingEngine.PI, "r", profile, messages) {} }
        }
    }

    @Test fun composerValidatesProjectAndUsesSelectedEngine() = runTest {
        val text = object : LlmGateway { override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Text fallback") }
        val runtime = Runtime(flowOf(CodingEvent.FinalText("{\"reply\":\"Готово\"}"), CodingEvent.Finished))
        val composer = PlanComposer(text, planningGateway = RuntimePlanningGateway(runtime), projectLookup = { project })
        val plan = Plan("p", project.id, "Goal", engine = CodingEngine.CODEX)
        composer.completePlanning(plan, profile, messages) {}
        assertEquals(CodingEngine.CODEX, runtime.calls.single().second.engine)
        assertFailsWith<IllegalArgumentException> { composer.completePlanning(plan.copy(projectId = "other"), profile, messages) {} }
        assertFailsWith<IllegalStateException> { PlanComposer(text).completePlanning(plan, profile, messages) {} }
    }
}
