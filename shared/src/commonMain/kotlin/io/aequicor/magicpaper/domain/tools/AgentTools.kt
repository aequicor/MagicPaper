package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable enum class ToolRole { ORCHESTRATOR, PLANNER, WORKER, CHAT }
@Serializable enum class ToolCategory { READ, SEARCH, EDIT, EXEC, ACTION }
@Serializable enum class ToolPhase { STARTED, PROGRESS, WAITING, SUCCEEDED, FAILED, CANCELLED }

/** Only application code constructs this scope. It is never decoded from tool arguments. */
data class ToolExecutionContext(
    val projectId: String, val ownerSessionId: String, val sessionId: String, val requestId: String,
    val role: ToolRole, val mode: CodingInteractionMode,
    val planId: String? = null, val runId: String? = null, val stageId: String? = null,
    val attemptId: String? = null, val turnIndex: Int = 0,
    val sourceInput: OrchestrationInput? = null, val parentSessionId: String? = null,
) {
    companion object {
        fun worker(session: CodingSession) = ToolExecutionContext(session.projectId, session.id, session.id,
            session.pendingRun?.runId ?: Id.new(), if (session.role == CodingSessionRole.WORKER) ToolRole.WORKER else ToolRole.CHAT,
            session.interactionMode, session.planId, stageId = session.stageId, parentSessionId = session.parentSessionId)
    }
}

data class ToolDefinition(
    val id: String, val description: String, val schema: JsonObject, val category: ToolCategory = ToolCategory.ACTION,
    val roles: Set<ToolRole> = ToolRole.entries.toSet(), val mutating: Boolean = false, val native: Boolean = false,
    val needsPlan: Boolean = false,
) {
    val wireName: String get() = "magicpaper_" + id.replace('.', '_')
    fun allowed(context: ToolExecutionContext): Boolean = context.role in roles && (!needsPlan || context.planId != null) &&
        (!needsPlan || context.role !in setOf(ToolRole.ORCHESTRATOR, ToolRole.PLANNER) || context.mode == CodingInteractionMode.PLANNING) &&
        (id != "stage.handoff" || context.mode == CodingInteractionMode.CODE) &&
        (!native || !mutating || (context.mode == CodingInteractionMode.CODE && context.role in setOf(ToolRole.WORKER, ToolRole.CHAT))) &&
        (id != "research_check" || context.mode == CodingInteractionMode.RESEARCH)
    fun protocolDefinition() = buildJsonObject {
        put("name", wireName); put("description", description); put("inputSchema", schema)
        putJsonObject("annotations") { put("readOnlyHint", !mutating); put("destructiveHint", false) }
    }
}

@Serializable data class ToolEvent(
    val projectId: String, val ownerSessionId: String, val requestId: String, val callId: String,
    val toolId: String, val category: ToolCategory, val phase: ToolPhase,
    val summary: String, val result: String = "",
    val title: String? = null,
) {
    fun codingEvent(): CodingEvent = when (phase) {
        ToolPhase.STARTED -> CodingEvent.ToolStarted(toolId, summary, callId, category == ToolCategory.EXEC, category = category, title = title)
        ToolPhase.PROGRESS, ToolPhase.WAITING -> CodingEvent.ToolProgress(toolId, callId, result, phase)
        else -> CodingEvent.ToolFinished(toolId, phase != ToolPhase.SUCCEEDED, callId, result, phase, title = title)
    }
}

/** Observers attach before execution. Back pressure preserves lifecycle boundaries. */
class ToolEventHub {
    private val observers = MutableStateFlow<List<suspend (ToolEvent) -> Unit>>(emptyList())
    val events: Flow<ToolEvent> = channelFlow {
        val close = observe { send(it) }
        awaitClose { close() }
    }
    fun observe(observer: suspend (ToolEvent) -> Unit): () -> Unit {
        observers.update { it + observer }
        return { observers.update { it - observer } }
    }
    suspend fun publish(event: ToolEvent) {
        val safe = event.copy(summary = PlanningDiagnostics.redact(event.summary), result = PlanningDiagnostics.redact(event.result))
        observers.value.forEach { observer ->
            try { observer(safe) } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                observers.update { it - observer }
            }
        }
    }
}

