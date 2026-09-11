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
     * contextLimit — из факта провайдера (ProviderModel.contextWindow), если объявлен;
     * иначе из текущего профиля; иначе дефолт AdvancedLlmOptions.
     */
    fun recommendation(
        provider: ProviderType,
        modelId: String,
        current: AdvancedLlmOptions = AdvancedLlmOptions(),
        declared: DeclaredReasoning? = null,
        fact: ProviderModel? = null,
    ): ModelRecommendation {
        val capability = capability(provider, modelId, declared)
        val controls = capability as? ReasoningCapability.Controls
        val effort = controls?.default?.let { EffortSelection.of(it) } ?: EffortSelection.Default
        val maxTokens = when {
            provider == ProviderType.ANTHROPIC -> maxOf(current.maxTokens, 8192)
            provider == ProviderType.GOOGLE -> maxOf(current.maxTokens, 8192)
            controls != null -> maxOf(current.maxTokens, 16384)
            else -> current.maxTokens
        }
        val contextLimit = fact?.contextWindow?.takeIf { it > 0 } ?: current.safeContextLimit
        return ModelRecommendation(
            effort = effort,
            advanced = current.copy(
                temperature = if (controls != null) null else current.temperature,
                maxTokens = maxTokens,
                contextLimit = contextLimit,
            ),
        )
    }

    /**
     * Возможность модели по профилю: сначала то, что провайдер объявил сам
     * ([LlmProfile.modelReasoning]), затем каталог и эвристика по имени.
     */
    fun capability(profile: LlmProfile, modelId: String = profile.modelId): ReasoningCapability =
        capability(profile.provider, profile.sourceModelId(modelId),
            profile.modelCatalog.firstOrNull { it.id == profile.sourceModelId(modelId) }?.reasoning
                ?: profile.modelReasoning[profile.sourceModelId(modelId)])

    /** Возможность модели по провайдеру и её идентификатору. */
    fun capability(
        provider: ProviderType,
        modelId: String,
        declared: DeclaredReasoning? = null,
    ): ReasoningCapability {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return ReasoningCapability.None
        // Объявление провайдера — факт о конкретной модели, оно важнее догадок.
        if (declared != null && !declared.supports) return ReasoningCapability.None
        return declared?.let { heuristic(provider, id).withDeclared(it) } ?: heuristic(provider, id)
    }

    private fun heuristic(provider: ProviderType, id: String): ReasoningCapability =
        when (provider) {
            ProviderType.OPENAI_SUBSCRIPTION, ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENROUTER ->
                openAiCapability(id)
            ProviderType.ANTHROPIC -> anthropicCapability(id)
            ProviderType.GOOGLE -> googleCapability(id)
        }

    /** Есть ли у модели нативная ручка усилия — спрашивают транспорты и UI. */
    fun supportsEffort(
        provider: ProviderType,
        modelId: String,
        declared: DeclaredReasoning? = null,
    ): Boolean = capability(provider, modelId, declared).supportsEffort

    fun supportsEffort(profile: LlmProfile, modelId: String = profile.modelId): Boolean =
        capability(profile, modelId).supportsEffort

    /**
     * Модель из живого каталога провайдера: что нашли + что рекомендуем.
     * [supportsEffort] — сводное свойство рассуждения: ручка или бюджет.
     */
    data class DiscoveredModel(
        val id: String,
        val reasoning: ReasoningCapability = ReasoningCapability.None,
        val recommendation: ModelRecommendation,
        /** Что сервер объявил сам; null — объявлений не было, работаем по эвристике. */
        val declared: DeclaredReasoning? = null,
        val metadata: ProviderModel? = null,
    ) {
        val supportsEffort: Boolean get() = reasoning.supportsEffort

        /** Уровни, которые показывает ручка: только объявленные моделью. */
        val levels: List<ReasoningEffort> get() = reasoning.selectableLevels
    }

    /**
     * Разбор каталога провайдера. [declared] — объявления из ответа сервера
     * (ключ — id модели): они перевешивают эвристику, потому что это факт.
     * [facts] — полная ProviderModel (с contextWindow, maxOutputTokens и т.д.) для каждой модели.
     */
    fun discover(
        provider: ProviderType,
        ids: List<String>,
        declared: Map<String, DeclaredReasoning> = emptyMap(),
        facts: Map<String, ProviderModel> = emptyMap(),
    ): List<DiscoveredModel> =
        ids.filter { it.isNotBlank() }.distinct().sorted().map { id ->
            val fact = declared[id] ?: declared[id.trim().lowercase()]
            val metadata = facts[id] ?: facts[id.trim().lowercase()]
            DiscoveredModel(
                id = id,
                reasoning = capability(provider, id, fact),
                recommendation = recommendation(provider, id, declared = fact, fact = metadata),
                declared = fact,
            )
        }

    // --- семейные профили -------------------------------------------------------------

    /**
     * OpenAI-совместимые reasoning-модели. `reasoning_effort` — общий язык
     * совместимых серверов, поэтому уровни отдаются дословно.
     */
    /** Сегменты-префиксы провайдеров в маршрутах каталогов-агрегаторов. */
    private val PROVIDER_SEGMENTS = setOf(
        "openrouter", "anthropic", "openai", "google", "mistral", "meta",
        "deepseek", "qwen", "x-ai", "cohere", "perplexity",
    )

    /**
     * «openrouter/anthropic/claude-sonnet-4.5» → «claude-sonnet-4.5»: снимаем
     * известные префиксы провайдеров, пока они идут в начале.
     */
    private fun stripProviderPrefix(id: String): String {
        var current = id
        while (true) {
            val slash = current.indexOf('/')
            if (slash <= 0) break
            if (current.substring(0, slash) !in PROVIDER_SEGMENTS) break
            current = current.substring(slash + 1)
        }
        return current
    }

    private fun openAiCapability(id: String): ReasoningCapability {
        val bare = stripProviderPrefix(id)
        // Кураторский каталог — факты о моделях, без угадывания.
        ProviderCatalog.modelInfo(ProviderType.OPENAI_COMPATIBLE, bare)?.reasoning?.let { return it }
        // Префиксы имён: эпоха рассуждающих поколений.
        if (bare.startsWith("gpt-5") || bare.startsWith("o1") || bare.startsWith("o3") || bare.startsWith("o4")) {
            return ReasoningPresets.OPENAI_EFFORT
        }
        if (bare.startsWith("deepseek-reasoner") || bare.startsWith("qwq")) {
            // У legacy deepseek-reasoner своя вокабуляр, честно говорим: «режим на модели».
            return ReasoningPresets.MODE_ONLY
        }
        if ("deepseek-v4" in bare) return ReasoningPresets.COMPAT_EFFORT
        // Семейные ряды (включая локальные серверы с «модель:размер» и чужих
        // вендоров через агрегаторы). Поколения без ручки усилия (gpt-4o и т.п.)
        // сюда не попадают — семейство само по себе ручку не обещает.
        when (ProviderCatalog.familyOf(bare)) {
            "qwen", "glm", "kimi", "deepseek" -> return ReasoningPresets.COMPAT_EFFORT
            "claude" -> return ReasoningPresets.ANTHROPIC_BUDGET
        }
        if ("reasoning" in bare || "thinking" in bare) return ReasoningPresets.REACT_EFFORT
        return ReasoningCapability.None
    }

    /** Anthropic: две эпохи — «адаптивное усилие» (4.6+) и бюджет токенов. */
    private fun anthropicCapability(id: String): ReasoningCapability {
        ProviderCatalog.modelInfo(ProviderType.ANTHROPIC, id)?.reasoning?.let { return it }
        return when {
            "fable" in id || "claude-opus-4-6" in id || "claude-opus-4-7" in id ||
                "claude-sonnet-4-6" in id || "claude-5" in id -> ReasoningPresets.ANTHROPIC_ADAPTIVE

            id.startsWith("claude-") -> ReasoningPresets.ANTHROPIC_BUDGET

            else -> ReasoningCapability.None
        }
    }

    /** Google: 2.5 думает бюджетом токенов (есть «динамически»), 3 — уровнем. */
    private fun googleCapability(id: String): ReasoningCapability {
        ProviderCatalog.modelInfo(ProviderType.GOOGLE, id)?.reasoning?.let { return it }
        return when {
            id.startsWith("gemini-3") || "3-pro" in id || "3-flash" in id -> ReasoningPresets.GEMINI_LEVEL
            id.startsWith("gemini-2.5") -> ReasoningPresets.GEMINI_BUDGET
            else -> ReasoningCapability.None
        }
    }
}

/** Живой каталог моделей провайдера; реализации — в data/llm/ModelDirectories. */
interface ModelDirectory {
    suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel>
}
