package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlin.test.*

class DeclaredRuntimeLimitsTest {
    @Test fun reasoningReserveNeverExceedsProviderLimits() {
        val profile = LlmProfile("p", "Profile", modelId = "gpt-5",
            advanced = AdvancedLlmOptions(maxTokens = 8192, contextLimit = 128000),
            modelCatalog = listOf(ProviderModel("gpt-5", contextWindow = 32768, maxOutputTokens = 4096)))
        assertEquals(32768, PiModelsConfig.contextWindow(profile))
        assertEquals(4096, PiModelsConfig.maxTokens(profile))
    }
}
