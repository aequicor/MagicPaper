package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class QueritSearchEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun searchUsesNestedResultsAndOptionalWebpageText() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://search.example/v1/search", request.url.toString())
            assertEquals("Bearer search-key", request.headers[HttpHeaders.Authorization])
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(true, body["needContent"]!!.jsonPrimitive.boolean)
            assertEquals(20, body["count"]!!.jsonPrimitive.int)
            respond("""{"error_code":200,"results":{"result":[
                {"url":"https://a.example","title":"A","snippet":"summary","sentence":["Full text","Second line"]},
                {"url":"https://b.example","snippet":"fallback"},
                {"url":"https://a.example","title":"duplicate"},
                {"url":"javascript:alert(1)"}
            ]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val hits = QueritSearchEngine(client, json).search("test", AppSettings(
                queritApiKey = " search-key ", queritBaseUrl = "https://search.example/", queritWebpageTextEnabled = true,
            ), 40)
            assertEquals(2, hits.size)
            assertEquals("Full text\nSecond line", hits[0].snippet)
            assertEquals("fallback", hits[1].snippet)
            assertEquals("https://b.example", hits[1].title)
        } finally { client.close() }
    }

    @Test fun contentsUsesSeparateCredentialsAndPreservesFailedPages() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://content.example/v1/contents", request.url.toString())
            assertEquals("Bearer content-key", request.headers[HttpHeaders.Authorization])
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("text", body["format"]!!.jsonPrimitive.content)
            assertEquals(2, body["urls"]!!.jsonArray.size)
            respond("""{"error_code":200,"results":[
                {"id":"1","url":"https://a.example","content":"page text"},
                {"id":"2","url":"https://b.example","content":"bad text"}
            ],"statuses":[{"id":"1","status":"success"},{"id":"2","status":"failed"}]}""")
        })
        try {
            val reader = QueritSearchEngine(client, json)
            val composite = CompositeSearchEngine(listOf(fake(SearchProvider.GOOGLE) {
                listOf(SearchHit("A", "https://a.example", "A summary"), SearchHit("B", "https://b.example", "B summary"))
            }, reader))
            val hits = composite.search("test", AppSettings(searchProvider = SearchProvider.GOOGLE,
                queritContentEnabled = true, queritContentApiKey = "content-key", queritContentBaseUrl = "https://content.example/v1"), 5)
            assertEquals("page text", hits[0].snippet)
            assertEquals("B summary", hits[1].snippet)
        } finally { client.close() }
    }

    @Test fun contentFailureDoesNotLoseSearchResults() = runTest {
        val client = HttpClient(MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) })
        try {
            val reader = QueritSearchEngine(client, json)
            val hits = listOf(SearchHit("A", "https://a.example", "summary"))
            assertEquals(hits, reader.enrich(hits, AppSettings(queritContentEnabled = true, queritContentApiKey = "key")))
            assertFailsWith<IllegalStateException> { reader.search("test", AppSettings(queritApiKey = "key"), 5) }
        } finally { client.close() }
    }

    @Test fun disabledContentsMakesNoRequestsAndCancellationPropagates() = runTest {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            val reader = QueritSearchEngine(client, json)
            val hits = listOf(SearchHit("A", "https://a.example"))
            assertEquals(hits, reader.enrich(hits, AppSettings(queritContentApiKey = "key")))
            assertEquals(hits, reader.enrich(hits, AppSettings(queritContentEnabled = true)))
            assertFailsWith<CancellationException> { reader.enrich(hits, AppSettings(queritContentEnabled = true, queritContentApiKey = "key")) }
        } finally { client.close() }
    }

    @Test fun autoFallsBackButExplicitChoiceDoesNot() = runTest {
        val calls = mutableListOf<SearchProvider>()
        val composite = CompositeSearchEngine(listOf(
            fake(SearchProvider.GOOGLE) { calls += SearchProvider.GOOGLE; error("offline") },
            fake(SearchProvider.QUERIT) { calls += SearchProvider.QUERIT; emptyList() },
            fake(SearchProvider.WIKIPEDIA) { calls += SearchProvider.WIKIPEDIA; listOf(SearchHit("wiki", "https://w.example")) },
        ))
        assertEquals(1, composite.search("test", AppSettings(), 5).size)
        assertEquals(listOf(SearchProvider.GOOGLE, SearchProvider.QUERIT, SearchProvider.WIKIPEDIA), calls)
        calls.clear()
        assertTrue(composite.search("test", AppSettings(searchProvider = SearchProvider.QUERIT), 5).isEmpty())
        assertEquals(listOf(SearchProvider.QUERIT), calls)
    }

    @Test fun settingsRoundTripAndLegacyDefaults() {
        val legacy = json.decodeFromString<AppSettings>("""{"queritApiKey":"old-key"}""")
        assertEquals("old-key", legacy.queritApiKey)
        assertFalse(legacy.queritContentEnabled)
        val settings = legacy.copy(queritContentApiKey = "separate", queritContentBaseUrl = "https://proxy.example", queritContentEnabled = true, queritWebpageTextEnabled = true)
        assertEquals(settings, json.decodeFromString<AppSettings>(json.encodeToString(AppSettings.serializer(), settings)))
        assertEquals("https://proxy.example/v1/search", QueritSearchEngine.endpoint("https://proxy.example/v1/search/", "search"))
    }

    private fun fake(provider: SearchProvider, block: suspend () -> List<SearchHit>) = object : SearchEngine {
        override val provider = provider
        override val displayName = provider.name
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = block()
    }
}
