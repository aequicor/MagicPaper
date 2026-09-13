package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class SearchUsageTest {
    @Test fun searchAndFailedContentCountActualCallsAndDistinctPages() = runTest {
        val ledger = UsageLedger(JsonUsageRepository(InMemoryKeyValueStore(), Json))
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("contents")) respond("failure", HttpStatusCode.BadGateway)
            else respond("""{"results":[{"url":"https://a.example","title":"A"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val reader = QueritSearchEngine(client, Json, ledger)
            val settings = AppSettings(queritApiKey = "fixture", queritContentApiKey = "fixture", queritContentEnabled = true)
            withContext(UsageOwner(UsageScope.chat("chat"))) {
                val hits = reader.search("test", settings, 5)
                val duplicates = hits + hits + SearchHit("B", "https://b.example")
                assertEquals(duplicates, reader.enrich(duplicates, settings))
            }
            val records = ledger.state.value.records
            assertEquals(listOf(UsageKind.SEARCH, UsageKind.CONTENT), records.map { it.kind })
            assertEquals(2L, records.sumOf { it.requests })
            assertEquals(1L, records.sumOf { it.contentRequests })
            assertEquals(2L, records.sumOf { it.pages })
            assertTrue(records.all { it.scope.includes("chat:chat") && it.cost == null })
            assertFalse(records.last().completed)
        } finally { client.close() }
    }
}
