package io.aequicor.magicpaper.domain

import kotlin.test.*

class CodingSessionTemplateTest {
    private val native = CodingModelSelection(CodingEngine.CODEX, "openai", "gpt-6", "high")
    private val profileModel = ModelSelection("profile", "model")
    private val last = CodingSession("last", "p", "Task", 10, engine = CodingEngine.CODEX, codingModel = native,
        modelSelection = profileModel, llmProfileId = "profile", researchMode = true, worktreeEnabled = false,
        featureFlags = FeatureFlagOverride.INHERIT.with(FeatureFlag.NATIVE_CODING_MODELS, true),
        mediaTools = SessionMediaTools(images = false))
    private val fresh = CodingSession("new", "p", "Новая сессия", 20, engine = CodingEngine.CODEX,
        modelSelection = ModelSelection("favorite", "default"))

    @Test fun openConversationWinsOverAMoreRecentOne() {
        val older = last.copy(id = "older", createdAt = 1)
        assertEquals("older", listOf(older, last).lastConversation("older")?.id)
        assertEquals("last", listOf(older, last).lastConversation(null)?.id)
    }

    @Test fun recencyCountsTheLastStatusChangeNotOnlyCreation() {
        val active = last.copy(id = "active", createdAt = 1, statusChangedAt = 50)
        assertEquals("active", listOf(active, last).lastConversation("elsewhere")?.id)
    }

    @Test fun planningStagesAndWorkersAreNeverTemplates() {
        val planning = last.copy(id = "plan", createdAt = 99, planningMode = true, role = CodingSessionRole.ORCHESTRATOR)
        val stage = last.copy(id = "stage", createdAt = 99, stageId = "s", role = CodingSessionRole.WORKER)
        assertEquals("last", listOf(last, planning, stage).lastConversation("plan")?.id)
        assertNull(listOf(planning, stage).lastConversation(null))
    }

    @Test fun parametersCarryOverIntoTheNewSession() {
        val next = fresh.withParametersOf(last) { true }
        assertEquals(native, next.codingModel)
        assertEquals(profileModel, next.modelSelection); assertEquals("profile", next.llmProfileId)
        assertEquals(CodingInteractionMode.RESEARCH, next.interactionMode)
        assertFalse(next.worktreeEnabled)
        assertEquals(last.featureFlags, next.featureFlags)
        assertEquals(last.mediaTools, next.mediaTools)
        assertEquals(fresh.id, next.id); assertEquals(fresh.name, next.name); assertEquals("", next.piSessionId)
    }

    @Test fun nativeModelOfAnotherEngineAndAnUnusableProfileModelKeepTheNewSessionsDefaults() {
        val projectDefault = CodingModelSelection(CodingEngine.CLAUDE_CODE, "anthropic", "opus")
        val next = fresh.copy(engine = CodingEngine.CLAUDE_CODE, codingModel = projectDefault).withParametersOf(last) { false }
        assertEquals(projectDefault, next.codingModel)
        assertEquals(fresh.modelSelection, next.modelSelection); assertNull(next.llmProfileId)
    }

    @Test fun withoutATemplateTheNewSessionIsUnchanged() {
        assertEquals(fresh, fresh.withParametersOf(null) { true })
    }
}
