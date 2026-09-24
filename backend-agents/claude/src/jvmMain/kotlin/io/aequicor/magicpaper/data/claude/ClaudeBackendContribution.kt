package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

class ClaudeBackendContribution : BackendAgentContribution {
    override val descriptor = ClaudeNativeAdapter().descriptor
    override val paths = NativeBackendPaths("claude", "claude-processes", "claude/questionnaires", "native-claude")
    override fun create(environment: NativeBackendEnvironment): NativeAgentAdapter = ClaudeBackendAgent(environment, descriptor)
}

/** Sessions, attempts and the child process of Claude Code; application enrichment is already resolved. */
internal class ClaudeBackendAgent(
    private val environment: NativeBackendEnvironment,
    override val descriptor: BackendAgentDescriptor,
) : NativeAgentAdapter {
    private val executable = ClaudeExecutable(environment.commandOverride, report = { failure ->
        environment.diagnostics.error("coding.claude", "version_probe_failed", failure, emptyMap())
    }, selected = environment.commandSelection)
    private val root = File(environment.home)
    private val running = ConcurrentHashMap<String, ClaudeProcessExecution>()
    private val lifecycleLock = Any()
    private var closed = false
    private val active = mutableSetOf<String>()
    private val aborted = ConcurrentHashMap.newKeySet<String>()
    override val rootPath = root.absolutePath
    override val approvals: NativeApprovalRequests? = null
    override val history: NativeToolHistory? = null
    override val removal: NativeRemoval? = null
    override val models = NativeModelCatalog { ClaudeModelCatalog.models }
    private val claudeSignIn = ClaudeSignIn(executable, environment.diagnostics)
    /**
     * The CLI's `auth status` keeps claiming a login while its OAuth token is dead, so a run that failed on
     * authentication downgrades the reported account until a sign-in or a successful run proves otherwise.
     */
    @Volatile private var authenticationFailed = false
    override val signIn: NativeSignIn = object : NativeSignIn {
        override suspend fun signIn(): EngineSignInResult = claudeSignIn.signIn().also {
            if (it is EngineSignInResult.SignedIn) authenticationFailed = false
        }
    }
    override val completion: NativeCompletion =
        ClaudeCompletion(executable, File(root, "chat"), environment.diagnostics, environment.toolPresentation)

    override suspend fun status(): NativeInstallationStatus {
        val status = executable.status()
        return if (authenticationFailed && status.signedIn == true) status.copy(signedIn = false) else status
    }
    override fun prepare() = flow { emit(status()) }
    override fun modelConnection(profile: LlmProfile) = NativeModelConnectionKind.DIRECT
    override fun modelProfile(profile: LlmProfile, mode: CodingInteractionMode, speedBoost: Boolean) = profile

    override fun run(request: NativeAgentRequest): Flow<CodingEvent> = channelFlow<CodingEvent> {
        try {
            coroutineScope {
                val sessionId = request.session.id
                synchronized(lifecycleLock) {
                    check(!closed) { "Native agent is closed" }
                    check(active.add(sessionId)) { "Session is already running" }
                }
                try {
                    start(request) { send(it) }?.let { send(it) }
                    send(CodingEvent.Finished)
                } catch (cancelled: CancellationException) {
                    try { abort(sessionId) } catch (cleanup: Throwable) {
                        // Coroutine stack recovery may copy a caught cancellation; a fresh causal wrapper retains cleanup evidence.
                        throw CancellationException("Native run cancelled during cleanup").apply { initCause(cancelled); addSuppressed(cleanup) }
                    }
                    throw cancelled
                } finally { synchronized(lifecycleLock) { active.remove(sessionId); aborted.remove(sessionId) } }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Closing normally with a cause preserves queued events until the receiver reaches it.
            close(failure)
        }
    }.flowOn(Dispatchers.IO)

    /** Runs one attempt; the result is the user-facing failure, or null when the engine finished with an answer. */
    private suspend fun start(request: NativeAgentRequest, publish: suspend (CodingEvent) -> Unit): CodingEvent.Failed? {
        if (request.profile.provider != ProviderType.ANTHROPIC && request.profile.provider != ProviderType.ANTHROPIC_SUBSCRIPTION)
            return CodingEvent.Failed("Claude Code работает с подключениями Anthropic. Выберите модель Anthropic для этой сессии.")
        val command = executable.find() ?: return CodingEvent.Failed(executable.status().detail)
        check(File(request.workingDirectory).isDirectory) { "Working directory is unavailable" }
        val home = sessionHome(request.session.id)
        check(home.isDirectory || home.mkdirs()) { "Native session directory is unavailable" }
        val servers = ClaudeMcpServers.from(request.tools.mcpConfiguration)
        val temporary = mutableListOf<File>()
        try {
            val prompt = File.createTempFile("system-", ".md", home).also(temporary::add)
            prompt.writeText(listOfNotNull(request.baseInstructions, request.instructions).filter { it.isNotBlank() }.joinToString("\n\n"))
            // The file holds bearer tokens of this run's loopback endpoints; it lives only as long as the attempt.
            val mcp = servers.takeIf { it.names.isNotEmpty() }?.let {
                Files.createTempFile(home.toPath(), "mcp-", ".json").toFile().also(temporary::add).apply { writeText(it.document.toString()) }
            }
            val uploads = File(root, "uploads/" + safe(request.session.id))
            val text = attachmentPrompt(request.prompt, uploads, request.attachments)
            val resume = request.session.piSessionId.takeIf { request.mode != CodingInteractionMode.PLANNING && it.isNotBlank() }
            val files = ClaudeLaunchFiles(prompt.absolutePath, mcp?.absolutePath, if (request.attachments.isEmpty()) emptyList() else listOf(uploads.absolutePath))
            val launch = ClaudeCommand.build(command.absolutePath, request, files, servers, resume)
            return attempt(request, text, launch, publish)
        } finally {
            temporary.forEach { file ->
                try { Files.deleteIfExists(file.toPath()) } catch (cleanup: java.io.IOException) {
                    environment.diagnostics.error("coding.claude", "temporary_file_left", cleanup, mapOf("sessionId" to request.session.id))
                }
            }
        }
    }

    private suspend fun attempt(request: NativeAgentRequest, prompt: String, launch: ClaudeLaunch, publish: suspend (CodingEvent) -> Unit): CodingEvent.Failed? {
        val sessionId = request.session.id
        val lifecycle = checkNotNull(currentCoroutineContext()[NativeAttemptContext]) { "Native lifecycle owner is missing" }
        val attempt = lifecycle.events.admitLaunch(lifecycle.run)
        val execution = ClaudeProcessExecution(environment.processes, lifecycle.events, attempt)
        check(running.putIfAbsent(sessionId, execution) == null) { "Session is already running" }
        val parser = ClaudeStreamParser(environment.toolPresentation, contextWindow(request.profile))
        var answer = false
        try {
            if (sessionId in aborted) execution.abort()
            val result = execution.run(ClaudeExecutionRequest(sessionId, prompt, request.workingDirectory, launch)) { line ->
                parser.parse(line).forEach { event ->
                    if (event is CodingEvent.FinalText) answer = true
                    publish(event)
                }
            }
            val terminal = parser.result
            if (terminal != null && !terminal.failed && !answer && terminal.text.isNotBlank()) { answer = true; publish(CodingEvent.FinalText(terminal.text)) }
            if (terminal != null && !result.aborted && result.streamBroken == null && result.launchError == null && result.exitCode != null)
                lifecycle.events.terminal(attempt, if (terminal.failed) NativeOutcome.FAILED else NativeOutcome.SUCCEEDED)
            result.cause?.let { failure -> environment.diagnostics.error("coding.claude", "attempt_failed",
                IllegalStateException("Native agent attempt failed").apply { stackTrace = failure.stackTrace },
                mapOf("sessionId" to sessionId, "result" to "native_failure", "failure" to failure.javaClass.simpleName)) }
            return when {
                result.aborted -> CodingEvent.Failed("Прогон прерван по команде пользователя.")
                terminal?.signedOut == true -> {
                    authenticationFailed = true
                    CodingEvent.Failed(
                        "Claude Code не авторизован. Войдите в аккаунт Claude или укажите ключ API в подключении Anthropic.",
                        CodingRecovery.SignIn(descriptor.engine))
                }
                terminal?.failed == true -> terminal.message?.let { CodingEvent.Failed(it) }
                result.launchError != null -> CodingEvent.Failed("Не удалось запустить Claude Code. Проверьте установку в настройках движков.")
                terminal == null && result.exitCode != null && result.exitCode != 0 -> CodingEvent.Failed("Claude Code завершился с кодом ${result.exitCode}.")
                terminal == null || !answer -> CodingEvent.Failed("Не удалось получить ответ движка. Проверьте подключение и продолжите сессию.")
                else -> {
                    authenticationFailed = false
                    null
                }
            }
        } finally { running.remove(sessionId, execution) }
    }

    override fun abort(sessionId: String) { aborted.add(sessionId); running[sessionId]?.abort() }
    override fun abortAll() { synchronized(lifecycleLock) { active.toList() }.forEach(::abort) }
    override fun close() { synchronized(lifecycleLock) { closed = true }; abortAll() }
    override suspend fun reconcile(sessionId: String) = withContext(Dispatchers.IO) { environment.processes.reconcile(sessionId) }

    /**
     * The window the CLI will use for this model. The profile's context limit bounds the application's own requests,
     * not the CLI's conversation, so it is never offered as the window; an unknown model waits for the CLI's figure.
     */
    private fun contextWindow(profile: LlmProfile): Long? {
        val declared = ClaudeModelCatalog.models.firstOrNull { it.id == profile.modelId }?.contextWindow
            ?: profile.modelCatalog.firstOrNull { it.id == profile.modelId }?.contextWindow
        return ClaudeContext.window(profile, declared)
    }

    private fun sessionHome(id: String) = File(root, "session-configs/" + safe(id))
    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private fun attachmentPrompt(prompt: String, directory: File, attachments: List<Attachment>): String {
        if (attachments.isEmpty()) return prompt
        check(directory.mkdirs() || directory.isDirectory) { "Attachment directory is unavailable" }
        val files = attachments.map { attachment ->
            val format = if (attachment.kind == AttachmentKind.IMAGE) ClaudeImageFormat.of(attachment.bytes) else null
            val safe = attachment.name.replace(Regex("[^\\p{L}\\p{N}._\\-]+"), "_").take(120).ifBlank { "file" }
            val name = "${System.currentTimeMillis()}-" + (format?.let { ClaudeImageFormat.fileName(safe, it) } ?: safe)
            var file = File(directory, name)
            var count = 1
            while (file.exists()) file = File(directory, "${count++}-$name")
            file.writeBytes(attachment.bytes)
            file to (attachment.kind == AttachmentKind.IMAGE && format == null)
        }
        return buildString {
            append(prompt).append("\n\nК запросу приложены файлы (лежат вне папки проекта, пути абсолютные):\n")
            files.forEach { (file, unreadable) ->
                append("- ").append(file.absolutePath)
                if (unreadable) append(" — изображение в формате, который Read не показывает как картинку (нужны PNG, JPEG, GIF или WebP)")
                append('\n')
            }
            append("Если файл нужен для задачи — прочитай его инструментом Read; изображения PNG, JPEG, GIF и WebP он показывает как картинки.")
        }
    }
}
