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

/** Google Programmable Search JSON API (Custom Search). Требует ключ и ID движка. */
class GoogleSearchEngine(
    private val client: HttpClient,
    private val json: Json,
) : SearchEngine {

    override val provider = SearchProvider.GOOGLE
    override val displayName = "Google"

    override fun isConfigured(settings: AppSettings) =
        settings.googleApiKey.isNotBlank() && settings.googleSearchEngineId.isNotBlank()

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
        if (!isConfigured(settings)) return emptyList()
        val response = client.get(settings.googleSearchUrl.trim()) {
            parameter("q", query)
            parameter("key", settings.googleApiKey)
            parameter("cx", settings.googleSearchEngineId)
            parameter("num", limit.coerceIn(1, 10))
        }
        val body = response.bodyAsText()
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val items = root["items"]?.jsonArray ?: return@runCatching emptyList<SearchHit>()
            items.mapNotNull { item ->
                val o = item.jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SearchHit(
                    title = title,
                    url = o["link"]?.jsonPrimitive?.contentOrNull ?: "",
                    snippet = o["snippet"]?.jsonPrimitive?.contentOrNull ?: "",
                    provider = displayName,
                )
            }
        }.getOrDefault(emptyList())
    }
}
