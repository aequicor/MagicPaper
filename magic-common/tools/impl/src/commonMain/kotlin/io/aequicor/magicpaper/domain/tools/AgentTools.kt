package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
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





/** Observers attach before execution. Back pressure preserves lifecycle boundaries. */
class DefaultToolEventHub(private val knownSecrets: () -> Set<String> = { emptySet() }) : ToolEventHub {
    private val observers = MutableStateFlow<List<suspend (ToolEvent) -> Unit>>(emptyList())
    override val events: Flow<ToolEvent> = channelFlow {
        val close = observe { send(it) }
        awaitClose { close() }
    }
    override fun observe(observer: suspend (ToolEvent) -> Unit): () -> Unit {
        observers.update { it + observer }
        return { observers.update { it - observer } }
    }
    suspend fun publish(event: ToolEvent) {
        val secrets = knownSecrets()
        val safe = event.copy(summary = PlanningDiagnostics.redact(event.summary, secrets), result = PlanningDiagnostics.redact(event.result, secrets),
            title = event.title?.let { PlanningDiagnostics.redact(it, secrets) },
            media = event.media?.let { it.copy(caption = PlanningDiagnostics.redact(it.caption, secrets),
                message = PlanningDiagnostics.redact(it.message, secrets)) },
            sources = event.sources.map { source -> source.copy(title = PlanningDiagnostics.redact(source.title, secrets),
                url = PlanningDiagnostics.redact(source.url, secrets), snippet = PlanningDiagnostics.redact(source.snippet, secrets)) })
        observers.value.forEach { observer ->
            try { observer(safe) } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                AppLog.error("tools", "observer.failed", fields = mapOf("callId" to event.callId,
                    "phase" to event.phase.name, "causeType" to error::class.simpleName.orEmpty()))
                observers.update { it - observer }
            }
        }
    }
}

class ToolRegistry(commands: List<ToolCommand<*, *>>) {
    private val commands = MutableStateFlow(commands.associateBy { it.definition.id }.also { require(it.size == commands.size) { "Повтор инструмента" } })
    /** Native capabilities belong to a running bridge, while calls keep the session's executor and receipts. */
    internal fun attach(additional: List<ToolCommand<*, *>>): () -> Unit {
        val added = additional.associateBy { it.definition.id }
        require(added.size == additional.size) { "Повтор инструмента" }
        commands.update { current ->
            require(added.keys.none { it in current }) { "Инструмент уже подключён" }
            current + added
        }
        return { commands.update { current -> current.filterNot { (id, command) -> added[id] === command } } }
    }
    fun available(context: ToolExecutionContext) = commands.value.values.map { it.definition }.filter { !it.native && it.allowed(context) }
    fun command(name: String): ToolCommand<*, *> = commands.value[name] ?: commands.value.values.firstOrNull { it.definition.wireName == name }
        ?: error("Неизвестный инструмент: $name")
}

class MemoryToolReceiptStore : ToolReceiptStore {
    private val lock = Mutex()
    private val receipts = mutableMapOf<String, ToolReceipt>()
    override suspend fun get(id: String) = lock.withLock { receipts[id] }
    override suspend fun forRequest(prefix: String) = lock.withLock { receipts.values.filter { it.id.startsWith("$prefix/") } }
    override suspend fun forOwner(projectId: String, ownerSessionId: String) = lock.withLock {
        receipts.values.filter { it.id.startsWith("$projectId/$ownerSessionId/") }
    }
    override suspend fun save(receipt: ToolReceipt) { lock.withLock {
        val safe = receipt.forPersistence()
        receipts[receipt.id]?.validateUpdate(safe)
        receipts[receipt.id] = safe
    } }
    override suspend fun claim(receipt: ToolReceipt): ToolReceipt? = lock.withLock {
        val safe = receipt.forPersistence()
        receipts[receipt.id].also { if (it == null) receipts[receipt.id] = safe }
    }
}