interface ToolCommand<A, R> {
    val definition: ToolDefinition
    fun decode(arguments: JsonObject): A
    suspend fun execute(context: ToolExecutionContext, operationId: String, args: A): R
    fun encode(result: R): JsonElement
}

class JsonToolCommand(
    override val definition: ToolDefinition,
    private val action: suspend (ToolExecutionContext, String, JsonObject) -> JsonElement,
) : ToolCommand<JsonObject, JsonElement> {
    override fun decode(arguments: JsonObject): JsonObject {
        validateToolArguments(definition.schema, arguments)
        return arguments
    }
    override suspend fun execute(context: ToolExecutionContext, operationId: String, args: JsonObject) = action(context, operationId, args)
    override fun encode(result: JsonElement) = result
}

class ToolRegistry(commands: List<ToolCommand<*, *>>) {
    private val commands = commands.associateBy { it.definition.id }.also { require(it.size == commands.size) { "Повтор инструмента" } }
    fun available(context: ToolExecutionContext) = commands.values.map { it.definition }.filter { !it.native && it.allowed(context) }
    fun command(name: String): ToolCommand<*, *> = commands[name] ?: commands.values.firstOrNull { it.definition.wireName == name }
        ?: error("Неизвестный инструмент: $name")
}

@Serializable data class ToolReceipt(val id: String, val toolId: String, val arguments: JsonObject,
    val phase: ToolPhase = ToolPhase.STARTED, val result: JsonElement = JsonNull, val operationId: String = "")
interface ToolReceiptStore {
    suspend fun get(id: String): ToolReceipt?
    suspend fun save(receipt: ToolReceipt)
    suspend fun claim(receipt: ToolReceipt): ToolReceipt?
    suspend fun forRequest(prefix: String): List<ToolReceipt>
}
class MemoryToolReceiptStore : ToolReceiptStore {
    private val lock = Mutex()
    private val receipts = mutableMapOf<String, ToolReceipt>()
    override suspend fun get(id: String) = lock.withLock { receipts[id] }
    override suspend fun forRequest(prefix: String) = lock.withLock { receipts.values.filter { it.id.startsWith("$prefix/") } }
    override suspend fun save(receipt: ToolReceipt) { lock.withLock { receipts[receipt.id] = receipt } }
    override suspend fun claim(receipt: ToolReceipt): ToolReceipt? = lock.withLock {
        receipts[receipt.id].also { if (it == null) receipts[receipt.id] = receipt }
    }
}

