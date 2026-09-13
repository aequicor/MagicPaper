package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchEngine
import io.aequicor.magicpaper.domain.SearchHit
import io.aequicor.magicpaper.domain.SearchProvider
import io.aequicor.magicpaper.domain.SearchResult

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

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> =
        searchWithDiagnostics(query, settings, limit).hits

    override suspend fun searchWithDiagnostics(query: String, settings: AppSettings, limit: Int): SearchResult {
        if (limit <= 0 || query.isBlank()) return SearchResult()
        val candidates = if (settings.searchProvider == SearchProvider.AUTO) {
            listOf(SearchProvider.GOOGLE, SearchProvider.QUERIT, SearchProvider.WIKIPEDIA)
                .mapNotNull(::engineFor).filter { it.isConfigured(settings) }
        } else listOfNotNull(engineFor(settings.searchProvider))
        val issues = mutableListOf<String>()
        if (candidates.isEmpty()) issues += "Выбранный поисковый движок недоступен."
        for (engine in candidates) {
            if (!engine.isConfigured(settings)) {
                issues += "${engine.displayName}: подключение не настроено."
                continue
            }
            val result = try { engine.searchWithDiagnostics(query, settings, limit) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val message = listOf(settings.queritApiKey, settings.queritContentApiKey, settings.googleApiKey)
                    .filter { it.isNotBlank() }.fold(e.message ?: "ошибка поиска") { text, secret -> text.replace(secret, "[скрыто]") }
                issues += "${engine.displayName}: $message"
                continue
            }
            issues += result.issues
            val hits = result.hits.take(limit)
            if (hits.isNotEmpty()) {
                val reader = engines.filterIsInstance<QueritSearchEngine>().firstOrNull()
                return SearchResult(reader?.enrich(hits, settings) ?: hits, issues)
            }
        }
        return SearchResult(issues = issues)
    }

    fun engineFor(provider: SearchProvider): SearchEngine? =
        engines.firstOrNull { it.provider == provider }
}