class ToolExecutor(
    private val registry: ToolRegistry, private val receipts: ToolReceiptStore,
    private val checkScope: suspend (ToolExecutionContext) -> Unit = {},
    private val checkReplayScope: suspend (ToolExecutionContext) -> Unit = checkScope,
    private val reconcile: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null },
    private val knownSecrets: () -> Set<String> = { emptySet() },
    private val recoverQuestionnaire: suspend (ToolExecutionContext, ToolReceipt) -> JsonElement? = { _, _ -> null },
    private val unknownOutcome: suspend (ToolExecutionContext, ToolReceipt) -> Unit = { _, _ -> },
    private val authorizeTool: suspend (ToolExecutionContext, ToolDefinition) -> Unit = { _, _ -> },
    private val authorizeCommand: suspend (ToolExecutionContext, ToolDefinition, JsonObject) -> Unit = { _, _, _ -> },
    private val authorizeReceipt: suspend (ToolExecutionContext, ToolDefinition) -> Unit = authorizeTool,
    private val mediaReceiptLock: Mutex = Mutex(),
) {
    private val locks = Mutex()
    private val calls = mutableMapOf<String, Mutex>()

    /** False means an obsolete event must not reopen the recorder or pending native calls. */
    internal suspend fun recordNative(context: ToolExecutionContext, event: ToolEvent): Boolean {
        if (event.callId.isBlank()) return true // Display without inventing a durable identity.
        val lock = locks.withLock { calls.getOrPut(event.callId) { Mutex() } }
        return lock.withLock {
            val previous = receipts.get(event.callId)
            require(previous == null || previous.runtimeGeneration == context.runtimeGeneration) { "Поколение вызова изменилось" }
            require(previous == null || previous.native && previous.toolId == event.toolId) { "Идентификатор вызова изменился" }
            if (previous?.phase in nativeTerminalPhases) return@withLock false
            // Progress after an uncertain finish cannot erase the uncertainty. Only a native
            // terminal result for this exact generation can establish the historical outcome.
            if (previous?.phase == ToolPhase.UNKNOWN) {
                if (event.phase !in nativeTerminalPhases) return@withLock false
                checkReplayScope(context)
            } else checkScope(context)
            val now = Id.now()
            val receipt = (previous ?: ToolReceipt(event.callId, event.toolId, JsonObject(emptyMap()),
                recordedAt = now, runtimeGeneration = context.runtimeGeneration, argumentsComplete = false,
                resultComplete = false, native = true,
                mutating = ToolCatalog.definitions.firstOrNull { it.id == event.toolId }?.mutating ?: true)).copy(
                phase = event.phase, updatedAt = now,
                summary = event.summary.ifBlank { previous?.summary.orEmpty() },
                title = event.title ?: previous?.title,
                result = if (event.result.isNotBlank()) JsonPrimitive(event.result) else previous?.result ?: JsonNull,
            ).forPersistence(knownSecrets())
            receipts.save(receipt)
            ToolCallDiagnostics.log(context, event, native = true,
                durationMs = if (receipt.phase == ToolPhase.STARTED) null else now - receipt.recordedAt,
                cause = null, secrets = knownSecrets())
            if (receipt.phase == ToolPhase.UNKNOWN && receipt.mutating) unknownOutcome(context, receipt)
            true
        }
    }
    suspend fun receipt(context: ToolExecutionContext, callId: String): ToolReceipt? = receipts.get(
        "${context.projectId}/${context.ownerSessionId}/${context.requestId}/${callId.replace("%", "%25").replace("/", "%2F")}")
    suspend fun execute(session: DefaultToolSession, callId: String, name: String, arguments: JsonObject): JsonElement {
        require(callId.isNotBlank() && callId.length <= 512) { "Некорректный идентификатор вызова" }
        val context = session.context
        val key = "${context.projectId}/${context.ownerSessionId}/${context.requestId}/${callId.replace("%", "%25").replace("/", "%2F")}"
        val lock = locks.withLock { calls.getOrPut(key) { Mutex() } }
        return lock.withLock {
            @Suppress("UNCHECKED_CAST")
            val command = runCatching { registry.command(name) as ToolCommand<Any?, Any?> }.getOrNull()
            val definition = command?.definition ?: ToolDefinition(name, name, JsonObject(emptyMap()))
            var media: GeneratedMedia? = when (definition.id) {
                "image.generate" -> MediaKind.IMAGE
                "video.generate" -> MediaKind.VIDEO
                else -> null
            }?.let { kind -> GeneratedMedia(key, kind, caption = (arguments["caption"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                width = (arguments["width"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: if (kind == MediaKind.VIDEO) 1280 else 1024,
                height = (arguments["height"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: if (kind == MediaKind.VIDEO) 720 else 1024) }
            fun event(phase: ToolPhase, result: String = "") = ToolEvent(context.projectId, context.ownerSessionId,
                context.requestId, key, definition.id, definition.category, phase,
                toolArgumentPreview(definition.id, redactToolJson(arguments, knownSecrets()).jsonObject),
                PlanningDiagnostics.redact(result, knownSecrets()).let {
                    if (it.length <= 64_000) it else it.take(64_000) + "\n… Вывод сокращён; полный результат сохранён: $key"
                }, media = media)
            val startedAt = Id.now()
            suspend fun report(event: ToolEvent, cause: Throwable? = null) {
                ToolCallDiagnostics.log(context, event, native = false,
                    durationMs = if (event.phase == ToolPhase.STARTED) null else Id.now() - startedAt,
                    cause = cause, secrets = knownSecrets())
                session.events.publish(event)
            }
            report(event(ToolPhase.STARTED))
            var claimed: ToolReceipt? = null
            var executing = false
            var failurePhase: ToolPhase? = null
            try {
                require(command != null) { "Неизвестный инструмент: $name" }
                require(definition.allowed(context)) { "Инструмент недоступен для этой роли или режима" }
                val args = command.decode(arguments)
                val fingerprint = toolArgumentsFingerprint(arguments)
                val intent = ToolReceipt(key, definition.id, arguments, operationId = Id.uuid(), argumentFingerprint = fingerprint,
                    recordedAt = Id.now(), updatedAt = Id.now(), runtimeGeneration = context.runtimeGeneration,
                    mutating = definition.mutating).forPersistence(knownSecrets())
                var receipt = receipts.get(key)
                if (receipt == null) {
                    authorizeTool(context, definition)
                    authorizeCommand(context, definition, arguments)
                    checkScope(context)
                    if (definition.mutating && definition.id != "immunity.signal") {
                        val unresolved = receipts.forRequest("${context.projectId}/${context.ownerSessionId}/${context.requestId}")
                            .firstOrNull { it.mutating && it.phase == ToolPhase.UNKNOWN }
                        if (unresolved != null) {
                            failurePhase = ToolPhase.UNKNOWN
                            error("Предыдущая операция требует восстановления: ${unresolved.id}. Новые изменения заблокированы.")
                        }
                    }
                    // Every application call shares one identity namespace, including reads and questions.
                    receipt = receipts.claim(intent)
                    if (receipt == null) claimed = intent
                }
                if (receipt != null) {
                    require(receipt.toolId == definition.id &&
                        (receipt.argumentFingerprint.ifBlank { toolArgumentsFingerprint(receipt.arguments) } == fingerprint)) {
                        "Идентификатор вызова уже использован с другими аргументами"
                    }
                    require(receipt.runtimeGeneration == context.runtimeGeneration) { "Поколение вызова изменилось" }
                    // This branch can only inspect/reconcile a saved effect; it never calls execute.
                    // A quarantined owner may prove its historical result without regaining write authority.
                    authorizeReceipt(context, definition)
                    authorizeCommand(context, definition, arguments)
                    checkReplayScope(context)
                    if (receipt.phase in setOf(ToolPhase.FAILED, ToolPhase.CANCELLED)) {
                        failurePhase = receipt.phase
                        error(receipt.error.ifBlank { "Предыдущий вызов не выполнен. Для новой попытки нужен новый идентификатор." })
                    }
                    val recovered = when {
                        definition.id == "questionnaire" && questionnaireSecretIds(arguments).isNotEmpty() -> recoverQuestionnaire(context, receipt)
                        receipt.phase == ToolPhase.SUCCEEDED -> receipt.result
                        definition.id == "questionnaire" -> recoverQuestionnaire(context, receipt)
                        else -> reconcile(context, receipt)
                    }?.let { redactToolJson(it, knownSecrets()) }
                    if (recovered == null) {
                        failurePhase = ToolPhase.UNKNOWN
                        if (receipt.mutating) withContext(NonCancellable) {
                            val uncertain = receipt.copy(phase = ToolPhase.UNKNOWN, updatedAt = Id.now())
                            receipts.save(uncertain)
                            unknownOutcome(context, uncertain)
                        }
                        error("Исход предыдущего вызова неизвестен. Требуется восстановление; повторное выполнение заблокировано.")
                    }
                    if (receipt.phase != ToolPhase.SUCCEEDED) {
                        checkScope(context)
                        receipts.save(receipt.copy(phase = ToolPhase.SUCCEEDED, result = recovered, error = "", updatedAt = Id.now()))
                    }
                    if (media != null) (recovered as? JsonObject)?.get("media")?.let {
                        media = Json.decodeFromJsonElement<GeneratedMedia>(it).copy(id = key)
                    }
                    report(event(ToolPhase.SUCCEEDED, (if (definition.id == "questionnaire") redactQuestionnaireToolResult(arguments, recovered) else recovered).toString())
                        .copy(sources = toolResultSources(definition.id, recovered)))
                    session.completed(key, definition.id, intent.arguments, recovered)
                    return@withLock recovered
                }
                if (definition.id == "questionnaire") {
                    claimed = intent.copy(phase = ToolPhase.WAITING, updatedAt = Id.now())
                    receipts.save(claimed)
                    report(event(ToolPhase.WAITING, "Ожидается ответ пользователя"))
                }
                authorizeTool(context, definition)
                authorizeCommand(context, definition, arguments)
                executing = true
                val progress = MediaToolProgress(key) { update ->
                    checkScope(context)
                    media = update.copy(id = key)
                    report(event(ToolPhase.PROGRESS))
                }
                val result = withContext(session + progress + UsageOwner(context.usageScope ?: UsageScope("coding:${context.ownerSessionId}",
                    context.parentSessionId?.let { "coding:$it" }, context.projectId, context.planId))) {
                    command.encode(command.execute(context, intent.operationId, args))
                }.let { redactToolJson(it, knownSecrets()) }
                // A late response must not publish current-session success after its authority expires.
                checkScope(context)
                if (media != null) (result as? JsonObject)?.get("media")?.let {
                    media = Json.decodeFromJsonElement<GeneratedMedia>(it).copy(id = key)
                }
                withContext(NonCancellable) { receipts.save(intent.copy(phase = ToolPhase.SUCCEEDED, result = result, updatedAt = Id.now())) }
                claimed = null
                session.completed(key, definition.id, intent.arguments, result)
                report(event(ToolPhase.SUCCEEDED, (if (definition.id == "questionnaire") redactQuestionnaireToolResult(arguments, result) else result).toString())
                    .copy(sources = toolResultSources(definition.id, result)))
                result
            } catch (error: Exception) {
                // Cancellation of an awaiter is not proof that an external effect was cancelled.
                var phase = failurePhase ?: if (error is RejectedToolCall || error is ConfirmedToolFailure) ToolPhase.FAILED
                    else if (executing && definition.mutating) ToolPhase.UNKNOWN
                    else if (error is CancellationException) ToolPhase.CANCELLED else ToolPhase.FAILED
                val message = PlanningDiagnostics.redact(error.message ?: "Ошибка инструмента", knownSecrets())
                media = media?.copy(phase = when (phase) {
                    ToolPhase.CANCELLED -> MediaPhase.CANCELLED
                    ToolPhase.UNKNOWN -> MediaPhase.UNKNOWN
                    else -> MediaPhase.FAILED
                }, message = if (phase == ToolPhase.UNKNOWN) "Исход генерации проверяется. Повторная генерация не запущена."
                    else "Не удалось создать медиа. Проверьте подключение и повторите попытку.")
                withContext(NonCancellable) {
                    var displayedResult = message
                    suspend fun cleanup(event: String, action: suspend () -> Unit) {
                        try { action() }
                        catch (failure: Exception) {
                            AppLog.error("tools", event, fields = mapOf("callId" to key,
                                "failure" to failure::class.simpleName.orEmpty()))
                        }
                    }
                    suspend fun persistFailure() {
                        // Application-owned media may finish while this awaiter is being cancelled.
                        // The host reconciler uses this same short lock, so quarantine sees the saved outcome.
                        val intent = claimed
                        var settled: ToolReceipt? = null
                        if (media != null && intent != null) cleanup("outcome.read.failed") {
                            settled = receipts.get(key)?.takeIf {
                                it.phase in setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED) && it.operationId == intent.operationId &&
                                    it.runtimeGeneration == context.runtimeGeneration && it.toolId == definition.id
                            }
                        }
                        settled?.let { terminal ->
                            phase = terminal.phase
                            displayedResult = if (phase == ToolPhase.SUCCEEDED) terminal.result.toString() else terminal.error
                            if (phase == ToolPhase.SUCCEEDED) cleanup("media.result.decode.failed") {
                                (terminal.result as? JsonObject)?.get("media")?.let {
                                    media = Json.decodeFromJsonElement<GeneratedMedia>(it)
                                }
                            } else media = media?.copy(phase = MediaPhase.FAILED, message = terminal.error)
                            return
                        }
                        if (intent == null) cleanup("outcome.claim.failed") {
                            // Rejections are real invocations too. Claim-only preserves any earlier identity/result.
                            receipts.claim(ToolReceipt(key, definition.id, arguments,
                                phase = if (!executing && phase == ToolPhase.UNKNOWN) ToolPhase.FAILED else phase, operationId = Id.uuid(),
                                error = message, recordedAt = Id.now(), updatedAt = Id.now(), runtimeGeneration = context.runtimeGeneration,
                                mutating = definition.mutating).forPersistence(knownSecrets()))
                        } else {
                            val updated = intent.copy(phase = phase, error = message, updatedAt = Id.now())
                            cleanup("outcome.save.failed") { receipts.save(updated) }
                            if (phase == ToolPhase.UNKNOWN && intent.mutating)
                                cleanup("outcome.quarantine.failed") { unknownOutcome(context, updated) }
                        }
                    }
                    if (media != null) mediaReceiptLock.withLock { persistFailure() } else persistFailure()
                    cleanup("outcome.publish.failed") { report(event(phase, displayedResult), error.takeUnless { phase == ToolPhase.SUCCEEDED }) }
                }
                if (message != error.message) throw when (error) {
                    is CancellationException -> CancellationException(message)
                    is IllegalArgumentException -> IllegalArgumentException(message)
                    else -> IllegalStateException(message)
                }
                throw error
            }
        }
    }
}

/** The executor supplies identity and diagnostics for progress from a trusted media command. */
internal class MediaToolProgress(val callId: String, val publish: suspend (GeneratedMedia) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MediaToolProgress>
}

class DefaultToolSession(override val context: ToolExecutionContext, val registry: ToolRegistry, val executor: ToolExecutor,
    knownSecrets: () -> Set<String> = { emptySet() },
) : AbstractCoroutineContextElement(ToolSession), ToolSession {
    override val events = DefaultToolEventHub(knownSecrets)
    override val results = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    override val calls = MutableStateFlow<List<CompletedToolCall>>(emptyList())
    internal fun completed(id: String, tool: String, arguments: JsonObject, result: JsonElement) {
        results.update { it + (tool to result) }
        calls.update { previous -> previous.filterNot { it.id == id } + CompletedToolCall(id, tool, arguments, result) }
    }
    override val definitions get() = registry.available(context)
    override suspend fun call(id: String, name: String, args: JsonObject) = executor.execute(this, id, name, args)
    override suspend fun receipt(id: String) = executor.receipt(context, id)
    override fun attachNativeCommands(commands: List<ToolCommand<*, *>>) = registry.attach(commands)
    override suspend fun recordNative(event: ToolEvent): Boolean = executor.recordNative(context, event)
    override suspend fun publishNative(event: ToolEvent) = events.publish(event)
}


private val nativeTerminalPhases = setOf(ToolPhase.SUCCEEDED, ToolPhase.FAILED, ToolPhase.CANCELLED)
