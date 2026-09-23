package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.data.coding.*
import io.aequicor.magicpaper.domain.browser.BrowserSessions
import io.aequicor.magicpaper.domain.checks.CommandChecks
import io.aequicor.magicpaper.data.coding.OwnedCodingProcess
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.tools.ToolSession
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.*

/** Host facade: subscription/usage integration and application enrichment stay outside native adapters. */
class CodexAppServerOpenAiSubscription(
    private val json: Json,
    private val appHome: Path = defaultAppHome(),
    private val commandOverride: String? = System.getenv("MAGICPAPER_CODEX_PATH"),
    val computerUse: NativeComputerUse? = null,
    private val ownsComputerUse: Boolean = true,
    private val sharedQuestionnaires: RuntimeQuestionnaireService? = null,
    private val secretStore: io.aequicor.magicpaper.data.storage.SecretStore =
        io.aequicor.magicpaper.data.storage.CodexAuthSecretStore(authFile = appHome.resolve("auth.json").toFile()),
    internal val browser: BrowserSessions,
    internal val checks: CommandChecks,
    private val questionnaireFactory: RuntimeQuestionnaireFactory,
    private val journal: io.aequicor.magicpaper.data.storage.EventJournal,
    sharedProviderLibrary: NativeProviderLibrary? = null,
) : OpenAiSubscriptionService, AutoCloseable {
    private val ownsProviderLibrary = sharedProviderLibrary == null
    internal val providerLibrary = sharedProviderLibrary ?: createNativeProviderLibrary(appHome.resolveSibling("coding").toString(),
        nativeResources, OwnedCodingProcess(appHome.resolveSibling("coding").resolve("provider-processes").toFile()), nativeDiagnostics,
        NativeLifecycleJournalAdapter(journal, appHome.resolveSibling("coding").resolve("provider-processes").toString()))
    private val authSecrets = io.aequicor.magicpaper.data.storage.CodexAuthSecretStore(secretStore, appHome.resolve("auth.json").toFile())
    internal val questionnaireRegistry = sharedQuestionnaires ?: questionnaireFactory.create("native-codex:${appHome.resolve("questionnaires").toAbsolutePath()}",
        io.aequicor.magicpaper.data.coding.FileRuntimeQuestionnaireStore(appHome.resolve("questionnaires").toFile()))
    private val native: NativeSubscriptionAccess = checkNotNull(createNativeSubscriptionAccess(CodingEngine.CODEX,
        NativeSubscriptionEnvironment(json, appHome.toAbsolutePath().toString(), commandOverride,
            processes = OwnedCodingProcess(appHome.resolve("coding-processes").toFile()),
            accessTokens = NativeAuthTokens { cachedAccessToken() },
            questionnaires = questionnaireRegistry.asNativeQuestionnaires(),
            diagnostics = io.aequicor.magicpaper.data.coding.nativeDiagnostics,
            toolPresentation = NativeToolPresentationResolver { server, tool, arguments ->
                if (server == "magicpaper_computer") NativeToolPresentation("computer",
                    io.aequicor.magicpaper.data.computer.ComputerTool.label((arguments as? JsonObject)?.get("action")?.jsonPrimitive?.contentOrNull.orEmpty()))
                else NativeToolPresentation("$server:$tool", "$tool · ${arguments ?: ""}".take(1500))
            }))) { "Codex не установлен в этой сборке" }

    val codingQuestionnaires = questionnaireRegistry.requests
    suspend fun respondCodingQuestionnaire(id: String, answers: List<PlanningAnswer>) = questionnaireRegistry.respond(id, answers)
    internal suspend fun subscriptionAccessToken() = native.subscriptionAccessToken()
    internal fun withCachedContextWindow(profile: LlmProfile) = native.withCachedContextWindow(profile)
    internal fun newCodingClient() = CodexAppServerOpenAiSubscription(json, appHome, commandOverride, computerUse,
        ownsComputerUse = false, sharedQuestionnaires = questionnaireRegistry, secretStore = secretStore,
        browser = browser, checks = checks, questionnaireFactory = questionnaireFactory, journal = journal,
        sharedProviderLibrary = providerLibrary)

    override suspend fun account(refreshToken: Boolean) = native.account(refreshToken)
    override suspend fun startLogin() = native.startLogin()
    override suspend fun awaitLogin(loginId: String) = native.awaitLogin(loginId)
    override suspend fun cancelLogin(loginId: String) = native.cancelLogin(loginId)
    override suspend fun logout() = native.logout()
    override suspend fun models(profile: LlmProfile) = native.models(profile)
    private val providerTurns = SubscriptionProviderTurns(providerLibrary) { subscriptionAccessToken() }
    override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>): LlmToolTurn = providerTurns.turn(withCachedContextWindow(profile), messages, tools, exchanges)
    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = completeWithActivity(profile, messages) {}
    override suspend fun completeWithActivity(profile: LlmProfile, messages: List<LlmMessage>, onActivity: (CodingStep) -> Unit): String {
        require(profile.provider == ProviderType.OPENAI_SUBSCRIPTION) { "Этот транспорт принимает только OpenAI по подписке ChatGPT." }
        val system = messages.filter { it.role == LlmChatRole.SYSTEM }.joinToString("\n\n") { it.content }
            .ifBlank { profile.advanced.systemPromptOverride }
        val usage = currentCoroutineContext()[UsageCall]
        return native.complete(NativeCompletionRequest(profile.modelId, system, CHAT_INSTRUCTIONS,
            buildTurnInput(messages, profile.advanced.contextMessages), profile.resolveEffort(ModelDefaults.capability(profile)).level?.wire,
            profile.advanced.safeTimeoutSeconds), onActivity) { usage?.result?.value = it }
    }

    internal suspend fun cachedAccessToken(): String? = authSecrets.read(io.aequicor.magicpaper.data.storage.SecretReferences.CODEX_AUTH)?.let { raw ->
        (json.parseToJsonElement(raw).jsonObject["tokens"] as? JsonObject)?.get("access_token")?.jsonPrimitive?.contentOrNull
    }
    private val codingBinding = lazy {
        NativeHostEnvironment(json, journal, appHome.toAbsolutePath().parent.toFile(), providerLibrary, computerUse, browser, checks,
            questionnaireFactory, NativeAuthTokens { cachedAccessToken() }, NativeAuthTokens { subscriptionAccessToken() },
            { check(account().signedIn) { "Войдите в ChatGPT в настройках движков" } }, ::withCachedContextWindow,
            rootOverride = appHome.toFile(), commandOverride = commandOverride).create(CodingEngine.CODEX)
    }
    val binding get() = codingBinding.value
    fun runCoding(project: CodingProject, session: CodingSession, prompt: String, profile: LlmProfile,
        attachments: List<Attachment>, planning: Boolean = false): Flow<CodingEvent> =
        if (planning) binding.runtime.runPlanning(project, session, prompt, profile)
        else binding.runtime.run(project, session, prompt, profile, attachments)

    fun abortCoding(sessionId: String) {
        checks.abort(sessionId)
        computerUse?.disable(sessionId)
        if (codingBinding.isInitialized()) binding.runtime.abort(sessionId)
    }
    fun abortAllCoding() {
        checks.abortAll()
        computerUse?.disable()
        if (codingBinding.isInitialized()) binding.runtime.abortAll()
    }
    override fun close() {
        var failure: Throwable? = null
        try { if (ownsComputerUse) computerUse?.disable() } catch (error: Throwable) { failure = error }
        try { native.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try { if (codingBinding.isInitialized()) binding.closeNative() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try { if (ownsProviderLibrary) providerLibrary.close() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
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

    internal companion object {
        const val CHAT_INSTRUCTIONS =
            "Ты отвечаешь в чате MagicPaper. Не используй инструменты, файлы или команды. Верни только полезный ответ пользователю."

        fun defaultAppHome(): Path = Paths.get(System.getProperty("user.home"), ".MagicPaper", "codex")

    }
}

internal fun RuntimeQuestionnaireService.asNativeQuestionnaires(): NativeQuestionnaires = object : NativeQuestionnaires {
    override suspend fun ask(request: UserInteractionRequest) = this@asNativeQuestionnaires.ask(request)
    override suspend fun beginDelivery(requestId: String) = this@asNativeQuestionnaires.beginDelivery(requestId)
    override suspend fun finishDelivery(requestId: String, attemptId: String, outcome: QuestionnaireDeliveryOutcome) =
        this@asNativeQuestionnaires.finishDelivery(requestId, attemptId, outcome)
}
