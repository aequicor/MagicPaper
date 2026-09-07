package io.aequicor.magicpaper.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModelDescriptionsTest {
    private val target = LlmProfile("target", "Target", baseUrl = "https://target", modelId = "model-a")
    private val judge = LlmProfile("default", "Default", baseUrl = "https://judge", modelId = "model-b")
    private class Search : SearchEngine {
        override val provider = SearchProvider.GOOGLE
        override val displayName = "Test search"
        var received: AppSettings? = null
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
            received = settings
            return listOf(SearchHit("Model card", "https://example.com/model", "Capabilities and limitations"))
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
    }
    @Test fun cancelledResearchIsNotConvertedIntoAHeuristicDescription() = runTest {
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = throw CancellationException()
        }
        assertFailsWith<CancellationException> { DossierResearcher(gateway, Search()).research(target, judge, AppSettings()) }
    }
}
