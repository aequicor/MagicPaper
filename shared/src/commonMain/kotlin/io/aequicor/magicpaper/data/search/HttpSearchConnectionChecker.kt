package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Checks the draft directly: no saving, fallback provider or swallowed crawl failures. */
class HttpSearchConnectionChecker(private val client: HttpClient, private val json: Json, private val usage: UsageLedger? = null) : SearchConnectionChecker {
    override suspend fun check(connection: SearchConnection, settings: AppSettings): SearchConnectionResult {
        val key = when (connection) {
            SearchConnection.QUERIT -> settings.queritApiKey
            SearchConnection.CONTENT -> settings.queritContentApiKey
            SearchConnection.GOOGLE -> settings.googleApiKey
            SearchConnection.WIKIPEDIA -> "unused"
        }
        if (key.isBlank()) return failure("Введите API-ключ.")
        if (connection == SearchConnection.GOOGLE && settings.googleSearchEngineId.isBlank())
            return failure("Введите Search Engine ID.")
        val endpoint = try {
            when (connection) {
                SearchConnection.QUERIT -> QueritSearchEngine.endpoint(settings.queritBaseUrl, "search")
                SearchConnection.CONTENT -> QueritSearchEngine.endpoint(settings.queritContentBaseUrl, "contents")
                SearchConnection.GOOGLE -> settings.googleSearchUrl.trim()
                SearchConnection.WIKIPEDIA -> "https://ru.wikipedia.org/w/api.php"
            }.also {
                val url = Url(it)
                require(url.protocol.name in listOf("http", "https") && url.host.isNotBlank() && url.user == null && url.password == null)
            }
        } catch (_: Exception) { return failure("Проверьте адрес сервера: нужен URL с https:// или http://.") }
        return try {
            measuredSearch(usage, connection.name, if (connection == SearchConnection.CONTENT) UsageKind.CONTENT else UsageKind.SEARCH,
                pages = if (connection == SearchConnection.CONTENT) 1 else 0) {
            withTimeoutOrNull(20_000) {
                val response = when (connection) {
                    SearchConnection.QUERIT, SearchConnection.CONTENT -> client.post(endpoint) {
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Authorization, "Bearer ${key.trim()}")
                        setBody(buildJsonObject {
                            if (connection == SearchConnection.CONTENT) {
                                put("urls", buildJsonArray { add("https://example.com") })
                                put("format", "text")
                                put("crawlTimeout", 10)
                            } else {
                                put("query", "Wikipedia")
                                put("count", 1)
                                put("needContent", settings.queritWebpageTextEnabled)
                            }
                        }.toString())
                    }
                    SearchConnection.GOOGLE -> client.get(endpoint) {
                        parameter("q", "Wikipedia")
                        parameter("key", key.trim())
                        parameter("cx", settings.googleSearchEngineId.trim())
                        parameter("num", 1)
                    }
                    SearchConnection.WIKIPEDIA -> client.get(endpoint) {
                        parameter("action", "query")
                        parameter("list", "search")
                        parameter("srsearch", "Wikipedia")
                        parameter("srlimit", 1)
                        parameter("format", "json")
                        parameter("origin", "*")
                    }
                }
                if (!response.status.isSuccess()) return@withTimeoutOrNull statusFailure(response.status.value)
                val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                    ?: return@withTimeoutOrNull failure("Сервер вернул неожиданный формат ответа.")
                val code = (root["error_code"] as? JsonPrimitive)?.intOrNull
                    ?: ((root["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
                if (code != null && code !in listOf(0, 200)) return@withTimeoutOrNull statusFailure(code)
                if (root["error"] != null) return@withTimeoutOrNull failure("API вернул ошибку. Проверьте параметры подключения.")
                val items = when (connection) {
                    SearchConnection.QUERIT -> (root["results"] as? JsonObject)?.get("result") as? JsonArray
                    SearchConnection.CONTENT -> root["results"] as? JsonArray
                    SearchConnection.GOOGLE -> root["items"] as? JsonArray
                    SearchConnection.WIKIPEDIA -> (root["query"] as? JsonObject)?.get("search") as? JsonArray
                } ?: return@withTimeoutOrNull failure("API ответил, но не вернул ожидаемые результаты.")
                if (connection == SearchConnection.CONTENT) {
                    val failedIds = (root["statuses"] as? JsonArray).orEmpty().mapNotNull {
                        val item = it as? JsonObject
                        if (item?.get("status")?.jsonPrimitive?.contentOrNull == "failed") item["id"] else null
                    }.toSet()
                    val readable = items.any {
                        val item = it as? JsonObject
                        item != null && item["id"] !in failedIds &&
                            (item["content"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                    }
                    if (!readable) return@withTimeoutOrNull failure("API доступен, но тестовую страницу прочитать не удалось.")
                } else if (items.isEmpty()) return@withTimeoutOrNull failure("API доступен, но тестовый поиск не дал результатов.")
                SearchConnectionResult(true, if (connection == SearchConnection.CONTENT) "Подключено · тестовая страница прочитана" else "Подключено · поиск работает")
            } ?: failure("Сервер не ответил за 20 секунд. Проверьте адрес и сеть.")
        }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Exception messages can contain URLs, query-string keys and server response bodies.
            failure("Не удалось проверить подключение. Проверьте сеть, адрес и доступ браузера к API (CORS).")
        }
    }

    private fun failure(message: String) = SearchConnectionResult(false, message)
    private fun statusFailure(code: Int) = failure(when (code) {
        401 -> "Ключ не принят (401). Проверьте API-ключ."
        403 -> "Доступ запрещён (403). Проверьте ключ и разрешения API."
        404 -> "API не найден (404). Проверьте адрес сервера."
        429 -> "Лимит запросов исчерпан (429). Повторите позже или проверьте квоту."
        else -> "Ошибка API ($code). Проверьте настройки или повторите позже."
    })
}
