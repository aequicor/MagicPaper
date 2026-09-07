package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable

/** Тип провайдера: определяет, какой транспорт и какой формат запроса использовать. */
@Serializable
enum class ProviderType {
    /** Модели Codex, оплачиваемые подпиской ChatGPT. Доступны только в desktop-сборке. */
    OPENAI_SUBSCRIPTION,

    /** Любой сервер с /chat/completions: OpenAI, Ollama, LM Studio, vLLM, OpenRouter… */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages API (/v1/messages). */
    ANTHROPIC,

    /** Google AI (Generative Language API, generateContent). */
    GOOGLE,

    /** OpenRouter: OpenAI-совместимый транспорт + поле reasoning для effort-моделей. */
    OPENROUTER,
}

/** Как транспорт подставляет секрет в запрос. Значение по умолчанию сохраняет прежнее поведение. */
@Serializable
enum class LlmAuthType {
    /** Заголовок Authorization: Bearer … (OpenAI, OpenRouter, Google). */
    BEARER,

    /** Заголовок x-api-key (прямой доступ к Anthropic). */
    X_API_KEY,

    /** Параметр запроса ?key=… (Ollama Cloud и совместимые шлюзы). */
    QUERY_KEY,
}

/** Для каких задач подходит модель. В UI используется как метка, в домене — как фильтр. */
@Serializable
enum class ModelKind {
    GENERAL,
    CODING,
    REASONING,
    VISION,
    EMBEDDING,
}

/** Расширенные параметры подключения: таймауты, лимиты и режимы вывода. */
@Serializable
data class AdvancedLlmOptions(
    /** Температура запроса; `null` — не отправлять (модель решает сама, типично для reasoning). */
    val temperature: Double? = 0.7,
    /** Top-p сэмплирование; `null` — не отправлять. */
    val topP: Double? = null,
    /** Сколько секунд ждать отклик; 0 — без ограничения. */
    val timeoutSeconds: Int = 120,
    /**
     * Верхняя граница ответа в токенах; Anthropic требует её явно. Значение — для
     * чата: кодинг-прогон рассуждающей модели поднимает потолок до дефолта пи
     * (`PiModelsConfig.REASONING_MIN_MAX_TOKENS`), потому что рассуждение платит
     * из того же бюджета вывода и на 8192 телу сообщения не остаётся ничего.
     */
    val maxTokens: Int = 8192,
    /** Верхняя граница контекста модели; используется для индикатора загрузки. */
    val contextLimit: Int = 128_000,
    /** Пусто = штатный системный промпт агента. */
    val systemPromptOverride: String = "",
    /** Сколько последних сообщений истории отправлять модели. */
    val contextMessages: Int = 8,
    val sendMaxTokens: Boolean = true,
    val extraParameters: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
) {
    val safeTemperature: Double? get() = temperature?.coerceIn(0.0, 2.0)
    val safeTopP: Double? get() = topP?.coerceIn(0.0, 1.0)
    val safeMaxTokens: Int get() = maxTokens.coerceIn(1, 10_000_000)
    val safeTimeoutSeconds: Int get() = timeoutSeconds.coerceIn(0, 3600)
    val safeContextLimit: Int get() = contextLimit.coerceIn(1_024, 10_000_000)
}

