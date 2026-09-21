package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** The installation that model-only requests and the Pi agent share, so their preparation cannot race. */
interface PiInstallationSource { val installation: PiInstallation }

/** The provider dependency is shared by native agents and model-only requests, never by tool executors. */
class PiNativeProviderLibrary(
    private val root: File,
    private val resources: NativeResources,
    private val ownership: NativeProviderProcesses,
    private val diagnostics: NativeDiagnostics,
    private val lifecycle: NativeExecutionLifecycle,
    installationOverride: PiInstallation? = null,
) : NativeProviderLibrary, PiInstallationSource {
    override val installation: PiInstallation = installationOverride ?: PiNativeInstallation(root, resources, diagnostics)
    private val turns = PiProviderTurnExecution(ownership, diagnostics)
    private val bridges = ConcurrentHashMap<String, Bridge>()
    private val providerJobs = ConcurrentHashMap<NativeRunRef, Job>()
    private val lock = Any()
    private var closed = false
    private val recovery = Mutex()
    private var recovered = false
    override fun prepare(): Flow<NativeInstallationStatus> = flow {
        recovery.withLock {
            synchronized(lock) { check(!closed) { "Provider library is closed" } }
            if (!recovered) {
                withContext(Dispatchers.IO) { ownership.reconcileOrphans() }
                recovered = true
            }
        }
        emitAll(installation.ensureReady())
    }

    override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
        exchanges: List<LlmToolExchange>, accessToken: String, onUsage: (UsageCallResult) -> Unit): LlmToolTurn {
        ready()
        val request = withContext(Dispatchers.IO) {
            PiProviderTurnRequest(profile, messages, tools, exchanges, installation.node(), libraryDirectory(),
                script("provider-turn.mjs").absolutePath, accessToken)
        }
        val run = UUID.randomUUID().toString().let { NativeRunRef("provider-turn:$it", it) }
        lifecycle.begin(run)
        var primary: Throwable? = null
        try { return withContext(NativeAttemptContext(run, lifecycle)) {
            val job = currentCoroutineContext().job
            providerJobs[run] = job
            try { turns.turn(request, onUsage) } finally { providerJobs.remove(run, job) }
        } }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally { withContext(NonCancellable) {
            try { lifecycle.finished(run) } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        } }
    }

    override suspend fun bridge(profile: LlmProfile, parameters: JsonObject): NativeProviderBridge = withContext(Dispatchers.IO) {
        ready()
        val id = UUID.randomUUID().toString()
        val run = NativeRunRef("provider-bridge:$id", id)
        lifecycle.begin(run)
        val attempt = lifecycle.admitLaunch(run)
        val key = UUID.randomUUID().toString()
        val modelRoot = PiModelsConfig.root(profile, imageInput = PiModelsConfig.supportsImageInput(profile.modelId))
            .getValue("providers").jsonObject.getValue(PiModelsConfig.PROVIDER_ID).jsonObject
        val declared = modelRoot.getValue("models").jsonArray.first().jsonObject
        val model = JsonObject(declared + mapOf(
            "input" to (declared["input"] ?: buildJsonArray { add("text") }),
            "cost" to buildJsonObject { put("input", 0); put("output", 0); put("cacheRead", 0); put("cacheWrite", 0) },
            "api" to modelRoot.getValue("api"), "baseUrl" to modelRoot.getValue("baseUrl"),
            "provider" to JsonPrimitive(when (profile.provider) {
                ProviderType.ANTHROPIC -> "anthropic"; ProviderType.GOOGLE -> "google"; ProviderType.OPENROUTER -> "openrouter"
                ProviderType.OPENAI_SUBSCRIPTION -> "openai-codex"; ProviderType.OPENAI_COMPATIBLE -> "magicpaper"
            }), "compat" to (modelRoot["compat"] ?: JsonObject(emptyMap())),
        ))
        val config = buildJsonObject {
            put("key", key); put("model", model); put("apiKey", profile.apiKey.ifBlank { "anonymous" })
            put("parameters", parameters); PiModelsConfig.thinkingLevel(profile)?.let { put("reasoning", it) }
        }
        val node = installation.node()
        val adapter = script("provider-bridge.mjs")
        val library = libraryDirectory()
        var handle: Bridge? = null
        try {
            val child = synchronized(lock) {
                check(!closed) { "Provider library is closed" }
                ProcessBuilder(node, adapter.absolutePath).directory(adapter.parentFile)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply {
                        environment().remove("NODE_OPTIONS")
                        environment().keys.removeIf { it.startsWith("MAGICPAPER_") }
                        environment()["MAGICPAPER_PI_AI"] = File(library).toURI().toString()
                    }.start().also { process ->
                        handle = Bridge(id, process, attempt)
                        bridges[id] = checkNotNull(handle)
                    }
            }
            ownership.record(id, child)
            lifecycle.attached(attempt, NativeProcessIdentity(id, child.pid(), child.info().startInstant().orElseThrow().toEpochMilli()))
            lifecycle.deliver(attempt, NativeDelivery.PROVIDER_STDIN)
            val bridge = checkNotNull(handle)
            val ready = coroutineScope {
                val reader = async(Dispatchers.IO) { bridge.stdout.readLine() }
                val writer = async(Dispatchers.IO) {
                    child.outputStream.write((config.toString() + "\n").toByteArray()); child.outputStream.flush()
                }
                var received = false
                var primary: Throwable? = null
                try {
                    withTimeout(20_000) { writer.await(); reader.await() }.also { received = true }
                } catch (failure: Throwable) { primary = failure; throw failure } finally {
                    if (!received) withContext(NonCancellable) {
                        try { bridge.shutdown() } catch (cleanup: Throwable) {
                            if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
                        }
                    }
                }
            }
            val port = Json.parseToJsonElement(checkNotNull(ready)).jsonObject["port"]?.jsonPrimitive?.intOrNull
            check(port != null && port in 1..65535) { "Provider bridge did not publish a valid endpoint" }
            // This attempt owns endpoint setup; proxied model outcomes belong to their journaled coding run.
            lifecycle.terminal(attempt, NativeOutcome.SUCCEEDED)
            bridge.connectionValue = NativeModelConnection("magicpaper-api", "http://127.0.0.1:$port", key)
            bridge
        } catch (cancelled: CancellationException) {
            try { withContext(NonCancellable) { handle?.shutdown() ?: lifecycle.unavailable(attempt) } } catch (cleanup: Throwable) { cancelled.addSuppressed(cleanup) }
            throw cancelled
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { handle?.shutdown() ?: lifecycle.unavailable(attempt) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            val message = "Не удалось подготовить подключение модели. Проверьте настройки и повторите запрос."
            diagnostics.error("NativeProvider", "bridge_start_failed", IllegalStateException(message),
                mapOf("attemptId" to id, "failure" to failure.javaClass.simpleName))
            throw IllegalStateException(message, failure)
        }
    }

    private suspend fun ready() {
        synchronized(lock) { check(!closed) { "Provider library is closed" } }
        check(prepare().last().phase == NativeInstallationPhase.READY) {
            "Не удалось подготовить подключение модели. Повторите установку в настройках движков."
        }
    }
    private fun libraryDirectory() = checkNotNull(installation.aiDirectory()) { "Provider library is not installed" }
    internal fun script(name: String): File {
        val content = checkNotNull(resources.read("/coding/$name")) { "Bundled native adapter is missing" }
        val digest = MessageDigest.getInstance("SHA-256").digest(content).take(8).joinToString("") { "%02x".format(it) }
        val target = File(root, "adapters/$digest/$name")
        if (!target.isFile) {
            check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Native adapter directory is unavailable" }
            val temporary = File.createTempFile("adapter-", ".tmp", target.parentFile)
            try {
                temporary.writeBytes(content)
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { java.nio.file.Files.deleteIfExists(temporary.toPath()) }
        }
        return target
    }

    override suspend fun prepareForReset() {
        lifecycle.closing()
        val tasks = providerJobs.values.toList()
        tasks.forEach { it.cancel(CancellationException("Provider reset")) }
        tasks.joinAll()
        var failure: Throwable? = null
        bridges.values.toList().forEach { bridge -> try { bridge.shutdown() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        failure?.let { throw it }
        check(lifecycle.inspect().items.all { it.termination == NativeTermination.STOPPED }) { "Provider cleanup is not confirmed" }
    }
    override suspend fun resumeAfterReset() { lifecycle.reload(); recovery.withLock { recovered = false } }
    override suspend fun shutdown() {
        lifecycle.closing()
        var failure: Throwable? = null
        val turns = providerJobs.values.toList()
        turns.forEach { it.cancel(CancellationException("Provider runtime closed")) }
        turns.joinAll()
        bridges.values.toList().forEach { bridge -> try { bridge.shutdown() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        try { close() } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup) }
        try { if (failure == null) { lifecycle.closed() } } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup) }
        failure?.let { throw it }
    }

    override fun close() {
        val active = synchronized(lock) { closed = true; bridges.values.toList() }
        var failure: Throwable? = null
        (active + listOf<AutoCloseable>(turns)).forEach { resource -> try { resource.close() } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        failure?.let { throw it }
    }

    private inner class Bridge(private val id: String, private val child: Process, private val attempt: NativeAttemptRef) : NativeProviderBridge {
        val stdout = child.inputStream.bufferedReader()
        var connectionValue: NativeModelConnection? = null
        override val connection get() = checkNotNull(connectionValue) { "Provider connection is not ready" }
        private val closing = AtomicBoolean(false)
        private val collecting = AtomicBoolean(false)
        private var released = false
        private val shutdownLock = Mutex()
        private var receiptCleared = false
        override val usage: Flow<NativeProviderUsage> = flow {
            check(collecting.compareAndSet(false, true)) { "Provider metrics already have an owner" }
            try {
                while (true) {
                    val line = runInterruptible(Dispatchers.IO) { stdout.readLine() } ?: break
                    val value = Json.parseToJsonElement(line).jsonObject
                    if (value["type"]?.jsonPrimitive?.content != "magicpaper_usage") continue
                    val metricId = checkNotNull(value["id"]?.jsonPrimitive?.contentOrNull) { "Provider metric identity is missing" }
                    val usage = value["usage"] as? JsonObject
                    emit(NativeProviderUsage(metricId, value["phase"]?.jsonPrimitive?.content == "completed",
                        UsageCallResult(usage?.let(PiUsageParsing::tokens) ?: TokenUsage(), usage?.let(PiUsageParsing::cost))))
                }
                check(closing.get() || child.isAlive) { "Provider bridge exited during an active run" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IOException) { if (!closing.get()) throw failure }
        }
        override suspend fun shutdown(): Unit = shutdownLock.withLock {
            if (receiptCleared) return@withLock
            var failure: Throwable? = null
            try { lifecycle.stopping(attempt) } catch (cleanup: Throwable) { failure = cleanup }
            try { close() } catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure.addSuppressed(cleanup) }
            if (failure == null) {
                lifecycle.stopped(attempt)
                lifecycle.finished(attempt.run)
                ownership.clear(id)
                receiptCleared = true
            }
            failure?.let { throw it }
            Unit
        }
        @Synchronized override fun close() {
            if (released) return
            closing.set(true)
            var failure: Throwable? = null
            try {
                val children = child.descendants().use { it.toList() }
                children.asReversed().forEach { it.destroyForcibly() }
                children.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
                if (child.isAlive) {
                    child.destroyForcibly()
                    check(child.waitFor(10, TimeUnit.SECONDS)) { "Provider shutdown is unconfirmed" }
                }
                released = true
                bridges.remove(id, this)
            } catch (cleanup: Throwable) { failure = cleanup }
            try { stdout.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            failure?.let {
                diagnostics.error("NativeProvider", "bridge_cleanup_failed", it, mapOf("attemptId" to id, "outcome" to "unknown"))
                throw it
            }
        }
    }
}
