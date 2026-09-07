package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchEngine
import io.aequicor.magicpaper.domain.SearchHit
import io.aequicor.magicpaper.domain.SearchProvider

/**
 * Композит: реализует стратегию выбора движка по настройкам (паттерн "фасад" + стратегия).
 * Порядок в режиме AUTO: настроенный ключевой движок (Google -> Querit) -> Wikipedia.
 */
class CompositeSearchEngine(
    private val engines: List<SearchEngine>,
) : SearchEngine {

    override val provider = SearchProvider.AUTO
    override val displayName = "Авто"

    override fun isConfigured(settings: AppSettings) = true

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
        if (limit <= 0 || query.isBlank()) return emptyList()
        val candidates = if (settings.searchProvider == SearchProvider.AUTO) {
            listOf(SearchProvider.GOOGLE, SearchProvider.QUERIT, SearchProvider.WIKIPEDIA)
                .mapNotNull(::engineFor).filter { it.isConfigured(settings) }
        } else listOfNotNull(engineFor(settings.searchProvider))
        for (engine in candidates) {
            val hits = try { engine.search(query, settings, limit).take(limit) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { emptyList() }
            if (hits.isNotEmpty()) {
                val reader = engines.filterIsInstance<QueritSearchEngine>().firstOrNull()
                return reader?.enrich(hits, settings) ?: hits
            }
        }
        return emptyList()
    }

    fun engineFor(provider: SearchProvider): SearchEngine? =
        engines.firstOrNull { it.provider == provider }
}
