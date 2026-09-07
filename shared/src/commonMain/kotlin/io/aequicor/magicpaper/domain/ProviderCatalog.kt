package io.aequicor.magicpaper.domain

/** Модель из каталога: id + что она принимает в управлении усилием. */
data class ModelInfo(
    val id: String,
    /** Что модель умеет с усилием: набор уровней, диалект, границы бюджета. */
    val reasoning: ReasoningCapability = ReasoningCapability.None,
) {
    /** Есть ли у модели ручка усилия — спрашивают UI и транспорты. */
    val supportsEffort: Boolean get() = reasoning.supportsEffort
}

/** Каталогное описание известного провайдера — для быстрого подключения. */
data class ProviderSpec(
    val type: ProviderType,
    val displayName: String,
    val defaultBaseUrl: String,
    val keyHint: String,
    val requiresKey: Boolean,
    val models: List<ModelInfo>,
    /** Провайдер физически реализован только JVM desktop-адаптером. */
    val desktopOnly: Boolean = false,
    /** Авторизация через ChatGPT, без Base URL и API-ключа. */
    val usesSubscription: Boolean = false,
)

/**
 * Каталог известных провайдеров. Живёт в коде (как документация и лавка навыков):
 * работает офлайн, расширяется коммитом, без внешних зависимостей.
 *
 * Формы ручек берутся из [ReasoningPresets] — там они однажды сверены с
 * API-справочниками вендоров, здесь указано только, какой форме какая модель
 * принадлежит (это факт, а не догадка по имени).
 */
object ProviderCatalog {
    private val none = ReasoningCapability.None

