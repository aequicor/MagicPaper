package io.aequicor.magicpaper.data.tools

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Flush headers immediately and keep JSON fetch clients alive during human deliberation.
 * JSON whitespace is ignored by both MCP and Pi clients; it never resolves the tool call.
 * A disconnected client cancels the pending operation instead of leaving an orphan question.
 */
internal fun respondJson(
    exchange: HttpExchange,
    heartbeat: Boolean,
    intervalMillis: Long = 15_000,
    block: suspend () -> JsonObject,
) = runBlocking {
    exchange.responseHeaders.set("Cache-Control", "no-store")
    exchange.responseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, 0)
    exchange.responseBody.use { output ->
        val pulses = if (heartbeat) launch(Dispatchers.IO) {
            while (isActive) {
                output.write(' '.code)
                output.flush()
                delay(intervalMillis)
            }
        } else null
        val result = try { block() } finally {
            withContext(NonCancellable) { pulses?.cancelAndJoin() }
        }
        output.write(result.toString().toByteArray(Charsets.UTF_8))
    }
}
