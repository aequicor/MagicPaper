package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.RuntimeQuestionnaires
import io.aequicor.magicpaper.domain.PlanningAnswer
import io.aequicor.magicpaper.data.research.ResearchCheckBridge
import io.aequicor.magicpaper.domain.forPendingRun
import io.aequicor.magicpaper.domain.tools.*
import io.aequicor.magicpaper.data.tools.AgentToolBridge
import io.aequicor.magicpaper.data.questionnaire.QuestionnaireBridge
import io.aequicor.magicpaper.data.questionnaire.QuestionnaireTool
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
import io.aequicor.magicpaper.domain.OpenAiSubscriptionService
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
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
class CodexAppServerOpenAiSubscription(
    private val json: Json,
    private val appHome: Path = defaultAppHome(),
    private val commandOverride: String? = System.getenv("MAGICPAPER_CODEX_PATH"),
    val computerUse: io.aequicor.magicpaper.data.computer.DesktopComputerUse? = null,
    private val ownsComputerUse: Boolean = true,
    private val sharedQuestionnaires: RuntimeQuestionnaires? = null,
) : OpenAiSubscriptionService {
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
    private val codingThreads = ConcurrentHashMap.newKeySet<String>()
    private val approvalBroker = CodexApprovalBroker { threadId, message ->
        codingRuns[threadId]?.emit(CodingEvent.Notice(message))
    }
    val codingApprovals = approvalBroker.requests
    private val questionnaireRegistry = sharedQuestionnaires ?: RuntimeQuestionnaires(
        io.aequicor.magicpaper.data.coding.FileRuntimeQuestionnaireStore(appHome.resolve("questionnaires").toFile()))
    val codingQuestionnaires = questionnaireRegistry.requests
    suspend fun respondCodingQuestionnaire(id: String, answers: List<PlanningAnswer>) { questionnaireRegistry.respond(id, answers) }
    private val questionnaireBroker = CodexQuestionnaireBroker(scope, questionnaireRegistry,
        { threadId, message -> codingRuns[threadId]?.emit(CodingEvent.Notice(message)) },
        { threadId, error -> codingRuns[threadId]?.done?.completeExceptionally(error) })


    suspend fun respondCodingApproval(id: String, decision: io.aequicor.magicpaper.domain.CodingApprovalDecision) =
        approvalBroker.respond(id, decision)
    private val stderrTail = ArrayDeque<String>()
    private val ownedCoding = io.aequicor.magicpaper.data.coding.OwnedCodingProcess(appHome.resolve("coding-processes").toFile())
    internal suspend fun readCodingToolResults(threadId: String, callIds: Set<String>): List<CodingEvent.ToolFinished> {
        if (callIds.isEmpty()) return emptyList()
        val response = request("thread/read", buildJsonObject { put("threadId", threadId); put("includeTurns", true) }).jsonObject
        return CodexNativeToolResults.read(response, threadId, callIds)
    }
    suspend fun reconcileCoding(sessionId: String) = withContext(Dispatchers.IO) {
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

    internal fun newCodingClient() = CodexAppServerOpenAiSubscription(json, appHome, commandOverride, computerUse,
        ownsComputerUse = false, sharedQuestionnaires = questionnaireRegistry)

    private val tokenMutex = Mutex()
    /** Refresh ownership remains with app-server; pi receives only the current access token. */
    internal suspend fun subscriptionAccessToken(): String = tokenMutex.withLock {
        val response = request("account/read", buildJsonObject { put("refreshToken", true) }).jsonObject
        check((response["account"] as? JsonObject)?.string("type") == "chatgpt") { "Войдите в ChatGPT в настройках движков" }
        withContext(Dispatchers.IO) {
            val auth = json.parseToJsonElement(Files.readString(appHome.resolve("auth.json"))).jsonObject
            (auth["tokens"] as? JsonObject)?.string("access_token")?.takeIf { it.isNotBlank() }
                ?: error("Не удалось получить доступ к подписке. Повторите вход в ChatGPT.")
        }
    }
    suspend fun runtimeStatus(): io.aequicor.magicpaper.domain.RuntimeStatus = try {
        ensureStarted()
        io.aequicor.magicpaper.domain.RuntimeStatus(io.aequicor.magicpaper.domain.RuntimePhase.READY,
            "Codex app-server: ${resolveCodexCommand(commandOverride)}")
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (e: Exception) { io.aequicor.magicpaper.domain.RuntimeStatus(io.aequicor.magicpaper.domain.RuntimePhase.ERROR, e.message.orEmpty()) }

    override suspend fun account(refreshToken: Boolean): OpenAiSubscriptionAccount {
        val response = request("account/read", buildJsonObject { put("refreshToken", refreshToken) }).jsonObject
        val account = response["account"] as? JsonObject
        if (account?.string("type") != "chatgpt") return OpenAiSubscriptionAccount(signedIn = false)
        val limits = runCatching { readRateLimits() }.getOrDefault(emptyList())
        return OpenAiSubscriptionAccount(
            signedIn = true,
            email = account.string("email"),
            planType = account.string("planType"),
            rateLimits = limits,
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
        return ModelDefaults.discover(ProviderType.OPENAI_SUBSCRIPTION, ids, declarations).map { it.copy(metadata = metadata[it.id]) }
    }

    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = completeWithActivity(profile, messages) {}

    override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (io.aequicor.magicpaper.domain.CodingStep) -> Unit): String {
        require(profile.provider == ProviderType.OPENAI_SUBSCRIPTION) {
            "Этот транспорт принимает только OpenAI по подписке ChatGPT."
        }
        check(account().signedIn) { "Сначала войдите в ChatGPT в настройках источника." }
        val system = messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content }
            .ifBlank { profile.advanced.systemPromptOverride }
        val thread = request(
            "thread/start",
            buildJsonObject {
                put("ephemeral", true)
                put("model", profile.modelId)
                put("approvalPolicy", "never")
                put("sandbox", "read-only")
                put("serviceName", "MagicPaper")
                put("baseInstructions", CHAT_INSTRUCTIONS)
                if (system.isNotBlank()) put("developerInstructions", system)
            },
        ).jsonObject
        val threadId = thread["thread"]?.jsonObject?.requireString("id")
            ?: error("Codex не вернул идентификатор диалога.")
        val accumulator = TurnAccumulator(onActivity).also { it.usage = kotlinx.coroutines.currentCoroutineContext()[UsageCall] }
        turns[threadId] = accumulator
        var turnId: String? = null
        try {
            val input = buildTurnInput(messages, profile.advanced.contextMessages)
            val capability = ModelDefaults.capability(profile)
            val effort = profile.resolveEffort(capability).level?.wire
            val started = request(
                "turn/start",
                buildJsonObject {
                    put("threadId", threadId)
                    put("input", input)
                    put("model", profile.modelId)
                    effort?.let { put("effort", it) }
                    // Request readable summaries explicitly instead of inheriting a disabled default.
                    put("summary", "auto")
                    put("approvalPolicy", "never")
                    put("sandboxPolicy", buildJsonObject { put("type", "readOnly") })
                },
            ).jsonObject
            turnId = (started["turn"] as? JsonObject)?.string("id")
            val timeout = profile.advanced.safeTimeoutSeconds
            val text = accumulator.awaitResult(timeout)
            return text.ifBlank { error("OpenAI вернул пустой ответ.") }
        } finally {
            turns.remove(threadId)
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    if (!accumulator.done.isCompleted && turnId != null) runCatching {
                        request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) })
                    }
                    runCatching { request("thread/unsubscribe", buildJsonObject { put("threadId", threadId) }) }
                }
            }
        }
    }

    /** Полноценный coding-прогон Codex с инструментами в папке проекта. */
    fun runCoding(
        project: CodingProject,
        session: CodingSession,
        prompt: String,
        profile: LlmProfile,
        attachments: List<io.aequicor.magicpaper.domain.Attachment>,
        modelProvider: String = "openai",
        providerConfig: JsonObject = JsonObject(emptyMap()),
        planning: Boolean = false,
    ): Flow<CodingEvent> = channelFlow {
        val research = !planning && session.researchMode
        val restricted = planning || research
        var confirmedFinished = false
        var researchBridge: ResearchCheckBridge? = null
        var computerBridge: io.aequicor.magicpaper.data.computer.ComputerUseBridge? = null
        var questionnaireBridge: QuestionnaireBridge? = null
        var agentBridge: AgentToolBridge? = null
        try {
            session.forPendingRun()
            require(planning || !session.planningMode) { "Используйте защищённый маршрут планирования" }
            check(File(project.path).isDirectory) { "Папка проекта недоступна: ${project.path}" }
            check(profile.configured) { "Не настроено подключение модели." }
            if (profile.provider == ProviderType.OPENAI_SUBSCRIPTION) check(account().signedIn) { "Сначала войдите в ChatGPT в настройках источника." }
            val codingProfile = if (planning) profile.forModel() else profile.forCoding()
            val permissions = if (restricted) null else CodexCodingPermissions(Paths.get(project.path))
            fun JsonObjectBuilder.applyApprovals() {
                if (permissions != null) with(permissions) { approvals() } else with(CodexPlanningPermissions) { approvals() }
            }
            computerBridge = if (restricted) null else computerUse?.bridge(session.id)
            val baseConfig = io.aequicor.magicpaper.data.computer.ComputerUseBridge.codexConfig(
                if (restricted) JsonObject(providerConfig + CodexPlanningPermissions.threadConfig()) else JsonObject(permissions!!.threadConfig() + providerConfig), computerBridge)
            val agentTools = kotlinx.coroutines.currentCoroutineContext()[ToolSession]
            agentBridge = agentTools?.let { AgentToolBridge(it) }
            questionnaireBridge = if (planning || agentTools != null) null else QuestionnaireBridge(questionnaireRegistry, session)
            researchBridge = if (research) ResearchCheckBridge(session, project) else null
            val questionnaireConfig = questionnaireBridge?.codexConfig(baseConfig) ?: baseConfig
            val researchConfig = researchBridge?.codexConfig(questionnaireConfig) ?: questionnaireConfig
            val threadConfig = agentBridge?.codexConfig(researchConfig) ?: researchConfig
            val instructions = io.aequicor.magicpaper.data.coding.codingSystemPrompt(
                io.aequicor.magicpaper.domain.CodingEngine.CODEX, planning, codingProfile.advanced.systemPromptOverride, research, session.planningRulesSnapshot)
            val resumed = session.piSessionId.takeIf { !planning && it.isNotBlank() }?.let { oldId ->
                if ((research || computerUse != null) && oldId in codingThreads) {
                    request("thread/unsubscribe", buildJsonObject { put("threadId", oldId) })
                    codingThreads.remove(oldId)
                }
                request(
                    "thread/resume",
                    buildJsonObject {
                        put("threadId", oldId)
                        put("cwd", project.path)
                        put("model", codingProfile.modelId)
                        applyApprovals()
                        put("sandbox", if (restricted) "read-only" else "workspace-write")
                        put("modelProvider", modelProvider)
                        put("config", threadConfig)
                        put("developerInstructions", instructions)
                    },
                ).jsonObject["thread"]?.jsonObject?.requireString("id")
                    ?: error("Codex не восстановил coding-сессию.")
            }
            val threadId = resumed ?: request(
                "thread/start",
                buildJsonObject {
                    put("cwd", project.path)
                    put("model", codingProfile.modelId)
                    applyApprovals()
                    put("sandbox", if (restricted) "read-only" else "workspace-write")
                    put("modelProvider", modelProvider)
                    put("config", threadConfig)
                    put("serviceName", if (planning) "MagicPaper Planning" else if (research) "MagicPaper Research" else "MagicPaper Coding")
                    if (restricted) put("baseInstructions", if (research) io.aequicor.magicpaper.domain.RESEARCH_INSTRUCTIONS else io.aequicor.magicpaper.domain.PLANNING_INSTRUCTIONS)
                    put("developerInstructions", instructions)
                },
            ).jsonObject["thread"]?.jsonObject?.requireString("id")
                ?: error("Codex не вернул идентификатор coding-сессии.")
            codingThreads.add(threadId)
            send(CodingEvent.SessionStarted(threadId))
            val accumulator = CodingAccumulator(planning, research)
            approvalBroker.clearTurn(threadId)
            questionnaireBroker.clearTurn(threadId)
            codingRuns[threadId] = accumulator
            codingSessions[session.id] = threadId
            codingContexts[threadId] = session
            ownedCoding.record(session.id, process ?: error("Codex process unavailable"))
            val effort = codingProfile.resolveEffort(ModelDefaults.capability(codingProfile)).level?.wire
            val turn = request(
                "turn/start",
                buildJsonObject {
                    put("threadId", threadId)
                    put("input", buildCodingInput(prompt, attachments))
                    put("model", codingProfile.modelId)
                    effort?.let { put("effort", it) }
                    // Request readable summaries explicitly instead of inheriting a disabled default.
                    put("summary", "auto")
                    applyApprovals()
                    put("cwd", project.path)
                    put("sandboxPolicy", permissions?.sandboxPolicy() ?: CodexPlanningPermissions.sandboxPolicy())
                },
            ).jsonObject
            accumulator.turnId = turn["turn"]?.jsonObject?.string("id")
            for (event in accumulator.events) {
                if (event is CodingEvent.Finished) confirmedFinished = accumulator.confirmed
                send(event)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            abortCoding(session.id)
            throw error
        } catch (error: Throwable) {
            send(CodingEvent.Failed(error.message ?: "Codex coding завершился с ошибкой."))
            send(CodingEvent.Finished)
        } finally {
            researchBridge?.close()
            agentBridge?.close()
            questionnaireBridge?.close()
            computerBridge?.close()
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

    fun abortCoding(sessionId: String) {
        io.aequicor.magicpaper.data.research.ResearchCheckRunner.shared.abort(sessionId)
        computerUse?.disable(sessionId)
        val threadId = codingSessions[sessionId] ?: return
        val run = codingRuns[threadId] ?: return
        approvalBroker.clearTurn(threadId)
        questionnaireBroker.clearTurn(threadId)
        val turnId = run.turnId ?: return
        scope.launch {
            runCatching {
                request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) })
            }
        }
    }

    fun abortAllCoding() = codingSessions.keys.toList().forEach(::abortCoding)

    override fun close() {
        if (ownsComputerUse) computerUse?.disable()
        approvalBroker.clear()
        questionnaireBroker.clear()
        pending.values.forEach { it.cancel() }
        turns.values.forEach { it.done.cancel() }
        codingRuns.values.forEach { it.events.close() }
        try {
            process?.let { owned ->
                // Keep the parent alive until its recorded descendants have stopped, so a
                // failed cleanup retains ancestry for durable recovery on macOS/Linux too.
                val children = owned.descendants().use { it.toList() }
                children.asReversed().forEach { it.destroyForcibly() }
                children.forEach { if (it.isAlive) it.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS) }
                owned.destroyForcibly()
                check(owned.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "Не удалось остановить процесс Codex" }
            }
        } finally { scope.cancel() }
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

    private fun buildTurnInput(messages: List<LlmMessage>, contextMessages: Int): JsonArray = buildJsonArray {
        val conversational = messages.filter { it.role != LlmChatRole.SYSTEM }
        val history = conversational.takeLast(contextMessages.coerceIn(1, 100))
        val transcript = history.joinToString("\n\n") { message ->
            val role = if (message.role == LlmChatRole.USER) "Пользователь" else "Ассистент"
            buildString {
                append(role).append(": ").append(message.content)
                message.attachments.filter { it.kind == AttachmentKind.TEXT }.forEach { attachment ->
                    append("\n\nФайл ").append(attachment.name).append(":\n").append(attachment.decodeText())
                }
            }
        }
        add(buildJsonObject { put("type", "text"); put("text", transcript) })
        conversational.lastOrNull()?.attachments
            ?.filter { it.kind == AttachmentKind.IMAGE }
            ?.forEach { attachment ->
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("url", "data:${attachment.mimeType};base64,${attachment.dataBase64}")
                    },
                )
            }
    }

    private fun buildCodingInput(
        prompt: String,
        attachments: List<io.aequicor.magicpaper.domain.Attachment>,
    ): JsonArray = buildJsonArray {
        val text = buildString {
            append(prompt)
            attachments.filter { it.kind == AttachmentKind.TEXT }.forEach { attachment ->
                append("\n\nВложение ").append(attachment.name).append(":\n").append(attachment.decodeText())
            }
            val binary = attachments.filter { it.kind == AttachmentKind.FILE }
            if (binary.isNotEmpty()) {
                append("\n\nБинарные вложения не переданы: ").append(binary.joinToString { it.name })
            }
        }
        add(buildJsonObject { put("type", "text"); put("text", text) })
        attachments.filter { it.kind == AttachmentKind.IMAGE }.forEach { attachment ->
            add(buildJsonObject {
                put("type", "image")
                put("url", "data:${attachment.mimeType};base64,${attachment.dataBase64}")
            })
        }
    }

    private suspend fun request(method: String, params: JsonElement): JsonElement {
        ensureStarted()
        return requestStarted(method, params)
    }

    private suspend fun requestStarted(method: String, params: JsonElement): JsonElement {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) })
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
        val created = runCatching {
            withContext(Dispatchers.IO) {
                ProcessBuilder(command, "app-server")
                    .directory(appHome.toFile())
                    .apply { environment()["CODEX_HOME"] = appHome.toAbsolutePath().toString() }
                    .start()
            }
        }.getOrElse { cause ->
            throw AppServerException(
                "Не найден Codex app-server. Установите Codex desktop/CLI или задайте " +
                    "MAGICPAPER_CODEX_PATH. ${cause.message}",
            )
        }
        process = created
        writer = created.outputStream.bufferedWriter()
        scope.launch { readStdout(created.inputStream.bufferedReader()) }
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

    private suspend fun readStdout(reader: BufferedReader) {
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
            process = null
            writer = null
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
                val totals = UsageParsing.codex(total)
                val latest = UsageParsing.codex(last)
                val window = usage.count("modelContextWindow")
                codingRuns[threadId]?.let { run ->
                    if (run.turnId != null) {
                        run.emit(CodingEvent.UsageObserved(latest, fingerprint, cumulative = totals))
                        run.emit(CodingEvent.ContextUpdated(latest.totalTokens, window))
                    }
                }
                turns[threadId]?.let { run ->
                    if (run.usageFingerprint != fingerprint) { run.usageRequests++; run.usageFingerprint = fingerprint }
                    run.usage?.result?.value = UsageCallResult(totals, contextTokens = latest.totalTokens,
                        contextLimit = window, requests = run.usageRequests.coerceAtLeast(1))
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
                        if (run.items.containsKey(item.string("id").orEmpty()) && CodexNativeToolResults.terminal(item) != null) run.completeItem(item)
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
        var usage: UsageCall? = null
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

    private class CodingAccumulator(val planning: Boolean = false, val research: Boolean = false) {
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
                    category = ToolCategory.SEARCH, title = codexWebTitle(item)))
                "mcpToolCall" -> {
                    val server = item.string("server").orEmpty()
                    val tool = item.string("tool").orEmpty()
                    val name = if (server == "magicpaper_computer") "computer" else "$server:$tool"
                    val summary = if (server == "magicpaper_computer") io.aequicor.magicpaper.data.computer.ComputerTool.label(
                        (item["arguments"] as? JsonObject)?.string("action").orEmpty()) else "$tool · ${item["arguments"] ?: ""}".take(1500)
                    emit(CodingEvent.ToolStarted(name, summary, id))
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
                CodexNativeToolResults.terminal(item) ?: return else null
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
                        title = codexWebTitle(item)))
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
    private class AppServerException(message: String) : IllegalStateException(message)

    internal companion object {
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1_000L
        const val CHAT_INSTRUCTIONS =
            "Ты отвечаешь в чате MagicPaper. Не используй инструменты, файлы или команды. Верни только полезный ответ пользователю."
        const val CODING_INSTRUCTIONS =
            "Ты coding-агент MagicPaper. Изменяй исходники внутри открытой папки проекта, выполняй задачу до результата и кратко сообщи итог. " +
                "Для сборки используй установленный toolchain и обычный кеш Gradle (GRADLE_USER_HOME из окружения или ~/.gradle); " +
                "не переноси кеш в проект ради обхода ограничений и не добавляй --offline без необходимости. " +
                "Если сборке нужны сеть, зависимости или доступ за пределами песочницы, запроси разрешение штатным механизмом Codex: " +
                "Codex автоматически проверит запрос; если движок передаст его пользователю, MagicPaper покажет операцию и причину для подтверждения. " +
                "Не останавливайся после первого отказа песочницы. Дождись решения через штатный механизм подтверждения. " +
                "Если автоматическая проверка или пользователь отклонили действие, не обходи отказ; выбери безопасный вариант или сообщи ограничение."

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