    val all: List<ProviderSpec> = listOf(
        ProviderSpec(
            type = ProviderType.OPENAI_SUBSCRIPTION,
            displayName = "OpenAI (подписка ChatGPT)",
            defaultBaseUrl = "",
            keyHint = "вход через ChatGPT",
            requiresKey = false,
            models = listOf(ModelInfo("gpt-5.6-terra", ReasoningPresets.OPENAI_EFFORT)),
            desktopOnly = true,
            usesSubscription = true,
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Ollama (локально)",
            defaultBaseUrl = "http://localhost:11434/v1",
            keyHint = "ключ не нужен",
            requiresKey = false,
            models = listOf(
                ModelInfo("qwen3:8b", ReasoningPresets.MODE_ONLY),
                ModelInfo("deepseek-r1:8b", ReasoningPresets.REACT_EFFORT),
                ModelInfo("llama3.2:3b"),
                ModelInfo("phi4"),
                ModelInfo("gemma3:4b"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "OpenAI",
            defaultBaseUrl = "https://api.openai.com/v1",
            keyHint = "sk-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("gpt-5.2", ReasoningPresets.OPENAI_EFFORT),
                ModelInfo("gpt-5.2-codex", ReasoningPresets.OPENAI_EFFORT),
                ModelInfo("gpt-5-mini", ReasoningPresets.OPENAI_EFFORT),
                ModelInfo("sora-2"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "LM Studio (локально)",
            defaultBaseUrl = "http://localhost:1234/v1",
            keyHint = "ключ не нужен",
            requiresKey = false,
            models = listOf(
                ModelInfo("qwen2.5-coder-32b-instruct"),
                ModelInfo("deepseek-r1-distill-qwen-14b", ReasoningPresets.REACT_EFFORT),
            ),
        ),
        ProviderSpec(
            type = ProviderType.ANTHROPIC,
            displayName = "Anthropic",
            defaultBaseUrl = "https://api.anthropic.com",
            keyHint = "sk-ant-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("claude-haiku-4-5", ReasoningPresets.ANTHROPIC_BUDGET),
                ModelInfo("claude-sonnet-5", ReasoningPresets.ANTHROPIC_ADAPTIVE),
            ),
        ),
        ProviderSpec(
            type = ProviderType.GOOGLE,
            displayName = "Google AI",
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            keyHint = "AIza…",
            requiresKey = true,
            models = listOf(
                ModelInfo("gemini-3.1-pro-preview", ReasoningPresets.GEMINI_LEVEL),
                ModelInfo("gemini-3.1-flash-lite", ReasoningPresets.GEMINI_LEVEL),
                ModelInfo("gemini-2.5-pro", ReasoningPresets.GEMINI_BUDGET),
                ModelInfo("imagen-4.0-generate-001"),
                ModelInfo("gemini-embedding-001"),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "DeepSeek",
            defaultBaseUrl = "https://api.deepseek.com/v1",
            keyHint = "sk-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("deepseek-chat"),
                ModelInfo("deepseek-reasoner", ReasoningPresets.MODE_ONLY),
                ModelInfo("deepseek-v4-flash", ReasoningPresets.COMPAT_EFFORT),
                ModelInfo("deepseek-v4-pro", ReasoningPresets.COMPAT_EFFORT),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Zhipu GLM",
            defaultBaseUrl = "https://api.z.ai/api/paas/v4",
            keyHint = "…",
            requiresKey = true,
            models = listOf(
                ModelInfo("glm-5.2", ReasoningPresets.COMPAT_EFFORT),
                ModelInfo("glm-5v-turbo", ReasoningPresets.COMPAT_EFFORT),
                ModelInfo("glm-5.1", ReasoningPresets.COMPAT_EFFORT),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Kimi (Moonshot)",
            defaultBaseUrl = "https://api.moonshot.ai/v1",
            keyHint = "sk-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("kimi-for-coding", ReasoningPresets.KIMI_EFFORT),
                ModelInfo("kimi-k3", ReasoningPresets.KIMI_EFFORT),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENROUTER,
            displayName = "OpenRouter",
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            keyHint = "sk-or-…",
            requiresKey = true,
            models = listOf(
                ModelInfo("openai/gpt-5.2", ReasoningPresets.OPENAI_EFFORT),
                ModelInfo("x-ai/grok-4.5", ReasoningPresets.REACT_EFFORT),
                ModelInfo("anthropic/claude-4.6-sonnet", ReasoningPresets.ANTHROPIC_ADAPTIVE),
                ModelInfo("google/gemini-2.5-flash", ReasoningPresets.GEMINI_BUDGET),
                ModelInfo("deepseek/deepseek-v4-pro", ReasoningPresets.COMPAT_EFFORT),
            ),
        ),
        ProviderSpec(
            type = ProviderType.OPENAI_COMPATIBLE,
            displayName = "Self-hosted (vLLM)",
            defaultBaseUrl = "http://localhost:8000/v1",
            keyHint = "при необходимости",
            requiresKey = false,
            models = listOf(
                ModelInfo("Qwen3-Coder", ReasoningPresets.MODE_ONLY),
                ModelInfo("DeepSeek-R1", ReasoningPresets.REACT_EFFORT),
            ),
        ),
    )

    /** Пресеты для локальных моделей (Ollama, LM Studio) — без ключей. */
    val localPresets: List<ProviderSpec> = all.filter {
        !it.requiresKey && it.type == ProviderType.OPENAI_COMPATIBLE
    }

    /** Быстрые кнопки «добавить провайдер»: по одному на тип, без локальных дублей. */
    val quickPickPresets: List<ProviderSpec> = all.filter {
        it.type != ProviderType.OPENAI_COMPATIBLE || localPresets.none { local -> local.displayName == it.displayName }
    }

    val popularModels: List<ModelInfo> = all
        .filter { it.requiresKey || it.usesSubscription }
        .flatMap { it.models }
        .distinctBy { it.id }

    /** Все id моделей из каталога — для фильтра «своя модель» в редакторе профиля. */
    val allModelIds: List<String> = all.flatMap { it.models }.map { it.id }.distinct().sorted()

    /** Описание модели из каталога; null для своих (вне каталога) моделей. */
    fun modelInfo(provider: ProviderType, modelId: String): ModelInfo? =
        all.asSequence()
            .filter { it.type == provider }
            .flatMap { it.models.asSequence() }
            .firstOrNull { it.id == modelId }

    /**
     * Модель по умолчанию для нового профиля: первая модель выбранного пресета,
     * иначе — первая из каталога по типу провайдера, иначе пустая строка.
     */
    fun defaultModelFor(spec: ProviderSpec?): String =
        spec?.models?.firstOrNull()?.id
            ?: all.firstOrNull { it.type == spec?.type }?.models?.firstOrNull()?.id
            ?: ""

    /** Подсказка «для какого это модельного ряда», чтобы не подставлять DeepSeek-модель в профиль OpenAI. */
    fun familyOf(modelId: String): String {
        val id = modelId.trim().lowercase().substringBefore('/')
        return when {
            id.startsWith("glm") -> "glm"
            id.startsWith("kimi") -> "kimi"
            id.startsWith("deepseek") -> "deepseek"
            id.startsWith("claude") -> "claude"
            id.startsWith("gemini") || id.startsWith("imagen") -> "gemini"
            id.startsWith("gpt") || id.startsWith("o1") || id.startsWith("o3") -> "openai"
            id.startsWith("qwen") -> "qwen"
            else -> ""
        }
    }

    /** Категория модели для подписи: effort/reasoning — это «рассуждающие». */
    fun defaultKindFor(modelId: String): ModelKind =
        if ("reasoning" in modelId.lowercase() || modelId.supportsEffort()) ModelKind.REASONING else ModelKind.GENERAL

    /** Есть ли у модели ручка усилия (по каталогу, а при его отсутствии — по эвристике имён). */
    private fun String.supportsEffort(): Boolean =
        ModelDefaults.capability(ProviderType.OPENAI_COMPATIBLE, this).supportsEffort

    /** Все семейные id из каталога — для фильтрации и автодополнения в редакторе профиля. */
    val allFamilyModelIds: List<String> = allModelIds

    /**
     * Модель по умолчанию для новой сессии в конкретном чате:
     * берём профиль по умолчанию, при его недоступности — первый профиль с моделью.
     */
    fun defaultModelFor(profiles: List<LlmProfile>, defaultProfileId: String?): String {
        val profile = profiles.firstOrNull { it.id == defaultProfileId }
            ?: profiles.firstOrNull { it.configured }
        return profile?.modelId.orEmpty()
    }
}
