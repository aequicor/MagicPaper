package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PiBackendContribution : BackendAgentContribution {
    override val descriptor = PiNativeAdapter().descriptor
    override val paths = NativeBackendPaths("coding", "owned-processes", "coding-questionnaires", "native-pi")
    override fun create(environment: NativeBackendEnvironment): NativeAgentAdapter = PiBackendAgent(environment, descriptor)
}

/** Native configuration, session files, attempts and continuation; application enrichment is already resolved. */
internal class PiBackendAgent(private val environment: NativeBackendEnvironment,
    override val descriptor: BackendAgentDescriptor) : NativeAgentAdapter {
    private val protocol = PiNativeAdapter()
    private val root = File(environment.home)
    private val installation = checkNotNull((environment.providerLibrary as? PiInstallationSource)?.installation) {
        "Pi runs only with the provider library created by its own backend"
    }
    private val sessions = File(root, "sessions")
    private val uploads = File(root, "uploads")
    private val running = ConcurrentHashMap<String, PiNativeExecution>()
    private val lifecycleLock = Any()
    private var closed = false
    private val active = mutableSetOf<String>()
    private val aborted = ConcurrentHashMap.newKeySet<String>()
    override val rootPath = root.absolutePath
    override val approvals: NativeApprovalRequests? = null
    override val history: NativeToolHistory? = null
    override val removal = NativeRemoval {
        check(synchronized(lifecycleLock) { active.isEmpty() }) { "Сначала остановите выполняющиеся сессии" }
        installation.uninstall()
    }
    override suspend fun status() = installation.status()
    override fun prepare() = installation.ensureReady()
    override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
    override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean): LlmProfile {
        if (!speedBoost) return profile
        val max = (profile.advanced.maxTokens.toLong() * 2).coerceAtMost(profile.advanced.safeContextLimit / 2L).toInt()
        return profile.copy(advanced = if (max > profile.advanced.maxTokens) profile.advanced.copy(maxTokens = max) else profile.advanced,
            effort = if (mode == CodingInteractionMode.PLANNING) EffortSelection.of(ReasoningEffort.LOW) else profile.effort)
    }

    override fun run(request: NativeAgentRequest): Flow<CodingEvent> = channelFlow<CodingEvent> {
        try {
            coroutineScope {
                val sessionId = request.session.id
                synchronized(lifecycleLock) {
                    check(!closed) { "Native agent is closed" }
                    check(active.add(sessionId)) { "Session is already running" }
                }
                try {
                    check(File(installation.cliPath).isFile) { "Native agent is not installed" }
                    check(File(request.workingDirectory).isDirectory) { "Working directory is unavailable" }
                    val node = installation.node()
                    installation.prepareBundledTools()
                    installation.toolsNotice().takeIf { it.isNotBlank() }?.let { send(CodingEvent.Notice(it)) }
                    installation.ensureFuzzySafety()
                    val restricted = request.mode != CodingInteractionMode.CODE
                    if (!restricted && System.getProperty("os.name").startsWith("Windows", true) && installation.bashPath() == null) {
                        send(CodingEvent.Notice("Рабочего bash не найдено — команды агент выполняет через PowerShell. Для bash установите Git for Windows или повторите подготовку движка."))
                    }
                    val home = sessionHome(sessionId)
                    check(home.isDirectory || home.mkdirs()) { "Native session configuration directory is unavailable" }
                    check(sessions.isDirectory || sessions.mkdirs()) { "Native session directory is unavailable" }
                    installation.homeDefaults(home.absolutePath)
                    val model = protocol.modelConfiguration(request.profile,
                        imageInput = !restricted && (request.imageInput || protocol.supportsImageInput(request.profile.modelId)))
                    writeAtomically(File(home, "models.json"), model.root.toString())
                    writeAtomically(File(home, "agent-hints.md"), request.instructions)
                    writeAtomically(File(home, "model-options.mjs"), piModelOptions(request.profile, request.providerParameters))
                    request.tools.extensionSources.forEach { (name, source) ->
                        require(name == File(name).name && name.endsWith(".mjs")) { "Invalid native extension resource name" }
                        writeAtomically(File(home, name), source)
                    }
                    val extensionPaths = buildList {
                        if (restricted) add(resourceScript("planning-tools.mjs").absolutePath)
                        add(File(home, "model-options.mjs").absolutePath)
                        add(resourceScript("usage-context.mjs").absolutePath)
                        add(resourceScript("shell-timeout.mjs").absolutePath)
                        addAll(request.tools.extensionSources.keys.map { File(home, it).absolutePath })
                        if (request.profile.provider == ProviderType.OPENAI_SUBSCRIPTION) add(resourceScript("subscription-provider.mjs").absolutePath)
                    }
                    val toolNames: List<String>? = if (restricted) buildList<String> {
                        addAll(listOf("read", "grep", "find", "ls", "planning_git"))
                        if (request.mode == CodingInteractionMode.RESEARCH) {
                            add("research_check")
                            if ("questionnaire.mjs" in request.tools.extensionSources) add("questionnaire")
                        }
                        addAll(request.tools.names)
                    } else null
                    val tokenBroker = if (request.profile.provider == ProviderType.OPENAI_SUBSCRIPTION)
                        NativeSubscriptionTokenBroker(environment.refreshedAccessTokens, environment.diagnostics) else null
                    lateinit var outcome: AttemptOutcome
                    var continuation = 0
                    tokenBroker.use { broker ->
                        val removed = request.tools.removedEnvironment + protocol.apiKeyEnvironment
                        val env = buildMap<String, String> {
                            putAll(installation.environment(node, home.absolutePath))
                            removed.forEach(::remove)
                            putAll(model.environment); putAll(request.tools.environment)
                            broker?.let { putAll(it.environment); put("MAGICPAPER_PI_AI", File(checkNotNull(installation.aiDirectory())).toURI().toString()) }
                        }
                        var prompt = attachmentPrompt(request.prompt, sessionId, request.attachments)
                        var nativeId = request.session.piSessionId.takeIf { request.mode != CodingInteractionMode.PLANNING && it.isNotBlank() }
                        val launch = PiLaunchRequest(node, installation.cliPath, request.workingDirectory, request.profile.modelId,
                            sessions.absolutePath, File(home, "agent-hints.md").absolutePath, restricted, extensionPaths, toolNames,
                            model.thinkingLevel, nativeId, env, removed)
                        while (true) {
                            outcome = attempt(sessionId, request.profile, prompt, launch.copy(nativeSessionId = nativeId)) { send(it) }
                            val next = outcome.truncated != null && !outcome.answer && outcome.failure == null &&
                                !outcome.result.aborted && outcome.result.exitCode == 0 && !outcome.nativeId.isNullOrBlank() &&
                                continuation < request.maxOutputContinuations && sessionId !in aborted
                            if (!next) break
                            continuation++
                            nativeId = outcome.nativeId
                            prompt = "Предыдущий ответ обрезан лимитом токенов. Не пересказывай разбор: сделай одно следующее действие (правка файла или команда) и опиши его кратко."
                            send(CodingEvent.Notice("Ответ обрезан лимитом токенов — продолжаю прогон, попытка $continuation из ${request.maxOutputContinuations}…"))
                        }
                    }
                    val result = outcome
                    if (result.failure != null || !result.answer || result.result.launchError != null ||
                        result.result.streamBroken != null || result.result.exitCode != 0) {
                        val message = when {
                            result.result.aborted -> "Прогон прерван по команде пользователя."
                            result.result.exitCode != null && result.result.exitCode != 0 -> "Агент завершился с кодом ${result.result.exitCode}."
                            result.truncated != null -> protocol.truncationAdvice(request.profile, result.truncated.outputTokens, result.truncated.reasoningTokens) +
                                if (continuation > 0) " Автопродолжение ($continuation попыток) не помогло." else ""
                            else -> "Не удалось получить ответ движка. Проверьте подключение и продолжите сессию."
                        }
                        send(CodingEvent.Failed(message))
                    }
                    send(CodingEvent.Finished)
                } catch (cancelled: CancellationException) {
                    try { abort(sessionId) } catch (cleanup: Throwable) {
                        // Coroutine stack recovery may copy a caught cancellation; a fresh causal wrapper retains cleanup evidence.
                        throw CancellationException("Native run cancelled during cleanup").apply {
                            initCause(cancelled); addSuppressed(cleanup)
                        }
                    }
                    throw cancelled
                }
                finally { synchronized(lifecycleLock) { active.remove(sessionId); aborted.remove(sessionId) } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Closing normally with a cause preserves queued events until the receiver reaches it.
            close(failure)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun attempt(sessionId: String, profile: LlmProfile, prompt: String, request: PiLaunchRequest,
        publish: suspend (CodingEvent) -> Unit): AttemptOutcome {
        val lifecycle = checkNotNull(currentCoroutineContext()[NativeAttemptContext]) { "Native lifecycle owner is missing" }
        val attempt = lifecycle.events.admitLaunch(lifecycle.run)
        val execution = protocol.execution(environment.processes, lifecycle.events, attempt)
        check(running.putIfAbsent(sessionId, execution) == null) { "Session is already running" }
        var answer = false
        var failure: CodingEvent.Failed? = null
        var truncated: CodingEvent.OutputTruncated? = null
        var nativeId: String? = null
        var agentEnded = false
        val tools = mutableSetOf<String>()
        try {
            if (sessionId in aborted) execution.abort()
            val result = execution.run(PiExecutionRequest(sessionId, prompt, request)) { line ->
                protocol.parseEvents(line, profile.provider in setOf(ProviderType.OPENAI_SUBSCRIPTION, ProviderType.GOOGLE)).forEach { event ->
                    when (event) {
                        is CodingEvent.Failed -> failure = event
                        is CodingEvent.FinalText -> { answer = true; failure = null }
                        is CodingEvent.ToolStarted -> { failure = null; tools += event.callId }
                        is CodingEvent.ToolFinished -> tools -= event.callId
                        CodingEvent.AgentEnd -> agentEnded = true
                        is CodingEvent.OutputTruncated -> { truncated = event; failure = null }
                        is CodingEvent.SessionStarted -> nativeId = event.sessionId.takeIf { it.isNotBlank() }
                        else -> Unit
                    }
                    if (event !is CodingEvent.Failed) publish(event)
                }
            }
            if (agentEnded && tools.isEmpty() && result.exitCode == 0 && !result.aborted && result.streamBroken == null && result.launchError == null)
                lifecycle.events.terminal(attempt, if (failure == null) NativeOutcome.SUCCEEDED else NativeOutcome.FAILED)
            result.cause?.let { failure -> environment.diagnostics.error("coding.pi", "attempt_failed",
                IllegalStateException("Native agent attempt failed").apply { stackTrace = failure.stackTrace },
                mapOf("sessionId" to sessionId, "result" to "native_failure", "failure" to failure.javaClass.simpleName)) }
            return AttemptOutcome(answer, failure, truncated, nativeId, result)
        } finally { running.remove(sessionId, execution) }
    }
    private data class AttemptOutcome(val answer: Boolean, val failure: CodingEvent.Failed?,
        val truncated: CodingEvent.OutputTruncated?, val nativeId: String?, val result: PiExecutionResult)

    override fun abort(sessionId: String) { aborted.add(sessionId); running[sessionId]?.abort() }
    override fun abortAll() { synchronized(lifecycleLock) { active.toList() }.forEach(::abort) }
    override fun close() { synchronized(lifecycleLock) { closed = true }; abortAll() }
    override suspend fun eraseSessionsForReset() = withContext(Dispatchers.IO) {
        check(synchronized(lifecycleLock) { active.isEmpty() }) { "Сначала остановите выполняющиеся сессии" }
        eraseTree(sessions.toPath())
    }
    // Only Pi writes here, yet a link is removed as an entry and never followed: the host's deleteTree rule, which
    // an engine module may not depend on. Windows reports a junction as a directory that is also "other".
    private fun eraseTree(path: Path) {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { return }
        if (attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther)
            Files.newDirectoryStream(path).use { entries -> entries.forEach(::eraseTree) }
        Files.deleteIfExists(path)
    }
    override suspend fun reconcile(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val terminated = environment.processes.reconcile(sessionId)
        migrateCredentials(sessionHome(sessionId))
        if (File(root, "owned-processes").listFiles().orEmpty().none { it.extension == "process" }) {
            migrateCredentials(File(root, "pihome"))
            File(root, "session-configs").listFiles().orEmpty().filter { it.isDirectory }.forEach(::migrateCredentials)
        }
        terminated
    }
    private fun sessionHome(id: String) = File(root, "session-configs/" + id.replace(Regex("[^a-zA-Z0-9_-]"), "_"))
    private fun migrateCredentials(home: File) {
        val file = File(home, "models.json")
        if (!file.isFile) return
        val document = Json.parseToJsonElement(file.readText()).jsonObject
        val providers = document["providers"] as? JsonObject ?: return
        val provider = providers[protocol.providerId] as? JsonObject ?: return
        val reference = if (provider["api"]?.jsonPrimitive?.content == "openai-codex-responses") "managed-by-magicpaper" else protocol.apiKeyReference
        if (provider["apiKey"]?.jsonPrimitive?.content != reference) writeAtomically(file, JsonObject(document +
            ("providers" to JsonObject(providers + (protocol.providerId to JsonObject(provider + ("apiKey" to JsonPrimitive(reference))))))).toString())
    }
    private fun resourceScript(name: String): File {
        val bytes = checkNotNull(environment.resources.read("/coding/$name")) { "Native adapter resource is missing" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        val file = File(root, "adapters/$digest/$name")
        if (!file.isFile) { check(file.parentFile.mkdirs() || file.parentFile.isDirectory); writeAtomically(file, bytes.toString(Charsets.UTF_8)) }
        return file
    }
    private fun writeAtomically(target: File, content: String) {
        val temporary = File.createTempFile("native-", ".tmp", target.parentFile)
        try { temporary.writeText(content); Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary.toPath()) }
    }
    private fun attachmentPrompt(prompt: String, sessionId: String, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return prompt
        val directory = File(uploads, sessionId)
        check(directory.mkdirs() || directory.isDirectory)
        val files = attachments.map { attachment ->
            val safe = attachment.name.replace(Regex("[^\\p{L}\\p{N}._\\-]+"), "_").take(120).ifBlank { "file" }
            val name = "${System.currentTimeMillis()}-$safe"
            var file = File(directory, name)
            var count = 1
            while (file.exists()) file = File(directory, "${count++}-$name")
            file.writeBytes(attachment.bytes); file
        }
        return buildString {
            append(prompt).append("\n\nК запросу приложены файлы (лежат вне папки проекта, пути абсолютные):\n")
            files.forEach { append("- ").append(it.absolutePath).append('\n') }
            append("Если файл нужен для задачи — прочитай его инструментом чтения; изображения тоже читаются.")
        }
    }
}
