package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.ResearchPageReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ResearchPageReaderTest {
    @Test fun successfulHttpStatusDoesNotMakeChallengeLoginOrEmptyPagesReadable() = runTest {
        val pages = mapOf(
            "/captcha" to "<title>Just a moment...</title><p>Verify you are human</p>",
            "/blocked" to "<title>Attention Required! | Cloudflare</title><p>Please complete the security check</p>",
            "/login" to "<title>Sign in</title><form>Password</form>",
            "/paywall" to "<title>Report</title><p>Subscribe to read this article</p>",
            "/javascript" to "<p>Please enable JavaScript to continue</p>",
            "/empty" to "<script>loadArticle()</script>",
            "/shell" to "<head><title>Article</title></head><body><div id='app'></div><script>loadArticle()</script></body>",
            "/article" to "<title>CAPTCHA research and accessibility</title><article>We compare CAPTCHA recognition methods and their impact on users.</article>",
        )
        val client = HttpClient(MockEngine { respond(pages.getValue(it.url.encodedPath),
            headers = headersOf(HttpHeaders.ContentType, "text/html")) })
        try {
            val reader = ResearchPageReader(client)
            for (path in pages.keys - "/article") assertFailsWith<IllegalStateException>(path) { reader.read("https://example.org$path") }
            assertContains(reader.read("https://example.org/article"), "recognition methods")
        } finally { client.close() }
    }

    @Test fun readerUsesTheSelectedUrlAndRejectsNonTextOversizedAndFailedResponses() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/text" -> respond("<h1>Evidence</h1><p>Measured results</p>", headers = headersOf(HttpHeaders.ContentType, "text/html"))
                "/binary" -> respond("pdf", headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
                "/large" -> respond("x".repeat(1_000_001), headers = headersOf(HttpHeaders.ContentType, "text/plain"))
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        })
        try {
            val reader = ResearchPageReader(client)
            assertEquals("Evidence Measured results", reader.read("https://example.org/text"))
            for (path in listOf("binary", "large", "missing")) assertFailsWith<IllegalStateException> { reader.read("https://example.org/$path") }
        } finally { client.close() }
    }

    private val docs = object : DocRepository {
        override suspend fun articles() = emptyList<DocArticle>()
        override suspend fun search(query: String, limit: Int) = emptyList<DocMatch>()
    }
    private val search = object : SearchEngine {
        override val provider = SearchProvider.AUTO
        override val displayName = "Search"
        override fun isConfigured(settings: AppSettings) = true
        override suspend fun search(query: String, settings: AppSettings, limit: Int) = listOf(SearchHit("Found", "https://example.org/found"))
    }

    @Test fun gatewayPassesPageTextToModelAndExposesReadFailuresEvenIfModelOmitsThem() = runTest {
        var input = emptyList<LlmMessage>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { input = messages; return "Вывод" }
        }
        val runtime = testGatewayRuntime(gateway, search, docs, readResearchPage = { url ->
            if (url.endsWith("missing")) error("Unavailable") else "Original page evidence"
        })
        val session = ChatSession("research", "Research", 1, 1,
            messages = listOf(ChatMessage("question", ChatRole.USER, "Поищи сведения", 1)),
            resources = listOf(ResearchResource("good", "Report", "https://example.org/report", snippet = "UNVERIFIED_GOOD_SNIPPET"),
                ResearchResource("missing", "Unavailable report", "https://example.org/missing", snippet = "UNREADABLE_PAGE_CLAIM")))
        val events = runtime.runChat(session, "Поищи сведения", LlmProfile("model", "Model", baseUrl = "https://example.org/v1", modelId = "model")).toList()
        assertTrue(input.any { "Original page evidence" in it.content })
        assertFalse(input.any { "UNREADABLE_PAGE_CLAIM" in it.content || "UNVERIFIED_GOOD_SNIPPET" in it.content })
        assertEquals("Поищи сведения", input.last().content)
        assertTrue(events.none { it is CodingEvent.ToolFinished && it.tool == "web.search" },
            "ChatService has already prepared sources; gateway must not search a second time")
        val answer = events.filterIsInstance<CodingEvent.FinalText>().single().text
        assertTrue(answer.startsWith("Вывод"))
        assertTrue("Unavailable report" in answer && "Недоступные источники" in answer)
        assertTrue(events.filterIsInstance<CodingEvent.FinalText>().single().sources.none { it.url.endsWith("missing") })
        val cancelled = testGatewayRuntime(gateway, search, docs, readResearchPage = { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { cancelled.runChat(session, "Question", null).toList() }
    }

    @Test fun gatewaySummarizesTheSuppliedUrlWithoutSearchingOrReadingOtherSelectedPages() = runTest {
        var searches = 0
        val reads = mutableListOf<String>()
        var input = emptyList<LlmMessage>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { input = messages; return "Краткий пересказ" }
        }
        val countedSearch = object : SearchEngine by search {
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> { searches++; return emptyList() }
        }
        val runtime = testGatewayRuntime(gateway, countedSearch, docs,
            readResearchPage = { reads += it; "Text of the requested publication" })
        val result = runtime.answer(emptyList(), "https://example.org/paper\nкраткий пересказ", AppSettings(),
            LlmProfile("model", "Model", baseUrl = "https://example.org/v1", modelId = "model"),
            researchResources = listOf(ResearchResource("unrelated", "Other", "https://example.org/other")))
        assertEquals(0, searches)
        assertEquals(listOf("https://example.org/paper"), reads)
        assertEquals(listOf("https://example.org/paper"), result.sources.map { it.url })
        assertTrue(input.any { "Не запускай поиск в интернете" in it.content })
        assertFalse(input.any { "https://example.org/other" in it.content })
    }

    @Test fun searchSnippetsCannotLeakThroughWhenNoFoundPageIsReadable() = runTest {
        var input = emptyList<LlmMessage>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String { input = messages; return "Недостаточно данных" }
        }
        val searchWithSnippet = object : SearchEngine by search {
            override suspend fun search(query: String, settings: AppSettings, limit: Int) =
                listOf(SearchHit("Unreadable", "https://example.org/blocked", "FORBIDDEN_SNIPPET_ASSERTION"))
        }
        val runtime = testGatewayRuntime(gateway, searchWithSnippet, docs,
            readResearchPage = { "Verify you are human before continuing" })
        val result = runtime.answer(emptyList(), "Поищи исследования", AppSettings(),
            LlmProfile("model", "Model", baseUrl = "https://example.org/v1", modelId = "model"), researchResources = emptyList())
        assertTrue(result.sources.isEmpty())
        assertFalse(input.any { "FORBIDDEN_SNIPPET_ASSERTION" in it.content || "Verify you are human" in it.content })
        assertTrue(input.any { "Недоступные источники исключены" in it.content })
    }

    @Test fun anUnreadableSummarySourceDoesNotTriggerReplacementSearch() = runTest {
        var searches = 0
        var input = emptyList<LlmMessage>()
        val gateway = object : LlmGateway {
            override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String {
                input = messages
                return "Загрузите текст публикации."
            }
        }
        val countedSearch = object : SearchEngine by search {
            override suspend fun search(query: String, settings: AppSettings, limit: Int): List<SearchHit> {
                searches++
                return emptyList()
            }
        }
        val runtime = testGatewayRuntime(gateway, countedSearch, docs,
            readResearchPage = { "Verify you are human before continuing" })
        val result = runtime.answer(emptyList(), "https://example.org/blocked\nкраткий пересказ", AppSettings(),
            LlmProfile("model", "Model", baseUrl = "https://example.org/v1", modelId = "model"),
            researchResources = emptyList())
        assertEquals(0, searches)
        assertTrue(result.sources.isEmpty())
        assertContains(result.text, "Недоступные источники")
        assertTrue(input.any { "Не ищи замену в интернете" in it.content })
        assertFalse(input.any { "Найди доступный первоисточник" in it.content || "Можно найти и отдельно прочитать" in it.content })
        assertFalse(input.any { "Verify you are human" in it.content })
    }
}
