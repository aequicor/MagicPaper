package io.aequicor.magicpaper.domain

/** Описание модели в каталоге провайдера. */
data class ModelInfo(
    val id: String,
    /** Поддерживает ли модель нативное управление усилием (reasoning/thinking). */
    val supportsEffort: Boolean = false,
)

/** Каталогное описание известного провайдера — для быстрого подключения. */
data class ProviderSpec(
    val type: ProviderType,
    val displayName: String,
    val defaultBaseUrl: String,
    val keyHint: String,
    val requiresKey: Boolean,
    val models: List<ModelInfo>,
)

/**
 * Каталог известных провайдеров. Живёт в коде (как документация и лавка навыков):
 * работает офлайн, расширяется коммитом, без внешних зависимостей.
 */
object ProviderCatalog {
    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Ollama (локально)",
            defaultBaseUrl = "http://localhost:11434/v1",
            keyHint = "ключ не нужен",
            requiresKey = false,
            models = listOf(
                ModelInfo("llama3.2"),
                ModelInfo("qwen3"),
                ModelInfo("mistral"),
                ModelInfo("gemma3"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "LM Studio (локально)",
            defaultBaseUrl = "http://localhost:1234/v1",
            keyHint = "ключ не нужен",
            requiresKey = false,
            models = emptyList(),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("openrouter/auto"),
                ModelInfo("anthropic/claude-sonnet-4"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            keyHint = "sk-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("gpt-5-mini", supportsEffort = true),
                ModelInfo("gpt-5", supportsEffort = true),
                ModelInfo("gpt-4o"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Другой OpenAI-совместимый сервер",
            defaultBaseUrl = "",
            keyHint = "зависит от сервера",
            requiresKey = false,
            models = emptyList(),
        ),
        ProviderSpec(
            type = ProviderType.ANTHROPIC,
            displayName = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com",
            keyHint = "sk-ant-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("claude-sonnet-4-5", supportsEffort = true),
                ModelInfo("claude-opus-4-1", supportsEffort = true),
                ModelInfo("claude-haiku-4-5", supportsEffort = true),
            ),
        ),
        ProviderSpec(
            type = ProviderType.GOOGLE,
            displayName = "Google Gemini",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyHint = "AIza…",
            requiresKey = true,
            models = listOf(
                ModelInfo("gemini-2.5-flash", supportsEffort = true),
                ModelInfo("gemini-2.5-pro", supportsEffort = true),
                ModelInfo("gemini-2.0-flash"),
            ),
        ),
    )

    /** Описание модели из каталога; null для своих (вне каталога) моделей. */
    fun modelInfo(provider: ProviderType, modelId: String): ModelInfo? =
        all.asSequence()
            .filter { it.type == provider }
            .flatMap { it.models.asSequence() }
            .firstOrNull { it.id == modelId }

    /** Все описания провайдеров данного типа. */
    fun specs(type: ProviderType): List<ProviderSpec> = all.filter { it.type == type }
}
