package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Объявление провайдера перевешивает эвристику: ручка показывает словарь
 * модели, а диалект и бюджет остаются от формы ручки.
 */
class DeclaredReasoningTest {

    private val openAi = ProviderType.OPENAI_COMPATIBLE

    @Test
    fun declaredLevelsNarrowTheLadder() {
        val declared = DeclaredReasoning(
            efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
        )
        val capability = ModelDefaults.capability(openAi, "kimi-k3", declared) as ReasoningCapability.Controls
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), capability.values)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX), capability.selectableLevels)
        assertEquals(ReasoningEffort.HIGH, capability.default, "прежний дефолт вне словаря — берём ближайший объявленный")
    }

    @Test
    fun declaredLevelsKeepDialectAndBudget() {
        val declared = DeclaredReasoning(efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        val capability = ModelDefaults.capability(ProviderType.ANTHROPIC, "claude-opus-4-1", declared)
            as ReasoningCapability.Controls
        assertEquals(WireDialect.BUDGET_TOKENS, capability.dialect)
        assertEquals(ReasoningPresets.ANTHROPIC_BUDGET.budget, capability.budget)
    }

    @Test
    fun mandatoryRemovesOffSwitch() {
        val declared = DeclaredReasoning(
            efforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH),
            mandatory = true,
        )
        val capability = ModelDefaults.capability(openAi, "gpt-5.2", declared) as ReasoningCapability.Controls
        assertFalse(ReasoningEffort.NONE in capability.values, "мышление нельзя выключить — уровня нет на ручке")
        assertTrue(capability.mandatory)
    }

    @Test
    fun providerSaysNoReasoningAtAll() {
        assertFalse(ModelDefaults.supportsEffort(openAi, "gpt-5.2", DeclaredReasoning.None))
        assertEquals(
            ReasoningCapability.None,
            ModelDefaults.capability(openAi, "gpt-5.2", DeclaredReasoning.None),
        )
    }

    @Test
    fun reasoningWithoutLevelsKeepsHeuristic() {
        val heuristic = ModelDefaults.capability(openAi, "gpt-5.2")
        assertEquals(heuristic, ModelDefaults.capability(openAi, "gpt-5.2", DeclaredReasoning()))
    }

    @Test
    fun declaredLevelsReachUnknownModel() {
        val declared = DeclaredReasoning(efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM))
        val capability = ModelDefaults.capability(openAi, "some-local-model", declared)
            as ReasoningCapability.Controls
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM), capability.values)
        assertEquals(WireDialect.EFFORT, capability.dialect)
    }

    @Test
    fun profileRemembersDeclarationPerModel() {
        val profile = LlmProfile(
            id = "p",
            name = "Источник",
            baseUrl = "http://localhost:11434/v1",
            modelId = "gpt-5.2",
            codingModelId = "llama3.2",
        ).withDeclaredReasoning("llama3.2", DeclaredReasoning.None)

        assertFalse(ModelDefaults.supportsEffort(profile, "llama3.2"), "объявление важнее эвристики по имени")
        assertTrue(ModelDefaults.supportsEffort(profile, "gpt-5.2"), "другие модели объявление не задевает")
        assertFalse(ModelDefaults.capability(profile, "llama3.2").supportsEffort)
    }

    @Test
    fun unsupportedSelectionIsClampedToDeclaredLevel() {
        val capability = ModelDefaults.capability(
            openAi,
            "glm-5.2",
            DeclaredReasoning(efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX)),
        )
        val resolved = capability.resolveEffort(EffortSelection.of(ReasoningEffort.LOW))
        assertTrue(resolved.clamped)
        assertEquals(ReasoningEffort.HIGH, resolved.level)
    }
}
