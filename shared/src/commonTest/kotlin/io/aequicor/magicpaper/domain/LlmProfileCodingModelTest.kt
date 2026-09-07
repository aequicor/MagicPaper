package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Кодинг-контур модели: codingModelId задаёт модель агента, не сдвигая
 * чатную модель профиля; без неё — общее значение.
 */
class LlmProfileCodingModelTest {

    private val base = LlmProfile(
        id = "p",
        name = "OpenAI-совместимый",
        baseUrl = "http://x/v1",
        modelId = "chat-model",
    )

    @Test
    fun codingModelFallsBackToProfileModel() {
        assertEquals("chat-model", base.codingModel)
        assertEquals("chat-model", base.forCoding().modelId)
        assertEquals("chat-model", base.forCoding().selectionKey)
        assertEquals(base.baseUrl, base.forCoding().baseUrl)
    }

    @Test
    fun codingModelOverridesOnlyForCodingContour() {
        val withCoding = base.copy(codingModelId = "agent-model")
        assertEquals("agent-model", withCoding.codingModel)
        val coding = withCoding.forCoding()
        assertEquals("agent-model", coding.modelId)
        // Остальные поля уходят как есть, а исходный профиль не меняется.
        assertEquals(base.name, coding.name)
        assertEquals(base.baseUrl, coding.baseUrl)
        assertEquals("chat-model", withCoding.modelId)
    }

    @Test
    fun effortOverrideFollowsCodingModel() {
        val profile = base.copy(
            codingModelId = "agent-model",
            effortOverrides = mapOf("agent-model" to EffortSelection.of(ReasoningEffort.HIGH)),
        )
        assertEquals(ReasoningEffort.HIGH, profile.effortSelectionFor(profile.codingModel).level)
        // Общая модель профиля усилия не наследует — выбор привязан к id модели.
        assertEquals(EffortSelection.Default, profile.effortSelectionFor("chat-model"))
    }
}
