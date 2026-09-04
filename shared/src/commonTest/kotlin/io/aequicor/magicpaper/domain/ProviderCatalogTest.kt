package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ProviderCatalogTest {

    @Test
    fun everyKnownProviderTypeHasSpec() {
        ProviderType.entries.forEach { type ->
            assertNotNull(
                ProviderCatalog.all.firstOrNull { it.type == type },
                "в каталоге нет описания для $type",
            )
        }
    }

    @Test
    fun catalogModelsLookup() {
        val info = ProviderCatalog.modelInfo(ProviderType.OPENAI_COMPATIBLE, "gpt-5-mini")
        assertNotNull(info)
        assertTrue(info.supportsEffort)
        // Модель из кураторского каталога — поддерживает усилие по каталогу.
        assertTrue(
            ModelDefaults.supportsEffort(
                LlmProfile(id = "p", name = "x", provider = ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://x/v1", modelId = "gpt-5-mini"),
            ),
        )
    }

    @Test
    fun unknownModelHasNoEffortSupport() {
        assertTrue(
            !ModelDefaults.supportsEffort(
                LlmProfile(id = "p", name = "x", provider = ProviderType.OPENAI_COMPATIBLE, baseUrl = "http://x/v1", modelId = "своя-модель"),
            ),
        )
    }

    @Test
    fun localProvidersDoNotRequireKey() {
        val ollama = ProviderCatalog.all.firstOrNull { it.displayName.contains("Ollama") }
        assertNotNull(ollama)
        assertTrue(!ollama.requiresKey)
    }
}
