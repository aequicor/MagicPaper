package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.AdvancedSettings
import io.aequicor.magicpaper.domain.EffortLevel
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поведение пейлоадов. Ключевое правило: поле усилия попадает в запрос
 * ТОЛЬКО если модель его поддерживает (supportsEffort); иначе усилие
 * деградирует в температурный пресет, чтобы сервер не вернул 400.
 */
class LlmPayloadsTest {

    private val messages = listOf(
        LlmMessage("system", "ты ассистент"),
        LlmMessage("user", "привет"),
    )

    private fun profile(
        effort: EffortLevel = EffortLevel.MEDIUM,
        advanced: AdvancedSettings = AdvancedSettings(),
        provider: ProviderType = ProviderType.OPENAI_COMPATIBLE,
        modelId: String = "custom-model",
    ) = LlmProfile(
        id = "p",
        name = "тест",
        provider = provider,
        baseUrl = "http://x/v1",
        modelId = modelId,
        effort = effort,
        advanced = advanced,
    )

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.num(key: String): Double? = (get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    // ---- OpenAI-совместимый -------------------------------------------------

    @Test
    fun openAiNativeEffortSendsReasoningEffort() {
        val p = profile(effort = EffortLevel.HIGH, modelId = "gpt-5-mini")
        val payload = LlmPayloads.openAi(p, messages, supportsEffort = true)
        assertEquals("high", payload.str("reasoning_effort"))
        assertNull(payload.num("temperature"), "явной температуры нет — модель сама управляет усилием")
        assertEquals("gpt-5-mini", payload.str("model"))
    }

    @Test
    fun openAiWithoutNativeEffortSendsTemperaturePresetInstead() {
        val p = profile(effort = EffortLevel.LOW)
        val payload = LlmPayloads.openAi(p, messages, supportsEffort = false)
        assertNull(payload.str("reasoning_effort"), "без поддержки усилия поле не отправляется")
        assertEquals(0.2, payload.num("temperature"))
    }

    @Test
    fun openAiExplicitTemperatureWins() {
        val p = profile(advanced = AdvancedSettings(temperature = 1.7))
        val payload = LlmPayloads.openAi(p, messages, supportsEffort = false)
        assertEquals(1.7, payload.num("temperature"))
    }

    @Test
    fun openAiEmptyAdvancedNotSent() {
        val payload = LlmPayloads.openAi(profile(), messages, supportsEffort = false)
        assertNull(payload.int("max_tokens"))
        assertNull(payload.num("top_p"))
    }

    @Test
    fun openAiMaxTokensAndTopPSentWhenSet() {
        val p = profile(advanced = AdvancedSettings(maxTokens = 512, topP = 0.9))
        val payload = LlmPayloads.openAi(p, messages, supportsEffort = false)
        assertEquals(512, payload.int("max_tokens"))
        assertEquals(0.9, payload.num("top_p"))
    }

    // ---- Anthropic -----------------------------------------------------------

    @Test
    fun anthropicSystemExtractedAndMaxTokensAlwaysPresent() {
        val payload = LlmPayloads.anthropic(profile(), messages, supportsEffort = false)
        assertEquals("ты ассистент", payload.str("system"))
        assertNotNull(payload.int("max_tokens"), "Anthropic требует max_tokens")
    }

    @Test
    fun anthropicThinkingOnlyForSupportingModels() {
        val payload = LlmPayloads.anthropic(profile(effort = EffortLevel.MEDIUM), messages, supportsEffort = true)
        val thinking = payload["thinking"] as JsonObject
        assertEquals("enabled", thinking.str("type"))
        assertEquals(LlmPayloads.anthropicThinkingBudget(EffortLevel.MEDIUM), thinking.int("budget_tokens"))
        // max_tokens обязан превышать бюджет мышления — автоматически поднимаем.
        assertTrue(payload.int("max_tokens")!! > thinking.int("budget_tokens")!!)
    }

    @Test
    fun anthropicHighEffortBumpsMaxTokens() {
        val p = profile(effort = EffortLevel.HIGH, advanced = AdvancedSettings(maxTokens = 2000))
        val payload = LlmPayloads.anthropic(p, messages, supportsEffort = true)
        val budget = (payload["thinking"] as JsonObject).int("budget_tokens")!!
        assertTrue(payload.int("max_tokens")!! > budget, "max_tokens должен превышать бюджет")
    }

    @Test
    fun anthropicWithoutEffortSupportHasNoThinking() {
        val p = profile(advanced = AdvancedSettings(temperature = 0.5, maxTokens = 2000))
        val payload = LlmPayloads.anthropic(p, messages, supportsEffort = false)
        assertNull(payload["thinking"], "модель без нативного усилия — без блока thinking")
        assertEquals(0.5, payload.num("temperature"))
        assertEquals(2000, payload.int("max_tokens"))
    }

    @Test
    fun anthropicSystemMessagesNotInMessages() {
        val payload = LlmPayloads.anthropic(profile(), messages, supportsEffort = false)
        val roles = (payload["messages"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull }
        assertTrue(roles != null && "system" !in roles, "системные сообщения уходят в поле system")
    }

    @Test
    fun anthropicNoTemperatureWithThinking() {
        // С включённым thinking Anthropic требует температуру 1 — не отправляем вовсе.
        val p = profile(advanced = AdvancedSettings(temperature = 0.5))
        val payload = LlmPayloads.anthropic(p, messages, supportsEffort = true)
        assertNull(payload.num("temperature"))
    }

    // ---- Google ---------------------------------------------------------------

    @Test
    fun googleSystemInstructionAndContentsRoles() {
        val payload = LlmPayloads.google(profile(), messages, supportsEffort = false)
        val systemInstruction = payload["systemInstruction"] as JsonObject
        assertNotNull(systemInstruction)
        val contents = payload["contents"] as kotlinx.serialization.json.JsonArray
        val roles = contents.mapNotNull { ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("user"), roles)
        assertFalse(roles.contains("system"))
    }

    @Test
    fun googleThinkingConfigOnlyForSupportingModels() {
        val payload = LlmPayloads.google(profile(effort = EffortLevel.HIGH), messages, supportsEffort = true)
        val config = (payload["generationConfig"] as JsonObject)["thinkingConfig"] as JsonObject
        assertEquals(LlmPayloads.googleThinkingBudget(EffortLevel.HIGH), config.int("thinkingBudget"))
    }

    @Test
    fun googleWithoutEffortSupportHasNoThinkingConfig() {
        val payload = LlmPayloads.google(profile(), messages, supportsEffort = false)
        val config = payload["generationConfig"] as JsonObject
        assertNull(config["thinkingConfig"])
    }

    @Test
    fun googleTemperaturePresetWhenNoExplicit() {
        val payload = LlmPayloads.google(profile(effort = EffortLevel.LOW), messages, supportsEffort = false)
        val config = payload["generationConfig"] as JsonObject
        assertEquals(LlmPayloads.temperatureForEffort(EffortLevel.LOW), config.num("temperature"))
    }
}
