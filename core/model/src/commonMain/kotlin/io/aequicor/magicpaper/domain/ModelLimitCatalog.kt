package io.aequicor.magicpaper.domain

/**
 * Пределы модели, объявленные авторитетным каталогом. `null` означает «не объявлено»:
 * потребитель оставляет собственную конфигурацию или сообщает о неизвестном пределе,
 * но не подставляет догадку по имени семейства.
 */
data class CatalogModelLimits(
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    val isUnknown: Boolean get() = contextWindow == null && maxOutputTokens == null
}

/**
 * Источник объявленных пределов моделей (например, каталог, поставляемый с движком).
 * Совпадение определяется эндпоинтом провайдера и идентификатором модели; ключи результата
 * повторяют запрошенные идентификаторы. Пустой результат — фактов нет, а не «нулевой контекст».
 */
fun interface ModelLimitCatalog {
    fun limits(baseUrl: String, modelIds: Collection<String>): Map<String, CatalogModelLimits>
}

/**
 * Дополняет каталог профиля объявленными пределами. Ответ провайдера остаётся источником правды:
 * заполняются только значения, которых в сохранённых фактах нет. Профиль без неизвестных пределов
 * возвращается без изменений, чтобы не создавать лишние сохранения.
 */
fun LlmProfile.withCatalogLimits(catalog: ModelLimitCatalog?): LlmProfile {
    if (catalog == null || modelCatalog.isEmpty()) return this
    val unknown = modelCatalog.filter { it.contextWindow == null || it.maxOutputTokens == null }.map { it.id }
    if (unknown.isEmpty()) return this
    val facts = catalog.limits(baseUrl, unknown)
    if (facts.isEmpty()) return this
    var completed = false
    val catalogWithFacts = modelCatalog.map { model ->
        val fact = facts[model.id] ?: return@map model
        val next = model.copy(
            contextWindow = model.contextWindow ?: fact.contextWindow,
            maxOutputTokens = model.maxOutputTokens ?: fact.maxOutputTokens,
        )
        if (next == model) model else { completed = true; next }
    }
    return if (completed) copy(modelCatalog = catalogWithFacts) else this
}

/**
 * Дополняет метаданные найденной модели объявленными пределами и пересчитывает рекомендацию
 * по тому же правилу, что и [ModelDefaults.discover]: значения провайдера остаются сильнее фактов каталога.
 */
fun ModelDefaults.DiscoveredModel.withCatalogLimits(
    provider: ProviderType,
    fact: CatalogModelLimits?,
): ModelDefaults.DiscoveredModel {
    if (fact == null || fact.isUnknown) return this
    val known = metadata ?: ProviderModel(id = id)
    val next = known.copy(
        contextWindow = known.contextWindow ?: fact.contextWindow,
        maxOutputTokens = known.maxOutputTokens ?: fact.maxOutputTokens,
    )
    if (metadata != null && next == metadata) return this
    return copy(
        metadata = next,
        recommendation = ModelDefaults.recommendation(provider, id, declared = declared, fact = next),
    )
}
