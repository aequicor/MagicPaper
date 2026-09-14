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

    @Test fun defaultProfileWaitsForSlowStartupAndStillSupportsCancellation() = runTest {
        val runtime = Runtime(flow { awaitCancellation() })
        val request = launch { RuntimePlanningGateway(runtime).completeWithActivity(project, CodingEngine.CODEX, "default",
            profile.copy(advanced = AdvancedLlmOptions()), messages) {} }
        runCurrent(); advanceTimeBy(3_600_000); runCurrent()
        assertTrue(request.isActive)
        assertTrue(runtime.aborted.isEmpty())
        request.cancelAndJoin()
        assertEquals(listOf(runtime.calls.single().second.id), runtime.aborted)
    }

    @Test fun explicitStartupTimeoutCanExceedOneHour() = runTest {
        val runtime = Runtime(flow {
            delay(3_600_001)
            emit(CodingEvent.SessionStarted("native"))
            emit(CodingEvent.FinalText("Ready")); emit(CodingEvent.Finished)
        })
        val result = RuntimePlanningGateway(runtime).completeWithActivity(project, CodingEngine.CODEX, "long-startup",
            profile.copy(advanced = AdvancedLlmOptions(timeoutSeconds = 7200)), messages) {}
        assertEquals("Ready", result)
        assertTrue(runtime.aborted.isEmpty())
    }

    @Test fun nativeSessionMayGenerateAPlanWithoutVisibleEventsPastTheChatDeadline() = runTest {
        for (engine in CodingEngine.entries) {
            val runtime = Runtime(flow {
                emit(CodingEvent.SessionStarted("native"))
                emit(CodingEvent.TextDelta("Готовлю предложение"))
                delay(125_000) // Native tool arguments/private reasoning are not chat deltas.
                emit(CodingEvent.ToolStarted("magicpaper_plan_propose", "Plan", "proposal"))
                emit(CodingEvent.ToolFinished("magicpaper_plan_propose", false, "proposal", "Saved"))
                emit(CodingEvent.FinalText("Предложение готово"))
                emit(CodingEvent.Finished)
            })
            val result = RuntimePlanningGateway(runtime).completeWithActivity(project, engine, "long-plan",
                profile.copy(advanced = profile.advanced.copy(timeoutSeconds = 120)), messages) {}
            assertEquals("Предложение готово", result)
            assertTrue(runtime.aborted.isEmpty())
        }
    }

    @Test fun silentNativeSessionStillPropagatesEngineFailureAndUserCancellation() = runTest {
        val runtime = Runtime(flow {
            emit(CodingEvent.SessionStarted("native"))
            delay(2000)
            emit(CodingEvent.Failed("Connection lost"))
            emit(CodingEvent.Finished)
        })
        val failure = assertFailsWith<IllegalStateException> {
            RuntimePlanningGateway(runtime).completeWithActivity(project, CodingEngine.CODEX, "failed", profile, messages) {}
        }
        assertEquals("Connection lost", failure.message)
        val silent = Runtime(flow { emit(CodingEvent.SessionStarted("native")); awaitCancellation() })
        val request = launch { RuntimePlanningGateway(silent).completeWithActivity(project, CodingEngine.CODEX, "cancel", profile, messages) {} }
        runCurrent(); advanceTimeBy(2000); runCurrent()
        assertTrue(request.isActive)
        request.cancelAndJoin()
        assertEquals(listOf(silent.calls.single().second.id), silent.aborted)
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
