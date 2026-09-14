package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Сервер, который отдаёт только `id`, дополняется фактами каталога движка. */
class DeclaredLimitsModelDirectoryTest {

    private val baseUrl = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"

    private val profile = LlmProfile("alibaba", "Alibaba", baseUrl = baseUrl, modelId = "qwen3.8-max")

    private fun directory(models: List<ModelDefaults.DiscoveredModel>) = object : ModelDirectory {
        override suspend fun models(profile: LlmProfile) = models
    }

    private fun discovered(id: String, metadata: ProviderModel? = null): ModelDefaults.DiscoveredModel =
        ModelDefaults.discover(ProviderType.OPENAI_COMPATIBLE, listOf(id),
            facts = metadata?.let { mapOf(id to it) }.orEmpty()).single()
            .let { if (metadata == null) it else it.copy(metadata = metadata) }

    private fun catalog(limits: CatalogModelLimits) = ModelLimitCatalog { _, ids -> ids.associateWith { limits } }

    @Test fun idOnlyEndpointReceivesCatalogLimits() = runTest {
        val models = DeclaredLimitsModelDirectory(directory(listOf(discovered("qwen3.8-max"))),
            catalog(CatalogModelLimits(1_000_000, 131_072))).models(profile)
        val model = models.single()
        assertEquals(1_000_000, model.metadata?.contextWindow)
        assertEquals(131_072, model.metadata?.maxOutputTokens)
        assertEquals(1_000_000, model.recommendation.advanced.contextLimit)
    }

    @Test fun providerMetadataStaysStronger() = runTest {
        val declared = ProviderModel("qwen3.8-max", contextWindow = 262_144, maxOutputTokens = 8_192)
        val models = DeclaredLimitsModelDirectory(directory(listOf(discovered("qwen3.8-max", declared))),
            catalog(CatalogModelLimits(1_000_000, 131_072))).models(profile)
        assertEquals(declared, models.single().metadata)
    }

    @Test fun endpointAndUnknownIdsArePassedToTheCatalog() = runTest {
        var endpoint = ""
        var requested: List<String> = emptyList()
        DeclaredLimitsModelDirectory(directory(listOf(discovered("a"), discovered("b"))),
            ModelLimitCatalog { base, ids -> endpoint = base; requested = ids.toList(); emptyMap() }).models(profile)
        assertEquals(baseUrl, endpoint)
        assertEquals(listOf("a", "b"), requested)
    }

    @Test fun fullyDeclaredCatalogSkipsTheLookup() = runTest {
        var calls = 0
        val declared = ProviderModel("qwen3.8-max", contextWindow = 262_144, maxOutputTokens = 8_192)
        val models = DeclaredLimitsModelDirectory(directory(listOf(discovered("qwen3.8-max", declared))),
            ModelLimitCatalog { _, _ -> calls++; emptyMap() }).models(profile)
        assertEquals(0, calls)
        assertEquals(listOf(discovered("qwen3.8-max", declared)), models)
    }

    /** Дополненный список уходит в профиль и в рабочий запрос. */
    @Test fun enrichedCatalogReachesTheRequestProfile() = runTest {
        val models = DeclaredLimitsModelDirectory(directory(listOf(discovered("qwen3.8-max"))),
            catalog(CatalogModelLimits(1_000_000, null))).models(profile)
        val saved = profile.withCatalog(models)
        assertEquals(1_000_000, saved.modelCatalog.single().contextWindow)
        assertEquals(1_000_000, saved.forModel().advanced.safeContextLimit)
    }
}
