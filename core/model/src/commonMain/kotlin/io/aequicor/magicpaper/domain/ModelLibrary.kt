package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Facts from the provider. Missing values mean unknown, never invented provider defaults. */
@Serializable
data class ProviderModel(
    val id: String,
    val name: String = id,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val defaultParameters: Map<String, JsonElement> = emptyMap(),
    val supportedParameters: Set<String>? = null,
    val reasoning: DeclaredReasoning? = null,
    val pricing: ModelPricing? = null,
)

/** A named fork. Its identity is independent of the provider's wire model id and effort. */
@Serializable
data class ModelVariant(
    val id: String,
    val name: String,
    val sourceModelId: String,
    val options: AdvancedLlmOptions,
)

/** A choice belongs to a chat, a project/session, or the operational default. */
@Serializable
data class ModelSelection(
    val profileId: String,
    val modelId: String,
    val effort: EffortSelection = EffortSelection.Default,
)

fun LlmProfile.sourceModelId(key: String): String = variants.firstOrNull { it.id == key }?.sourceModelId ?: key
fun LlmProfile.modelName(key: String): String = variants.firstOrNull { it.id == key }?.name
    ?: modelCatalog.firstOrNull { it.id == key }?.name ?: key
val LlmProfile.selectionKey: String get() = invocationKey ?: modelId
val LlmProfile.connectionConfigured: Boolean get() = provider == ProviderType.OPENAI_SUBSCRIPTION || baseUrl.isNotBlank()
val LlmProfile.supportsCoding: Boolean get() = provider in setOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.OPENAI_SUBSCRIPTION, ProviderType.OPENROUTER, ProviderType.ANTHROPIC, ProviderType.GOOGLE)

fun LlmProfile.providerOptions(key: String): AdvancedLlmOptions {
    val model = modelCatalog.firstOrNull { it.id == sourceModelId(key) }
    val defaults = model?.defaultParameters.orEmpty()
    fun number(vararg names: String): JsonPrimitive? = names.firstNotNullOfOrNull { defaults[it] as? JsonPrimitive }
    // Передаём fact из каталога, чтобы recommendation подхватила contextWindow
    // и maxOutputTokens, объявленные провайдером (DashScope не отдаёт их в /models,
    // но OpenRouter/Google отдают; без fact Qwen fallback на 1M не срабатывает).
    val recommendation = ModelDefaults.recommendation(provider, sourceModelId(key), fact = model)
    return AdvancedLlmOptions(
        temperature = number("temperature")?.doubleOrNull,
        topP = number("top_p", "topP")?.doubleOrNull,
        maxTokens = number("max_tokens", "max_output_tokens", "maxOutputTokens")?.intOrNull
            ?: minOf(recommendation.advanced.maxTokens, model?.maxOutputTokens ?: Int.MAX_VALUE),
        contextLimit = model?.contextWindow?.takeIf { it > 0 } ?: recommendation.advanced.contextLimit,
        sendMaxTokens = number("max_tokens", "max_output_tokens", "maxOutputTokens") != null,
        extraParameters = defaults.filterKeys { it in (model?.supportedParameters ?: emptySet()) && it in CUSTOM_MODEL_PARAMETERS },
    )
}

// Only generation controls belong in variants; model, messages, tools and effort are owned by the app.
val CUSTOM_MODEL_PARAMETERS = setOf("seed", "frequency_penalty", "presence_penalty", "top_k", "min_p", "repetition_penalty", "stop")

/** Build an immutable request snapshot. Never changes the connection or another conversation. */
fun LlmProfile.forModel(key: String = modelId, selection: EffortSelection = effortSelectionFor(key)): LlmProfile {
    if (invocationKey != null && (key == modelId || key == invocationKey)) {
        return copy(effort = selection, effortOverrides = emptyMap())
    }
    val source = sourceModelId(key)
    val options = variants.firstOrNull { it.id == key }?.options ?: providerOptions(key)
    val limit = modelCatalog.firstOrNull { it.id == source }?.maxOutputTokens
    val bounded = if (limit != null) options.copy(maxTokens = minOf(options.maxTokens, limit)) else options
    return copy(modelId = source, codingModelId = source, advanced = bounded,
        effort = selection, effortOverrides = emptyMap(), invocationKey = key)
}

fun LlmProfile.withCatalog(models: List<ModelDefaults.DiscoveredModel>): LlmProfile = copy(
    modelCatalog = models.map { it.metadata ?: ProviderModel(it.id, reasoning = it.declared) },
    modelReasoning = models.mapNotNull { it.declared?.let { declaration -> it.id to declaration } }.toMap(),
)

/** Preserve old custom settings as explicit forks, and old visible choices as favorites once. */
fun LlmProfile.migrateModelLibrary(): LlmProfile {
    if (modelLibraryVersion >= 1) return this
    val oldModels = (favoriteModels + listOf(modelId, codingModelId)).filter { it.isNotBlank() }.distinct()
    val custom = oldModels.mapNotNull { model ->
        if (advanced == providerOptions(model)) null
        else ModelVariant("variant:legacy:$model", "$model · сохранённые настройки", model, advanced)
    }
    return copy(modelLibraryVersion = 1, favoriteModels = oldModels, variants = variants + custom,
        modelId = custom.firstOrNull { it.sourceModelId == modelId }?.id ?: modelId,
        codingModelId = custom.firstOrNull { it.sourceModelId == codingModelId }?.id ?: codingModelId,
        effortOverrides = effortOverrides + custom.associate { it.id to effortSelectionFor(it.sourceModelId) })
}

fun List<ModelDossier>.forModel(profile: LlmProfile, key: String): ModelDossier? =
    firstOrNull { it.profileId == profile.id && it.modelId == key }
        ?: firstOrNull { it.profileId == profile.id && it.modelId == profile.sourceModelId(key) }
        ?: firstOrNull { it.profileId == profile.id && it.modelId.isBlank() }
