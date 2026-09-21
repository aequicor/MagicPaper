package io.aequicor.magicpaper.data.coding

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** No application tools, profiles, credentials service or transcript policy enter this owner. */
internal class PiProcessExecution(
    private val protocol: PiNativeAdapter,
    private val ownership: NativeProcessOwnership,
    private val events: NativeAttemptEvents,
    private val attempt: NativeAttemptRef,
) : PiNativeExecution {
    private val started = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val process = AtomicReference<Process?>()

    override fun abort() {
        aborted.set(true)
        process.get()?.let(::stopTree)
    }

    override suspend fun run(request: PiExecutionRequest, onLine: suspend (String) -> Unit): PiExecutionResult =
        withContext(Dispatchers.IO) {
            check(started.compareAndSet(false, true)) { "A native attempt can only be started once" }
            if (aborted.get()) { events.unavailable(attempt); return@withContext PiExecutionResult(aborted = true) }
            val stderr = File.createTempFile("magicpaper-pi-stderr", ".log")
            var child: Process? = null
            var primaryFailure: Throwable? = null
            var streamBroken: String? = null
            var consumerFailure: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                child = protocol.launch(request.launch.copy(stderrFile = stderr.absolutePath))
                process.set(child)
                ownership.record(request.sessionId, child)
                events.attached(attempt, NativeProcessIdentity(request.sessionId, child.pid(), child.info().startInstant().orElseThrow().toEpochMilli()))
                currentCoroutineContext().ensureActive()
                // An abort racing with launch must never deliver the prompt afterward.
                if (aborted.get()) {
                    stopTree(child)
                    return@withContext PiExecutionResult(aborted = true)
                }
                events.deliver(attempt, NativeDelivery.PI_STDIN)
                child.outputStream.use { out ->
                    out.write(request.prompt.toByteArray(UTF_8))
                    out.flush()
                }
                val running = child
                coroutineScope {
                    val lines = Channel<String>(Channel.BUFFERED)
                    val reader = launch(Dispatchers.IO) {
                        try {
                            val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                            BufferedReader(InputStreamReader(running.inputStream, decoder)).use { input ->
                                while (true) lines.send(input.readLine() ?: break)
                            }
                            lines.close()
                        } catch (cancelled: CancellationException) {
                            lines.cancel(cancelled)
                            throw cancelled
                        } catch (failure: Throwable) {
                            lines.close(failure)
                        }
                    }
                    var streamConsumed = false
                    try {
                        for (line in lines) {
                            try { onLine(line) } catch (failure: Throwable) {
                                consumerFailure = failure
                                throw failure
                            }
                        }
                        streamConsumed = true
                    } catch (failure: IOException) {
                        consumerFailure?.let { throw it }
                        streamBroken = failure.message ?: failure.javaClass.simpleName
                        streamConsumed = true
                    } finally {
                        // Close the process before waiting for the blocking stdout reader on cancellation.
                        if (!streamConsumed) stopTree(running)
                        reader.cancel()
                    }
                }
                val exit = runInterruptible { child.waitFor() }
                PiExecutionResult(exitCode = exit, aborted = aborted.get(), stderr = tail(stderr), streamBroken = streamBroken)
            } catch (cancelled: CancellationException) {
                primaryFailure = cancelled
                throw cancelled
            } catch (failure: Exception) {
                primaryFailure = failure
                consumerFailure?.let { throw it }
                val diagnostics = try { tail(stderr) } catch (readFailure: IOException) {
                    failure.addSuppressed(readFailure)
                    ""
                }
                PiExecutionResult(aborted = aborted.get(), stderr = diagnostics, streamBroken = streamBroken,
                    launchError = failure.javaClass.simpleName, cause = failure)
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
                    } finally {
                        process.set(null)
                        stderr.delete()
                    }
                }
            }
        }

    private fun tail(file: File): String = if (!file.isFile) "" else
        String(file.readBytes(), UTF_8).filter { it.code >= 32 || it == '\n' }.takeLast(800).trim()

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
