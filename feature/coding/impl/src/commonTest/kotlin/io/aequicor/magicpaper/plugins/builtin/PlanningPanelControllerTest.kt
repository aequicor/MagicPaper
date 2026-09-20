package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.data.coding.JsonCodingProjectRepository
import io.aequicor.magicpaper.data.coding.NoopCodingRuntime
import io.aequicor.magicpaper.data.planning.*
import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningPanelControllerTest {
    private class Fixture(scope: CoroutineScope) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val kv = InMemoryKeyValueStore()
        val store = PlanningStore(JsonPlanningRepository(kv, json))
        val projects = JsonCodingProjectRepository(kv, json)
        val profiles = JsonLlmProfileRepository(kv, json)
        val settings = JsonSettingsRepository(kv, json)
        val profile = LlmProfile("model", "Planner", baseUrl = "http://test/v1", modelId = "m")
        val plan = Plan("plan", "project", "Goal", plannerSelection = ModelSelection(profile.id, profile.modelId))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var fail = false
        var failLoad = false
        var ignoreCancellation = false
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                calls++
                if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
                if (fail) error("Private provider request details")
                return """{"reply":"Ready to clarify","questions":[{"id":"format","title":"Format?","kind":"TEXT"}]}"""
            }
        }
        val execution = PlanningExecutionService(store, NoopCodingRuntime, projects, profiles, settings,
            object : MilestoneVerifier {
                override suspend fun verify(milestone: Milestone, goal: String, report: String, profile: LlmProfile?) = Verdict(true, "Checked")
            }, scope = scope)
        val controller = PlanningPanelController(store, textPlanComposer(gateway, retryLimit = 0), execution,
            object : CodingProjectRepository by projects {
                override suspend fun all(): List<CodingProject> {
                    if (failLoad) error("Private storage error")
                    return projects.all()
                }
            }, profiles, settings, scope)
        val form = PersistentDraftValue(InMemoryDraftRepository(), "form", PlanningFormDraft.serializer(),
            PlanningFormDraft(input = "Original draft"), scope)
        suspend fun seed() {
            projects.save(CodingProject(plan.projectId, "Project", "/fixture", 1))
            profiles.save(profile)
            settings.save(AppSettings(activeLlmProfileId = profile.id))
            store.save(plan)
            form.draft
            controller.load()
        }
    }

    @Test fun refinementSurvivesScreenCollectorAndKeepsNewerDraft() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        val screen = backgroundScope.launch { f.controller.state.collect() }
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        assertEquals(1, f.calls)
        assertEquals(setOf(PlanningPanelWork.REFINING), f.controller.state.value.operations[f.plan.projectId]?.active)
        screen.cancelAndJoin()
        f.form.update { it.copy(input = "New unsent draft") }
        f.controller.refine(f.plan, "Duplicate", null, false, f.form); runCurrent()
        assertEquals(1, f.calls)
        f.gate.complete(Unit); runCurrent()
        assertTrue(f.controller.state.value.operations.isEmpty())
        assertEquals("New unsent draft", f.form.draft.state.value.value.input)
        val saved = assertNotNull(f.store.planFor(f.plan.id))
        assertEquals(1, saved.dialogue.count { it.role == "user" })
        assertTrue(saved.dialogue.any { it.role == "assistant" })
    }

    @Test fun failureRemainsDiscoverableAndSafeAfterLeavingPanel() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        f.fail = true
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        f.gate.complete(Unit); runCurrent()
        val failed = assertNotNull(f.controller.state.value.operations[f.plan.projectId])
        assertTrue(failed.active.isEmpty())
        assertEquals("Не удалось получить ответ. Повторите запрос.", failed.notice)
        assertTrue(failed.activity.any { it.kind == CodingStepKind.ERROR })
        assertFalse(failed.activity.any { "Private provider" in it.title })
        assertEquals("Original draft", f.form.draft.state.value.value.input)
        f.fail = false
        f.controller.refine(assertNotNull(f.store.planFor(f.plan.id)), "Retry", null, false, f.form); runCurrent()
        assertTrue(f.controller.state.value.operations.isEmpty())
        assertEquals("", f.form.draft.state.value.value.input)
    }

    @Test fun removalCancelsRefinementAndPreventsStalePlanCommands() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        f.controller.remove(f.plan.projectId, setOf(f.plan.id)); runCurrent()
        f.gate.complete(Unit); runCurrent()
        f.controller.refine(f.plan, "Stale click", null, false, f.form); runCurrent()
        assertEquals(1, f.calls)
        assertTrue(f.controller.state.value.operations.isEmpty())
        assertTrue(assertNotNull(f.store.planFor(f.plan.id)).dialogue.none { it.role == "assistant" })
        assertContains(f.controller.state.value.removedPlans, f.plan.projectId to f.plan.id)
    }

    @Test fun controlRemainsAvailableWhileRefiningAndDoesNotClearItsActivity() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        val originalActivity = f.controller.state.value.operations.getValue(f.plan.projectId).activity
        val controlGate = CompletableDeferred<Unit>()
        var controls = 0
        f.controller.action(f.plan.projectId) { controls++; controlGate.await() }
        f.controller.action(f.plan.projectId) { controls++ }
        runCurrent()
        assertEquals(1, controls)
        assertEquals(setOf(PlanningPanelWork.REFINING, PlanningPanelWork.SUBMITTING),
            f.controller.state.value.operations.getValue(f.plan.projectId).active)
        controlGate.complete(Unit); runCurrent()
        assertEquals(setOf(PlanningPanelWork.REFINING), f.controller.state.value.operations.getValue(f.plan.projectId).active)
        assertEquals(originalActivity, f.controller.state.value.operations.getValue(f.plan.projectId).activity)
        f.gate.complete(Unit); runCurrent()
        assertTrue(f.controller.state.value.operations.isEmpty())
    }

    @Test fun deletionRejectsAProviderReplyThatArrivesAfterCancellation() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        f.ignoreCancellation = true
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        val removal = backgroundScope.launch { f.controller.remove(f.plan.projectId, null) }
        runCurrent()
        assertFalse(removal.isCompleted)
        f.gate.complete(Unit); runCurrent(); removal.join()
        assertTrue(assertNotNull(f.store.planFor(f.plan.id)).dialogue.none { it.role == "assistant" })
        assertEquals("Original draft", f.form.draft.state.value.value.input)
        assertTrue(f.controller.state.value.operations.isEmpty())
    }

    @Test fun resetDrainsWorkAndStaysPausedUntilExplicitResume() = runTest {
        val f = Fixture(backgroundScope); f.seed(); runCurrent()
        f.controller.refine(f.plan, "Question", null, false, f.form); runCurrent()
        f.controller.prepareForReset(); runCurrent()
        f.gate.complete(Unit)
        var actions = 0
        f.controller.action(f.plan.projectId) { actions++ }; f.controller.load(); runCurrent()
        assertEquals(0, actions)
        assertFalse(f.controller.state.value.loaded)
        assertTrue(f.controller.state.value.operations.isEmpty())
        f.controller.resumeAfterReset()
        f.controller.load(); f.controller.action(f.plan.projectId) { actions++ }; runCurrent()
        assertEquals(1, actions)
        assertTrue(f.controller.state.value.loaded)
        assertEquals(1, f.calls)
    }

    @Test fun loadFailureCanRetryWithoutLaunchingAPlanner() = runTest {
        val f = Fixture(backgroundScope); f.failLoad = true; f.seed(); runCurrent()
        assertFalse(f.controller.state.value.loaded)
        assertEquals("Не удалось загрузить планирование. Повторите загрузку.", f.controller.state.value.loadError)
        f.failLoad = false; f.controller.load(); runCurrent()
        assertTrue(f.controller.state.value.loaded)
        assertNull(f.controller.state.value.loadError)
        assertEquals(0, f.calls)
    }

    @Test fun liveProjectionChangesOnlyDisplayOutputAndCanReplaySavedSnapshot() {
        val attempt = StageAttempt("attempt", "worker", StageAssignment("profile", "model"), updatedAt = 10)
        val saved = Plan("plan", "project", "Goal", milestones = listOf(Milestone("stage", "Work", attempts = listOf(attempt))))
        val checkpoint = PlanningPanelState(plans = listOf(saved))
        val live = checkpoint.copy(live = mapOf(attempt.id to attempt.copy(sessionId = "untrusted-session", updatedAt = 20,
            phase = AttemptPhase.COMPLETE, report = "Live output")))
        val projected = assertNotNull(live.planFor("project")).milestones.single().attempts.single()
        assertEquals("Live output", projected.report)
        assertEquals(attempt.sessionId, projected.sessionId)
        assertEquals(attempt.phase, projected.phase)
        assertEquals(saved, checkpoint.planFor("project"))
        assertEquals(checkpoint.planFor("project"), checkpoint.copy(live = emptyMap()).planFor("project"))
        assertNull(checkpoint.planFor("another-project"))
    }
}