@Serializable
data class LlmProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val provider: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    /** Как подставлять ключ; null при пустом ключе означает «без авторизации». */
    val authType: LlmAuthType? = LlmAuthType.BEARER,
    /** Модель по умолчанию: выбирается при старте чата и в переключателе моделей. */
    val modelId: String = "",
    /** Усилие по умолчанию для профиля; см. [EffortSelection]. */
    val effort: EffortSelection = EffortSelection.Default,
    /** Модель по умолчанию для coding-сессий (отдельный контур, см. domain/CodingSession.kt). */
    val codingModelId: String = "",
    /** Избранные модели: быстрые кнопки переключения в чате, порядок = порядок добавления. */
    val favoriteModels: List<String> = emptyList(),
    /**
     * Усилие по конкретным моделям. Наследуется от [effort], когда ключа нет,
     * поэтому профиль, где ничего не меняли, остаётся одним значением.
     */
    val effortOverrides: Map<String, EffortSelection> = emptyMap(),
    /**
     * Что провайдер сам объявил об уровнях модели (из его `/models`). Храним,
     * чтобы ручка усилия показывала словарь модели и между запросами каталога:
     * эвристика по имени — только запасной вариант.
     */
    val modelReasoning: Map<String, DeclaredReasoning> = emptyMap(),
    val advanced: AdvancedLlmOptions = AdvancedLlmOptions(),
    /** Когда профиль создан; 0 — наследие ранних версий. */
    val createdAt: Long = 0,
    val modelCatalog: List<ProviderModel> = emptyList(),
    val variants: List<ModelVariant> = emptyList(),
    val modelLibraryVersion: Int = 0,
    @kotlinx.serialization.Transient val invocationKey: String? = null,
) {
    val configured: Boolean
        get() = modelId.isNotBlank() && (provider == ProviderType.OPENAI_SUBSCRIPTION || baseUrl.isNotBlank())
    val codingConfigured: Boolean
        get() = codingModelId.isNotBlank() && (provider == ProviderType.OPENAI_SUBSCRIPTION || baseUrl.isNotBlank())

    /** Модель кодинг-контура: своя, если отмечена, иначе общая модель профиля. */
    val codingModel: String get() = codingModelId.ifBlank { modelId }

    /**
     * Профиль для кодинг-сессий: в контуре пи модель берётся из [codingModelId],
     * чтобы выбор в чате и выбор для агента непересекались. Без своей модели
     * профиль уходит как есть.
     */
    fun forCoding(): LlmProfile = forModel(codingModel)

    /** Короткая подпись для чипа в чате: «Ollama (локально) · llama3.2». */
    val shortLabel: String get() = if (modelId.isBlank()) name else "$name · ${modelName(selectionKey)}"

    /**
     * Рабочее состояние переключателя: показываем модель по умолчанию, даже если
     * последняя активная сессия была из другого профиля.
     */
    fun resolvedModel(activeModel: String?): String? =
        activeModel?.takeIf { it.isNotBlank() }?.takeIf { isFavoriteModel(it) || it == modelId || it == codingModelId }
            ?: modelId.takeIf { it.isNotBlank() }

    /** Все модели в переключателях: явно отмеченные модели поставщика и пользовательские варианты. */
    val displayModels: List<String>
        get() = (favoriteModels + variants.map { it.id }).filter { it.isNotBlank() }.distinct()

    fun isFavoriteModel(model: String): Boolean = model in displayModels

    /**
     * Переключение модели: если модель уже в списке — снимаем избранное, иначе добавляем.
     * При удалении активной модели следующей становится следующая по списку.
     */
    fun withFavoriteModel(model: String): LlmProfile {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return this
        return if (isFavoriteModel(trimmed)) {
            copy(favoriteModels = favoriteModels - trimmed)
        } else {
            copy(favoriteModels = favoriteModels + trimmed)
        }
    }

    /** Запомнить объявление провайдера о модели (пустое объявление не хранит). */
    fun withDeclaredReasoning(modelId: String, declared: DeclaredReasoning?): LlmProfile {
        val key = modelId.trim()
        if (key.isEmpty() || declared == null) return this
        return copy(modelReasoning = modelReasoning + (key to declared))
    }

    /** Выбор усилия для модели: персональная настройка, иначе профильная по умолчанию. */
    fun effortSelectionFor(modelId: String = this.modelId): EffortSelection =
        effortOverrides[modelId.trim()] ?: effort

    /** Что реально уйдёт в запрос по модели, с учётом её возможностей. */
    fun resolveEffort(
        capability: ReasoningCapability,
        modelId: String = this.modelId,
    ): ResolvedEffort = capability.resolveEffort(effortSelectionFor(modelId))

    /** Подпись для UI: «default», «medium», а при подмене уровня — «xhigh→high». */
    fun effortLabel(
        capability: ReasoningCapability,
        modelId: String = this.modelId,
    ): String {
        val resolved = resolveEffort(capability, modelId)
        val shown = resolved.level?.shortLabel ?: EffortSelection.DEFAULT_LABEL
        return if (resolved.clamped) "${resolved.requested?.shortLabel ?: shown}→$shown" else shown
    }

    /**
     * Запомнить усилие для модели. Выбор, совпадающий с профилем по умолчанию,
     * ключом не засоряется — модель наследует [effort].
     */
    fun withEffortFor(modelId: String, selection: EffortSelection): LlmProfile {
        val key = modelId.trim()
        if (key.isEmpty()) return copy(effort = selection)
        val overrides = effortOverrides.toMutableMap().apply {
            if (selection == effort) remove(key) else put(key, selection)
        }
        return copy(effortOverrides = overrides)
    }
}

@Serializable
enum class LlmChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
data class LlmMessage(
    val role: LlmChatRole,
    val content: String,
    /** Вложения запроса; исторические сообщения идут без них. */
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class LlmProfileSummary(
    val version: Int = 1,
    val profiles: List<LlmProfile> = emptyList(),
)

interface LlmProfileRepository {
    suspend fun load(): List<LlmProfile>
    suspend fun save(profile: LlmProfile)
    suspend fun delete(id: String)
    suspend fun replaceAll(profiles: List<LlmProfile>)
}