class ToolExecutor(
    private val registry: ToolRegistry, private val receipts: ToolReceiptStore,
    private val checkScope: suspend (ToolExecutionContext) -> Unit = {},
    private val checkReplayScope: suspend (ToolExecutionContext) -> Unit = checkScope,
    private val reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null },
) {
    private val locks = Mutex()
    private val calls = mutableMapOf<String, Mutex>()
    suspend fun execute(session: ToolSession, callId: String, name: String, arguments: JsonObject): JsonElement {
        require(callId.isNotBlank() && callId.length <= 512) { "Некорректный идентификатор вызова" }
        val context = session.context
        val key = "${context.projectId}/${context.ownerSessionId}/${context.requestId}/${callId.replace("%", "%25").replace("/", "%2F")}"
        val lock = locks.withLock { calls.getOrPut(key) { Mutex() } }
        return lock.withLock {
            @Suppress("UNCHECKED_CAST")
            val command = runCatching { registry.command(name) as ToolCommand<Any?, Any?> }.getOrNull()
            val definition = command?.definition ?: ToolDefinition(name, name, JsonObject(emptyMap()))
            fun event(phase: ToolPhase, result: String = "") = ToolEvent(context.projectId, context.ownerSessionId,
                context.requestId, key, definition.id, definition.category, phase,
                toolArgumentPreview(definition.id, arguments), result.let { if (it.length <= 64_000) it else it.take(64_000) + "\n… Вывод сокращён" })
            session.events.publish(event(ToolPhase.STARTED))
            try {
                require(command != null) { "Неизвестный инструмент: $name" }
                require(definition.allowed(context)) { "Инструмент недоступен для этой роли или режима" }
                val args = command.decode(arguments)
                val operationId = Id.uuid()
                var receipt = if (definition.mutating) receipts.get(key) else null
                if (receipt == null) {
                    checkScope(context)
                    if (definition.mutating) receipt = receipts.claim(ToolReceipt(key, definition.id, arguments, operationId = operationId))
                }
                if (receipt != null) {
                    require(receipt.toolId == definition.id && receipt.arguments == arguments) { "Идентификатор вызова уже использован с другими аргументами" }
                    checkReplayScope(context)
                    val recovered = if (receipt.phase == ToolPhase.SUCCEEDED) receipt.result else reconcile(context, receipt)
                    check(recovered != null) { "Исход предыдущего вызова неизвестен. Требуется восстановление; повторное выполнение заблокировано." }
                    if (receipt.phase != ToolPhase.SUCCEEDED) receipts.save(receipt.copy(phase = ToolPhase.SUCCEEDED, result = recovered))
                    session.events.publish(event(ToolPhase.SUCCEEDED, recovered.toString()))
                    session.completed(key, definition.id, arguments, recovered)
                    return@withLock recovered
                }
                if (definition.id == "questionnaire") session.events.publish(event(ToolPhase.WAITING, "Ожидается ответ пользователя"))
                val result = withContext(session + UsageOwner(UsageScope("coding:${context.ownerSessionId}",
                    context.parentSessionId?.let { "coding:$it" }, context.projectId, context.planId))) {
                    command.encode(command.execute(context, operationId, args))
                }
                if (definition.mutating) withContext(NonCancellable) { receipts.save(ToolReceipt(key, definition.id, arguments, ToolPhase.SUCCEEDED, result, operationId)) }
                session.completed(key, definition.id, arguments, result)
                session.events.publish(event(ToolPhase.SUCCEEDED, result.toString()))
                result
            } catch (error: Exception) {
                // A failed mutating call remains uncertain; it is never automatically replayed.
                withContext(NonCancellable) { runCatching { session.events.publish(event(if (error is CancellationException) ToolPhase.CANCELLED else ToolPhase.FAILED,
                    error.message ?: "Ошибка инструмента")) } }
                throw error
            }
        }
    }
}

data class CompletedToolCall(val id: String, val tool: String, val arguments: JsonObject, val result: JsonElement)

class ToolSession(val context: ToolExecutionContext, val registry: ToolRegistry, val executor: ToolExecutor) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolSession>
    val events = ToolEventHub()
    internal val nativeCalls = mutableMapOf<String, Pair<String, ToolCategory>>()
    val results = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    val calls = MutableStateFlow<List<CompletedToolCall>>(emptyList())
    internal fun completed(id: String, tool: String, arguments: JsonObject, result: JsonElement) {
        results.update { it + (tool to result) }
        calls.update { previous -> previous.filterNot { it.id == id } + CompletedToolCall(id, tool, arguments, result) }
    }
    val definitions get() = registry.available(context)
    suspend fun call(id: String, name: String, args: JsonObject) = executor.execute(this, id, name, args)
}

