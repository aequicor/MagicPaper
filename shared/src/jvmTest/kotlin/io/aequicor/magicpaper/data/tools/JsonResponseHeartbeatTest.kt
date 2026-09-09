package io.aequicor.magicpaper.data.tools

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class JsonResponseHeartbeatTest {
    @Test fun delayedConfirmationSurvivesClientReadTimeoutWithoutResolvingEarly() = runBlocking {
        val confirmed = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            exchange.use {
                respondJson(it, heartbeat = true, intervalMillis = 25) {
                    entered.complete(Unit)
                    confirmed.await()
                    buildJsonObject { put("answer", "Подтверждено") }
                }
            }
        }
        server.start()
        try {
            val response = async(Dispatchers.IO) {
                val connection = URI("http://127.0.0.1:${server.address.port}/mcp").toURL().openConnection() as HttpURLConnection
                connection.readTimeout = 500
                try { Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()) }
                finally { connection.disconnect() }
            }
            withTimeout(5000) { entered.await() }
            delay(1500)
            assertFalse(response.isCompleted, "Waiting for a human must outlive the client's idle deadline")
            confirmed.complete(Unit)
            assertEquals(JsonPrimitive("Подтверждено"), response.await().jsonObject["answer"])
        } finally {
            confirmed.cancel()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
