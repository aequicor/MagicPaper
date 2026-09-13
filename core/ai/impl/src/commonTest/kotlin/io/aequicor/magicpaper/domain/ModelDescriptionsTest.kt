package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class ModelDescriptionsTest {
    private val target = LlmProfile("target", "Target", baseUrl = "https://target", modelId = "model-a")
    private val judge = LlmProfile("default", "Default", baseUrl = "https://judge", modelId = "model-b")
    private class Search : SearchEngine {
        override val provider = SearchProvider.GOOGLE
        override val displayName = "Test search"
        var received: AppSettings? = null
        var hits = listOf(SearchHit("Model card", "https://example.com/model", "Capabilities and limitations"))
        val queries = mutableListOf<String>()
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
            received = settings
            queries += query
            return hits
        }
    }
    @Test fun descriptionUsesOperationalModelAndSelectedSearchAndPreservesTargetIdentity() = runTest {
        var called: LlmProfile? = null
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                called = profile
                assertTrue(messages.any { "https://example.com/model" in it.content })
                return """{"strengths":"Coding","limitations":"Limited context","rating":4,"assessment":{"quality":3}}"""
            }
        }
        val settings = AppSettings(searchProvider = SearchProvider.GOOGLE)
        val search = Search()
        val result = DossierResearcher(gateway, search).research(target, judge, settings)
        assertEquals(judge, called)
        assertEquals(settings, search.received)
        assertEquals("target", result.profileId)
        assertEquals("model-a", result.modelId)
        assertEquals("Limited context", result.limitations)
        assertEquals(4, result.rating)
        assertEquals(listOf("https://example.com/model"), result.references)
        assertTrue(result.note.contains("Default"))
        assertTrue(result.updatedAt > 0)
        assertEquals(listOf("model-a model card benchmarks"), search.queries)
    }
    @Test fun cancelledResearchIsNotConvertedIntoAHeuristicDescription() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = throw CancellationException()
        }
        assertFailsWith<CancellationException> { DossierResearcher(gateway, Search()).research(target, judge, AppSettings()) }
    }

    @Test fun noSourcesRetriesShortQueryAndNeverAsksTheModelToInventFacts() = runTest {
        val search = Search().apply { hits = emptyList() }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Must not be called")
        }
        val result = DossierResearcher(gateway, search).research(target, judge, AppSettings())
        assertEquals(DossierSource.HEURISTIC, result.source)
        assertTrue(result.note.contains("не вернул источников"))
        assertEquals(listOf("model-a model card benchmarks", "model-a"), search.queries)
        assertEquals(0, result.rating)
        assertEquals(StageAssessment(), result.assessment)
    }

    @Test fun linksWithoutTextAreNotResearchEvidence() = runTest {
        val search = Search().apply { hits = listOf(SearchHit("Model", "https://example.com")) }
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Must not be called")
        }
        assertEquals(DossierSource.HEURISTIC, DossierResearcher(gateway, search).research(target, judge, AppSettings()).source)
    }

    @Test fun modelTimeoutIsAnActionableFailureAndDoesNotCancelTheBatch() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = withTimeout(10) { delay(20); "" }
        }
        val result = DossierResearcher(gateway, Search()).research(target, judge, AppSettings())
        assertEquals(DossierSource.HEURISTIC, result.source)
        assertTrue(result.note.contains("время ожидания"))
    }

    @Test fun subscriptionReceivesFreshSourceTextInTheSameUserMessageAsTheTarget() = runTest {
        val subscription = judge.copy(provider = ProviderType.OPENAI_SUBSCRIPTION)
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                assertEquals(subscription, profile)
                assertEquals(1, messages.count { it.role == LlmChatRole.USER })
                val request = messages.last().content
                assertTrue(request.contains("model-a"))
                assertTrue(request.contains("Capabilities and limitations"))
                assertFalse(request.contains("AdvancedLlmOptions"))
                return """{"strengths":"Подтверждено источником [1]","rating":3}"""
            }
        }
        val result = DossierResearcher(gateway, Search()).research(target, subscription, AppSettings())
        assertEquals(DossierSource.WEB, result.source)
        assertTrue(result.note.contains("Codex"))
    }
}
