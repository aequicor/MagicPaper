package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchEngine
import io.aequicor.magicpaper.domain.SearchHit
import io.aequicor.magicpaper.domain.SearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Поиск в Wikipedia через открытый API (без ключей). */
class WikipediaSearchEngine(
    private val client: HttpClient,
    private val json: Json,
) : SearchEngine {

    override val provider = SearchProvider.WIKIPEDIA
    override val displayName = "Wikipedia"

    override fun isConfigured(settings: AppSettings) = true

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
        val response = client.get("https://ru.wikipedia.org/w/api.php") {
            parameter("action", "query")
            parameter("list", "search")
            parameter("srsearch", query)
            parameter("srlimit", limit)
            parameter("format", "json")
            parameter("utf8", 1)
        }
        val body = response.bodyAsText()
        val root = json.parseToJsonElement(body).jsonObject
        val items = root["query"]?.jsonObject?.get("search")?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val title = item.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = item.jsonObject["snippet"]?.jsonPrimitive?.contentOrNull ?: ""
            val url = "https://ru.wikipedia.org/wiki/" + title.replace(' ', '_')
            SearchHit(
                title = title,
                url = url,
                snippet = snippet.replace(Regex("<[^>]+>"), ""),
                provider = displayName,
            )
        }
    }
}
