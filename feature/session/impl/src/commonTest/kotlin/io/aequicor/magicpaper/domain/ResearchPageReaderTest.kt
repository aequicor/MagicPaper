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
        val runtime = GatewaySessionRuntime(gateway, search, docs, readResearchPage = { url ->
            if (url.endsWith("missing")) error("Unavailable") else "Original page evidence"
        })
        val session = ChatSession("research", "Research", 1, 1,
            messages = listOf(ChatMessage("question", ChatRole.USER, "Поищи сведения", 1)),
            resources = listOf(ResearchResource("good", "Report", "https://example.org/report"),
                ResearchResource("missing", "Unavailable report", "https://example.org/missing")))
        val events = runtime.runChat(session, "Поищи сведения", LlmProfile("model", "Model", baseUrl = "https://example.org/v1", modelId = "model")).toList()
        assertTrue(input.any { "Original page evidence" in it.content })
        assertEquals("Поищи сведения", input.last().content)
        assertEquals("https://example.org/found", (events.first() as CodingEvent.ToolFinished).sources.single().url)
        val answer = events.filterIsInstance<CodingEvent.FinalText>().single().text
        assertTrue(answer.startsWith("Вывод"))
        assertTrue("Unavailable report" in answer && "Недоступные источники" in answer)
        val cancelled = GatewaySessionRuntime(gateway, search, docs, readResearchPage = { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { cancelled.runChat(session, "Question", null).toList() }
    }
}
