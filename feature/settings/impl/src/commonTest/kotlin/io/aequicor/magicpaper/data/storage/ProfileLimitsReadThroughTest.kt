package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Профиль, записанный до появления каталога движка, читается с заявленными пределами:
 * эндпоинты без метаданных иначе оставляют запрос на потолке конфигурации.
 */
class ProfileLimitsReadThroughTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
    private val profile = LlmProfile(
        id = "alibaba", name = "Alibaba", baseUrl = baseUrl, modelId = "qwen3.8-max", modelLibraryVersion = 1,
        modelCatalog = listOf(
            ProviderModel("qwen3.8-max"),
            ProviderModel("known", contextWindow = 8_192, maxOutputTokens = 1_024),
        ),
    )
    private val facts = ModelLimitCatalog { _, ids -> ids.associateWith { CatalogModelLimits(1_000_000, 131_072) } }

    @Test fun loadCompletesUnknownLimits() = runTest {
        val store = InMemoryKeyValueStore()
        JsonLlmProfileRepository(store, json).save(profile)

        val loaded = JsonLlmProfileRepository(store, json, modelLimits = facts).load().single()
        val unknown = loaded.modelCatalog.first { it.id == "qwen3.8-max" }
        assertEquals(1_000_000, unknown.contextWindow)
        assertEquals(131_072, unknown.maxOutputTokens)
        // Объявленное провайдером остаётся, даже когда каталог знает модель иначе.
        assertEquals(8_192, loaded.modelCatalog.first { it.id == "known" }.contextWindow)
        assertEquals(1_000_000, loaded.forCoding().advanced.safeContextLimit)
    }

    @Test fun readDoesNotRewriteStoredFacts() = runTest {
        val store = InMemoryKeyValueStore()
        JsonLlmProfileRepository(store, json).save(profile)
        val stored = checkNotNull(store.read("llm_profiles"))
        assertFalse(stored.contains("\"contextWindow\":1000000"))

        JsonLlmProfileRepository(store, json, modelLimits = facts).load()
        assertEquals(stored, store.read("llm_profiles"))
    }

    @Test fun absentCatalogKeepsStoredProfile() = runTest {
        val store = InMemoryKeyValueStore()
        JsonLlmProfileRepository(store, json).save(profile)
        assertEquals(profile, JsonLlmProfileRepository(store, json).load().single())
    }
}
