package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal data class ClaudeExecutionRequest(val sessionId: String, val prompt: String, val workingDirectory: String, val launch: ClaudeLaunch)

internal class ClaudeExecutionResult(
    val exitCode: Int? = null,
    val aborted: Boolean = false,
    val streamBroken: String? = null,
    val launchError: String? = null,
    val cause: Throwable? = null,
)

/**
 * One attempt owns the child, its streams and its shutdown. Durable ownership is recorded before the prompt can
 * reach stdin, and cleared only once the whole process tree is confirmed stopped.
 */
internal class ClaudeProcessExecution(
    private val ownership: NativeProcessOwnership,
    private val events: NativeAttemptEvents,
    private val attempt: NativeAttemptRef,
) {
    private val started = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val process = AtomicReference<Process?>()

    fun abort() {
        aborted.set(true)
        process.get()?.let(::stopTree)
    }

    suspend fun run(request: ClaudeExecutionRequest, onLine: suspend (String) -> Unit): ClaudeExecutionResult = withContext(Dispatchers.IO) {
        check(started.compareAndSet(false, true)) { "A native attempt can only be started once" }
        if (aborted.get()) { events.unavailable(attempt); return@withContext ClaudeExecutionResult(aborted = true) }
        var child: Process? = null
        var primaryFailure: Throwable? = null
        var consumerFailure: Throwable? = null
        var streamBroken: String? = null
        try {
            currentCoroutineContext().ensureActive()
            child = launch(request)
            process.set(child)
            ownership.record(request.sessionId, child)
            events.attached(attempt, NativeProcessIdentity(request.sessionId, child.pid(), child.info().startInstant().orElseThrow().toEpochMilli()))
            currentCoroutineContext().ensureActive()
            // An abort racing with launch must never deliver the prompt afterward.
            if (aborted.get()) {
                stopTree(child)
                return@withContext ClaudeExecutionResult(aborted = true)
            }
            events.deliver(attempt, NativeDelivery.CLAUDE_STDIN)
            child.outputStream.use { out -> out.write(request.prompt.toByteArray(UTF_8)); out.flush() }
            val running = child
            coroutineScope {
                val lines = Channel<String>(Channel.BUFFERED)
                val reader = launch(Dispatchers.IO) {
                    try {
                        val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                        BufferedReader(InputStreamReader(running.inputStream, decoder)).use { input ->
                            while (true) lines.send(input.readLine() ?: break)
                        }
                        lines.close()
                    } catch (cancelled: CancellationException) {
                        lines.cancel(cancelled)
                        throw cancelled
                    } catch (failure: Throwable) { lines.close(failure) }
                }
                var consumed = false
                try {
                    for (line in lines) {
                        try { onLine(line) } catch (failure: Throwable) { consumerFailure = failure; throw failure }
                    }
                    consumed = true
                } catch (failure: IOException) {
                    consumerFailure?.let { throw it }
                    streamBroken = failure.message ?: failure.javaClass.simpleName
                    consumed = true
                } finally {
                    // Stop the process before waiting for the blocking stdout reader on cancellation.
                    if (!consumed) stopTree(running)
                    reader.cancel()
                }
            }
            ClaudeExecutionResult(exitCode = runInterruptible { child.waitFor() }, aborted = aborted.get(), streamBroken = streamBroken)
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (failure: Exception) {
            primaryFailure = failure
            consumerFailure?.let { throw it }
            ClaudeExecutionResult(aborted = aborted.get(), streamBroken = streamBroken, launchError = failure.javaClass.simpleName, cause = failure)
        } finally {
            withContext(NonCancellable) {
                try {
                    if (child == null) events.unavailable(attempt)
                    child?.let { running ->
                        var cleanup: Throwable? = null
                        try { events.stopping(attempt) } catch (failure: Throwable) { cleanup = failure }
                        try { stopTree(running) } catch (failure: Throwable) { if (cleanup == null) cleanup = failure else cleanup.addSuppressed(failure) }
                        if (!running.isAlive && cleanup == null) {
                            events.stopped(attempt)
                            ownership.clear(request.sessionId)
                        }
                        cleanup?.let { throw it }
                    }
                } catch (cleanup: Throwable) {
                    if (primaryFailure is CancellationException || consumerFailure != null) primaryFailure?.addSuppressed(cleanup)
                    else {
                        primaryFailure?.let(cleanup::addSuppressed)
                        throw cleanup
                    }
                } finally { process.set(null) }
            }
        }
    }

    private fun launch(request: ClaudeExecutionRequest): Process = ProcessBuilder(request.launch.arguments)
        .directory(File(request.workingDirectory))
        // stderr is never read, so it must not fill a pipe the child blocks on; diagnostics come from the stream.
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply {
            request.launch.removedEnvironment.forEach(environment()::remove)
            environment().putAll(request.launch.environment)
        }.start()

    /** Keep the parent alive until known descendants stop, preserving recovery evidence on failure. */
    @Synchronized private fun stopTree(child: Process) {
        val descendants = child.descendants().use { it.toList() }
        descendants.asReversed().forEach { it.destroyForcibly() }
        descendants.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
        if (child.isAlive) {
            child.destroy()
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                check(child.waitFor(10, TimeUnit.SECONDS)) { "Native process termination is unconfirmed" }
            }
        }
    }
}
