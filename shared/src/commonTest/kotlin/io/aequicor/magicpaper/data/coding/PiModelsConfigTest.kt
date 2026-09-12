package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.EffortSelection
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.AdvancedLlmOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Конфиг модели для пи-агента: единственный источник правды о лимитах и
 * рассуждении. Тесты держат инварианты, каждый из которых уже стоил прогона
 * «Агент завершился без ответа».
 */
class PiModelsConfigTest {

    private fun profile(
        modelId: String,
        baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/",
        maxTokens: Int = 16_384,
        contextLimit: Int = 128_000,
        effort: EffortSelection = EffortSelection.Default,
        apiKey: String = "sk-test",
    ) = LlmProfile(
        id = "p",
        name = "Alibaba",
        baseUrl = baseUrl,
        apiKey = apiKey,
        provider = ProviderType.OPENAI_COMPATIBLE,
        modelId = modelId,
        effort = effort,
        advanced = AdvancedLlmOptions(maxTokens = maxTokens, contextLimit = contextLimit),
    )

    private val qwen get() = profile("qwen3.8-flash")

    @Test fun computerUseKeepsToolImagesInPiModelInput() {
        assertEquals(listOf("text", "image"), PiModelsConfig.root(qwen, imageInput = true).model()["input"]!!.jsonArray.map { it.toString().trim('"') })
        assertNull(PiModelsConfig.root(qwen).model()["input"])
    }

    @Test fun visionOpenAiCompatRequiresAssistantAfterToolResult() {
        // DashScope требует чередования ролей: после tool-сообщения нельзя
        // сразу ставить user-сообщение (которым pi отправляет image_url из
        // toolResult). Без флага сервер возвращает 400 «Unexpected item type».
        // Используем vision-модель (qwen3.7-max), т.к. флаг выставляется
        // только когда модель реально поддерживает изображения.
        val visionProfile = profile("qwen3.7-max")
        val compat = PiModelsConfig.root(visionProfile, imageInput = true).compat()
        assertEquals(true, compat.bool("requiresAssistantAfterToolResult"),
            "OpenAI-совместимый с vision — требует assistant между tool и user")
        // Без imageInput промежуточный assistant не нужен.
        val noImage = PiModelsConfig.root(visionProfile, imageInput = false).compat()
        assertNull(noImage["requiresAssistantAfterToolResult"],
            "Без imageInput — флаг не выставляется")
        // Text-only модель: флаг не выставляется даже с imageInput=true
        // (computerUse может включить imageInput, но модель не примет image_url).
        val textOnly = PiModelsConfig.root(qwen, imageInput = true).compat()
        assertNull(textOnly["requiresAssistantAfterToolResult"],
            "Text-only модель — флаг не выставляется")
    }

    @Test fun nonOpenAiCompatDoesNotRequireAssistantAfterToolResult() {
        // Anthropic и Google имеют собственные форматы сообщений и не требуют
        // промежуточного assistant между toolResult и user.
        val anthropic = profile("claude-sonnet-4-20250514", baseUrl = "https://api.anthropic.com/")
            .copy(provider = ProviderType.ANTHROPIC)
        assertNull(PiModelsConfig.root(anthropic, imageInput = true).compat()["requiresAssistantAfterToolResult"])
        val google = profile("gemini-2.5-pro", baseUrl = "https://generativelanguage.googleapis.com/")
            .copy(provider = ProviderType.GOOGLE)
        assertNull(PiModelsConfig.root(google, imageInput = true).compat()["requiresAssistantAfterToolResult"])
    }

    @Test fun visionModelsDetectedById() {
        // Qwen-VL ряд
        assertTrue(PiModelsConfig.supportsImageInput("qwen-vl-max"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen2.5-vl-72b-instruct"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen-vl-plus"))
        // Qwen флагманы (Qwen 3.5+ — мультимодальные, hybrid-thinking)
        assertTrue(PiModelsConfig.supportsImageInput("qwen-max"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen3-max"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen3.8-max"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen3.7-max"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen-plus"))
        assertTrue(PiModelsConfig.supportsImageInput("qwen-turbo"))
        // Claude 3+
        assertTrue(PiModelsConfig.supportsImageInput("claude-sonnet-4-20250514"))
        assertTrue(PiModelsConfig.supportsImageInput("claude-3-opus-20240229"))
        assertTrue(PiModelsConfig.supportsImageInput("claude-haiku-4-5"))
        // GPT-4+, o-серия
        assertTrue(PiModelsConfig.supportsImageInput("gpt-4o"))
        assertTrue(PiModelsConfig.supportsImageInput("gpt-5.2"))
        assertTrue(PiModelsConfig.supportsImageInput("o1-preview"))
        // Gemini
        assertTrue(PiModelsConfig.supportsImageInput("gemini-2.5-pro"))
        assertTrue(PiModelsConfig.supportsImageInput("gemini-3.1-flash-lite"))
        // GLM-4V
        assertTrue(PiModelsConfig.supportsImageInput("glm-4v-plus"))
    }

