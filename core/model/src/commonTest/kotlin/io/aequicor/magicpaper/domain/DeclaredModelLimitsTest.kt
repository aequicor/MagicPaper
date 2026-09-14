package io.aequicor.magicpaper.domain

import kotlin.test.*

class DeclaredModelLimitsTest {
    @Test fun familyNameDoesNotInventOneMillionTokenContext() {
        val recommendation = ModelDefaults.recommendation(ProviderType.OPENAI_COMPATIBLE, "qwen-local",
            current = AdvancedLlmOptions(contextLimit = 32768))
        assertEquals(32768, recommendation.advanced.contextLimit)
    }
    @Test fun declaredOutputAndContextCapReasoningRecommendations() {
        val recommendation = ModelDefaults.recommendation(ProviderType.OPENAI_COMPATIBLE, "gpt-5",
            fact = ProviderModel("gpt-5", contextWindow = 16000, maxOutputTokens = 2048))
        assertEquals(16000, recommendation.advanced.contextLimit)
        assertEquals(2048, recommendation.advanced.maxTokens)
    }
}
