package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDefaultsTest {

    private val openAi = ProviderType.OPENAI_COMPATIBLE

    @Test
    fun catalogModelSupportsEffort() {
        assertTrue(ModelDefaults.supportsEffort(openAi, "gpt-5-mini"))
    }

    @Test
    fun heuristicEffortByFamily() {
        assertTrue(ModelDefaults.supportsEffort(openAi, "gpt-5"))
        assertTrue(ModelDefaults.supportsEffort(openAi, "o3-mini"))
        assertTrue(ModelDefaults.supportsEffort(openAi, "openrouter/anthropic/claude-sonnet-4.5"), "префиксы провайдеров")
        assertTrue(ModelDefaults.supportsEffort(ProviderType.ANTHROPIC, "claude-opus-4-1"))
        assertTrue(ModelDefaults.supportsEffort(ProviderType.GOOGLE, "gemini-2.5-pro"))
        assertTrue(ModelDefaults.supportsEffort(openAi, "deepseek-r1"))
        assertTrue(ModelDefaults.supportsEffort(openAi, "qwq-32b"))
        assertTrue(ModelDefaults.supportsEffort(openAi, "my-reasoning-model"))
    }

    @Test
    fun plainModelsHaveNoEffortSupport() {
        assertFalse(ModelDefaults.supportsEffort(openAi, "llama3.2"))
        assertFalse(ModelDefaults.supportsEffort(openAi, "gpt-4o"))
        assertFalse(ModelDefaults.supportsEffort(openAi, "mistral"))
        assertFalse(ModelDefaults.supportsEffort(openAi, "gemini-2.0-flash"))
    }

    @Test
    fun discoverMarksDiscoveredModelsAndRecommends() {
        val found = ModelDefaults.discover(openAi, listOf("gpt-5-mini", "gpt-5-mini", "llama3.2", ""))
        assertEquals(2, found.size, "дубли и пустые отбрасываются")
        val gpt = found.first { it.id == "gpt-5-mini" }
        assertTrue(gpt.supportsEffort)
        assertEquals(EffortLevel.MEDIUM, gpt.recommendation.effort)
        val llama = found.first { it.id == "llama3.2" }
        assertFalse(llama.supportsEffort)
        assertEquals(0.7, llama.recommendation.advanced.temperature)
    }

    @Test
    fun anthropicRecommendationCarriesMaxTokens() {
        val rec = ModelDefaults.recommendation(ProviderType.ANTHROPIC, "claude-sonnet-4-5")
        assertEquals(8192, rec.advanced.maxTokens, "max_tokens обязан перекрывать бюджет мышления")
    }

    @Test
    fun reasoningModelRecommendationHasNoTemperature() {
        val rec = ModelDefaults.recommendation(openAi, "gpt-5")
        assertEquals(null, rec.advanced.temperature)
    }
}
