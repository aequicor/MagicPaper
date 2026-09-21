package io.aequicor.magicpaper.data.llm

import kotlinx.serialization.json.Json
import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderModel
import io.aequicor.magicpaper.domain.ProviderType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodexModelCacheTest {

    @Test
    fun effectiveContextWindowMatchesNativeCodexBudget() {
        val windows = codexCachedContextWindows(Json, """
            {
              "models": [
                {
                  "slug": "gpt-6-astra",
                  "context_window": 272000,
                  "max_context_window": 872000,
                  "effective_context_window_percent": 95
                },
                {
                  "slug": "gpt-default-percent",
                  "context_window": 200000
                }
              ]
            }
        """.trimIndent())

        assertEquals(258_400, windows["gpt-6-astra"])
        assertEquals(200_000, windows["gpt-default-percent"])
        assertFalse(872_000 in windows.values, "experimental maximum is not the active context budget")
    }

    @Test
    fun invalidEntriesDoNotInventAContextLimit() {
        val windows = codexCachedContextWindows(Json, """
            {"models":[
              {"slug":"missing"},
              {"slug":"zero","context_window":0},
              {"slug":"bad-percent","context_window":128000,"effective_context_window_percent":0}
            ]}
        """.trimIndent())

        assertEquals(mapOf("bad-percent" to 128_000), windows)
    }

    @Test
    fun savedSubscriptionProfileReceivesCurrentWindowBeforePiRun() {
        val home = Files.createTempDirectory("codex-model-cache")
        try {
            home.resolve("models_cache.json").toFile().writeText("""
                {"models":[{"slug":"gpt-6-astra","context_window":272000,"effective_context_window_percent":95}]}
            """.trimIndent())
            val service = nativeTestClient(Json, home)
            val saved = LlmProfile(
                id = "subscription",
                name = "ChatGPT",
                provider = ProviderType.OPENAI_SUBSCRIPTION,
                modelId = "gpt-6-astra",
                advanced = AdvancedLlmOptions(contextLimit = 128_000, maxTokens = 300_000),
                modelCatalog = listOf(ProviderModel("gpt-6-astra")),
            )

            val runtime = service.withCachedContextWindow(saved)

            assertEquals(258_400, runtime.advanced.contextLimit)
            assertEquals(258_400, runtime.advanced.maxTokens)
            assertEquals(258_400, runtime.modelCatalog.single().contextWindow)
            service.close()
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
