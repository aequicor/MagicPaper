package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.SearchEngine
import io.aequicor.magicpaper.domain.SearchHit
import io.aequicor.magicpaper.domain.SearchProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Движок Querit.ai — Global Search API для LLM.
 * POST https://api.querit.ai/v1/search
 * Тело: { "query": "...", "count": N }, заголовок Authorization: Bearer KEY.
 */
class QueritSearchEngine(
    private val client: HttpClient,
    private val json: Json,
) : SearchEngine {

    override val provider = SearchProvider.QUERIT
    override val displayName = "Querit.ai"

    override fun isConfigured(settings: AppSettings) = settings.queritApiKey.isNotBlank()

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
        if (!isConfigured(settings)) return emptyList()
        val response = client.post("https://api.querit.ai/v1/search") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer " + settings.queritApiKey)
            setBody(json.encodeToString(QueritRequest.serializer(), QueritRequest(query, limit)))
        }
        val body = response.bodyAsText()
        return runCatching {
            val parsed = json.parseToJsonElement(body)
            extractHits(parsed)
        }.getOrDefault(emptyList())
    }

    private fun extractHits(element: kotlinx.serialization.json.JsonElement): List<SearchHit> {
        val obj = element.jsonObjectOrNull() ?: return emptyList()
        val candidates = obj["results"] ?: obj["data"] ?: obj["items"] ?: return emptyList()
        val array = candidates.jsonArrayOrNull() ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item.jsonObjectOrNull() ?: return@mapNotNull null
            val title = o.string("title") ?: return@mapNotNull null
            val url = o.string("url") ?: o.string("link") ?: ""
            val snippet = o.string("snippet") ?: o.string("description") ?: o.string("content") ?: ""
            SearchHit(title = title, url = url, snippet = snippet, provider = displayName)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
        this as? kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonObject.string(key: String) =
        this[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

    @Serializable
    private data class QueritRequest(val query: String, val count: Int)
}
