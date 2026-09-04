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
        val target = when (settings.searchProvider) {
            SearchProvider.AUTO -> pickAuto(settings)
            else -> settings.searchProvider
        }
        val engine = engines.firstOrNull { it.provider == target } ?: fallback()
        return runCatching { engine.search(query, settings, limit) }.getOrElse { emptyList() }
    }

    private fun pickAuto(settings: AppSettings): SearchProvider {
        val google = engines.firstOrNull { it.provider == SearchProvider.GOOGLE }
        if (google != null && google.isConfigured(settings)) return SearchProvider.GOOGLE
        val querit = engines.firstOrNull { it.provider == SearchProvider.QUERIT }
        if (querit != null && querit.isConfigured(settings)) return SearchProvider.QUERIT
        return SearchProvider.WIKIPEDIA
    }

    private fun fallback(): SearchEngine =
        engines.firstOrNull { it.provider == SearchProvider.WIKIPEDIA }
            ?: engines.first()

    fun engineFor(provider: SearchProvider): SearchEngine? =
        engines.firstOrNull { it.provider == provider }
}
