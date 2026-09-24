package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    /**
     * Ключ подписки GLM Coding Plan принимает только coding-эндпоинт: на общем адресе Z.AI
     * отвечает 429 с кодом 1113 «Insufficient balance or no resource package», даже когда
     * квота плана цела. Каталог обязан предлагать оба адреса как разные подключения.
     */
    @Test
    fun zaiCodingPlanIsItsOwnPresetWithTheCodingEndpoint() {
        val plan = ProviderCatalog.all.first { it.displayName.contains("Coding Plan") }
        val general = ProviderCatalog.all.first { it.displayName == "Zhipu GLM" }
        assertEquals("https://api.z.ai/api/coding/paas/v4", plan.defaultBaseUrl)
        assertEquals("https://api.z.ai/api/paas/v4", general.defaultBaseUrl)
        assertTrue(plan.requiresKey)
        assertTrue(plan.models.isNotEmpty())
        // Оба подключения открыты в быстром выборе и не сливаются в одну строку.
        assertTrue(ProviderCatalog.quickPickPresets.contains(plan))
        assertTrue(ProviderCatalog.quickPickPresets.contains(general))
        // Сохранённый профиль редактор опознаёт по адресу: coding-пресет не подменяется общим.
        val specForSavedProfile = ProviderCatalog.all.first {
            it.type == ProviderType.OPENAI_COMPATIBLE &&
                (it.defaultBaseUrl.isBlank() || it.defaultBaseUrl == plan.defaultBaseUrl)
        }
        assertEquals(plan.displayName, specForSavedProfile.displayName)
    }

    /** Отказ в доступе к модели чаще всего означает несовпадение ключа и адреса вендора. */
    @Test
    fun alternativeEndpointSwitchesBetweenTheGeneralAndTheCodingSurface() {
        assertEquals("https://api.z.ai/api/coding/paas/v4",
            ProviderCatalog.alternativeEndpoint("https://api.z.ai/api/paas/v4"))
        assertEquals("https://api.z.ai/api/paas/v4",
            ProviderCatalog.alternativeEndpoint("https://api.z.ai/api/coding/paas/v4/"))
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4",
            ProviderCatalog.alternativeEndpoint("https://open.bigmodel.cn/api/paas/v4"))
        assertNull(ProviderCatalog.alternativeEndpoint("https://api.openai.com/v1"))
        assertNull(ProviderCatalog.alternativeEndpoint(""))
    }

    @Test
    fun chatGptSubscriptionNeedsNeitherUrlNorApiKey() {
        val spec = ProviderCatalog.all.first { it.type == ProviderType.OPENAI_SUBSCRIPTION }
        assertTrue(spec.desktopOnly)
        assertTrue(spec.usesSubscription)
        assertTrue(!spec.requiresKey)
        assertTrue(spec.defaultBaseUrl.isBlank())
        assertTrue(
            LlmProfile(
                id = "subscription",
                name = spec.displayName,
                provider = ProviderType.OPENAI_SUBSCRIPTION,
                modelId = spec.models.first().id,
            ).configured,
        )
    }
}
