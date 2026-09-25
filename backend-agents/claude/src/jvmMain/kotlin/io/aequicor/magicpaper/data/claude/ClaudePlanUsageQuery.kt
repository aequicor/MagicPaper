package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.domain.PlanUsage
import io.aequicor.magicpaper.domain.PlanUsageWindow
import io.aequicor.magicpaper.domain.ProviderType
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Claude Code's experimental get_usage control request is the only structured, prompt-free subscription snapshot.
 * Keep it isolated from project sessions and treat an unsupported response as unavailable; streamed observations
 * remain the source during a run. The CLI owns credentials and may change this response shape.
 */
internal class ClaudePlanUsageQuery(
    private val executable: ClaudeExecutable,
    private val home: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(): PlanUsage? = withContext(Dispatchers.IO) {
        val command = executable.find() ?: throw IOException("Claude Code is unavailable")
        check(home.isDirectory || home.mkdirs()) { "Claude usage directory is unavailable" }
        val process = ProcessBuilder(command.path, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--verbose", "--model", "haiku", "--setting-sources", "", "--strict-mcp-config", "--no-session-persistence",
            "--disable-slash-commands", "--permission-mode", "dontAsk", "--tools", "", "--allowedTools", "")
            .directory(home).redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { ClaudeCommand.SUBSCRIPTION_OVERRIDES.forEach(environment()::remove) }.start()
        val lines = Channel<String>(Channel.BUFFERED)
        val reader = launch(Dispatchers.IO) {
            try {
                InputStreamReader(process.inputStream, Charsets.UTF_8).buffered().use { input ->
                    while (true) lines.send(input.readLine() ?: break)
                }
                lines.close()
            } catch (cancelled: CancellationException) {
                lines.cancel(cancelled)
                throw cancelled
            } catch (failure: IOException) { lines.close(failure) }
        }
        var primary: Throwable? = null
        try {
            process.outputStream.use { it.write((REQUEST + "\n").toByteArray(Charsets.UTF_8)) }
            try {
                withTimeout(QUERY_TIMEOUT_MS) {
                    for (line in lines) parse(line)?.let { return@withTimeout it.usage }
                    throw IOException("Claude Code returned no usage response")
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IOException("Claude Code usage request timed out", timeout)
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                var cleanup: Throwable? = null
                try {
                    process.descendants().forEach { it.destroyForcibly() }
                    if (process.isAlive) {
                        process.destroy()
                        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                } catch (failure: Throwable) { cleanup = failure }
                try { reader.cancelAndJoin() } catch (failure: Throwable) {
                    if (cleanup == null) cleanup = failure else cleanup.addSuppressed(failure)
                }
                cleanup?.let { failure -> primary?.addSuppressed(failure) ?: throw failure }
            }
        }
    }

    private class Reply(val usage: PlanUsage?)

    private fun parse(line: String): Reply? {
        if (!line.startsWith('{')) return null
        val event = Json.parseToJsonElement(line) as? JsonObject ?: return null
        if (event.text("type") != "control_response") return null
        val envelope = event["response"] as? JsonObject ?: throw IOException("Claude usage response has no envelope")
        if (envelope.text("request_id") != REQUEST_ID) return null
        if (envelope.text("subtype") != "success") throw IOException("Claude Code refused the usage request")
        val response = envelope["response"] as? JsonObject ?: throw IOException("Claude usage response has no data")
        if ((response["rate_limits_available"] as? JsonPrimitive)?.booleanOrNull == false) return Reply(null)
        val limits = response["rate_limits"] as? JsonObject ?: return Reply(null)
        val windows = buildList {
            listOf("five_hour" to (300L to null), "seven_day" to (10_080L to null),
                "seven_day_opus" to (10_080L to "Opus"), "seven_day_sonnet" to (10_080L to "Sonnet"))
                .forEach { (id, details) -> limits.window(id, details.first, details.second)?.let(::add) }
            (limits["model_scoped"] as? JsonArray).orEmpty().forEach { item ->
                val model = item as? JsonObject ?: return@forEach
                val scope = model.text("display_name")?.takeIf(String::isNotBlank) ?: return@forEach
                val id = "seven_day_model:${scope.lowercase()}"
                model.window(id, 10_080, scope)?.let { if (none { current -> current.scope.equals(scope, true) }) add(it) }
            }
        }
        if (windows.isEmpty()) return Reply(null)
        return Reply(PlanUsage(ProviderType.ANTHROPIC_SUBSCRIPTION, windows, response.text("subscription_type"),
            limited = windows.any { it.usedFraction >= 1f }, observedAt = now()))
    }

    private fun JsonObject.window(id: String, duration: Long, scope: String?): PlanUsageWindow? {
        val source = if (id.startsWith("seven_day_model:")) this else this[id] as? JsonObject ?: return null
        val percent = (source["utilization"] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: return null
        val reset = source.text("resets_at")?.let { OffsetDateTime.parse(it).toEpochSecond() }
        return PlanUsageWindow(id, (percent / 100).toFloat().coerceIn(0f, 1f), duration, reset, scope)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val REQUEST_ID = "magicpaper-usage"
        const val REQUEST = """{"type":"control_request","request_id":"magicpaper-usage","request":{"subtype":"get_usage","skip_behaviors":true}}"""
        const val QUERY_TIMEOUT_MS = 15_000L
    }
}
