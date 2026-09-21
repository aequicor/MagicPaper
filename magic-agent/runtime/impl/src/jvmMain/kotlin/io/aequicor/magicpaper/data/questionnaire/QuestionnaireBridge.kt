package io.aequicor.magicpaper.data.questionnaire

import io.aequicor.magicpaper.data.tools.respondJson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.QuestionnaireContract
import io.aequicor.magicpaper.domain.tools.ToolArgumentRejection
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal object QuestionnaireTool {
    /** Schema, limits and answer policy are shared with the application tool surface. */
    val instructions: String get() = QuestionnaireContract.instructions
    val schema: JsonObject get() = QuestionnaireContract.schema
    val definition = buildJsonObject {
        put("name", "questionnaire"); put("description", instructions); put("inputSchema", schema)
        put("annotations", buildJsonObject { put("readOnlyHint", true); put("destructiveHint", false); put("openWorldHint", false) })
    }
    fun decode(args: JsonObject): List<PlanningQuestion> {
        val questions = Json.decodeFromJsonElement(ListSerializer(PlanningQuestion.serializer()), args["questions"]
            ?: throw ToolArgumentRejection(QuestionnaireContract.problem(emptyList())!!))
        return QuestionnaireContract.ready(questions)
    }
    fun result(answers: List<PlanningAnswer>) = buildJsonObject {
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", "text"); put("text", Json.encodeToString(ListSerializer(PlanningAnswer.serializer()), answers))
        }) }); put("isError", false)
    }
}

/** Per-run authenticated local endpoint. Human deliberation has no short HTTP/tool deadline. */
internal class QuestionnaireBridge(private val registry: RuntimeQuestionnaireService, private val session: CodingSession) : AutoCloseable {
    private val epoch = UUID.randomUUID().toString()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "magicpaper-questionnaire").apply { isDaemon = true } }
    private val closed = AtomicBoolean()
    private val jobs = ConcurrentHashMap<JsonElement, Job>()
    private val results = ConcurrentHashMap<JsonElement, CompletableDeferred<JsonObject>>()
    private val signatures = ConcurrentHashMap<JsonElement, Pair<String?, JsonObject>>()
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    val url = "http://127.0.0.1:${server.address.port}/mcp"
    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try { handle(exchange) }
            catch (cancelled: CancellationException) {
                AppLog.info("coding.questionnaire", "request.cancelled", mapOf("outcome" to "connection_closed"))
            }
            catch (failure: Exception) {
                AppLog.error("coding.questionnaire", "request.crashed", mapOf("outcome" to "handler", "cause" to failure.javaClass.simpleName))
                try { reply(exchange, 500) }
                catch (replyFailure: Exception) {
                    AppLog.error("coding.questionnaire", "failure_response.failed", mapOf("outcome" to "connection_closed", "cause" to replyFailure.javaClass.simpleName))
                }
            }
            finally { exchange.close() }
        }
        server.start()
        AppLog.info("coding.questionnaire", "endpoint.started", mapOf("backend" to "mcp", "count" to "1"))
    }
    private fun reject(exchange: HttpExchange, status: Int, outcome: String, body: JsonObject? = null) {
        AppLog.error("coding.questionnaire", "request.rejected", mapOf("outcome" to outcome, "phase" to "transport"))
        reply(exchange, status, body)
    }
    private fun handle(exchange: HttpExchange) {
        if (closed.get()) { reject(exchange, 410, "endpoint_closed"); return }
        if (exchange.requestURI.path != "/mcp") { reject(exchange, 404, "unknown_path"); return }
        if (exchange.requestHeaders.containsKey("Origin") || !MessageDigest.isEqual(
                exchange.requestHeaders.getFirst("Authorization").orEmpty().toByteArray(), "Bearer $token".toByteArray())) { reject(exchange, 403, "unauthorized"); return }
        // MCP streamable HTTP: a client may probe GET (server stream) or DELETE (end of session). This endpoint offers
        // neither, and 405 with Allow is the specified answer, so it is a declined probe rather than a rejected request.
        if (exchange.requestMethod != "POST") {
            AppLog.debug("coding.questionnaire", "request.method_declined", mapOf("method" to exchange.requestMethod))
            exchange.responseHeaders.set("Allow", "POST"); reply(exchange, 405); return
        }
        val bytes = exchange.requestBody.readNBytes(65_537)
        if (bytes.size > 65_536) { reject(exchange, 413, "payload_too_large"); return }
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
        val signature = method to params
        val original = signatures.putIfAbsent(id, signature)
        if (original != null && original != signature) {
            reject(exchange, 409, "reused_request_id",
                buildJsonObject { put("error", "Идентификатор запроса уже использован с другими аргументами") })
            return
        }
        respondJson(exchange, heartbeat = method == "tools/call") {
            val response = CompletableDeferred<JsonObject>()
            val previous = results.putIfAbsent(id, response)
            if (previous != null) {
                // An HTTP retry cannot establish whether the first response reached its consumer.
                if (method == "tools/call") return@respondJson buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                        put("code", -32600); put("message", "Ответ уже отправлялся; повторная доставка недоступна")
                    })
                }
                return@respondJson previous.await()
            }
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
                            context = session.name, createdAt = System.currentTimeMillis(),
                            runtimeGeneration = session.runtimeGeneration, runId = session.pendingRun?.runId.orEmpty())
                        val answer = registry.ask(requestView)
                        val attempt = registry.beginDelivery(requestId)
                        // The endpoint has no protocol acknowledgment of answer consumption.
                        withContext(NonCancellable) { registry.finishDelivery(requestId, attempt, QuestionnaireDeliveryOutcome.UNKNOWN) }
                        QuestionnaireTool.result(answer)
                    }
                    else -> error("Неизвестный метод")
                }
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", payload) }
            } catch (cancelled: CancellationException) {
                response.cancel(cancelled)
                throw cancelled
            } catch (e: Exception) {
                AppLog.error("coding.questionnaire", "request.failed", mapOf("operation" to method.orEmpty(), "outcome" to "rejected", "cause" to e.javaClass.simpleName))
                buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject {
                    put("code", -32602); put("message", if (e is ToolArgumentRejection) e.message.orEmpty() else "Не удалось обработать опросник. Проверьте состояние запроса.")
                }) }
            } finally { jobs.remove(id) }
            response.complete(value)
            value
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = jobs.values.toList()
        active.forEach { it.cancel() }
        server.stop(0)
        try {
            // Endpoint work lives on HTTP threads; join it before the owning runtime can finish.
            runBlocking { withTimeout(10_000) { active.joinAll() } }
        } finally { executor.shutdownNow() }
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
