package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import io.aequicor.magicpaper.domain.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** One provider call, with no agent state or tool execution. Secrets cross only the private stdin pipe. */
internal class PiProviderTurnExecution(
    private val ownership: NativeProcessOwnership,
    private val diagnostics: NativeDiagnostics,
) : PiProviderTurns {
    private val lock = Any()
    private val active = mutableMapOf<String, Process>()
    private var closed = false
    override suspend fun turn(request: PiProviderTurnRequest, onUsage: (UsageCallResult) -> Unit): LlmToolTurn =
        withContext(Dispatchers.IO) {
            val lifecycle = checkNotNull(currentCoroutineContext()[NativeAttemptContext]) { "Native lifecycle owner is missing" }
            val attempt = lifecycle.events.admitLaunch(lifecycle.run)
            val id = UUID.randomUUID().toString()
            var process: Process? = null
            var primary: Throwable? = null
            var phase = "request"
            try {
                val input = PiProviderProtocol.request(request).toString().toByteArray(Charsets.UTF_8)
                check(input.size <= MAX_OUTPUT) { "Request size limit exceeded" }
                currentCoroutineContext().ensureActive()
                phase = "launch"
                process = synchronized(lock) {
                    check(!closed) { "Provider transport is closed" }
                    ProcessBuilder(request.nodePath, request.scriptPath)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply {
                        // Node preloads and host tool bridges must not enter this model-only transport.
                        environment().remove("NODE_OPTIONS")
                        environment().keys.removeIf { it.startsWith("MAGICPAPER_") || it == "OPENAI_API_KEY" }
                    }.start().also { active[id] = it }
                }
                ownership.record(id, process)
                lifecycle.events.attached(attempt, NativeProcessIdentity(id, process.pid(), process.info().startInstant().orElseThrow().toEpochMilli()))
                lifecycle.events.deliver(attempt, NativeDelivery.PROVIDER_STDIN)
                phase = "transport"
                val child = process
                val frame = coroutineScope {
                    val reader = async(Dispatchers.IO) { child.inputStream.use(::boundedOutput) }
                    val writer = async(Dispatchers.IO) { child.outputStream.use { it.write(input) } }
                    var exchangeFailure: Throwable? = null
                    try {
                        suspend fun exchange(): String {
                            writer.await()
                            val output = reader.await()
                            val exit = runInterruptible { child.waitFor() }
                            check(exit == 0) { "Provider process failed" }
                            return output
                        }
                        val timeout = request.profile.advanced.safeTimeoutSeconds
                        if (timeout == 0) exchange() else withTimeout(timeout * 1_000L) { exchange() }
                    } catch (failure: Throwable) {
                        exchangeFailure = failure
                        throw failure
                    } finally {
                        // Terminate before coroutineScope joins any blocked pipe reader/writer.
                        withContext(NonCancellable) {
                            try { stopTree(child) } catch (cleanup: Throwable) {
                                if (exchangeFailure == null) throw cleanup else exchangeFailure.addSuppressed(cleanup)
                            }
                        }
                    }
                }
                phase = "response"
                val result = Json.parseToJsonElement(frame).jsonObject
                val assistant = result["assistant"] as? JsonObject
                val usage = (assistant?.get("usage") ?: result["usage"]) as? JsonObject
                onUsage(UsageCallResult(tokens = usage?.let(PiUsageParsing::tokens) ?: TokenUsage(),
                    cost = usage?.let(PiUsageParsing::cost), contextTokens = usage?.count("totalTokens"),
                    contextLimit = PiModelsConfig.contextWindow(request.profile).toLong(), requests = 1))
                check(result["type"]?.jsonPrimitive?.content == "result" && assistant != null) { "Provider call failed" }
                PiProviderProtocol.result(assistant, request.tools.map { it.name }.toSet()).also {
                    lifecycle.events.terminal(attempt, NativeOutcome.SUCCEEDED)
                }
            } catch (cancelled: CancellationException) {
                primary = cancelled
                throw cancelled
            } catch (failure: Throwable) {
                // JSON/parser and provider exceptions can quote input. Keep type/location, never the untrusted message.
                val message = "Не удалось получить ответ по подписке. Проверьте подключение и повторите запрос."
                val safe = IllegalStateException(message, failure)
                primary = safe
                // Retain the original cause for its owner, but never pass provider-controlled exception text to logs.
                val diagnostic = IllegalStateException(message).also { it.stackTrace = failure.stackTrace }
                diagnostics.error("SubscriptionProvider", "turn_failed", diagnostic,
                    mapOf("phase" to phase, "failure" to failure.javaClass.simpleName, "attemptId" to id))
                throw safe
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (process == null) lifecycle.events.unavailable(attempt)
                        process?.let {
                            var journalFailure: Throwable? = null
                            try { lifecycle.events.stopping(attempt) } catch (failure: Throwable) { journalFailure = failure }
                            try { stopTree(it) } catch (failure: Throwable) { if (journalFailure == null) journalFailure = failure else journalFailure.addSuppressed(failure) }
                            if (journalFailure == null) { lifecycle.events.stopped(attempt); ownership.clear(id) }
                            journalFailure?.let { failure -> throw failure }
                        }
                    } catch (cleanup: Throwable) {
                        diagnostics.error("SubscriptionProvider", "cleanup_failed", cleanup,
                            mapOf("attemptId" to id, "result" to "unconfirmed"))
                        if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
                    } finally { synchronized(lock) { active.remove(id) } }
                }
            }
        }

    override fun close() {
        val processes = synchronized(lock) { closed = true; active.values.toList() }
        var failure: Throwable? = null
        processes.forEach { process -> try { stopTree(process) } catch (cleanup: Throwable) {
            if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
        } }
        failure?.let { throw it }
    }

    private fun boundedOutput(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            check(output.size() + size <= MAX_OUTPUT) { "Provider output size limit exceeded" }
            output.write(buffer, 0, size)
        }
        return output.toString(Charsets.UTF_8)
    }

    private fun stopTree(process: Process) {
        val children = process.descendants().use { it.toList() }
        children.asReversed().forEach { it.destroyForcibly() }
        children.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Provider termination is unconfirmed" }
        }
    }

    private companion object { const val MAX_OUTPUT = 32 * 1024 * 1024 }
}
