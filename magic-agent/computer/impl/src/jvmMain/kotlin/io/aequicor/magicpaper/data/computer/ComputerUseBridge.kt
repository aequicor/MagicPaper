package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.*

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Per-turn, authenticated loopback MCP endpoint. No screen images are written to disk. */
internal class ComputerUseBridge(
    private val computer: DesktopComputerUse,
    private val sessionId: String,
    private val epoch: ComputerLease,
) : ComputerEndpoint {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "magicpaper-computer").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val jobs = ConcurrentHashMap<JsonElement, Job>()
    private val seen = ConcurrentHashMap.newKeySet<JsonElement>()
    val token: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    override val descriptor: ComputerEndpointDescriptor get() = ComputerEndpointDescriptor(url, token, PiComputerExtension.source)
    val url: String = "http://127.0.0.1:${server.address.port}/mcp"

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try { handle(exchange) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                io.aequicor.magicpaper.logging.AppLog.error("computer", "endpoint_failed",
                    mapOf("sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty()))
                try { reply(exchange, 500) } catch (delivery: Exception) {
                    failure.addSuppressed(delivery)
                    io.aequicor.magicpaper.logging.AppLog.error("computer", "endpoint_error_delivery_failed",
                        mapOf("sessionId" to sessionId, "causeType" to delivery::class.simpleName.orEmpty()))
                }
            } finally { exchange.close() }
        }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        if (closed.get() || computer.grant(sessionId) != epoch) { reply(exchange, 403); return }
        if (exchange.requestURI.path != "/mcp") { reply(exchange, 404); return }
        if (exchange.requestHeaders.containsKey("Origin")) { reply(exchange, 403); return }
        val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        if (!MessageDigest.isEqual(authorization.toByteArray(), "Bearer $token".toByteArray())) { reply(exchange, 401); return }
        if (exchange.requestMethod != "POST") { exchange.responseHeaders.set("Allow", "POST"); reply(exchange, 405); return }
        val version = exchange.requestHeaders.getFirst("MCP-Protocol-Version")
        if (version != null && version !in versions) { reply(exchange, 400); return }
        if (exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim() != "application/json") { reply(exchange, 415); return }
        val bytes = exchange.requestBody.readNBytes(65_537)
        if (bytes.size > 65_536) { reply(exchange, 413); return }
        val request = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
        if (request == null || request["jsonrpc"] != JsonPrimitive("2.0")) { reply(exchange, 400); return }
        val id = request["id"]
        val method = request.optionalString("method")
        val params = request["params"] as? JsonObject ?: buildJsonObject { }
        if (id == null) {
            if (method == "notifications/cancelled") params["requestId"]?.let { jobs[it]?.cancel() }
            reply(exchange, 202)
            return
        }
        if (id !is JsonPrimitive || id == JsonNull || method == null) { reply(exchange, 400); return }
        // Bounded anti-replay ledger: never repeat a click if the client lost its response.
        if (seen.size >= 1024 || !seen.add(id)) {
            reply(exchange, 200, rpcError(id, -32600, "Duplicate request or turn request limit reached. Take a fresh screenshot; never blindly repeat input.")); return
        }
        val result: JsonObject = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", params.optionalString("protocolVersion")?.takeIf { it in versions } ?: "2025-06-18")
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                put("serverInfo", buildJsonObject { put("name", "MagicPaper Computer"); put("version", "1.0.0") })
                put("instructions", ComputerTool.instructions + "\n" + ApplicationTool.instructions)
            }
            "ping" -> buildJsonObject { }
            "tools/list" -> buildJsonObject { put("tools", buildJsonArray { add(ComputerTool.definition); add(ApplicationTool.definition) }) }
            "tools/call" -> {
                val tool = params.optionalString("name")
                if (tool !in listOf("computer", "application") || params["arguments"] !is JsonObject) {
                    reply(exchange, 200, rpcError(id, -32602, "Expected computer or application tool with object arguments")); return
                }
                runBlocking {
                    jobs[id] = currentCoroutineContext()[Job]!!
                    try {
                        if (tool == "application") computer.executeApplication(sessionId, epoch, params["arguments"]!!.jsonObject, id.toString())
                        else computer.execute(sessionId, epoch, params["arguments"]!!.jsonObject, id.toString())
                    }
                    finally { jobs.remove(id) }
                }
            }
            else -> { reply(exchange, 200, rpcError(id, -32601, "Method not found")); return }
        }
        reply(exchange, 200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        computer.release(epoch)
        jobs.values.forEach { it.cancel() }
        server.stop(0)
        executor.shutdownNow()
    }

    private fun reply(exchange: HttpExchange, status: Int, body: JsonObject? = null) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        if (body == null) { exchange.sendResponseHeaders(status, -1); return }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private val versions = setOf("2025-03-26", "2025-06-18", "2025-11-25")
        private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }

    }
}
