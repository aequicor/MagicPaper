package io.aequicor.magicpaper.domain

/**
 * Эвристические возможности моделей по управлению усилием.
 *
 * Порядок такой же, как в каталоге провайдера: сначала ручной пресет, затем
 * разбор идентификатора. Эвристика — это единственное место, где допустимо
 * «угадывать по имени»; она применяется один раз при импорте модели и всегда
 * возвращает конкретный набор уровней, а не булево «похоже на reasoning».
 */
object ModelDefaults {

    data class ModelRecommendation(
        val effort: EffortSelection,
        val advanced: AdvancedLlmOptions,
    )

    /**
     * Настройки по умолчанию для новой модели.
     * Дефолт усилия — «по умолчанию провайдера», если модель сама не объявила
     * штатный уровень; max_tokens приподнимаем, чтобы рассуждения влезли в ответ.
     */
    fun recommendation(
        provider: ProviderType,
        modelId: String,
        current: AdvancedLlmOptions = AdvancedLlmOptions(),
    ): ModelRecommendation {
        val capability = capability(provider, modelId)
        val controls = capability as? ReasoningCapability.Controls
        val effort = controls?.default?.let { EffortSelection.of(it) } ?: EffortSelection.Default
        val maxTokens = if (controls == null) {
            when (provider) {
                ProviderType.ANTHROPIC -> 8192
                ProviderType.GOOGLE -> 8192
                else -> current.maxTokens
            }
        } else {
            16384
        }
        return ModelRecommendation(
            effort = effort,
            advanced = current.copy(
                maxTokens = maxOf(current.maxTokens, maxTokens),
                contextLimit = current.safeContextLimit,
            ),
        )
    }

    /** Возможность модели по профилю (провайдер + активная модель). */
    fun capability(profile: LlmProfile): ReasoningCapability =
        capability(profile.provider, profile.modelId)

    /** Возможность модели по провайдеру и её идентификатору. */
    fun capability(provider: ProviderType, modelId: String): ReasoningCapability {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return ReasoningCapability.None
        return when (provider) {
            ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER -> openAiCapability(id)
            ProviderType.ANTHROPIC -> anthropicCapability(id)
            ProviderType.GOOGLE -> googleCapability(id)
        }
    }

    /** Есть ли у модели нативная ручка усилия — спрашивают транспорты и UI. */
    fun supportsEffort(provider: ProviderType, modelId: String): Boolean =
        capability(provider, modelId).supportsEffort

    fun supportsEffort(profile: LlmProfile): Boolean = supportsEffort(profile.provider, profile.modelId)

    fun discover(provider: ProviderType, ids: List<String>): List<DiscoveredModel> =
        ids.filter { it.isNotBlank() }.sorted().map { id ->
            ModelInfo(id, reasoning = capability(provider, id))
        }

    // --- семейные профили -------------------------------------------------------------

    /**
     * OpenAI-совместимые reasoning-модели. `reasoning_effort` — общий язык
     * совместимых серверов, поэтому уровни отдаются дословно.
     */
    private fun openAiCapability(id: String): ReasoningCapability = when {
        id.startsWith("gpt-5") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ->
            ReasoningPresets.OPENAI_EFFORT

        id.startsWith("deepseek-reasoner") || id.startsWith("qwq") ->
            // У legacy deepseek-reasoner своя вокабуляр, честно говорим: «режим на модели».
            ReasoningPresets.MODE_ONLY

        "deepseek-v4" in id || "glm" in id || "kimi" in id || ("qwen3" in id && "thinking" in id) ->
            ReasoningPresets.COMPAT_EFFORT

        "reasoning" in id || "thinking" in id -> ReasoningPresets.REACT_EFFORT

        else -> ReasoningCapability.None
    }

    /** Anthropic: две эпохи — «адаптивное усилие» (4.6+) и бюджет токенов. */
    private fun anthropicCapability(id: String): ReasoningCapability = when {
        "fable" in id || "claude-opus-4-6" in id || "claude-opus-4-7" in id ||
            "claude-sonnet-4-6" in id || "claude-5" in id -> ReasoningPresets.ANTHROPIC_ADAPTIVE

        id.startsWith("claude-") -> ReasoningPresets.ANTHROPIC_BUDGET

        else -> ReasoningCapability.None
    }

    /** Google: 2.5 думает бюджетом токенов (есть «динамически»), 3 — уровнем. */
    private fun googleCapability(id: String): ReasoningCapability = when {
        id.startsWith("gemini-3") || "3-pro" in id || "3-flash" in id -> ReasoningPresets.GEMINI_LEVEL
        id.startsWith("gemini-2.5") -> ReasoningPresets.GEMINI_BUDGET
        else -> ReasoningCapability.None
    }
}
