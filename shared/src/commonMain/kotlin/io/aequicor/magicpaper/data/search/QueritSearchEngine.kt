package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Querit Search (including optional Webpage Text) and independent Contents connection. */
class QueritSearchEngine(private val client: HttpClient, private val json: Json) : SearchEngine {
    override val provider = SearchProvider.QUERIT
    override val displayName = "Querit.ai"
    override fun isConfigured(settings: AppSettings) = settings.queritApiKey.isNotBlank()

    override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
        if (!isConfigured(settings) || query.isBlank() || limit <= 0) return emptyList()
        val root = request(settings.queritBaseUrl, "search", settings.queritApiKey, buildJsonObject {
            put("query", query)
            put("count", limit.coerceAtMost(20))
            put("needContent", settings.queritWebpageTextEnabled)
        })
        return parseHits(root, settings.queritWebpageTextEnabled).take(limit)
    }

    /** A failed/partial crawl must preserve the original search sources. */
    suspend fun enrich(hits: List<SearchHit>, settings: AppSettings): List<SearchHit> {
        if (!settings.queritContentEnabled || settings.queritContentApiKey.isBlank()) return hits
        val urls = hits.map { it.url }.filter(::isWebUrl).distinct().take(10)
        if (urls.isEmpty()) return hits
        return try {
            val root = request(settings.queritContentBaseUrl, "contents", settings.queritContentApiKey, buildJsonObject {
                put("urls", JsonArray(urls.map(::JsonPrimitive)))
                put("format", "text")
                put("crawlTimeout", 10)
            })
            val failed = (root["statuses"] as? JsonArray).orEmpty().mapNotNull {
                val status = it as? JsonObject
                if (status?.text("status") == "failed") status.text("id") else null
            }.toSet()
            val content = (root["results"] as? JsonArray).orEmpty().mapNotNull {
                val item = it as? JsonObject ?: return@mapNotNull null
                if (item.text("id") in failed) return@mapNotNull null
                val url = item.text("url") ?: return@mapNotNull null
                val text = item.text("content")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (url !in urls) return@mapNotNull null
                url to text.take(MAX_TEXT)
            }.toMap()
            hits.map { hit -> content[hit.url]?.let { hit.copy(snippet = it) } ?: hit }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            hits
        }
    }

    private suspend fun request(base: String, path: String, key: String, payload: JsonObject): JsonObject {
        val response = client.post(endpoint(base, path)) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${key.trim()}")
            setBody(payload.toString())
        }
        check(response.status.isSuccess()) { "Querit $path: HTTP ${response.status.value}" }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: error("Querit $path: invalid response")
        val code = (root["error_code"] as? JsonPrimitive)?.intOrNull
        check(code == null || code == 200 || code == 0) { "Querit $path: API $code" }
        return root
    }

    internal fun parseHits(root: JsonObject, includeText: Boolean): List<SearchHit> {
        val candidates = root["results"] ?: root["data"] ?: root["items"]
        val items = (candidates as? JsonArray) ?: ((candidates as? JsonObject)?.get("result") as? JsonArray)
            ?: return emptyList()
        return items.mapNotNull {
            val item = it as? JsonObject ?: return@mapNotNull null
            val url = item.text("url") ?: item.text("link") ?: return@mapNotNull null
            if (!isWebUrl(url)) return@mapNotNull null
            val sentences = if (includeText) (item["sentence"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter(String::isNotBlank).joinToString("\n") else ""
            SearchHit(
                title = item.text("title")?.takeIf(String::isNotBlank) ?: url,
                url = url,
                snippet = sentences.ifBlank { item.text("snippet") ?: item.text("description") ?: item.text("content") ?: "" }.take(MAX_TEXT),
                provider = displayName,
            )
        }.distinctBy { it.url }
    }

    internal companion object {
        const val MAX_TEXT = 8000
        fun endpoint(base: String, path: String): String {
            val value = base.trim().trimEnd('/')
            require(isWebUrl(value)) { "Укажите HTTP(S) URL Querit" }
            return when {
                value.endsWith("/v1/$path") -> value
                value.endsWith("/v1") -> "$value/$path"
                else -> "$value/v1/$path"
            }
        }
        private fun isWebUrl(value: String): Boolean = runCatching {
            val url = Url(value)
            url.protocol.name in listOf("http", "https") && url.host.isNotBlank() && url.user == null && url.password == null
        }.getOrDefault(false)
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
