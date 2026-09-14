package io.aequicor.magicpaper.domain

import kotlin.test.*

/**
 * Каталог движка дополняет пределы, которых провайдер не объявил. Догадок по семейству
 * модели здесь быть не должно: источник обязан назвать эндпоинт и идентификатор модели.
 */
class ModelLimitCatalogTest {

    private val baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"

    private fun profile(catalog: List<ProviderModel>) = LlmProfile(
        id = "alibaba", name = "Alibaba", baseUrl = baseUrl, modelId = "qwen3.8-max",
        advanced = AdvancedLlmOptions(contextLimit = 32_768), modelCatalog = catalog,
    )

    private val engineFacts = ModelLimitCatalog { _, ids ->
        ids.associateWith { CatalogModelLimits(contextWindow = 1_000_000, maxOutputTokens = 131_072) }
    }

    @Test fun endpointWithoutMetadataGetsDeclaredLimits() {
        val updated = profile(listOf(ProviderModel("qwen3.8-max"))).withCatalogLimits(engineFacts)
        val model = updated.modelCatalog.single()
        assertEquals(1_000_000, model.contextWindow)
        assertEquals(131_072, model.maxOutputTokens)
        // Ограничения профиля и его варианты — собственность пользователя, каталог их не трогает.
        assertEquals(32_768, updated.advanced.contextLimit)
        assertTrue(updated.variants.isEmpty())
    }

    @Test fun providerDeclarationStaysStrongerThanCatalog() {
        val updated = profile(listOf(ProviderModel("local-fork", contextWindow = 8_192))).withCatalogLimits(engineFacts)
        val model = updated.modelCatalog.single()
        assertEquals(8_192, model.contextWindow)
        assertEquals(131_072, model.maxOutputTokens)
    }

    @Test fun missingFactsLeaveTheProfileInstanceAlone() {
        val original = profile(listOf(ProviderModel("qwen3.8-max")))
        assertSame(original, original.withCatalogLimits(null))
        assertSame(original, original.withCatalogLimits(ModelLimitCatalog { _, _ -> emptyMap() }))
    }

    @Test fun fullyDeclaredCatalogIsNotRequested() {
        var calls = 0
        val declared = profile(listOf(ProviderModel("qwen3.8-max", contextWindow = 262_144, maxOutputTokens = 32_768)))
        val counting = ModelLimitCatalog { _, _ -> calls++; emptyMap() }
        assertSame(declared, declared.withCatalogLimits(counting))
        assertEquals(0, calls)
    }

    @Test fun onlyUnknownLimitsAreRequested() {
        var endpoint = ""
        var requested: List<String> = emptyList()
        val catalog = ModelLimitCatalog { base, ids -> endpoint = base; requested = ids.toList(); emptyMap() }
        profile(listOf(
            ProviderModel("known", contextWindow = 8_192, maxOutputTokens = 1_024),
            ProviderModel("qwen3.8-max"),
        )).withCatalogLimits(catalog)
        assertEquals(baseUrl, endpoint)
        assertEquals(listOf("qwen3.8-max"), requested)
    }

    @Test fun discoveredRecommendationFollowsTheCatalogFact() {
        val discovered = ModelDefaults.discover(ProviderType.OPENAI_COMPATIBLE, listOf("qwen3.8-max")).single()
        assertEquals(AdvancedLlmOptions().safeContextLimit, discovered.recommendation.advanced.contextLimit)

        val updated = discovered.withCatalogLimits(
            ProviderType.OPENAI_COMPATIBLE, CatalogModelLimits(1_000_000, 131_072))
        assertEquals(1_000_000, updated.metadata?.contextWindow)
        assertEquals(131_072, updated.metadata?.maxOutputTokens)
        assertEquals(1_000_000, updated.recommendation.advanced.contextLimit)
        assertSame(updated, updated.withCatalogLimits(ProviderType.OPENAI_COMPATIBLE, null))
    }

    /**
     * Симптом поломки: эндпоинт отдаёт только `id`, и запрос уходил с потолком конфигурации.
     * После дополнения фактом каталога рабочий профиль объявляет предел модели.
     */
    @Test fun requestProfileUsesTheCatalogFact() {
        assertEquals(AdvancedLlmOptions().safeContextLimit,
            profile(listOf(ProviderModel("qwen3.8-max"))).forCoding().advanced.safeContextLimit)
        assertEquals(1_000_000,
            profile(listOf(ProviderModel("qwen3.8-max")))
                .withCatalogLimits(engineFacts).forCoding().advanced.safeContextLimit)
    }
}
