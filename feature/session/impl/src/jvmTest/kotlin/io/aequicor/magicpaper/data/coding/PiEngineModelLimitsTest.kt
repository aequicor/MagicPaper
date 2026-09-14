package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderModel
import io.aequicor.magicpaper.domain.withCatalogLimits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Факты о пределах моделей берутся из каталога установленного движка, а не из имени семейства.
 * Правила: тот же хост эндпоинта, тот же идентификатор модели, противоречие — не факт.
 */
class PiEngineModelLimitsTest {

    private val tokenPlan = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
    private val api = "https://api.example.com/v1"

    private fun entry(id: String, baseUrl: String, contextWindow: Int? = null, maxTokens: Int? = null): String {
        val fields = buildList {
            add("\"id\":\"$id\"")
            add("\"baseUrl\":\"$baseUrl\"")
            contextWindow?.let { add("\"contextWindow\":$it") }
            maxTokens?.let { add("\"maxTokens\":$it") }
        }
        return "\"$id\":{${fields.joinToString(",")}}"
    }

    /** Форма файла каталога движка: раздел по диалекту API → модели. */
    private fun providerFile(vararg entries: String) = "{\"openai-completions\":{${entries.joinToString(",")}}}"

    private fun catalog(vararg files: Pair<String, String>): PiEngineModelLimits {
        val dir = kotlin.io.path.createTempDirectory("mp-model-limits").toFile()
        files.forEach { (name, body) -> File(dir, name).writeText(body, Charsets.UTF_8) }
        return PiEngineModelLimits(dir)
    }

    @Test fun apiPathDoesNotChangeTheEndpointFact() {
        val limits = catalog("provider.json" to providerFile(entry("qwen3.8-max", api, 1_000_000, 131_072)))
        val facts = limits.limits("https://api.example.com/compatible-mode/v1", listOf("qwen3.8-max"))
            .getValue("qwen3.8-max")
        assertEquals(1_000_000, facts.contextWindow)
        assertEquals(131_072, facts.maxOutputTokens)
    }

    @Test fun anotherEndpointIsNotAFact() {
        val limits = catalog("provider.json" to providerFile(entry("qwen3.8-max", api, 1_000_000)))
        assertTrue(limits.limits("https://ollama.local/v1", listOf("qwen3.8-max")).isEmpty())
        assertTrue(limits.limits("", listOf("qwen3.8-max")).isEmpty())
    }

    @Test fun unknownModelIsNotAFact() {
        val limits = catalog("provider.json" to providerFile(entry("qwen3.8-max", api, 1_000_000)))
        assertTrue(limits.limits(api, listOf("qwen-local")).isEmpty())
    }

    @Test fun contradictoryRecordsAreNotAFact() {
        val limits = catalog(
            "a.json" to providerFile(entry("glm-5.2", api, 1_000_000, 131_072)),
            "b.json" to providerFile(entry("glm-5.2", "https://api.example.com/v4", 200_000, 131_072)),
        )
        val facts = limits.limits(api, listOf("glm-5.2")).getValue("glm-5.2")
        assertNull(facts.contextWindow)
        assertEquals(131_072, facts.maxOutputTokens)
    }

    @Test fun modelIdIsMatchedCaseInsensitively() {
        val limits = catalog("provider.json" to providerFile(entry("Qwen3.8-Max", api, 262_144)))
        val facts = limits.limits(api, listOf("qwen3.8-max")).getValue("qwen3.8-max")
        assertEquals(262_144, facts.contextWindow)
    }

    @Test fun everyApiSectionOfAFileIsRead() {
        val limits = catalog("provider.json" to
            "{\"openai-completions\":{${entry("qwen3.8-max", api, 1_000_000)}}," +
                "\"anthropic-messages\":{${entry("claude-sonnet", api, 200_000)}}}")
        val facts = limits.limits(api, listOf("qwen3.8-max", "claude-sonnet"))
        assertEquals(1_000_000, facts.getValue("qwen3.8-max").contextWindow)
        assertEquals(200_000, facts.getValue("claude-sonnet").contextWindow)
    }

    @Test fun serviceFilesAreNotReadAsCatalogs() {
        val limits = catalog(
            ".manifest.json" to "{\"schemaVersion\":3,\"files\":{\"provider.json\":\"hash\"}}",
            "provider.json" to providerFile(entry("qwen3.8-max", api, 262_144)),
        )
        val facts = limits.limits(api, listOf("qwen3.8-max")).getValue("qwen3.8-max")
        assertEquals(262_144, facts.contextWindow)
    }

    @Test fun missingEngineLeavesLimitsToTheProvider() {
        val empty = kotlin.io.path.createTempDirectory("mp-no-engine").toFile()
        assertNull(engineModelLimits(empty))
    }

    @Test fun installedEngineDeclaresTheTokenPlanLimits() {
        val limits = installedEngine()
        val facts = limits.limits(tokenPlan, listOf("qwen3.8-max", "glm-5.2", "qwen-local-7b"))
        assertEquals(1_000_000, facts.getValue("qwen3.8-max").contextWindow)
        assertEquals(131_072, facts.getValue("qwen3.8-max").maxOutputTokens)
        assertEquals(1_000_000, facts.getValue("glm-5.2").contextWindow)
        // Локальной модели в каталоге нет: факт не выдаётся, предел остаётся за конфигурацией.
        assertTrue("qwen-local-7b" !in facts)
    }

    /**
     * Симптом, из-за которого правка нужна: профиль Alibaba сохранён с `contextWindow: null`,
     * и `models.json` движка уходил с потолком конфигурации вместо предела модели.
     */
    @Test fun installedEngineRepairsTheRuntimeConfiguration() {
        val limits = installedEngine()
        val saved = LlmProfile(
            id = "alibaba", name = "Alibaba", baseUrl = tokenPlan, modelId = "qwen3.8-max", modelLibraryVersion = 1,
            advanced = AdvancedLlmOptions(contextLimit = 128_000),
            modelCatalog = listOf(ProviderModel("qwen3.8-max"), ProviderModel("qwen3.7-plus")),
        )
        val enriched = saved.withCatalogLimits(limits)
        val runtime = enriched.forCoding()
        assertEquals(1_000_000, PiModelsConfig.contextWindow(runtime))
        assertTrue(PiModelsConfig.json(runtime).contains("\"contextWindow\":1000000"))
        // Без фактов каталога запрос остаётся на пределе конфигурации — догадок по семейству нет.
        assertEquals(128_000, PiModelsConfig.contextWindow(saved.forCoding()))
    }

    /** Каталог установленного движка; без движка проверка пропускается, а не проходит молча. */
    private fun installedEngine(): PiEngineModelLimits {
        val prefix = File(File(System.getProperty("user.home"), ".MagicPaper"), "coding/prefix")
        val data = resolvePiAiDist(prefix)?.let { File(it, "providers/data") }
        org.junit.Assume.assumeTrue("The installed Pi engine catalog is required", data?.isDirectory == true)
        return PiEngineModelLimits(checkNotNull(data))
    }
}
