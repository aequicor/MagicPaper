package io.aequicor.magicpaper.data.questionnaire

import io.aequicor.magicpaper.data.tools.respondJson
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal object QuestionnaireTool {
    const val instructions = "When you need a clarification or a decision from the user, call questionnaire instead of asking only in prose. Supply concise questions with answer options when useful. The user may add text or skip a clarification. Wait for the confirmed answers; never interpret silence as consent. This tool cannot authorize commands or grant permissions. If exec returns Script running with cell ID, the questionnaire is still pending: keep calling wait for that cell until confirmed answers arrive. Do not send a final response while any questionnaire or application tool call is pending; ending the turn cancels pending tool calls."
    val schema = Json.parseToJsonElement("""{
      "type":"object","additionalProperties":false,"required":["questions"],"properties":{"questions":{
        "type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,
        "required":["id","title","kind","options"],"properties":{
          "id":{"type":"string"},"title":{"type":"string"},"kind":{"type":"string","enum":["SINGLE","MULTIPLE","TEXT"]},
          "options":{"type":"array","items":{"type":"object","additionalProperties":false,
            "required":["id","label"],"properties":{"id":{"type":"string"},"label":{"type":"string"},"description":{"type":"string"}}}}
        }}}}}
    """).jsonObject
    val definition = buildJsonObject {
        put("name", "questionnaire"); put("description", instructions); put("inputSchema", schema)
        put("annotations", buildJsonObject { put("readOnlyHint", true); put("destructiveHint", false); put("openWorldHint", false) })
    }
    fun decode(args: JsonObject): List<PlanningQuestion> {
        val questions = Json.decodeFromJsonElement(ListSerializer(PlanningQuestion.serializer()), args["questions"] ?: error("Нет вопросов"))
        require(questions.isNotEmpty() && questions.all { it.id.isNotBlank() && it.title.isNotBlank() } && questions.distinctBy { it.id }.size == questions.size) { "Некорректные вопросы" }
        require(questions.all { q -> q.options.all { it.id.isNotBlank() && it.label.isNotBlank() } && q.options.distinctBy { it.id }.size == q.options.size }) { "Некорректные варианты ответа" }
        // Host-owned restrictions for approvals cannot be requested by an ordinary model tool.
        return questions.map { it.copy(kind = if (it.options.isEmpty()) QuestionKind.TEXT else it.kind,
            allowCustomInput = true, canSkip = true, secret = false, options = it.options.map { o -> o.copy(enabled = true) }) }
    }
    fun result(answers: List<PlanningAnswer>) = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", "text"); put("text", Json.encodeToString(ListSerializer(PlanningAnswer.serializer()), answers))
        }) }); put("isError", false)
    }
}

/** Per-run authenticated local endpoint. Human deliberation has no short HTTP/tool deadline. */
internal class QuestionnaireBridge(private val registry: RuntimeQuestionnaires, private val session: CodingSession) : AutoCloseable {
    private val epoch = UUID.randomUUID().toString()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "magicpaper-questionnaire").apply { isDaemon = true } }
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
                        put("serverInfo", buildJsonObject { put("name", "MagicPaper Questionnaire"); put("version", "1.0") })
                        put("instructions", QuestionnaireTool.instructions)
                    }
                    "ping" -> buildJsonObject {}
                    "tools/list" -> buildJsonObject { put("tools", buildJsonArray { add(QuestionnaireTool.definition) }) }
                    "tools/call" -> {
                        require(params["name"] == JsonPrimitive("questionnaire")) { "Неизвестный инструмент" }
                        val questions = QuestionnaireTool.decode(params["arguments"]!!.jsonObject)
                        val requestId = "runtime:$epoch:$id"
                        val requestView = UserInteractionRequest(requestId, session.projectId, session.id, InteractionKind.RUNTIME, questions,
                            ownerSessionId = session.parentSessionId ?: session.id, context = session.name,
                            createdAt = System.currentTimeMillis())
                        QuestionnaireTool.result(registry.ask(requestView))
                    }
                    else -> error("Неизвестный метод")
                }
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", payload) }
            } catch (e: Exception) {
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                    put("code", -32602); put("message", if (e is CancellationException) "Обращение отменено" else e.message ?: "Не удалось показать вопрос")
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
    fun codexConfig(base: JsonObject) = buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        put("mcp_servers.magicpaper_questionnaire", buildJsonObject {
            put("url", url); put("enabled", true); put("required", true)
            put("default_tools_approval_mode", "approve")
            put("startup_timeout_sec", 10); put("tool_timeout_sec", 604800)
            put("http_headers", buildJsonObject { put("Authorization", "Bearer $token") })
        })
    }
}