    @Test fun textOnlyModelsNotDetectedAsVision() {
        // Qwen-flash: лёгкие модели без Visual Understanding
        assertFalse(PiModelsConfig.supportsImageInput("qwen3.8-flash"))
        assertFalse(PiModelsConfig.supportsImageInput("qwen2.5-coder-32b-instruct"))
        // DeepSeek (текстовые)
        assertFalse(PiModelsConfig.supportsImageInput("deepseek-chat"))
        assertFalse(PiModelsConfig.supportsImageInput("deepseek-reasoner"))
        // Claude 2 (без цифры поколения = не 3+)
        assertFalse(PiModelsConfig.supportsImageInput("claude-2"))
        // Локальные текстовые
        assertFalse(PiModelsConfig.supportsImageInput("llama3.2:3b"))
        assertFalse(PiModelsConfig.supportsImageInput("phi4"))
    }

    private fun section(parsed: LlmProfile) =
        PiModelsConfig.reasoning(parsed)

    @Test
    fun limitsComeFromProfileNotConstants() {
        val root = PiModelsConfig.root(profile("qwen3.8-flash", maxTokens = 16_384, contextLimit = 200_000))
        val model = root.model()
        assertEquals(200_000, model.int("contextWindow"), "контекст — из «лимита контекста» профиля")
        assertEquals(16_384, model.int("maxTokens"), "потолок вывода — из «максимума токенов» профиля")
    }

    @Test
    fun reasoningCeilingNeverDropsBelowPiDefault() {
        // Профиль с 512 токенами рассуждающей модели не годится: тело сообщения
        // не успевает начаться. Мост поднимает минимум до дефолта pi.
        val thin = profile("qwen3.8-flash", maxTokens = 512)
        assertEquals(PiModelsConfig.REASONING_MIN_MAX_TOKENS, section(thin).maxTokens)
        assertEquals(
            PiModelsConfig.REASONING_MIN_MAX_TOKENS,
            PiModelsConfig.root(thin).model().int("maxTokens"),
        )
        // ...а нерассуждающей — не трогаем: там потолок вывода не про рассуждение.
        assertEquals(512, section(profile("llama3.2", maxTokens = 512)).maxTokens)
    }

    @Test
    fun reasoningModelIsDeclaredReasoning() {
        val setup = section(qwen)
        assertTrue(setup.enabled, "профиль говорит, что у модели есть усилие — pi обязан это знать")
        val compat = PiModelsConfig.root(qwen).compat()
        assertEquals(true, compat.bool("supportsReasoningEffort"))
        // Совпадение, а не «и то и другое»: рассогласование ломает переключатель.
        assertEquals(setup.enabled, compat.bool("supportsReasoningEffort"))
    }

    @Test
    fun nonReasoningModelIsDeclaredAsIs() {
        val setup = section(profile("llama3.2"))
        assertFalse(setup.enabled)
        assertNull(setup.thinkingLevel, "нечему выключать: флаг не отправляем")
        assertTrue(setup.thinkingLevelMap.isEmpty())
        val compat = PiModelsConfig.root(profile("llama3.2")).compat()
        assertEquals(false, compat.bool("supportsReasoningEffort"))
        assertNull(compat["thinkingFormat"])
    }

    @Test
    fun thinkingFormatFollowsQwenEndpoint() {
        assertEquals("qwen", section(qwen).thinkingFormat, "наружный сервер понимает enable_thinking")
        assertEquals(
            "qwen-chat-template",
            section(profile("qwen3.8-flash", baseUrl = "http://localhost:11434/v1")).thinkingFormat,
            "локальный сервер думает через chat_template_kwargs",
        )
        assertEquals(
            "qwen-chat-template",
            section(profile("qwen3.8-flash", baseUrl = "http://127.0.0.1:1234/v1")).thinkingFormat,
        )
        // Домашний сервер в сети — тоже локальный: у него тот же chat template.
        assertEquals(
            "qwen-chat-template",
            section(profile("qwen3.8-flash", baseUrl = "http://192.168.1.20:11434/v1")).thinkingFormat,
        )
        // Другое семейство — схему выбирает pi (`reasoning_effort`).
        assertNull(section(profile("deepseek-v4")).thinkingFormat)
    }

    @Test
    fun thinkingLevelMapMirrorsModelVocabulary() {
        val map = section(qwen).thinkingLevelMap
        // COMPAT_EFFORT знает off/low/medium/high — их модель и принимает.
        assertEquals("none", map["off"])
        assertEquals("low", map["low"])
        assertEquals("medium", map["medium"])
        assertEquals("high", map["high"])
        // Остальные помечены недоступными: pi обязан их прятать, а не подставлять.
        assertEquals(null, map["minimal"])
        assertEquals(null, map["xhigh"])
        assertEquals(null, map["max"])
        // Ключи — ровно вокабуляр pi (VALID_THINKING_LEVELS в cli/args.js).
        assertEquals(
            setOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
            map.keys,
            "имена уровней pi не должны расходиться с нашим списком",
        )
    }

