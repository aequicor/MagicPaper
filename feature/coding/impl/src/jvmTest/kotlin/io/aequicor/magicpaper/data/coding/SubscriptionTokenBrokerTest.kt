package io.aequicor.magicpaper.data.coding

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SubscriptionTokenBrokerTest {
    @Test fun authenticatesRequestsAndRefreshesOnlyOnAuthorizedCalls() {
        val count = AtomicInteger()
        SubscriptionTokenBroker { "fixture-${count.incrementAndGet()}" }.use { broker ->
            val client = HttpClient.newHttpClient()
            fun request(key: String?, origin: String? = null): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(URI(broker.environment.getValue("MAGICPAPER_TOKEN_URL"))).POST(HttpRequest.BodyPublishers.noBody())
                key?.let { builder.header("Authorization", "Bearer $it") }; origin?.let { builder.header("Origin", it) }
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(403, request(null).statusCode())
            val key = broker.environment.getValue("MAGICPAPER_TOKEN_KEY")
            assertEquals(403, request(key, "https://example.com").statusCode())
            assertEquals(0, count.get())
            val first = request(key); val second = request(key)
            assertEquals(200, first.statusCode()); assertContains(first.body(), "fixture-1"); assertContains(second.body(), "fixture-2")
            assertEquals("no-store", first.headers().firstValue("Cache-Control").orElse(null))
        }
    }
    @Test fun refreshFailureDoesNotExposeCredentialDetails() {
        SubscriptionTokenBroker { error("secret-refresh-token") }.use { broker ->
            val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(broker.environment.getValue("MAGICPAPER_TOKEN_URL")))
                .header("Authorization", "Bearer ${broker.environment.getValue("MAGICPAPER_TOKEN_KEY")}")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, response.statusCode()); assertEquals("{}", response.body())
        }
    }
}
