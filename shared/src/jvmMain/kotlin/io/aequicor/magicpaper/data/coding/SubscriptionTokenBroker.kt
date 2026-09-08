package io.aequicor.magicpaper.data.coding

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Only short-lived access tokens cross this per-turn loopback bridge. Refresh tokens stay in Codex. */
internal class SubscriptionTokenBroker(private val accessToken: suspend () -> String) : AutoCloseable {
    private val key = UUID.randomUUID().toString()
    private val executor = Executors.newFixedThreadPool(2) { Thread(it, "magicpaper-subscription").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@SubscriptionTokenBroker.executor
        createContext("/token") { exchange ->
            try {
                val allowed = exchange.requestMethod == "POST" && exchange.requestURI.path == "/token" &&
                    exchange.requestHeaders.getFirst("Authorization") == "Bearer $key" && exchange.requestHeaders.getFirst("Origin") == null
                val token = if (allowed) runCatching { runBlocking { accessToken() } }.getOrNull() else null
                val status = if (!allowed) 403 else if (token == null) 401 else 200
                val bytes = (if (token == null) "{}" else buildJsonObject { put("token", token) }.toString()).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        start()
    }
    val environment: Map<String, String> get() = mapOf("MAGICPAPER_TOKEN_URL" to "http://127.0.0.1:${server.address.port}/token", "MAGICPAPER_TOKEN_KEY" to key)
    override fun close() { server.stop(0); executor.shutdownNow() }
}