fun Flow<CodingEvent>.withTools(session: ToolSession): Flow<CodingEvent> = channelFlow {
    val close = session.events.observe { send(it.codingEvent()) }
    try { this@withTools.flowOn(session).collect { event ->
        val rawName = when (event) {
            is CodingEvent.ToolStarted -> event.tool; is CodingEvent.ToolFinished -> event.tool; is CodingEvent.ToolProgress -> event.tool; else -> ""
        }
        val owned = session.definitions.any { rawName == it.wireName || rawName == "magicpaper_agent_tools:${it.wireName}" }
        if (!owned) {
            val native = session.nativeEvent(event)
            if (native == null) send(event) else session.events.publish(native)
        }
    } } finally { close() }
}


private fun ToolSession.nativeEvent(event: CodingEvent): ToolEvent? {
    val name: String; val call: String; val phase: ToolPhase; val summary: String; val result: String
    var exec = false
    when (event) {
        is CodingEvent.ToolStarted -> { name = event.tool; call = event.callId; phase = ToolPhase.STARTED; summary = event.summary; result = ""; exec = event.isExec }
        is CodingEvent.ToolProgress -> { name = event.tool; call = event.callId; phase = event.phase ?: ToolPhase.PROGRESS; summary = ""; result = event.resultPreview }
        is CodingEvent.ToolFinished -> { name = event.tool; call = event.callId; phase = event.phase ?: if (event.isError) ToolPhase.FAILED else ToolPhase.SUCCEEDED; summary = ""; result = event.resultPreview }
        else -> return null
    }
    val mappedId = ToolCatalog.nativeId(name, exec)
    val mappedCategory = (event as? CodingEvent.ToolStarted)?.category
        ?: ToolCatalog.definitions.firstOrNull { it.id == mappedId }?.category ?: ToolCategory.ACTION
    val (id, category) = if (event is CodingEvent.ToolStarted || call.isBlank()) (mappedId to mappedCategory).also {
        if (call.isNotBlank()) nativeCalls[call] = it
    } else nativeCalls[call] ?: (mappedId to mappedCategory)
    val identity = if (call.isBlank()) "" else "${context.projectId}/${context.ownerSessionId}/${context.requestId}/native/${call.replace("%", "%25").replace("/", "%2F")}"
    val title = when (event) {
        is CodingEvent.ToolStarted -> event.title
        is CodingEvent.ToolFinished -> event.title
        else -> null
    }
    return ToolEvent(context.projectId, context.ownerSessionId, context.requestId, identity, id, category, phase, summary, result, title)
}

/** Validate the JSON subset used by the catalog before invoking any receiver. */
fun validateToolArguments(schema: JsonObject, value: JsonElement, path: String = "arguments") {
    (schema["anyOf"] as? JsonArray)?.let { variants ->
        require(variants.any { runCatching { validateToolArguments(it.jsonObject, value, path) }.isSuccess }) { "$path: неверный тип" }
        return
    }
    val type = schema["type"]?.jsonPrimitive?.content
    require(when (type) {
        "null" -> value == JsonNull
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        "object" -> value is JsonObject; "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        else -> true
    }) { "$path: ожидается $type" }
    schema["enum"]?.jsonArray?.let { require(value in it) { "$path: недопустимое значение" } }
    if (value is JsonObject) {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        (schema["required"] as? JsonArray).orEmpty().forEach { require(it.jsonPrimitive.content in value) { "$path: отсутствует ${it.jsonPrimitive.content}" } }
        if (schema["additionalProperties"] == JsonPrimitive(false)) require(value.keys.all { it in properties }) { "$path: неизвестные аргументы" }
        properties.forEach { (key, spec) -> value[key]?.let { validateToolArguments(spec.jsonObject, it, "$path.$key") } }
    }
    if (value is JsonArray) {
        schema["minItems"]?.jsonPrimitive?.intOrNull?.let { require(value.size >= it) { "$path: пустой список" } }
        (schema["items"] as? JsonObject)?.let { spec -> value.forEach { validateToolArguments(spec, it, path) } }
    }
}
