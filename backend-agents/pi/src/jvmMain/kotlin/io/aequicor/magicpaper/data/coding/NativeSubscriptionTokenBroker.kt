package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Per-run loopback token handoff; only the host can refresh or read the auth document. */
internal class NativeSubscriptionTokenBroker(tokens: NativeAuthTokens, diagnostics: NativeDiagnostics) : AutoCloseable {
    private val key = UUID.randomUUID().toString()
    private val executor = Executors.newFixedThreadPool(2) { Thread(it, "magicpaper-subscription").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@NativeSubscriptionTokenBroker.executor
        createContext("/token") { exchange ->
            try {
                val allowed = exchange.requestMethod == "POST" && exchange.requestURI.path == "/token" &&
                    exchange.requestHeaders.getFirst("Authorization") == "Bearer $key" && exchange.requestHeaders.getFirst("Origin") == null
                val token = if (allowed) try { runBlocking { tokens.readAccessToken() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    diagnostics.error("NativeProvider", "token_refresh_failed", IllegalStateException("Subscription token refresh failed"),
                        mapOf("failure" to failure.javaClass.simpleName)); null
                } else null
                val body = (if (token == null) "{}" else buildJsonObject { put("token", token) }.toString()).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(if (!allowed) 403 else if (token == null) 401 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        start()
    }
    val environment get() = mapOf("MAGICPAPER_TOKEN_URL" to "http://127.0.0.1:${server.address.port}/token", "MAGICPAPER_TOKEN_KEY" to key)
    override fun close() { server.stop(0); executor.shutdownNow() }
}
