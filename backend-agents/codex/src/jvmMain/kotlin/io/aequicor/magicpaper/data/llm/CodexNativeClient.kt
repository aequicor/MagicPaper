package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.domain.PlanningAnswer
import io.aequicor.magicpaper.domain.AttachmentKind
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingSession
import io.aequicor.magicpaper.domain.DeclaredReasoning
import io.aequicor.magicpaper.domain.LlmChatRole
import io.aequicor.magicpaper.domain.LlmMessage
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.OpenAiRateLimit
import io.aequicor.magicpaper.domain.OpenAiSubscriptionAccount
import io.aequicor.magicpaper.domain.OpenAiSubscriptionLogin
import io.aequicor.magicpaper.domain.ProviderType
import io.aequicor.magicpaper.domain.ReasoningEffort
import io.aequicor.magicpaper.domain.decodeText
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import io.aequicor.magicpaper.domain.forModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Официальный desktop-транспорт OpenAI: JSONL-клиент к `codex app-server`.
 * Авторизация хранится в отдельном CODEX_HOME MagicPaper и не затрагивает
 * аккаунт пользователя в Codex CLI/desktop.
 */
class CodexNativeClient(
    private val json: Json,
    private val appHome: Path,
    private val commandOverride: String?,
    private val ownedCoding: NativeProcessRecovery,
    private val tokens: NativeAuthTokens,
    questionnaires: NativeQuestionnaires,
    private val diagnostics: NativeDiagnostics,
    private val toolPresentation: NativeToolPresentationResolver,
) : CodexClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val loginEvents = MutableStateFlow<LoginEvent?>(null)
    private val turns = ConcurrentHashMap<String, TurnAccumulator>()
    private val codingRuns = ConcurrentHashMap<String, CodingAccumulator>()
    private val codingSessions = ConcurrentHashMap<String, String>()
    private val codingContexts = ConcurrentHashMap<String, CodingSession>()
    private val lifecycleAttempts = ConcurrentHashMap<NativeAttemptRef, NativeAttemptEvents>()
    // A disconnected reader cannot discard the sole cleanup handle, including a process
    // launched before receipt persistence/Attached succeeds.
    private val launchedProcesses = ConcurrentHashMap.newKeySet<Process>()
    private val codingThreads = ConcurrentHashMap.newKeySet<String>()
    private val approvalBroker = CodexApprovalBroker { threadId, message ->
        codingRuns[threadId]?.emit(CodingEvent.Notice(message))
    }
    override val codingApprovals = approvalBroker.requests
    private val questionnaireBroker = CodexQuestionnaireBroker(scope, questionnaires,
        { threadId, message -> codingRuns[threadId]?.emit(CodingEvent.Notice(message)) },
        { threadId, error -> codingRuns[threadId]?.done?.completeExceptionally(error) })


    override suspend fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) =
        approvalBroker.respond(id, decision)
    private val stderrTail = ArrayDeque<String>()
    override suspend fun readCodingToolResults(threadId: String, callIds: Set<String>): List<CodingEvent.ToolFinished> {
        if (callIds.isEmpty()) return emptyList()
        val response = request("thread/read", buildJsonObject { put("threadId", threadId); put("includeTurns", true) }).jsonObject
        return protocol.readToolResults(response, threadId, callIds)
    }
    override suspend fun reconcileCoding(sessionId: String) = withContext(Dispatchers.IO) {
        if (ownedCoding.belongsTo(sessionId, process)) {
            // Interrupt just the owned turn and await its acknowledgement; other projects keep running.
            val threadId = codingSessions[sessionId]
            val run = threadId?.let { codingRuns[it] }
                ?: error("Предыдущий Codex-прогон ещё не подтвердил завершение; требуется восстановление сессии")
            if (run.waitingForNativeCompletion) {
                // turn/interrupt cannot stop a command after that model turn has ended.
                // A per-session connection can instead reconcile its exact owned process tree;
                // never use this fallback on a shared connection with unrelated active work.
                check(codingRuns.none { (id, other) -> id != threadId && !other.done.isCompleted } &&
                    turns.values.none { !it.done.isCompleted }) { "Другие задачи используют этот процесс; дождитесь завершения команды" }
                ownedCoding.reconcile(sessionId)
                run.finish("Процесс остановлен; фактический результат незавершённых инструментов требует проверки", confirmed = false)
            } else if (!run.done.isCompleted) {
                check(run.turnId != null) { "Codex ещё не подтвердил идентификатор прерванной операции" }
                abortCoding(sessionId)
                withTimeout(10_000) { run.done.await() }
            } else {
                // Exceptional completion proves disconnection, not process termination.
                run.done.await()
            }
            ownedCoding.clear(sessionId)
            codingSessions.remove(sessionId)
            codingRuns.remove(threadId)
            codingContexts.remove(threadId)
            approvalBroker.clearTurn(threadId)
            questionnaireBroker.clearTurn(threadId)
            return@withContext
        }
        ownedCoding.reconcile(sessionId)
    }

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null

    private val tokenMutex = Mutex()
    /** Refresh ownership remains with app-server; pi receives only the current access token. */
    override suspend fun subscriptionAccessToken(): String = tokenMutex.withLock {
        val response = request("account/read", buildJsonObject { put("refreshToken", true) }).jsonObject
        check((response["account"] as? JsonObject)?.string("type") == "chatgpt") { "Войдите в ChatGPT в настройках движков" }
        withContext(Dispatchers.IO) {
            tokens.readAccessToken()?.takeIf { it.isNotBlank() }
                ?: error("Не удалось получить доступ к подписке. Повторите вход в ChatGPT.")
        }
    }
    override suspend fun runtimeStatus(): NativeRuntimeStatus = try {
        ensureStarted()
        NativeRuntimeStatus(true,
            "Codex app-server: ${resolveCodexCommand(commandOverride)}")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (e: Exception) {
          diagnostics.error("CodexSubscription", "runtime_status_failed", e, mapOf("result" to "unavailable"))
          NativeRuntimeStatus(false, "Не удалось подключиться к Codex. Проверьте установку и повторите проверку.")
      }

    override suspend fun account(refreshToken: Boolean): OpenAiSubscriptionAccount {
        val response = request("account/read", buildJsonObject { put("refreshToken", refreshToken) }).jsonObject
        val account = response["account"] as? JsonObject
        if (account?.string("type") != "chatgpt") return OpenAiSubscriptionAccount(signedIn = false)
        var limitsUnavailable = false
        val limits = try { readRateLimits() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            limitsUnavailable = true
            diagnostics.error("CodexSubscription", "rate_limits_read_failed", failure,
                mapOf("result" to "limits_unavailable", "account" to "signed_in"))
            emptyList()
        }
        return OpenAiSubscriptionAccount(
            signedIn = true,
            email = account.string("email"),
            planType = account.string("planType"),
            rateLimits = limits,
            rateLimitsUnavailable = limitsUnavailable,
        )
    }

    override suspend fun startLogin(): OpenAiSubscriptionLogin {
        loginEvents.value = null
        val result = request("account/login/start", buildJsonObject { put("type", "chatgpt") }).jsonObject
        check(result.string("type") == "chatgpt") { "Codex не вернул браузерный вход ChatGPT." }
        return OpenAiSubscriptionLogin(
            id = result.requireString("loginId"),
            url = result.requireString("authUrl"),
        )
    }

    override suspend fun awaitLogin(loginId: String): OpenAiSubscriptionAccount {
        val event = withTimeout(LOGIN_TIMEOUT_MS) {
            loginEvents.filter { it?.loginId == null || it.loginId == loginId }.first { it != null }!!
        }
        check(event.success) { event.error ?: "Вход через ChatGPT не завершён." }
        return account(refreshToken = true)
    }

    override suspend fun cancelLogin(loginId: String) {
        request("account/login/cancel", buildJsonObject { put("loginId", loginId) })
    }

    override suspend fun logout() {
        request("account/logout", JsonObject(emptyMap()))
    }

    override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> {
        require(profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
        val ids = mutableListOf<String>()
        val declarations = mutableMapOf<String, DeclaredReasoning>()
        val metadata = mutableMapOf<String, io.aequicor.magicpaper.domain.ProviderModel>()
        var cursor: String? = null
        do {
            val page = request(
                "model/list",
                buildJsonObject {
                    put("includeHidden", false)
                    put("limit", 100)
                    cursor?.let { put("cursor", it) }
                },
            ).jsonObject
            page["data"]?.jsonArray.orEmpty().forEach { element ->
                val model = element.jsonObject
                if (model["hidden"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                val id = model.string("model") ?: model.string("id") ?: return@forEach
                ids += id
                val efforts = model["supportedReasoningEfforts"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject.string("reasoningEffort") }
                    .mapNotNull(ReasoningEffort::fromWire)
                    .toSet()
                declarations[id] = if (efforts.isEmpty()) DeclaredReasoning.None else DeclaredReasoning(efforts = efforts, default = model.string("defaultReasoningEffort")?.let(ReasoningEffort::fromWire))
                metadata[id] = io.aequicor.magicpaper.domain.ProviderModel(id, model.string("displayName") ?: id, supportedParameters = emptySet(), reasoning = declarations[id])
            }
            cursor = page.string("nextCursor")
        } while (!cursor.isNullOrBlank())
        val cachedWindows = cachedContextWindows()
        cachedWindows.forEach { (id, window) ->
            metadata[id]?.let { metadata[id] = it.copy(contextWindow = window) }
        }
        return ModelDefaults.discover(ProviderType.OPENAI_SUBSCRIPTION, ids, declarations).map { it.copy(metadata = metadata[it.id]) }
    }

    /** Apply the same native Codex budget to a Pi subscription run, including already saved profiles. */
    override fun withCachedContextWindow(profile: LlmProfile): LlmProfile {
        if (profile.provider != ProviderType.OPENAI_SUBSCRIPTION) return profile
        val source = profile.sourceModelId(profile.modelId)
        val window = cachedContextWindows()[source] ?: return profile
        val catalog = profile.modelCatalog.map { model ->
            if (model.id == source) model.copy(contextWindow = window) else model
        }
        return profile.copy(
            advanced = profile.advanced.copy(
                contextLimit = window,
                maxTokens = minOf(profile.advanced.maxTokens, window),
            ),
            modelCatalog = catalog,
        )
    }

    private fun cachedContextWindows(): Map<String, Int> = try {
        val cache = appHome.resolve("models_cache.json").toFile()
        if (cache.isFile) codexCachedContextWindows(json, cache.readText()) else emptyMap()
    } catch (failure: Exception) {
        diagnostics.error(
            "CodexSubscription",
            "model_cache_read_failed",
            failure,
            mapOf("recovery" to "context_window_unknown"),
        )
        emptyMap()
    }

    override suspend fun complete(request: CodexCompletionRequest, onActivity: (CodingStep) -> Unit,
        onUsage: (UsageCallResult) -> Unit): String {
        check(account().signedIn) { "Сначала войдите в ChatGPT в настройках источника." }
        val thread = request(
            "thread/start",
            buildJsonObject {
                put("ephemeral", true)
                put("model", request.modelId)
                put("approvalPolicy", "never")
                put("sandbox", "read-only")
                put("serviceName", "MagicPaper")
                put("baseInstructions", request.baseInstructions)
                if (request.systemInstructions.isNotBlank()) put("developerInstructions", request.systemInstructions)
            },
        ).jsonObject
        val threadId = thread["thread"]?.jsonObject?.requireString("id")
            ?: error("Codex не вернул идентификатор диалога.")
        val connection = checkNotNull(writer) { "Соединение Codex потеряно." }
        val accumulator = TurnAccumulator(onActivity).also { it.usage = onUsage }
        turns[threadId] = accumulator
        var turnId: String? = null
        try {
            val started = request(
                "turn/start",
                buildJsonObject {
                    put("threadId", threadId)
                    put("input", request.input)
                    put("model", request.modelId)
                    request.effort?.let { put("effort", it) }
                    // Request readable summaries explicitly instead of inheriting a disabled default.
                    put("summary", "auto")
                    put("approvalPolicy", "never")
                    put("sandboxPolicy", buildJsonObject { put("type", "readOnly") })
                },
            ).jsonObject
            turnId = (started["turn"] as? JsonObject)?.string("id")
            val timeout = request.timeoutSeconds
            val text = accumulator.awaitResult(timeout)
            return text.ifBlank { error("OpenAI вернул пустой ответ.") }
        } finally {
            turns.remove(threadId)
            withContext(NonCancellable) {
                if (!accumulator.done.isCompleted && turnId != null) {
                    cleanupRequest("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) }, connection)
                }
                cleanupRequest("thread/unsubscribe", buildJsonObject { put("threadId", threadId) }, connection)
            }
        }
    }

    override fun runCoding(session: CodingSession, request: CodexRunRequest, refreshResume: Boolean): Flow<CodingEvent> = channelFlow {
        try {
            coroutineScope {
                val nativeRequest = request
                val planning = request.mode == CodingInteractionMode.PLANNING
                val research = request.mode == CodingInteractionMode.RESEARCH
                var confirmedFinished = false
                var failed = false
                val lifecycle = checkNotNull(currentCoroutineContext()[NativeAttemptContext]) { "Native lifecycle owner is missing" }
                val attempt = lifecycle.events.admitLaunch(lifecycle.run)
                lifecycleAttempts[attempt] = lifecycle.events
                try {
                ensureStarted()
                val ownedProcess = checkNotNull(process) { "Native process is unavailable" }
                ownedCoding.record(session.id, ownedProcess)
                lifecycle.events.attached(attempt, NativeProcessIdentity(session.id, ownedProcess.pid(), ownedProcess.info().startInstant().orElseThrow().toEpochMilli()))
                lifecycle.events.deliver(attempt, NativeDelivery.CODEX_THREAD)
                val payloads = protocol.prepareRun(nativeRequest)
                val resumed = nativeRequest.nativeSessionId?.let { oldId ->
                    if (refreshResume && oldId in codingThreads) {
                        request("thread/unsubscribe", buildJsonObject { put("threadId", oldId) })
                        codingThreads.remove(oldId)
                    }
                    request("thread/resume", checkNotNull(payloads.threadResume)).jsonObject["thread"]?.jsonObject?.requireString("id")
                        ?: error("Codex не восстановил coding-сессию.")
                }
                val threadId = resumed ?: request("thread/start", payloads.threadStart).jsonObject["thread"]?.jsonObject?.requireString("id")
                    ?: error("Codex не вернул идентификатор coding-сессии.")
                codingThreads.add(threadId)
                lifecycle.events.accepted(attempt, threadId, null)
                send(CodingEvent.SessionStarted(threadId))
                val accumulator = CodingAccumulator(planning, research, toolPresentation)
                approvalBroker.clearTurn(threadId)
                questionnaireBroker.clearTurn(threadId)
                codingRuns[threadId] = accumulator
                codingSessions[session.id] = threadId
                codingContexts[threadId] = session
                lifecycle.events.deliver(attempt, NativeDelivery.CODEX_TURN)
                val turn = request("turn/start", JsonObject(payloads.turnStart + ("threadId" to JsonPrimitive(threadId)))).jsonObject
                accumulator.turnId = turn["turn"]?.jsonObject?.string("id")
                lifecycle.events.accepted(attempt, threadId, accumulator.turnId)
                for (event in accumulator.events) {
                    if (event is CodingEvent.Failed) failed = true
                    if (event is CodingEvent.Finished) confirmedFinished = accumulator.confirmed
                    send(event)
                }
                if (confirmedFinished) lifecycle.events.terminal(attempt, if (failed) NativeOutcome.FAILED else NativeOutcome.SUCCEEDED)
                } catch (cancelled: CancellationException) {
                    abortCoding(session.id)
                    throw cancelled
                } finally {
                    withContext(NonCancellable) {
                        // Failure before Attached does not prove that ensureStarted created no process.
                        // shutdownCoding reports Stopped only after every retained handle is cleaned up.
                        lifecycle.events.stopping(attempt)
                    }
                    // Keep ownership after interruption: recovery must reconcile an uncertain turn.
                    if (confirmedFinished) {
                        val threadId = codingSessions.remove(session.id)
                        if (threadId != null) codingRuns.remove(threadId)?.events?.close()
                        if (threadId != null) {
                            codingContexts.remove(threadId)
                            approvalBroker.clearTurn(threadId)
                            questionnaireBroker.clearTurn(threadId)
                        }
                        ownedCoding.clear(session.id)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Closing normally with a cause preserves queued events until the receiver reaches it.
            close(failure)
        }
    }

    override fun abortCoding(sessionId: String) {
        val threadId = codingSessions[sessionId] ?: return
        val run = codingRuns[threadId] ?: return
        approvalBroker.clearTurn(threadId)
        questionnaireBroker.clearTurn(threadId)
        val turnId = run.turnId ?: return
        val connection = writer
        scope.launch {
            try {
                checkNotNull(connection) { "Codex connection unavailable during interrupt" }
                requestStarted("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) }, connection)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                diagnostics.error("CodexSubscription", "coding_interrupt_failed", failure,
                    mapOf("session_id" to sessionId, "result" to "unknown", "recovery" to "reconcile_required"))
                run.finish("Не удалось подтвердить остановку. Восстановите сессию перед новым запуском.", confirmed = false)
            }
        }
    }

    override fun abortAllCoding() = codingSessions.keys.toList().forEach(::abortCoding)

    override suspend fun shutdownCoding() {
        close()
        // close() waits for owned descendants and the app-server, and throws on any unconfirmed cleanup.
        lifecycleAttempts.forEach { (attempt, events) -> events.stopped(attempt) }
        lifecycleAttempts.clear()
    }

    override fun close() {
        approvalBroker.clear()
        questionnaireBroker.clear()
        pending.values.forEach { it.cancel() }
        turns.values.forEach { it.done.cancel() }
        codingRuns.values.forEach { it.events.close() }
        var cleanupFailure: Throwable? = null
        try {
            val resources = launchedProcesses.toList() + listOfNotNull(process).filter { it !in launchedProcesses }
            resources.forEach { owned -> try {
                check(owned.isAlive || lifecycleAttempts.isEmpty()) {
                    "Нельзя подтвердить остановку дочерних процессов Codex после потери родительского процесса"
                }
                // Keep the parent alive until its recorded descendants have stopped, so a
                // failed cleanup retains ancestry for durable recovery on macOS/Linux too.
                val children = owned.descendants().use { it.toList() }
                children.asReversed().forEach { it.destroyForcibly() }
                children.forEach { if (it.isAlive) it.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS) }
                owned.destroyForcibly()
                check(owned.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "Не удалось остановить процесс Codex" }
                launchedProcesses.remove(owned)
                if (process === owned) process = null
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
            } }
        } finally { scope.cancel() }
        cleanupFailure?.let { throw it }
    }

    private suspend fun readRateLimits(): List<OpenAiRateLimit> {
        val root = request("account/rateLimits/read", JsonObject(emptyMap())).jsonObject
        val byId = root["rateLimitsByLimitId"] as? JsonObject
        val snapshots: List<Pair<String, JsonElement>> = if (!byId.isNullOrEmpty()) {
            byId.entries.map { it.key to it.value }
        } else {
            listOf("codex" to root["rateLimits"]!!)
        }
        return buildList {
            snapshots.forEach { (fallbackId, value) ->
                val snapshot = value.jsonObject
                val id = snapshot.string("limitId") ?: fallbackId
                val name = snapshot.string("limitName") ?: id
                listOf("primary", "secondary").forEach { windowName ->
                    val window = snapshot[windowName] as? JsonObject ?: return@forEach
                    add(
                        OpenAiRateLimit(
                            id = id,
                            name = name,
                            window = windowName,
                            usedPercent = window["usedPercent"]?.jsonPrimitive?.intOrNull ?: return@forEach,
                            resetsAtEpochSeconds = window["resetsAt"]?.jsonPrimitive?.longOrNull,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun request(method: String, params: JsonElement): JsonElement {
        ensureStarted()
        return requestStarted(method, params)
    }

    private suspend fun cleanupRequest(method: String, params: JsonElement, connection: BufferedWriter) {
        try {
            val completed = withTimeoutOrNull(5_000) {
                requestStarted(method, params, connection)
                true
            } ?: false
            if (!completed) diagnostics.error("CodexSubscription", "completion_cleanup_timeout",
                java.util.concurrent.TimeoutException("Native cleanup deadline elapsed"),
                mapOf("operation" to method, "result" to "unknown"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            diagnostics.error("CodexSubscription", "completion_cleanup_failed", failure,
                mapOf("operation" to method, "result" to "unknown"))
        }
    }

    private suspend fun requestStarted(method: String, params: JsonElement, connection: BufferedWriter? = null): JsonElement {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) }, expectedWriter = connection)
            return withTimeoutOrNull(30_000) { deferred.await() }
                ?: throw AppServerException("Codex не ответил на $method за 30 секунд. Проверьте состояние движка и повторите запрос.")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun ensureStarted(): Unit = startMutex.withLock {
        if (process?.isAlive == true && writer != null) return
        withContext(Dispatchers.IO) { Files.createDirectories(appHome) }
        val command = resolveCodexCommand(commandOverride)
        currentCoroutineContext().ensureActive()
        val created = runCatching {
            // Publish the cleanup handle before cancellation can discard the result of a
            // dispatcher switch; initialize/request delivery remains cancellable afterward.
            withContext(Dispatchers.IO + NonCancellable) {
                protocol.launch(command, appHome.toAbsolutePath().toString()).also {
                    launchedProcesses.add(it)
                    process = it
                }
            }
        }.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            throw AppServerException(
                "Не найден Codex app-server. Установите Codex desktop/CLI или задайте " +
                    "MAGICPAPER_CODEX_PATH.", cause,
            )
        }
        currentCoroutineContext().ensureActive()
        val connection = created.outputStream.bufferedWriter()
        writer = connection
        scope.launch { readStdout(created.inputStream.bufferedReader(), created, connection) }
        scope.launch { readStderr(created.errorStream.bufferedReader()) }
        requestStarted(
            "initialize",
            buildJsonObject {
                put("clientInfo", buildJsonObject {
                    put("name", "magicpaper")
                    put("title", "MagicPaper")
                    put("version", "1")
                })
                put("capabilities", buildJsonObject { put("experimentalApi", false) })
            },
        )
        send(buildJsonObject { put("method", "initialized"); put("params", buildJsonObject { }) })
    }

    private suspend fun send(value: JsonObject, expectedWriter: BufferedWriter? = null, valid: () -> Boolean = { true }) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = writer ?: error("Codex app-server не запущен.")
            check(expectedWriter == null || target === expectedWriter) { "Соединение Codex уже изменилось." }
            check(valid()) { "Запрос подтверждения уже завершён." }
            target.write(json.encodeToString(JsonObject.serializer(), value))
            target.newLine()
            target.flush()
        }
    }

    private suspend fun readStdout(reader: BufferedReader, source: Process, connection: BufferedWriter) {
        try {
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val wireId = message["id"] as? JsonPrimitive
                val id = wireId?.longOrNull
                if (id != null && ("result" in message || "error" in message)) {
                    val deferred = pending.remove(id) ?: continue
                    val error = message["error"] as? JsonObject
                    if (error != null) deferred.completeExceptionally(AppServerException(error.string("message") ?: error.toString()))
                    else deferred.complete(message["result"] ?: JsonNull)
                    continue
                }
                if (wireId != null && wireId != JsonNull && message.string("method") != null) {
                    if (handleServerRequest(wireId, message.string("method")!!, message["params"] as? JsonObject)) continue
                    send(
                        buildJsonObject {
                            put("id", wireId)
                            put("error", buildJsonObject {
                                put("code", -32601)
                                put("message", "MagicPaper does not support this request outside an active coding turn")
                            })
                        },
                    )
                    continue
                }
                handleNotification(message.string("method"), message["params"] as? JsonObject)
            }
            failAll("Codex app-server завершился${stderrMessage()}.")
        } catch (error: Throwable) {
            failAll("Потеряно соединение с Codex app-server: ${error.message}${stderrMessage()}")
        } finally {
            if (process === source) process = null
            if (writer === connection) writer = null
        }
    }

    internal fun handleServerRequest(id: JsonPrimitive, method: String, params: JsonObject?): Boolean {
        if (params == null) return false
        val threadId = params.string("threadId") ?: return false
        val run = codingRuns[threadId]?.takeUnless { it.done.isCompleted } ?: return false
        if (run.planning) return false // Read-only requests must never open the coding approval broker.
        val session = codingContexts[threadId] ?: return false
        val turnId = params.string("turnId") ?: return false
        if (run.turnId != null && run.turnId != turnId) return false
        if (run.turnId == null) run.turnId = turnId
        val connection = writer ?: return false
        if (questionnaireBroker.receive(id, method, params, session) { response ->
            send(response, expectedWriter = connection) {
                codingRuns[threadId] === run && !run.done.isCompleted && (run.turnId == null || run.turnId == turnId) &&
                    questionnaireBroker.contains(threadId, id)
            }
        }) return true
        if (run.research) return false // No approval may grant writes during research.
        return approvalBroker.receive(id, method, params, session, run.items[params.string("itemId")]) { response ->
            send(response, expectedWriter = connection) {
                codingRuns[threadId] === run && !run.done.isCompleted && (run.turnId == null || run.turnId == turnId) &&
                    approvalBroker.contains(threadId, id)
            }
        }
    }

    private fun handleNotification(method: String?, params: JsonObject?) {
        if (params == null) return
        when (method) {
            "thread/tokenUsage/updated" -> {
                val threadId = params.string("threadId") ?: return
                val usage = params["tokenUsage"] as? JsonObject ?: return
                val total = usage["total"] as? JsonObject ?: return
                val last = usage["last"] as? JsonObject ?: return
                val fingerprint = params.string("turnId").orEmpty() + ":" + total.toString()
                val totals = codexUsage(total)
                val latest = codexUsage(last)
                val window = usage.count("modelContextWindow")
                codingRuns[threadId]?.let { run ->
                    if (run.turnId != null) {
                        run.emit(CodingEvent.UsageObserved(latest, fingerprint, cumulative = totals))
                        run.emit(CodingEvent.ContextUpdated(latest.totalTokens, window))
                    }
                }
                turns[threadId]?.let { run ->
                    if (run.usageFingerprint != fingerprint) { run.usageRequests++; run.usageFingerprint = fingerprint }
                    run.usage?.invoke(UsageCallResult(totals, contextTokens = latest.totalTokens,
                        contextLimit = window, requests = run.usageRequests.coerceAtLeast(1)))
                }
            }
            "thread/compacted" -> {
                // Older app-server compatibility; suppress duplicate completion when an item was seen.
                codingRuns[params.string("threadId")]?.legacyCompacted()
            }
            "serverRequest/resolved" -> {
                val threadId = params.string("threadId") ?: return
                params["requestId"]?.let { approvalBroker.resolved(threadId, it); questionnaireBroker.resolved(threadId, it) }
            }
            "guardianWarning" -> {
                val threadId = params.string("threadId") ?: return
                val warning = params.string("message") ?: return
                codingRuns[threadId]?.emit(CodingEvent.Notice("Проверка разрешений: $warning"))
            }
            "account/login/completed" -> loginEvents.value = LoginEvent(
                loginId = params.string("loginId"),
                success = params["success"]?.jsonPrimitive?.booleanOrNull == true,
                error = params.string("error"),
            )
            "item/completed" -> {
                val threadId = params.string("threadId") ?: return
                val item = params["item"] as? JsonObject ?: return
                if (item.string("type") == "agentMessage") {
                    turns[threadId]?.accept(item.string("text").orEmpty(), item.string("phase"), item.string("id").orEmpty())
                }
                codingRuns[threadId]?.completeItem(item)
                item.string("id")?.let { approvalBroker.completeItem(threadId, it); questionnaireBroker.completeItem(threadId, it) }
            }
            "item/started" -> {
                val threadId = params.string("threadId") ?: return
                val item = params["item"] as? JsonObject ?: return
                turns[threadId]?.started(item)
                codingRuns[threadId]?.startItem(item)
            }
            "item/agentMessage/delta" -> {
                val delta = params.string("delta").orEmpty()
                turns[params.string("threadId")]?.textDelta(delta, params.string("itemId").orEmpty())
                codingRuns[params.string("threadId")]?.emit(CodingEvent.TextDelta(delta, params.string("itemId").orEmpty()))
            }
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" -> {
                codingRuns[params.string("threadId")]?.let { run ->
                    if (run.planning && method == "item/reasoning/textDelta") run.emit(CodingEvent.Notice(""))
                    else run.reasoningDelta(params, summary = method == "item/reasoning/summaryTextDelta")
                }
                turns[params.string("threadId")]?.heartbeat()
                // Planning displays the provider's public reasoning summary only.
                if (method == "item/reasoning/summaryTextDelta") turns[params.string("threadId")]?.summary(params.string("delta").orEmpty(),
                    params.string("itemId").orEmpty(), params["summaryIndex"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            "item/commandExecution/outputDelta" -> codingRuns[params.string("threadId")]?.commandProgress(params)
            "item/mcpToolCall/progress" -> codingRuns[params.string("threadId")]?.mcpProgress(params)
            "turn/started" -> {
                val threadId = params.string("threadId") ?: return
                val run = codingRuns[threadId] ?: return
                val turnId = (params["turn"] as? JsonObject)?.string("id") ?: return
                if (run.turnId != null && run.turnId != turnId) { approvalBroker.clearTurn(threadId); questionnaireBroker.clearTurn(threadId) }
                run.turnId = turnId
            }
            "turn/completed" -> {
                val threadId = params.string("threadId") ?: return
                val turn = params["turn"] as? JsonObject
                val error = (turn?.get("error") as? JsonObject)?.string("message")
                turns[threadId]?.finish(error)
                codingRuns[threadId]?.let { run ->
                    val completedTurnId = turn?.string("id")
                    if (run.turnId != null && completedTurnId != null && run.turnId != completedTurnId) return@let
                    // Completion can carry terminal items whose individual notification was lost.
                    (turn?.get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item ->
                        if (run.items.containsKey(item.string("id").orEmpty()) && protocol.terminalToolResult(item) != null) run.completeItem(item)
                    }
                    run.finish(error, interrupted = turn?.string("status") == "interrupted")
                }
                approvalBroker.clearTurn(threadId, turn?.string("id"))
                questionnaireBroker.clearTurn(threadId, turn?.string("id"))
            }
        }
    }

    private suspend fun readStderr(reader: BufferedReader) {
        while (true) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: return
            synchronized(stderrTail) {
                if (stderrTail.size == 8) stderrTail.removeFirst()
                stderrTail.addLast(line)
            }
        }
    }

    private fun failAll(message: String) {
        approvalBroker.clear()
        questionnaireBroker.clear()
        val error = AppServerException(message)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        turns.values.forEach { it.done.completeExceptionally(error) }
        codingRuns.values.forEach { it.finish(message, confirmed = false) }
    }

    private fun stderrMessage(): String = synchronized(stderrTail) {
        stderrTail.lastOrNull()?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    }

    internal class TurnAccumulator(private val onActivity: (io.aequicor.magicpaper.domain.CodingStep) -> Unit) {
        var usage: ((UsageCallResult) -> Unit)? = null
        var usageFingerprint = ""
        var usageRequests = 0L
        private val activity = Channel<Unit>(Channel.CONFLATED)
        private var receivedCharacters = 0
        private val identity = io.aequicor.magicpaper.util.Id.new()
        private val summaries = mutableMapOf<String, IndexedText>()
        private var itemId = ""
        private val messageText = mutableMapOf<String, String>()
        private val completedMessages = mutableSetOf<String>()

        fun heartbeat() { activity.trySend(Unit) }

        suspend fun awaitResult(timeoutSeconds: Int): String {
            if (timeoutSeconds == 0) return done.await()
            while (true) {
                // Each provider event renews the inactivity deadline; completion wins a tie.
                val result = withTimeoutOrNull(timeoutSeconds * 1_000L) {
                    select<Pair<Boolean, String>> {
                        done.onAwait { true to it }
                        activity.onReceive { false to "" }
                    }
                } ?: throw AppServerException("OpenAI Subscription: нет ответа от Codex в течение $timeoutSeconds секунд. Повторите запрос или увеличьте таймаут модели.")
                if (result.first) return result.second
            }
        }

        fun textDelta(delta: String, messageId: String = itemId) {
            if (delta.isEmpty() || messageId in completedMessages) return
            heartbeat()
            receivedCharacters += delta.length
            onActivity(io.aequicor.magicpaper.domain.CodingStep(
                io.aequicor.magicpaper.domain.CodingStepKind.INFO,
                "Модель формирует ответ… Получено $receivedCharacters символов",
                callId = "model-response-progress", running = true,
            ))
            val text = messageText[messageId].orEmpty() + delta
            messageText[messageId] = text
            onActivity(io.aequicor.magicpaper.domain.CodingStep(
                io.aequicor.magicpaper.domain.CodingStepKind.ANSWER, text, callId = messageId, running = true, id = "$identity:answer:$messageId",
            ))
        }
        fun started(item: JsonObject) {
            heartbeat()
            receivedCharacters = 0
            itemId = item.string("id").orEmpty()
            onActivity(io.aequicor.magicpaper.domain.CodingStep(io.aequicor.magicpaper.domain.CodingStepKind.INFO,
                when (item.string("type")) { "reasoning" -> "Модель обдумывает план…"; "agentMessage" -> "Модель формирует ответ…"; else -> "Действие агента: ${item.string("type").orEmpty()}" }))
        }
        fun summary(delta: String, messageId: String = itemId, index: Int = 0) {
            heartbeat()
            val text = summaries.getOrPut(messageId) { IndexedText() }.append(index, delta)
            onActivity(io.aequicor.magicpaper.domain.CodingStep(io.aequicor.magicpaper.domain.CodingStepKind.SUMMARY,
                text, callId = messageId, id = "$identity:summary:$messageId"))
        }
        val done = CompletableDeferred<String>()
        private var last = ""
        private var final = ""

        fun accept(text: String, phase: String?, messageId: String = itemId) {
            heartbeat()
            if (text.isBlank()) return
            last = text
            messageText.remove(messageId)
            completedMessages += messageId
            onActivity(io.aequicor.magicpaper.domain.CodingStep(io.aequicor.magicpaper.domain.CodingStepKind.ANSWER, text, callId = messageId, id = "$identity:answer:$messageId"))
            if (phase == "final_answer" || phase == "finalAnswer") final = text
        }

        fun finish(error: String?) {
            if (done.isCompleted) return
            if (!error.isNullOrBlank()) done.completeExceptionally(AppServerException(error))
            else done.complete(final.ifBlank { last })
        }
    }

    /** Protocol indexes identify independent paragraphs, whose deltas may interleave. */
    private class IndexedText {
        private val parts = sortedMapOf<Int, StringBuilder>()
        fun append(index: Int, delta: String): String {
            parts.getOrPut(index) { StringBuilder() }.append(delta)
            return text
        }
        val text: String get() = parts.values.joinToString("\n\n")
    }

    private class CodingAccumulator(val planning: Boolean = false, val research: Boolean = false,
        private val presentation: NativeToolPresentationResolver = NativeToolPresentationResolver { server, tool, arguments ->
            NativeToolPresentation("$server:$tool", "$tool · ${arguments ?: ""}".take(1500))
        }) {
        private var compactionItemSeen = false
        fun legacyCompacted() { if (!compactionItemSeen) emit(CodingEvent.Compaction(CompactionStatus("legacy:${turnId.orEmpty()}", CompactionPhase.COMPLETED))) }
        private class Reasoning {
            val content = IndexedText()
            val summary = IndexedText()
        }
        private val reasoning = mutableMapOf<String, Reasoning>()
        fun reasoningDelta(params: JsonObject, summary: Boolean) {
            val id = params.string("itemId").orEmpty()
            val delta = params.string("delta").orEmpty()
            if (delta.isEmpty()) return
            if (id.isBlank()) { emit(CodingEvent.ThinkingDelta(delta, summary = summary)); return }
            val fragments = reasoning.getOrPut(id) { Reasoning() }
            val index = params[if (summary) "summaryIndex" else "contentIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val text = (if (summary) fragments.summary else fragments.content).append(index, delta)
            emit(CodingEvent.ThinkingDelta(text, id, replace = true, summary = summary))
        }

        val items = ConcurrentHashMap<String, JsonObject>()
        private val commandOutput = mutableMapOf<String, StringBuilder>()
        val events = Channel<CodingEvent>(Channel.UNLIMITED)
        val done = CompletableDeferred<Unit>()
        @Volatile var confirmed = false
        @Volatile var turnId: String? = null
        @Volatile private var turnCompleted = false
        @Volatile private var closed = false
        private val completedItems = mutableSetOf<String>()
        val waitingForNativeCompletion: Boolean get() = turnCompleted && !closed

        fun emit(event: CodingEvent) {
            events.trySend(event)
        }

        @Synchronized fun startItem(item: JsonObject) {
            val id = item.string("id").orEmpty()
            if (closed || id in completedItems) return
            if (id.isNotBlank()) items[id] = item
            when (item.string("type")) {
                "contextCompaction" -> { compactionItemSeen = true; emit(CodingEvent.Compaction(CompactionStatus(id, CompactionPhase.STARTED))) }
                "agentMessage", "reasoning" -> emit(CodingEvent.MessageStarted)
                "commandExecution" -> emit(
                    CodingEvent.ToolStarted(commandTool(item), item.string("command").orEmpty(), id, isExec = true),
                )
                "fileChange" -> emit(CodingEvent.ToolStarted("edit", fileSummary(item), id))
                "webSearch" -> emit(CodingEvent.ToolStarted("web_search", "", id,
                    category = ToolCategory.SEARCH, title = protocol.webTitle(item)))
                "mcpToolCall" -> {
                    val server = item.string("server").orEmpty()
                    val tool = item.string("tool").orEmpty()
                    val description = presentation.resolve(server, tool, item["arguments"])
                    emit(CodingEvent.ToolStarted(description.name, description.summary, id))
                }
            }
        }

        // App-server classifications affect presentation only; approvals still use the shell command.
        private fun commandTool(item: JsonObject): String {
            val actions = item["commandActions"] as? JsonArray ?: return "command"
            val types = actions.map { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }.distinct()
            return when (types.singleOrNull()) {
                "read" -> "read"
                "listFiles" -> "ls"
                "search" -> "grep"
                else -> "command"
            }
        }

        /** MCP image blocks are structured terminal output, unlike text that merely looks like a path or URL. */
        private fun resultImages(result: JsonObject?): List<CodingImageArtifact> =
            (result?.get("content") as? JsonArray).orEmpty().mapIndexedNotNull { index, block ->
                (block as? JsonObject)?.takeIf { it.string("type") == "image" }?.let { image ->
                    val data = image.string("data").orEmpty()
                    val mime = image.string("mimeType").orEmpty()
                    if (data.isBlank() || !mime.startsWith("image/", ignoreCase = true)) null
                    else CodingImageArtifact(
                        id = image.string("id").orEmpty().ifBlank { "image:$index" },
                        name = image.string("name").orEmpty(),
                        mimeType = mime,
                        sizeBytes = decodedBase64Size(data),
                        dataBase64 = data,
                    )
                }
            }

        private fun decodedBase64Size(data: String): Long =
            ((data.length.toLong() * 3) / 4 - data.takeLastWhile { it == '=' }.length).coerceAtLeast(0)

        fun commandProgress(params: JsonObject) {
            val id = params.string("itemId").orEmpty()
            val item = items[id]?.takeIf { it.string("type") == "commandExecution" } ?: return
            val output = commandOutput.getOrPut(id) { StringBuilder() }.append(params.string("delta").orEmpty())
            emit(CodingEvent.ToolProgress(commandTool(item), id, output.toString()))
        }

        fun mcpProgress(params: JsonObject) {
            val id = params.string("itemId").orEmpty()
            val item = items[id]?.takeIf { it.string("type") == "mcpToolCall" } ?: return
            val name = if (item.string("server") == "magicpaper_computer") "computer" else "${item.string("server")}:${item.string("tool")}"
            emit(CodingEvent.ToolProgress(name, id, params.string("message").orEmpty()))
        }

        @Synchronized fun completeItem(item: JsonObject) {
            val id = item.string("id").orEmpty()
            val terminal = if (item.string("type") in setOf("commandExecution", "fileChange", "mcpToolCall"))
                protocol.terminalToolResult(item) ?: return else null
            if (closed || id.isNotBlank() && !completedItems.add(id)) return
            val started = items.remove(id)
            commandOutput.remove(id)
            when (item.string("type")) {
                "contextCompaction" -> { compactionItemSeen = true; emit(CodingEvent.Compaction(CompactionStatus(id, CompactionPhase.COMPLETED))) }
                "webSearch" -> {
                    val failed = item.string("status") in listOf("failed", "declined", "cancelled") ||
                        item["error"]?.let { it != JsonNull } == true
                    val error = (item["error"] as? JsonObject)?.string("message")
                        ?: (item["error"] as? JsonPrimitive)?.contentOrNull
                    // WebSearch items expose action metadata, not the page body or search results.
                    emit(CodingEvent.ToolFinished("web_search", failed, id,
                        resultPreview = if (failed) error?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить веб-операцию."
                            else "Операция завершена. Результат не передан в историю.",
                        title = protocol.webTitle(item),
                        sources = if (failed) emptyList() else listOfNotNull(
                            (item["action"] as? JsonObject)?.takeIf { it.string("type") == "openPage" }
                                ?.string("url")?.let(::researchUrl)?.let { SearchHit(it, it) })))
                    val action = item["action"] as? JsonObject
                    val content = action?.string("type") == "openPage"
                    if (!failed && action?.string("type") in listOf("search", "openPage")) emit(CodingEvent.SearchObserved(id,
                        pages = if (content) 1 else 0, content = content,
                        requests = (action?.get("queries") as? JsonArray)?.size?.toLong()?.coerceAtLeast(1) ?: 1))
                }
                "agentMessage" -> item.string("text")?.takeIf { it.isNotBlank() }?.let { emit(CodingEvent.FinalText(it, id)) }
                "mcpToolCall" -> {
                    val result = item["result"] as? JsonObject
                    emit(CodingEvent.ToolFinished(if (item.string("server") == "magicpaper_computer") "computer" else "${item.string("server")}:${item.string("tool")}",
                        isError = checkNotNull(terminal).isError,
                        callId = id,
                        phase = terminal.phase,
                        resultPreview = (result?.get("content") as? JsonArray).orEmpty().mapNotNull { block ->
                            (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")
                        }.joinToString("\n").ifBlank { (item["error"] as? JsonObject)?.string("message").orEmpty() }.take(2000),
                        images = resultImages(result),
                    ))
                }
                "reasoning" -> {
                    fun text(field: String) = (item[field] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n\n")
                    val streamed = reasoning.remove(id)
                    val summary = text("summary").ifBlank { streamed?.summary?.text.orEmpty() }
                    val content = if (planning) "" else text("content").ifBlank { streamed?.content?.text.orEmpty() }
                    if (summary.isNotBlank()) emit(CodingEvent.FinalThinking(summary, id, summary = true))
                    if (content.isNotBlank()) emit(CodingEvent.FinalThinking(content, id))
                }
                // Keep the start identity even if the final item omits or revises its actions.
                "commandExecution" -> emit(checkNotNull(terminal).copy(tool = commandTool(started ?: item)))
                "fileChange" -> emit(
                    CodingEvent.ToolFinished(
                        tool = "edit",
                        isError = checkNotNull(terminal).isError,
                        callId = id,
                        phase = terminal.phase,
                        resultPreview = fileSummary(item),
                    ),
                )
            }
            if (turnCompleted) finish(null)
        }

        @Synchronized fun finish(error: String?, confirmed: Boolean = true, interrupted: Boolean = false) {
            if (closed) return
            // A native background command can outlive the model's final message. Keep its client,
            // process ownership and event collector alive until the actual tool result arrives.
            if (confirmed && error.isNullOrBlank() && !interrupted) {
                turnCompleted = true
                if (items.values.any { it.string("type") in setOf("commandExecution", "fileChange", "mcpToolCall", "webSearch") }) return
            }
            closed = true
            items.values.filter { it.string("type") == "contextCompaction" }.forEach {
                emit(CodingEvent.Compaction(CompactionStatus(it.string("id").orEmpty(),
                    if (error.isNullOrBlank()) CompactionPhase.CANCELLED else CompactionPhase.FAILED)))
            }
            items.clear()
            commandOutput.clear()
            this.confirmed = confirmed
            if (!error.isNullOrBlank()) emit(CodingEvent.Failed(error))
            emit(CodingEvent.AgentEnd)
            emit(CodingEvent.Finished)
            events.close()
            if (confirmed) done.complete(Unit) else done.completeExceptionally(AppServerException(error ?: "Соединение прервано"))
        }

        private fun fileSummary(item: JsonObject): String =
            (item["changes"] as? JsonArray).orEmpty().joinToString { change ->
                val obj = change as? JsonObject
                obj?.string("path") ?: obj?.string("filePath") ?: "файл"
            }
    }

    private data class LoginEvent(val loginId: String?, val success: Boolean, val error: String?)
    private class AppServerException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

    internal companion object {
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1_000L
        fun defaultAppHome(): Path = Paths.get(System.getProperty("user.home"), ".MagicPaper", "codex")

        internal fun resolveCodexCommand(
            override: String?,
            macAppRoots: List<Path> = defaultMacAppRoots(),
        ): String {
            override?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val local = System.getenv("LOCALAPPDATA")?.let { Paths.get(it, "OpenAI", "Codex", "bin") }
            if (local != null && Files.isDirectory(local)) {
                Files.list(local).use { entries ->
                    entries.filter { Files.isDirectory(it) }
                        .map { it.resolve("codex.exe") }
                        .filter { Files.isRegularFile(it) }
                        .sorted(Comparator.reverseOrder())
                        .findFirst()
                        .orElse(null)
                        ?.let { return it.toAbsolutePath().toString() }
                }
            }
            macAppRoots.asSequence()
                .mapNotNull(::findCodexInAppBundle)
                .firstOrNull()
                ?.let { return it.toAbsolutePath().toString() }
            if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                listOf(
                    Paths.get("/opt/homebrew/bin/codex"),
                    Paths.get("/usr/local/bin/codex"),
                    Paths.get(System.getProperty("user.home"), ".local", "bin", "codex"),
                    Paths.get(System.getProperty("user.home"), ".codex", "bin", "codex"),
                ).firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
                    ?.let { return it.toAbsolutePath().toString() }
            }
            return if (File.separatorChar == '\\') "codex.exe" else "codex"
        }

        private fun defaultMacAppRoots(): List<Path> {
            if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return emptyList()
            val home = Paths.get(System.getProperty("user.home"))
            // Codex can be installed as a standalone app or bundled with the
            // ChatGPT desktop app. Both distribute the same app-server CLI.
            return listOf(
                Paths.get("/Applications/Codex.app"),
                home.resolve("Applications/Codex.app"),
                Paths.get("/Applications/ChatGPT.app"),
                home.resolve("Applications/ChatGPT.app"),
            )
        }

        /**
         * The Codex or ChatGPT desktop app intentionally does not have to add
         * its CLI to PATH.
         * Locate the bundled executable instead, including versioned resource
         * directories whose exact layout changes between Codex releases.
         */
        private fun findCodexInAppBundle(app: Path): Path? {
            if (!Files.isDirectory(app)) return null
            return runCatching {
                Files.walk(app, 12).use { paths ->
                    paths.filter { candidate ->
                        candidate.fileName.toString() == "codex" &&
                            Files.isRegularFile(candidate) && Files.isExecutable(candidate)
                    }.findFirst().orElse(null)
                }
            }.getOrNull()
        }
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requireString(name: String): String = string(name) ?: error("В ответе Codex нет поля $name.")
private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

/**
 * Effective context windows from Codex's own model cache. `model/list` deliberately omits
 * these fields, while `models_cache.json` is the same app-server's versioned provider fact.
 * Pi needs the effective window, not the larger experimental maximum, so its compaction and
 * usage denominator match native Codex (`context_window * effective_context_window_percent`).
 */
internal fun codexCachedContextWindows(json: Json, body: String): Map<String, Int> {
    val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
    val models = root["models"] as? JsonArray ?: return emptyMap()
    return buildMap {
        models.forEach { element ->
            val model = element as? JsonObject ?: return@forEach
            val id = model.string("slug")?.takeIf { it.isNotBlank() } ?: return@forEach
            val nominal = model["context_window"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it > 0 } ?: return@forEach
            val percent = model["effective_context_window_percent"]?.jsonPrimitive?.intOrNull
                ?.takeIf { it in 1..100 } ?: 100
            val effective = (nominal.toLong() * percent / 100L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (effective > 0) put(id, effective)
        }
    }
}

private val protocol = CodexNativeAdapter()
private fun JsonObject.count(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
private fun codexUsage(usage: JsonObject) = TokenUsage(
    usage.count("inputTokens")?.let { (it - (usage.count("cachedInputTokens") ?: 0)).coerceAtLeast(0) },
    usage.count("outputTokens"), usage.count("cachedInputTokens"), reasoning = usage.count("reasoningOutputTokens"), total = usage.count("totalTokens"))