    @Test
    fun modeOnlyModelOffersNoSwitch() {
        // qwq рассуждает «режимом на модели»: ручной ручки уровня нет, остаётся
        // только выключатель — выдумывать за сервер остальные уровни нельзя.
        val setup = section(profile("qwq-32b"))
        assertTrue(setup.enabled, "модель думает — pi обязан это знать")
        assertEquals("none", setup.thinkingLevelMap["off"])
        assertEquals(null, setup.thinkingLevelMap["medium"])
        // «Авто» — это «решает сервер»: переключатель не отправляем вовсе.
        assertNull(
            section(profile("qwq-32b", effort = EffortSelection.of(ReasoningEffort.AUTO))).thinkingLevel,
        )
    }

    @Test
    fun thinkingLevelFollowsUserEffort() {
        assertEquals("medium", section(qwen).thinkingLevel, "без выбора — детерминированный medium")
        assertEquals("high", section(qwen.copy(effort = EffortSelection.of(ReasoningEffort.HIGH))).thinkingLevel)
        assertEquals("off", section(qwen.copy(effort = EffortSelection.of(ReasoningEffort.NONE))).thinkingLevel)
        assertEquals(
            "low",
            section(qwen.copy(effort = EffortSelection.of(ReasoningEffort.LOW))).thinkingLevel,
        )
    }

    @Test
    fun unsupportedEffortIsClampedToDeclaredLevels() {
        // qwen не объявляет xhigh — просить его бессмысленно: pi скрыл бы уровень
        // и прогон ушёл бы в обрезку на пустом месте.
        assertEquals(
            "high",
            section(qwen.copy(effort = EffortSelection.of(ReasoningEffort.XHIGH))).thinkingLevel,
        )
        // REACT_EFFORT («...-thinking» в имени) не знает «выключено»: off помечен
        // недоступным, и выключать рассуждение молча нельзя — берём самый слабый.
        val forced = profile("my-reasoning-model", effort = EffortSelection.of(ReasoningEffort.NONE))
        assertNull(section(forced).thinkingLevelMap["off"])
        assertEquals("low", section(forced).thinkingLevel)
    }

    @Test
    fun providerSectionCarriesEndpointAndKey() {
        val provider = PiModelsConfig.root(qwen).provider()
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", provider.str("baseUrl"), "без хвостового слэша")
        assertEquals(PiModelsConfig.API, provider.str("api"))
        assertEquals("sk-test", provider.str("apiKey"))
        // Локальный сервер без авторизации: пустой ключ — заглушка, а не пустая строка.
        assertEquals(
            PiModelsConfig.ANONYMOUS_KEY,
            PiModelsConfig.root(profile("llama3.2", apiKey = "")).provider().str("apiKey"),
        )
    }

    @Test
    fun writtenJsonIsSingleLineAndCarriesTheNumbers() {
        val json = PiModelsConfig.json(qwen)
        assertFalse(json.contains("\n"), "одна строка — файл для чтения машиной")
        assertTrue(json.contains("\"maxTokens\":16384"), json)
        assertTrue(json.contains("\"contextWindow\":128000"), json)
        assertTrue(json.contains("\"reasoning\":true"), json)
        assertTrue(json.contains("\"supportsReasoningEffort\":true"), json)
        assertTrue(json.contains("\"thinkingFormat\":\"qwen\""), json)
        assertTrue(json.contains("\"off\":\"none\""), json)
        assertTrue(json.contains("\"xhigh\":null"), json)
    }

    @Test
    fun truncationAdviceNamesTheRealFix() {
        val advice = PiModelsConfig.truncationAdvice(qwen, outputTokens = 16_384, reasoningTokens = 16_384)
        assertTrue(advice.contains("весь лимит вывода на рассуждение"), advice)
        assertTrue(advice.contains("16384 из 16384"), advice)
        assertTrue(advice.contains("Усилие сейчас — medium"), advice)
        assertTrue(advice.contains("снизьте"), advice)
        assertFalse(advice.contains("без ответа"), advice)
        // Потолок уже на дефолте pi: совет «поднимите лимит» был бы обманом.
        assertFalse(advice.contains("Поднимите"), advice)
        // Профиль с низким лимитом должен видеть, что мост поднял его сам.
        val stingy = PiModelsConfig.truncationAdvice(profile("qwen3.8-flash", maxTokens = 4096), 8192, 8192)
        assertTrue(stingy.contains("в профиле стоит 4096"), stingy)
        assertTrue(stingy.contains("Поднимите «максимум токенов»"), stingy)
    }

    // --- навигация по JSON конфига -------------------------------------------------

    private fun JsonObject.provider(): JsonObject =
        this["providers"]!!.jsonObject[PiModelsConfig.PROVIDER_ID]!!.jsonObject

    private fun JsonObject.compat(): JsonObject = provider()["compat"]!!.jsonObject

    private fun JsonObject.model(): JsonObject =
        provider()["models"]!!.jsonArray[0].jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
