package io.aequicor.magicpaper.data.search

import io.aequicor.magicpaper.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HttpSearchConnectionCheckerTest {
    private fun TestScope.testClient(handler: MockRequestHandler) = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = UnconfinedTestDispatcher(testScheduler)
        addHandler(handler)
    }))

    @Test fun checksDraftWithCorrectKeyAndWebpageText() = runTest {
        val client = testClient { request ->
            assertEquals("https://proxy.example/v1/search", request.url.toString())
            assertEquals("Bearer draft-key", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertTrue(body["needContent"]!!.jsonPrimitive.boolean)
            assertEquals(1, body["count"]!!.jsonPrimitive.int)
            respond("""{"error_code":200,"results":{"result":[{"url":"https://wikipedia.org"}]}}""")
        }
        try {
            assertTrue(HttpSearchConnectionChecker(client, Json).check(SearchConnection.QUERIT,
                AppSettings(queritApiKey = "draft-key", queritBaseUrl = "https://proxy.example", queritWebpageTextEnabled = true)).success)
        } finally { client.close() }
    }

    @Test fun validatesBeforeNetwork() = runTest {
        val client = testClient { error("Must not request") }
        try {
            val checker = HttpSearchConnectionChecker(client, Json)
            assertEquals("Введите API-ключ.", checker.check(SearchConnection.QUERIT, AppSettings()).message)
            assertEquals("Введите Search Engine ID.", checker.check(SearchConnection.GOOGLE, AppSettings(googleApiKey = "key")).message)
            assertFalse(checker.check(SearchConnection.CONTENT, AppSettings(queritContentApiKey = "key", queritContentBaseUrl = "file:///tmp")).success)
        } finally { client.close() }
    }

    @Test fun exposesSafeStatusInsteadOfServerBody() = runTest {
        for (status in listOf(401, 403, 404, 429, 500)) {
            val client = testClient { respond("secret-key", HttpStatusCode.fromValue(status)) }
            try {
                val result = HttpSearchConnectionChecker(client, Json).check(SearchConnection.QUERIT, AppSettings(queritApiKey = "secret-key"))
                assertFalse(result.success)
                assertTrue(result.message.contains(status.toString()))
                assertFalse(result.message.contains("secret-key"))
            } finally { client.close() }
        }
    }

    @Test fun contentCheckUsesOwnConnectionAndReportsFailedCrawl() = runTest {
        val client = testClient { request ->
            assertEquals("https://content.example/v1/contents", request.url.toString())
            assertEquals("Bearer content-key", request.headers[HttpHeaders.Authorization])
            respond("""{"error_code":200,"results":[{"id":"a","content":"text"}],"statuses":[{"id":"a","status":"failed"}]}""")
        }
        try {
            val result = HttpSearchConnectionChecker(client, Json).check(SearchConnection.CONTENT,
                AppSettings(queritApiKey = "search-key", queritContentApiKey = "content-key", queritContentBaseUrl = "https://content.example"))
            assertFalse(result.success)
            assertTrue(result.message.contains("прочитать"))
        } finally { client.close() }
    }

    @Test fun malformedAndApiErrorsDoNotPass() = runTest {
        for (body in listOf("not-json", "{}", """{"error_code":401} """, """{"results":{"result":[]}}""")) {
            val client = testClient { respond(body) }
            try {
                assertFalse(HttpSearchConnectionChecker(client, Json).check(SearchConnection.QUERIT, AppSettings(queritApiKey = "key")).success)
            } finally { client.close() }
        }
    }

    @Test fun timesOutAndPreservesExternalCancellation() = runTest {
        val client = testClient { delay(30_000); respond("{}") }
        try {
            val checker = HttpSearchConnectionChecker(client, Json)
            assertTrue(checker.check(SearchConnection.WIKIPEDIA, AppSettings()).message.contains("20 секунд"))
            assertFailsWith<CancellationException> { withTimeout(10) { checker.check(SearchConnection.WIKIPEDIA, AppSettings()) } }
        } finally { client.close() }
    }
}
