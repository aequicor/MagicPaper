package io.aequicor.magicpaper.data.tools

import io.aequicor.magicpaper.domain.tools.*

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Per-run authenticated local endpoint. Human deliberation has no short HTTP/tool deadline. */
internal class AgentToolBridge(private val tools: ToolSession) : AutoCloseable {
    // JSON-RPC IDs belong to this transport, not to the durable worker turn. A resumed
    // turn opens a new bridge and Codex restarts its counter; those are new calls.
    // Keep retries within this bridge stable, including the JSON ID's string/number type.
    private val callNamespace = UUID.randomUUID().toString()
    private fun callIdentity(id: JsonElement): String = "mcp:$callNamespace:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(id.toString().toByteArray(Charsets.UTF_8)))
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "magicpaper-agent-tools").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val jobs = ConcurrentHashMap<JsonElement, Job>()
    private val requestBodies = ConcurrentHashMap<JsonElement, JsonObject>()
    private val results = ConcurrentHashMap<JsonElement, CompletableDeferred<JsonObject>>()
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val url = "http://127.0.0.1:${server.address.port}/mcp"
    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try { handle(exchange) } catch (_: Exception) { runCatching { reply(exchange, 500) } }
            finally { exchange.close() }
        }
        server.start()
    }
    private fun handle(exchange: HttpExchange) {
        if (closed.get()) { reply(exchange, 410); return }
        if (exchange.requestURI.path != "/mcp") { reply(exchange, 404); return }
        if (exchange.requestHeaders.containsKey("Origin") || !MessageDigest.isEqual(
                exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(), "Bearer $token".toByteArray())) { reply(exchange, 403); return }
        if (exchange.requestMethod != "POST") { reply(exchange, 405); return }
        val bytes = exchange.requestBody.readNBytes(1_048_577)
        if (bytes.size > 1_048_576) { reply(exchange, 413); return }
        val request = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
        if (request == null || request["jsonrpc"] != JsonPrimitive("2.0")) { reply(exchange, 400); return }
        val id = request["id"]
        val params = request["params"] as? JsonObject ?: buildJsonObject { }
        val method = request["method"]?.jsonPrimitive?.content
        if (id == null) {
            if (method == "notifications/cancelled") params["requestId"]?.let { jobs[it]?.cancel() }
            reply(exchange, 202); return
        }
        if (id !is JsonPrimitive || id == JsonNull) { reply(exchange, 400); return }
        val previousRequest = requestBodies.putIfAbsent(id, request)
        if (previousRequest != null && previousRequest != request) { reply(exchange, 400); return }
        respondJson(exchange, heartbeat = method == "tools/call") {
            val response = CompletableDeferred<JsonObject>()
            val previous = results.putIfAbsent(id, response)
            if (previous != null) return@respondJson previous.await()
            jobs[id] = currentCoroutineContext()[Job]!!
            val value = try {
                check(!closed.get()) { "Запрос завершён" }
                val payload = when (method) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", "2025-06-18"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "MagicPaper Agent Tools"); put("version", "1.0") })
                        put("instructions", "Use the available tools for application actions. Tool results describe committed operations; plain text does not execute actions.")
                    }
                    "ping" -> buildJsonObject {}
                    "tools/list" -> buildJsonObject { put("tools", JsonArray(tools.definitions.map { it.protocolDefinition() })) }
                    "tools/call" -> {
                        val name = params["name"]?.jsonPrimitive?.content ?: error("Нет имени инструмента")
                        val args = params["arguments"] as? JsonObject ?: error("Нет аргументов инструмента")
                        try {
                            val result = tools.call(callIdentity(id), name, args)
                            buildJsonObject {
                                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", result.toString()) }) })
                                put("isError", false)
                            }
                        } catch (error: Exception) {
                            buildJsonObject {
                                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", error.message ?: "Ошибка инструмента") }) })
                                put("isError", true)
                            }
                        }
                    }
                    else -> error("Неизвестный метод")
                }
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", payload) }
            } catch (e: Exception) {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                    put("code", -32602); put("message", if (e is CancellationException) "Обращение отменено" else e.message ?: "Не удалось выполнить инструмент")
                }) }
            } finally { jobs.remove(id) }
            response.complete(value)
            value
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.values.forEach { it.cancel() }
        server.stop(0)
        executor.shutdownNow()
    }
    private fun reply(exchange: HttpExchange, status: Int, body: JsonObject? = null) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        if (body == null) { exchange.sendResponseHeaders(status, -1); return }
        val bytes = body.toString().toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    fun codexConfig(base: JsonObject): JsonObject {
        val config = buildJsonObject {
            put("url", url); put("enabled", true); put("required", true)
            put("default_tools_approval_mode", "approve")
            put("startup_timeout_sec", 10); put("tool_timeout_sec", 604800)
            put("http_headers", buildJsonObject { put("Authorization", "Bearer $token") })
        }
        return buildJsonObject {
            val restrictedServers = base["mcp_servers"] as? JsonObject
            if (restrictedServers == null) {
                // Leave inherited user MCP servers intact in an ordinary working session.
                base.forEach { (key, value) -> put(key, value) }
                put("mcp_servers.magicpaper_agent_tools", config)
            } else {
                // Codex applies root and dotted overrides in unspecified order. A single root
                // prevents the read-only empty allowlist from erasing our authenticated bridge.
                base.filterKeys { it != "mcp_servers" && !it.startsWith("mcp_servers.") }.forEach { (key, value) -> put(key, value) }
                put("mcp_servers", buildJsonObject {
                    restrictedServers.forEach { (key, value) -> put(key, value) }
                    base.filterKeys { it.startsWith("mcp_servers.") }.forEach { (key, value) -> put(key.removePrefix("mcp_servers."), value) }
                    put("magicpaper_agent_tools", config)
                })
            }
        }
    }
}
