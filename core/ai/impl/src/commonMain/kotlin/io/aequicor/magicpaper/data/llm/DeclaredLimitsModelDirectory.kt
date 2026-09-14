package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults.DiscoveredModel
import io.aequicor.magicpaper.domain.ModelDirectory
import io.aequicor.magicpaper.domain.ModelLimitCatalog
import io.aequicor.magicpaper.domain.withCatalogLimits

/**
 * Каталог провайдера, дополненный заявленными пределами из каталога движка ([ModelLimitCatalog]).
 * Ответ провайдера остаётся источником правды: заполняются только пределы, которых сервер не
 * объявил. Это случай совместимых эндпоинтов, отдающих перечень `id` без метаданных
 * (DashScope compatible-mode, часть локальных серверов): без фактов контекст оставался бы
 * на значении конфигурации, хотя модель и эндпоинт его объявляют.
 */
class DeclaredLimitsModelDirectory(
    private val delegate: ModelDirectory,
    private val limits: ModelLimitCatalog,
) : ModelDirectory {

    override suspend fun models(profile: LlmProfile): List<DiscoveredModel> {
        val discovered = delegate.models(profile)
        val unknown = discovered
            .filter { it.metadata?.contextWindow == null || it.metadata?.maxOutputTokens == null }
            .map { it.id }
        if (unknown.isEmpty()) return discovered
        val facts = limits.limits(profile.baseUrl, unknown)
        if (facts.isEmpty()) return discovered
        return discovered.map { it.withCatalogLimits(profile.provider, facts[it.id]) }
    }
}
