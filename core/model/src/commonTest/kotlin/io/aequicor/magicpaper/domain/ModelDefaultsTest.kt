package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun glmEffortVocabularyFollowsGeneration() {
        // GLM-5.3 и 5.3-Flash думают всегда и принимают только low/high/max:
        // «medium» из профиля провайдера возвращал 400 code 1210.
        val forced = ModelDefaults.capability(openAi, "glm-5.3-flash") as ReasoningCapability.Controls
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), forced.values)
        assertTrue(forced.mandatory, "выключить мышление у GLM-5.3 нельзя")
        assertEquals(ReasoningEffort.MAX, forced.default, "штатный уровень вендора — max")
        assertEquals(forced, ModelDefaults.capability(openAi, "glm-5.3"))

        // GLM-5.2 принимает всю шкалу, но low/medium сводит к high, xhigh — к max:
        // объявляем только различные на вайре значения.
        val v52 = ModelDefaults.capability(openAi, "glm-5.2") as ReasoningCapability.Controls
        assertEquals(setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH, ReasoningEffort.MAX), v52.values)
        assertEquals(WireDialect.EFFORT, v52.dialect)

        // 4.5…5.1: `reasoning_effort` появился только в 5.2 — остаётся переключатель.
        for (id in listOf("glm-5.1", "glm-5", "glm-4.7", "glm-4.6", "glm-4v-plus")) {
            val toggle = ModelDefaults.capability(openAi, id) as ReasoningCapability.Controls
            assertEquals(WireDialect.THINKING_TOGGLE, toggle.dialect, id)
            assertEquals(listOf(ReasoningEffort.NONE, ReasoningEffort.AUTO), toggle.selectableLevels, id)
        }
        assertEquals(
            WireDialect.THINKING_TOGGLE,
            (ModelDefaults.capability(openAi, "zai/glm-4.7") as ReasoningCapability.Controls).dialect,
            "префикс агрегатора не меняет модель",
        )
    }

    @Test
    fun missingLevelNeverSilentlyDisablesThinking() {
        // У модели есть лишь «выключено» и «сама решает» — просивший «high» не
        // получает выключение: поля в запросе нет, решает сервер.
        val toggle = ReasoningPresets.GLM_THINKING_TOGGLE
        val resolved = toggle.resolveEffort(EffortSelection.of(ReasoningEffort.HIGH))
        assertNull(resolved.level, "подменять включённое мышление выключением нельзя")
        assertEquals(ReasoningEffort.HIGH, resolved.requested)
        // А просьба «выключить» у обязательной модели сравнивается со слабым уровнем.
        assertEquals(
            ReasoningEffort.LOW,
            ReasoningPresets.GLM_53_EFFORT.resolveEffort(EffortSelection.of(ReasoningEffort.NONE)).level,
        )
    }

    @Test
    fun discoverMarksDiscoveredModelsAndRecommends() {
        val found = ModelDefaults.discover(openAi, listOf("gpt-5-mini", "gpt-5-mini", "llama3.2", ""))
        assertEquals(2, found.size, "дубли и пустые отбрасываются")
        val gpt = found.first { it.id == "gpt-5-mini" }
        assertTrue(gpt.supportsEffort)
        assertEquals(Effort.MEDIUM, gpt.recommendation.effort)
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
