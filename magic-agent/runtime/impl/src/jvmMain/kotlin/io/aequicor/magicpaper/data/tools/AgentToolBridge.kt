package io.aequicor.magicpaper.data.tools

import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.domain.browser.BrowserSession

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.logging.AppLog
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
internal class AgentToolBridge(
    private val tools: ToolSession,
    private val browser: BrowserSession,
    /** Optimization 6 (AGENT_SPEED_BOOST): cache tools/list JSON response. */
    private val cacheToolDefinitions: Boolean = false,
) : AutoCloseable {
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
    // Optimization 6 (AGENT_SPEED_BOOST): lazily cached tools/list response.
    // Tool definitions are stable per bridge instance (one bridge per run),
    // so computing the JSON once avoids repeated protocolDefinition() calls.
    @Volatile
    private var cachedToolsList: JsonObject? = null
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val url = "http://127.0.0.1:${server.address.port}/mcp"
    private val detachBrowser = tools.attachNativeCommands(if (tools.context.auxiliaryExecution) emptyList() else browser.commands)
    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try { handle(exchange) }
            catch (failure: Exception) {
                // A refused transport must stay discoverable after the run; the response body never enters the log.
                AppLog.error("coding.tools", "request.crashed", failure, mapOf("outcome" to "handler"))
                runCatching { reply(exchange, 500) }
            }
            finally { exchange.close() }
        }
        server.start()
        AppLog.info("coding.tools", "endpoint.started", mapOf("count" to tools.definitions.size.toString(), "backend" to "mcp"))
    }
    private fun reject(exchange: HttpExchange, status: Int, outcome: String) {
        AppLog.error("coding.tools", "request.rejected", mapOf("outcome" to outcome, "phase" to "transport"))
        reply(exchange, status)
    }
    private fun handle(exchange: HttpExchange) {
        if (closed.get()) { reject(exchange, 410, "endpoint_closed"); return }
        if (exchange.requestURI.path != "/mcp") { reject(exchange, 404, "unknown_path"); return }
        if (exchange.requestHeaders.containsKey("Origin") || !MessageDigest.isEqual(
                exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(), "Bearer $token".toByteArray())) { reject(exchange, 403, "unauthorized"); return }
        // MCP streamable HTTP: a client may probe GET (server stream) or DELETE (end of session). This endpoint offers
        // neither, and 405 with Allow is the specified answer, so it is a declined probe rather than a rejected request.
        if (exchange.requestMethod != "POST") {
            AppLog.debug("coding.tools", "request.method_declined", mapOf("method" to exchange.requestMethod))
            exchange.responseHeaders.set("Allow", "POST"); reply(exchange, 405); return
        }
        val bytes = exchange.requestBody.readNBytes(1_048_577)
        if (bytes.size > 1_048_576) { reject(exchange, 413, "payload_too_large"); return }
        val request = runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
        if (request == null || request["jsonrpc"] != JsonPrimitive("2.0")) { reject(exchange, 400, "invalid_request"); return }
        val id = request["id"]
        val params = request["params"] as? JsonObject ?: buildJsonObject { }
        val method = request["method"]?.jsonPrimitive?.content
        if (id == null) {
            if (method == "notifications/cancelled") params["requestId"]?.let { jobs[it]?.cancel() }
            reply(exchange, 202); return
        }
        if (id !is JsonPrimitive || id == JsonNull) { reject(exchange, 400, "invalid_request_id"); return }
        val previousRequest = requestBodies.putIfAbsent(id, request)
        if (previousRequest != null && previousRequest != request) { reject(exchange, 400, "reused_request_id"); return }
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
                    "tools/list" -> {
                        if (cacheToolDefinitions) {
                            cachedToolsList ?: run {
                                val built = buildJsonObject { put("tools", JsonArray(tools.definitions.map { it.protocolDefinition() })) }
                                cachedToolsList = built
                                built
                            }
                        } else buildJsonObject { put("tools", JsonArray(tools.definitions.map { it.protocolDefinition() })) }
                    }
                    "tools/call" -> {
                        val name = params["name"]?.jsonPrimitive?.content ?: error("Нет имени инструмента")
                        val args = params["arguments"] as? JsonObject ?: error("Нет аргументов инструмента")
                        try {
                            val result = tools.call(callIdentity(id), name, args)
                            buildJsonObject {
                                put("content", toolResultContent(name, result))
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
                // Protocol-level refusal: the call never reached the executor, so nothing else reports it.
                AppLog.error("coding.tools", "request.failed", e, mapOf("operation" to method.orEmpty(), "outcome" to "rejected"))
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
        try { browser.close() } finally { detachBrowser() }
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

/** Only the trusted screenshot command can emit image blocks; page text cannot forge MCP content. */
internal fun toolResultContent(name: String, result: JsonElement): JsonArray = buildJsonArray {
    val screenshot = name in setOf("browser.screenshot", "magicpaper_browser_screenshot")
    val objectResult = result as? JsonObject
    val image = if (screenshot) objectResult?.get("image") as? JsonObject else null
    add(buildJsonObject {
        put("type", "text")
        put("text", if (image != null) JsonObject(objectResult!! - "image").toString() else result.toString())
    })
    if (image != null) add(image)
}
