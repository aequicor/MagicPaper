package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeCompletion
import io.aequicor.magicpaper.backend.NativeCompletionFailure
import io.aequicor.magicpaper.backend.NativeCompletionRequest
import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeToolPresentationResolver
import io.aequicor.magicpaper.domain.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/**
 * A plain answer on the user's Claude subscription: `claude -p` in an empty directory of its own, so no project, memory
 * file or setting of the user's takes part. The conversation is one stream-json user message; the answer, Claude's
 * web-tool activity and the usage come back on the stream the coding runs also parse.
 */
internal class ClaudeCompletion(
    private val executable: ClaudeExecutable,
    private val home: File,
    private val diagnostics: NativeDiagnostics,
    private val presentation: NativeToolPresentationResolver,
) : NativeCompletion {
    override val provider = ProviderType.ANTHROPIC_SUBSCRIPTION

    override suspend fun complete(request: NativeCompletionRequest, onActivity: (CodingStep) -> Unit,
        onUsage: (UsageCallResult) -> Unit): String = withContext(Dispatchers.IO) {
        val file = executable.find() ?: throw NativeCompletionFailure(executable.status().detail)
        check(home.isDirectory || home.mkdirs()) { "Claude chat directory is unavailable" }
        val system = Files.createTempFile(home.toPath(), "system-", ".md").toFile()
        try {
            system.writeText(listOf(request.baseInstructions, request.systemInstructions).filter { it.isNotBlank() }.joinToString("\n\n"))
            val launch = ClaudeCommand.completion(file.path, request.modelId, request.effort, system.path)
            val answer = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(request.timeoutSeconds.toLong().coerceAtLeast(1))) {
                answer(launch, message(request.input), onActivity, onUsage)
            }
            answer ?: throw NativeCompletionFailure("Claude Code не ответил вовремя. Повторите запрос.")
        } finally {
            try { Files.deleteIfExists(system.toPath()) } catch (cleanup: IOException) {
                diagnostics.error(COMPONENT, "temporary_file_left", cleanup, emptyMap())
            }
        }
    }

    private suspend fun answer(launch: ClaudeLaunch, message: String, onActivity: (CodingStep) -> Unit,
        onUsage: (UsageCallResult) -> Unit): String = coroutineScope {
        val process = try {
            ProcessBuilder(launch.arguments).directory(home).redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { launch.removedEnvironment.forEach(environment()::remove) }.start()
        } catch (failure: IOException) {
            diagnostics.error(COMPONENT, "completion_launch_failed", failure, emptyMap())
            throw NativeCompletionFailure("Не удалось запустить Claude Code. Проверьте установку в настройках движков.", cause = failure)
        }
        val parser = ClaudeStreamParser(presentation)
        var text = ""
        var usage: TokenUsage? = null
        var finished = false
        try {
            process.outputStream.use { it.write((message + "\n").toByteArray(UTF_8)) }
            val lines = Channel<String>(Channel.BUFFERED)
            val reader = launch(Dispatchers.IO) {
                try {
                    val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                    BufferedReader(InputStreamReader(process.inputStream, decoder)).use { input ->
                        while (true) lines.send(input.readLine() ?: break)
                    }
                    lines.close()
                } catch (cancelled: CancellationException) {
                    lines.cancel(cancelled); throw cancelled
                } catch (failure: IOException) { lines.close(failure) }
            }
            try {
                for (line in lines) parser.parse(line).forEach { event ->
                    when (event) {
                        is CodingEvent.FinalText -> text = event.text
                        is CodingEvent.UsageObserved -> usage = usage?.let { it + event.tokens } ?: event.tokens
                        is CodingEvent.ToolStarted -> onActivity(CodingStep(CodingStepKind.TOOL, event.title ?: event.summary.ifBlank { event.tool },
                            tool = event.tool, callId = event.callId, running = true))
                        is CodingEvent.ToolFinished -> onActivity(CodingStep(CodingStepKind.TOOL, event.title ?: event.tool,
                            tool = event.tool, callId = event.callId, ok = !event.isError))
                        else -> Unit
                    }
                }
                finished = true
            } finally {
                // A cancelled or timed-out answer stops the child before waiting for its blocked reader.
                if (!finished) stop(process)
                reader.cancel()
            }
        } catch (failure: IOException) {
            throw NativeCompletionFailure("Claude Code прервал ответ. Повторите запрос.", cause = failure)
        } finally {
            withContext(NonCancellable) { if (process.isAlive) stop(process) }
        }
        usage?.let { onUsage(UsageCallResult(it)) }
        val terminal = parser.result ?: run {
            diagnostics.error(COMPONENT, "completion_unfinished", IllegalStateException("Claude Code ended without a result"),
                mapOf("exitCode" to process.exitValue().toString()))
            throw NativeCompletionFailure("Claude Code завершился без ответа. Повторите запрос.")
        }
        if (terminal.signedOut) throw NativeCompletionFailure(
            "Claude Code не авторизован. Войдите в подписку Claude Code в настройках движков.", signedOut = true)
        if (terminal.failed) throw NativeCompletionFailure(terminal.message ?: "Claude Code завершил запрос с ошибкой.")
        text.ifBlank { terminal.text }.ifBlank { throw NativeCompletionFailure("Claude Code вернул пустой ответ. Повторите запрос.") }
    }

    /** The host's input items (text, and images as data URLs) as one user message in Claude's content blocks. */
    private fun message(input: JsonArray): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                input.forEach { item ->
                    val block = item as? JsonObject ?: return@forEach
                    when (block["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
                            addJsonObject { put("type", "text"); put("text", text) }
                        }
                        "image" -> image(block["url"]?.jsonPrimitive?.contentOrNull)?.let(::add)
                    }
                }
            }
        }
    }.toString()

    private fun image(url: String?): JsonObject? {
        val match = url?.let(DATA_URL::matchEntire) ?: return null
        return buildJsonObject {
            put("type", "image")
            putJsonObject("source") { put("type", "base64"); put("media_type", match.groupValues[1]); put("data", match.groupValues[2]) }
        }
    }

    private operator fun TokenUsage.plus(other: TokenUsage): TokenUsage {
        fun sum(a: Long?, b: Long?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
        return TokenUsage(sum(input, other.input), sum(output, other.output), sum(cacheRead, other.cacheRead),
            sum(cacheWrite, other.cacheWrite), sum(reasoning, other.reasoning), sum(cachedOutput, other.cachedOutput), sum(total, other.total))
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private companion object {
        const val COMPONENT = "coding.claude"
        const val STOP_GRACE_SECONDS = 2L
        val DATA_URL = Regex("^data:([^;,]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)
    }
}
