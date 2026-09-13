package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderModelMetadataTest {
    @Test fun openRouterFactsIncludeDefaultsLimitsAndEffort() {
        val found = parseProviderModels(Json, """{"data":[{"id":"m","name":"Model","context_length":200000,"top_provider":{"max_completion_tokens":50000},"default_parameters":{"temperature":0.9,"top_p":null},"supported_parameters":["temperature","reasoning"],"reasoning":{"supported_efforts":["low","high"],"default_effort":"high"}}]}""", ProviderType.OPENROUTER).single()
        assertEquals(200000, found.metadata?.contextWindow)
        assertEquals(50000, found.metadata?.maxOutputTokens)
        assertEquals(.9, found.metadata?.defaultParameters?.get("temperature")?.jsonPrimitive?.double)
        assertFalse("top_p" in found.metadata!!.defaultParameters)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), found.levels)
        assertEquals(ReasoningEffort.HIGH, (found.reasoning as ReasoningCapability.Controls).default)
    }
    @Test fun idOnlyCatalogDoesNotClaimDefaultsOrLimits() {
        val found = parseProviderModels(Json, """{"data":[{"id":"m"}]}""", ProviderType.OPENAI_COMPATIBLE).single()
        assertNull(found.metadata?.contextWindow)
        assertNull(found.metadata?.supportedParameters)
        assertEquals(emptyMap(), found.metadata?.defaultParameters)
    }
    @Test fun googleMetadataFiltersEmbeddingModelsAndMapsSamplingParameters() {
        val found = parseProviderModels(Json, """{"models":[{"name":"models/gemini-test","displayName":"Gemini","inputTokenLimit":1000000,"outputTokenLimit":64000,"temperature":1,"topP":0.95,"topK":40,"supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}]}""", ProviderType.GOOGLE, "models").single()
        assertEquals("gemini-test", found.id)
        assertEquals(1000000, found.metadata?.contextWindow)
        assertEquals(40, found.metadata?.defaultParameters?.get("top_k")?.jsonPrimitive?.int)
        assertTrue("max_tokens" in found.metadata?.supportedParameters.orEmpty())
    }
}
