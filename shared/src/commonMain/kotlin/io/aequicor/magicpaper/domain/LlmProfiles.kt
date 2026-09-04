package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Тип провайдера: определяет транспорт и формат запроса. */
@Serializable
enum class ProviderType {
    /** Любой сервер с /chat/completions: OpenAI, Ollama, LM Studio, vLLM, OpenRouter… */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API (/v1/messages). */
    ANTHROPIC,

    /** Google AI (Generative Language API, generateContent). */
    GOOGLE,
}

/** Уровень усилия модели: единая шкала, каждый транспорт мапит её в свой формат. */
@Serializable
enum class EffortLevel { LOW, MEDIUM, HIGH }

/** Однобуквенная подпись уровня для чипов и списков. */
val EffortLevel.glyph: String
    get() = when (this) {
        EffortLevel.LOW -> "Н"
        EffortLevel.MEDIUM -> "С"
        EffortLevel.HIGH -> "В"
    }

/** Человекочитаемое название уровня. */
val EffortLevel.title: String
    get() = when (this) {
        EffortLevel.LOW -> "Низкое"
        EffortLevel.MEDIUM -> "Среднее"
        EffortLevel.HIGH -> "Высокое"
    }

/**
 * Тонкие параметры подключения. Пустое значение = «по умолчанию провайдера»:
 * транспорт просто не добавляет соответствующее поле в запрос.
 */
@Serializable
data class AdvancedSettings(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val timeoutSeconds: Int = 60,
    /** Пусто = штатный системный промпт агента. */
    val systemPromptOverride: String = "",
    /** Сколько последних сообщений истории отправлять модели. */
    val contextMessages: Int = 8,
)

/**
 * Профиль подключения: провайдер + доступ + модель + режимы.
 * Единица переключения в чате и настройках.
 */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val provider: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val effort: EffortLevel = EffortLevel.MEDIUM,
    val advanced: AdvancedSettings = AdvancedSettings(),
    val createdAt: Long = 0,
) {
    /** Достаточен ли профиль для вызова модели. */
    val configured: Boolean get() = baseUrl.isNotBlank() && modelId.isNotBlank()

    /** Короткая подпись для чипа в чате: «Ollama (локально) · llama3.2». */
    val shortLabel: String get() = if (modelId.isBlank()) name else "$name · $modelId"
}
