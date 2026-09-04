package io.aequicor.magicpaper.domain

/** Рекомендуемые параметры для модели: усилие и тонкие настройки. */
data class ModelRecommendation(
    val effort: EffortLevel,
    val advanced: AdvancedSettings,
)

/** Найденная у провайдера модель с её возможностями и рекомендациями. */
data class DiscoveredModel(
    val id: String,
    val supportsEffort: Boolean,
    val recommendation: ModelRecommendation,
)

/** Каталог моделей, доступных у провайдера (запрашивается по сети). */
interface ModelDirectory {
    suspend fun models(profile: LlmProfile): List<DiscoveredModel>
}

/**
 * Дефолты моделей: определение поддержки усилия и рекомендуемые параметры.
 * Кураторский каталог ([ProviderCatalog]) имеет приоритет; для моделей вне
 * каталога применяется эвристика по семействам имён.
 */
object ModelDefaults {

    fun supportsEffort(profile: LlmProfile): Boolean = supportsEffort(profile.provider, profile.modelId)

    fun supportsEffort(provider: ProviderType, modelId: String): Boolean =
        ProviderCatalog.modelInfo(provider, modelId)?.supportsEffort ?: heuristicEffort(modelId)

    /**
     * Эвристика по семействам моделей с нативным управлением усилием.
     * Префикс после последнего «/» — провайдерские списки часто идут с префиксами
     * вида «openrouter/…», «vertex/…».
     */
    fun heuristicEffort(modelId: String): Boolean {
        val id = modelId.substringAfterLast('/').lowercase()
        return when {
            id.startsWith("gpt-5") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") -> true
            id.startsWith("claude-sonnet-4") || id.startsWith("claude-opus-4") ||
                id.startsWith("claude-haiku-4") || id.startsWith("claude-3-7") || id.startsWith("claude-3.7") -> true
            id.startsWith("gemini-2.5") || id.startsWith("gemini-3") -> true
            id.startsWith("qwq") || id.startsWith("deepseek-r1") -> true
            "reasoning" in id || "thinking" in id -> true
            else -> false
        }
    }

    /** Рекомендуемые параметры для связки провайдер + модель. */
    fun recommendation(provider: ProviderType, modelId: String): ModelRecommendation {
        val effortModel = supportsEffort(provider, modelId)
        return when (provider) {
            ProviderType.ANTHROPIC -> ModelRecommendation(
                effort = EffortLevel.MEDIUM,
                // max_tokens обязан превышать бюджет мышления —
                // пейлоад при необходимости поднимает его автоматически.
                advanced = AdvancedSettings(maxTokens = 8192),
            )
            ProviderType.GOOGLE -> ModelRecommendation(
                effort = EffortLevel.MEDIUM,
                advanced = AdvancedSettings(),
            )
            ProviderType.OPENAI_COMPATIBLE -> if (effortModel) {
                // Рассуждающие модели не принимают температуру — оставляем провайдеру.
                ModelRecommendation(EffortLevel.MEDIUM, AdvancedSettings())
            } else {
                ModelRecommendation(EffortLevel.MEDIUM, AdvancedSettings(temperature = 0.7))
            }
        }
    }

    /** Собирает найденные модели из сырых идентификаторов (дедупликация, пометки). */
    fun discover(provider: ProviderType, ids: List<String>): List<DiscoveredModel> =
        ids.distinct()
            .filter { it.isNotBlank() }
            .map { id -> DiscoveredModel(id, supportsEffort(provider, id), recommendation(provider, id)) }
}
