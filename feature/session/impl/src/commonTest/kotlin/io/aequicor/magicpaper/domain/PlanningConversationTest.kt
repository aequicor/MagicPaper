package io.aequicor.magicpaper.domain

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class PlanningConversationTest {
    private val profile = LlmProfile("chosen", "Chosen", baseUrl = "http://test/v1", modelId = "planner")
    private val plan = Plan("p", "project", "Build an editor", plannerSelection = ModelSelection("chosen", "planner"),
        searchProvider = SearchProvider.WIKIPEDIA,
        tree = listOf(DecisionNode("root", "Build an editor", DecisionKind.GOAL)))

    @Test fun firstRequestUsesSelectedModelAndSearchAndReturnsQuestions() = runTest {
        var usedProfile: LlmProfile? = null
        var usedSettings: AppSettings? = null
        var context = emptyList<LlmMessage>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                usedProfile = profile
                context = messages
                return """{"reply":"Какие форматы должен поддерживать редактор?","tree":[],"milestones":[]}"""
            }
        }
        val search = object : SearchEngine {
            override val provider = SearchProvider.AUTO
            override val displayName = "Test"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
                assertEquals(plan.goal, query)
                usedSettings = settings
                return listOf(SearchHit("Formats", "https://example.com/formats", "Supported formats"))
            }
        }
        val progress = mutableListOf<String>()
        val result = textPlanComposer(gateway, searchEngine = search).refine(plan, "Сначала задай вопросы", profile,
            listOf(profile), emptyList(), AppSettings(searchProvider = SearchProvider.GOOGLE), onProgress = progress::add)
        assertEquals(profile, usedProfile)
        assertEquals(SearchProvider.WIKIPEDIA, usedSettings?.searchProvider)
        assertTrue(context.any { "https://example.com/formats" in it.content })
        assertEquals(2, progress.size)
        assertEquals("assistant", result.dialogue.last().role)
        assertTrue(result.dialogue.last().text.endsWith("?"))
        assertEquals(plan.tree, result.tree)
        assertTrue(result.milestones.isEmpty())
    }

    @Test fun missingSearchCredentialsProduceActionableErrorBeforeModelCall() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Must not call model")
        }
        val search = object : SearchEngine {
            override val provider = SearchProvider.AUTO
            override val displayName = "Test"
            override fun isConfigured(settings: AppSettings) = true
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> = error("Must not search")
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            textPlanComposer(gateway, searchEngine = search).refine(plan.copy(searchProvider = SearchProvider.QUERIT),
                "Questions", profile, listOf(profile), emptyList(), AppSettings())
        }
        assertContains(failure.message.orEmpty(), "API-ключ")
    }

    @Test fun questionsAboutExistingPlanStayOnClarificationStep() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = """{"reply":"Какой формат экспорта нужен?"}"""
        }
        val existing = plan.copy(wizardStep = PlanningStep.REVIEW, milestones = listOf(Milestone("export", "Экспорт")))
        val result = textPlanComposer(gateway).refine(existing, "Хочу уточнить", profile, listOf(profile), emptyList())
        assertEquals(PlanningStep.CLARIFY, result.currentPlanningStep)
        assertEquals(existing.milestones, result.milestones)
    }

    @Test fun wizardRestoresSavedStepAndExecutionTakesPrecedence() {
        assertEquals(PlanningStep.CLARIFY, plan.currentPlanningStep)
        val review = plan.copy(wizardStep = PlanningStep.REVIEW)
        assertEquals(PlanningStep.REVIEW, Json.decodeFromString<Plan>(Json.encodeToString(Plan.serializer(), review)).currentPlanningStep)
        assertEquals(PlanningStep.STATUS, review.copy(intent = ExecutionIntent.RUN).currentPlanningStep)
        assertEquals(PlanningStep.STATUS, review.copy(phase = ExecutionPhase.COMPLETE).currentPlanningStep)
        assertEquals(PlanningStep.GOAL, plan.copy(wizardStep = PlanningStep.GOAL).currentPlanningStep)
    }

    @Test fun activityFlowsThroughComposerAndRouter() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Expected observable call")
            override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
                onActivity(CodingStep(CodingStepKind.THINKING, "Уточняю критерии"))
                return """{"reply":"Как проверить результат?"}"""
            }
        }
        val activity = mutableListOf<CodingStep>()
        val router = io.aequicor.magicpaper.data.llm.RoutingLlmGateway(mapOf(profile.provider to gateway))
        textPlanComposer(router).refine(plan, "Вопросы", profile, listOf(profile), emptyList(), onActivity = activity::add)
        assertEquals("Уточняю критерии", activity.single().title)
    }

    @Test fun searchSelectionRoundTripsAndOldPlansStillLoad() {
        assertEquals(plan, Json.decodeFromString<Plan>(Json.encodeToString(Plan.serializer(), plan)))
        val old = Json.decodeFromString<Plan>("""{"id":"p","projectId":"project","goal":"goal"}""")
        assertEquals(SearchProvider.AUTO, old.searchProvider)
    }
}
