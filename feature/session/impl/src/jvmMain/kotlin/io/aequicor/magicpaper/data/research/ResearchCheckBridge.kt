package io.aequicor.magicpaper.data.research

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.*
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

/** A fresh authenticated endpoint for each run. It grants no filesystem privileges to the engine. */
internal class ResearchCheckBridge(private val session: CodingSession, private val project: CodingProject, private val runner: ResearchCheckRunner = ResearchCheckRunner.shared) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "magicpaper-research").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val jobs = ConcurrentHashMap<JsonElement, Job>()
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
        val bytes = exchange.requestBody.readNBytes(65_537)
        if (bytes.size > 65_536) { reply(exchange, 413); return }
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
        val result = runBlocking {
            val response = CompletableDeferred<JsonObject>()
            val previous = results.putIfAbsent(id, response)
            if (previous != null) return@runBlocking previous.await()
            jobs[id] = currentCoroutineContext()[Job]!!
            val value = try {
                check(!closed.get()) { "Запрос завершён" }
                val payload = when (method) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", "2025-06-18"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "MagicPaper Research"); put("version", "1.0") })
                        put("instructions", ResearchCheckTool.instructions)
                    }
                    "ping" -> buildJsonObject {}
                    "tools/list" -> buildJsonObject { put("tools", buildJsonArray { add(ResearchCheckTool.definition) }) }
                    "tools/call" -> {
                        require(params["name"] == JsonPrimitive("research_check")) { "Неизвестный инструмент" }
                        val args = params["arguments"] as? JsonObject ?: error("Нет аргументов")
                        require(args.keys.all { it in setOf("command", "cwd") }) { "Права и каталоги записи задаёт приложение" }
                        val command = (args["command"] as? JsonArray)?.map {
                            val value = it as? JsonPrimitive
                            require(value?.isString == true) { "Аргументы command должны быть строками" }
                            value.content
                        } ?: error("Нужен массив command")
                        require(args["cwd"] == null || (args["cwd"] as? JsonPrimitive)?.isString == true) { "cwd должен быть строкой" }
                        val result = runner.run(java.nio.file.Paths.get(project.path), session.id, command, args["cwd"]?.jsonPrimitive?.content ?: ".")
                        ResearchCheckTool.result(result)
                    }
                    else -> error("Неизвестный метод")
                }
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", payload) }
            } catch (e: Exception) {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                    put("code", -32602); put("message", if (e is CancellationException) "Проверка отменена" else e.message ?: "Не удалось выполнить проверку")
                }) }
            } finally { jobs.remove(id) }
            response.complete(value)
            value
        }
        reply(exchange, 200, result)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.values.forEach { it.cancel() }
        runner.abort(session.id)
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
    fun codexConfig(base: JsonObject) = buildJsonObject {
        base.filterKeys { it != "mcp_servers" && !it.startsWith("mcp_servers.") }.forEach { (key, value) -> put(key, value) }
        put("mcp_servers", buildJsonObject {
            base.filterKeys { it.startsWith("mcp_servers.") }.forEach { (key, value) -> put(key.removePrefix("mcp_servers."), value) }
            put("magicpaper_research", buildJsonObject {
            put("url", url); put("enabled", true); put("required", true)
            put("default_tools_approval_mode", "approve")
            put("startup_timeout_sec", 10); put("tool_timeout_sec", 1000)
            put("http_headers", buildJsonObject { put("Authorization", "Bearer $token") })
            })
        })
    }
}
