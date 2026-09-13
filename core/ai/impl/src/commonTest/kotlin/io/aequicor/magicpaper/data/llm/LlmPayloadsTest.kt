package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningCapability
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.ReasoningPresets
import kotlinx.serialization.json.JsonArray
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
 * Поведение пейлоадов. Ключевое правило: поле усилия попадает в запрос только
 * по объявленным моделью возможностям ([ReasoningCapability]); выбор
 * «по умолчанию провайдера» не порождает поля вовсе. Когда усилие активно,
 * температура не подменяет его и не отправляется.
 */
class LlmPayloadsTest {

    private val messages = listOf(
        LlmMessage(LlmChatRole.SYSTEM, "ты ассистент"),
        LlmMessage(LlmChatRole.USER, "привет"),
    )

    private fun profile(
        effort: EffortSelection = EffortSelection.Default,
        advanced: AdvancedLlmOptions = AdvancedLlmOptions(),
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
    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    // ---- OpenAI-совместимый -------------------------------------------------

    @Test
    fun openAiNativeEffortSendsReasoningEffort() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH), modelId = "gpt-5-mini")
        val payload = LlmPayloads.openAi(p, messages, ReasoningPresets.OPENAI_EFFORT)
        assertEquals("high", payload.str("reasoning_effort"))
        assertNull(payload.num("temperature"), "при активном усилии температура не подменяет его")
        assertEquals("gpt-5-mini", payload.str("model"))
    }

    @Test
    fun openAiDefaultSelectionSendsNoEffortField() {
        val payload = LlmPayloads.openAi(profile(), messages, ReasoningPresets.OPENAI_EFFORT)
        assertNull(payload.str("reasoning_effort"), "«по умолчанию» не выдумывает поле")
        assertNotNull(payload.num("temperature"), "без усилия температура отправляется")
    }

    @Test
    fun openAiUnknownLevelClampedToDeclared() {
        // Просим максимум, а модель объявила только до высокого — берём ближайший.
        val p = profile(effort = EffortSelection.of(ReasoningEffort.MAX))
        val payload = LlmPayloads.openAi(p, messages, ReasoningPresets.COMPAT_EFFORT)
        assertEquals("high", payload.str("reasoning_effort"))
    }

    @Test
    fun openAiWithoutCapabilitySendsNoEffort() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH))
        val payload = LlmPayloads.openAi(p, messages, ReasoningCapability.None)
        assertNull(payload.str("reasoning_effort"), "ручки нет — поле не отправляется")
        assertNotNull(payload.num("temperature"))
    }

    @Test
    fun openAiExplicitTemperatureWinsWhenEffortOff() {
        val p = profile(advanced = AdvancedLlmOptions(temperature = 1.7))
        val payload = LlmPayloads.openAi(p, messages, ReasoningCapability.None)
        assertEquals(1.7, payload.num("temperature"))
    }

    @Test
    fun openAiNullTemperatureNotSent() {
        val p = profile(advanced = AdvancedLlmOptions(temperature = null))
        val payload = LlmPayloads.openAi(p, messages, ReasoningCapability.None)
        assertNull(payload.num("temperature"), "null — «по умолчанию провайдера»")
    }

    @Test
    fun openAiMaxTokensAlwaysSentTopPWhenSet() {
        val p = profile(advanced = AdvancedLlmOptions(maxTokens = 512, topP = 0.9))
        val payload = LlmPayloads.openAi(p, messages, ReasoningCapability.None)
        assertEquals(512, payload.int("max_tokens"))
        assertEquals(0.9, payload.num("top_p"))
    }

    // ---- Anthropic -----------------------------------------------------------

    @Test
    fun anthropicSystemExtractedAndMaxTokensAlwaysPresent() {
        val payload = LlmPayloads.anthropic(profile(), messages, ReasoningCapability.None)
        assertEquals("ты ассистент", payload.str("system"))
        assertNotNull(payload.int("max_tokens"), "Anthropic требует max_tokens")
    }

    @Test
    fun anthropicBudgetThinkingForExplicitLevel() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.MEDIUM), provider = ProviderType.ANTHROPIC)
        val payload = LlmPayloads.anthropic(p, messages, ReasoningPresets.ANTHROPIC_BUDGET)
        val thinking = payload.obj("thinking")
        assertEquals("enabled", thinking?.str("type"))
        val budget = thinking?.int("budget_tokens")
        assertNotNull(budget)
        assertTrue(budget > 0, "бюджет мышления положительный")
        assertTrue(payload.int("max_tokens")!! > budget, "max_tokens обязан превышать бюджет")
        assertNull(payload.num("temperature"), "с включённым мышлением температура не отправляется")
    }

    @Test
    fun anthropicBudgetScalesWithEffort() {
        fun budget(level: ReasoningEffort): Int {
            val p = profile(effort = EffortSelection.of(level), provider = ProviderType.ANTHROPIC)
            val thinking = LlmPayloads.anthropic(p, messages, ReasoningPresets.ANTHROPIC_BUDGET).obj("thinking")
            return thinking!!.int("budget_tokens")!!
        }
        val low = budget(ReasoningEffort.LOW)
        val medium = budget(ReasoningEffort.MEDIUM)
        val high = budget(ReasoningEffort.HIGH)
        assertTrue(low < medium && medium < high, "бюджет растёт с усилием")
        assertTrue(low >= 1024, "нижняя граница бюджета")
    }

    @Test
    fun anthropicDefaultSelectionOmitsThinking() {
        val p = profile(provider = ProviderType.ANTHROPIC)
        val payload = LlmPayloads.anthropic(p, messages, ReasoningPresets.ANTHROPIC_BUDGET)
        assertNull(payload["thinking"], "«по умолчанию» не включает блок thinking")
        assertNotNull(payload.num("temperature"))
    }

    @Test
    fun anthropicNoneLevelOmitsThinking() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.NONE), provider = ProviderType.ANTHROPIC)
        val payload = LlmPayloads.anthropic(p, messages, ReasoningPresets.ANTHROPIC_BUDGET)
        assertNull(payload["thinking"], "выключенное усилие — без блока thinking")
    }

    @Test
    fun anthropicAdaptiveSendsEffortLevel() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH), provider = ProviderType.ANTHROPIC)
        val payload = LlmPayloads.anthropic(p, messages, ReasoningPresets.ANTHROPIC_ADAPTIVE)
        assertEquals("adaptive", payload.obj("thinking")?.str("type"))
        assertEquals("high", payload.obj("output_config")?.str("effort"))
        assertNull(payload.num("temperature"))
    }

    @Test
    fun anthropicSystemMessagesNotInMessages() {
        val payload = LlmPayloads.anthropic(profile(), messages, ReasoningCapability.None)
        val roles = (payload["messages"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull }
        assertTrue(roles != null && "system" !in roles, "системные сообщения уходят в поле system")
    }

    @Test
    fun anthropicWithoutCapabilityHasNoThinking() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH))
        val payload = LlmPayloads.anthropic(p, messages, ReasoningCapability.None)
        assertNull(payload["thinking"], "модель без возможностей — без блока thinking")
        assertNotNull(payload.num("temperature"))
    }

    // ---- Google ---------------------------------------------------------------

    @Test
    fun googleSystemInstructionAndContentsRoles() {
        val payload = LlmPayloads.google(profile(), messages, ReasoningCapability.None)
        assertNotNull(payload["systemInstruction"])
        val contents = payload["contents"] as JsonArray
        val roles = contents.mapNotNull { ((it as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf("user"), roles)
        assertFalse(roles.contains("system"))
    }

    @Test
    fun googleBudgetThinkingForExplicitLevel() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH), provider = ProviderType.GOOGLE)
        val payload = LlmPayloads.google(p, messages, ReasoningPresets.GEMINI_BUDGET)
        val config = payload.obj("generationConfig")?.obj("thinkingConfig")
        val budget = config?.int("thinkingBudget")
        assertNotNull(budget)
        assertTrue(budget > 0)
        assertNull(payload.obj("generationConfig")?.num("temperature"), "с мышлением температура не отправляется")
    }

    @Test
    fun googleAutoMapsToDynamicBudget() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.AUTO), provider = ProviderType.GOOGLE)
        val payload = LlmPayloads.google(p, messages, ReasoningPresets.GEMINI_BUDGET)
        val config = payload.obj("generationConfig")?.obj("thinkingConfig")
        assertEquals(-1, config?.int("thinkingBudget"), "«авто» — динамический бюджет")
    }

    @Test
    fun googleLevelDialectSendsThinkingLevel() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.MEDIUM), provider = ProviderType.GOOGLE)
        val payload = LlmPayloads.google(p, messages, ReasoningPresets.GEMINI_LEVEL)
        val config = payload.obj("generationConfig")?.obj("thinkingConfig")
        assertEquals("medium", config?.str("thinkingLevel"))
    }

    @Test
    fun googleDefaultSelectionOmitsThinkingConfig() {
        val payload = LlmPayloads.google(profile(provider = ProviderType.GOOGLE), messages, ReasoningPresets.GEMINI_BUDGET)
        assertNull(payload.obj("generationConfig")?.get("thinkingConfig"))
        assertNotNull(payload.obj("generationConfig")?.num("temperature"))
    }

    @Test
    fun googleWithoutCapabilityHasNoThinkingConfig() {
        val p = profile(effort = EffortSelection.of(ReasoningEffort.HIGH), provider = ProviderType.GOOGLE)
        val payload = LlmPayloads.google(p, messages, ReasoningCapability.None)
        assertNull(payload.obj("generationConfig")?.get("thinkingConfig"))
    }

    // ---- Вложения -------------------------------------------------------
    // Изображения — мультимодальными блоками, текст — в текст сообщения;
    // без вложений формат запроса не меняется.

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val imageAttachment = Attachment.fromBytes("снимок.png", "image/png", pngBytes)
    private val textAttachment = Attachment.fromBytes("заметка.txt", "text/plain", "строка из файла".encodeToByteArray())

    private fun userWith(vararg attachments: Attachment) = listOf(
        LlmMessage(LlmChatRole.SYSTEM, "ты ассистент"),
        LlmMessage(LlmChatRole.USER, "что на картинке?", attachments.toList()),
    )

    private fun openAiUserContent(payload: JsonObject): kotlinx.serialization.json.JsonElement? =
        (payload["messages"] as? JsonArray)?.last()?.let { (it as? JsonObject)?.get("content") }

    @Test
    fun openAiWithoutAttachmentsKeepsPlainContent() {
        val payload = LlmPayloads.openAi(profile(), messages)
        val content = openAiUserContent(payload)
        assertTrue(content is JsonPrimitive, "без вложений контент — строка")
        assertEquals("привет", (content as JsonPrimitive).content)
    }

    @Test
    fun openAiImageBecomesDataUrlBlock() {
        val payload = LlmPayloads.openAi(profile(), userWith(imageAttachment))
        val blocks = openAiUserContent(payload) as? JsonArray
        assertNotNull(blocks, "с картинкой контент — массив блоков")
        assertEquals("text", (blocks[0] as JsonObject).str("type"))
        assertTrue((blocks[0] as JsonObject).str("text")!!.contains("что на картинке?"))
        val imageBlock = blocks[1] as JsonObject
        assertEquals("image_url", imageBlock.str("type"))
        val url = imageBlock.obj("image_url")?.str("url")
        assertTrue(url!!.startsWith("data:image/png;base64,"), "картинка уходит data-URL'ом")
        assertTrue(url.endsWith(imageAttachment.dataBase64))
    }

    @Test
    fun openAiTextAttachmentInlinedIntoText() {
        val payload = LlmPayloads.openAi(profile(), userWith(textAttachment))
        val content = openAiUserContent(payload)
        assertTrue(content is JsonPrimitive, "текстовый файл не порождает блоки")
        val text = (content as JsonPrimitive).content
        assertTrue(text.contains("Файл заметка.txt:"))
        assertTrue(text.contains("строка из файла"))
    }

    @Test
    fun anthropicImageBecomesBase64Block() {
        val payload = LlmPayloads.anthropic(profile(provider = ProviderType.ANTHROPIC), userWith(imageAttachment))
        val blocks = (payload["messages"] as? JsonArray)?.last()?.let { (it as? JsonObject)?.get("content") } as? JsonArray
        assertNotNull(blocks, "с картинкой контент — массив блоков")
        assertEquals("text", (blocks[0] as JsonObject).str("type"))
        val imageBlock = blocks[1] as JsonObject
        assertEquals("image", imageBlock.str("type"))
        val source = imageBlock.obj("source")
        assertEquals("base64", source?.str("type"))
        assertEquals("image/png", source?.str("media_type"))
        assertEquals(imageAttachment.dataBase64, source?.str("data"))
    }

    @Test
    fun googleImageBecomesInlineData() {
        val payload = LlmPayloads.google(profile(provider = ProviderType.GOOGLE), userWith(imageAttachment))
        val parts = (payload["contents"] as? JsonArray)?.last()?.let { (it as? JsonObject)?.get("parts") } as? JsonArray
        assertNotNull(parts)
        assertTrue((parts[0] as JsonObject).str("text")!!.contains("что на картинке?"))
        val inline = (parts[1] as JsonObject).obj("inlineData")
        assertEquals("image/png", inline?.str("mimeType"))
        assertEquals(imageAttachment.dataBase64, inline?.str("data"))
    }

    @Test
    fun binaryAttachmentOnlyAddsNotice() {
        val fileAttachment = Attachment.fromBytes("архив.zip", "application/zip", byteArrayOf(1, 2, 3))
        assertEquals(AttachmentKind.FILE, fileAttachment.kind)
        val payload = LlmPayloads.openAi(profile(), userWith(fileAttachment))
        val content = openAiUserContent(payload)
        assertTrue(content is JsonPrimitive)
        val text = (content as JsonPrimitive).content
        assertTrue(text.contains("архив.zip"), "бинарный файл упоминается в примечании")
        assertTrue(text.contains("без передачи содержимого"))
    }
}
