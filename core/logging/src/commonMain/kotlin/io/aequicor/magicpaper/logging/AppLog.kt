package io.aequicor.magicpaper.logging

import kotlin.coroutines.cancellation.CancellationException

/** Increasing verbosity includes every preceding level. */
enum class LogLevel { ERROR, INFO, DEBUG, TRACE }

data class AppLogEntry internal constructor(
    val timestampMillis: Long,
    val level: LogLevel,
    val component: String,
    val event: String,
    val fields: Map<String, String>,
    val causeTypes: List<String> = emptyList(),
    val detail: String? = null,
    /** Sanitized message of the recorded cause; secrets and URLs are redacted, length is bounded. */
    val causeMessage: String? = null,
    /** Sanitized, frame-bounded stack trace of the recorded cause. */
    val causeStack: String? = null,
) {
    /** One bounded JSON line. Raw Throwable instances never reach a sink. */
    fun line(): String = buildString {
        append("{\"time\":").append(timestampMillis)
        append(",\"level\":").append(quote(level.name))
        append(",\"component\":").append(quote(component))
        append(",\"event\":").append(quote(event))
        append(",\"fields\":{")
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index != 0) append(',')
            append(quote(key)).append(':').append(quote(value))
        }
        append("},\"causeTypes\":[")
        causeTypes.forEachIndexed { index, value ->
            if (index != 0) append(',')
            append(quote(value))
        }
        append(']')
        detail?.let { append(",\"detail\":").append(quote(it)) }
        causeMessage?.let { append(",\"causeMessage\":").append(quote(it)) }
        causeStack?.let { append(",\"causeStack\":").append(quote(it)) }
        append('}')
    }
}

fun interface AppLogSink { fun write(entry: AppLogEntry) }

/** Synchronous, bounded diagnostics. Each instance has independent level, sinks and retention. */
class AppLogger(
    initialLevel: LogLevel = defaultLogLevel(),
    private val sink: AppLogSink = platformSink,
    private val fallbackSink: AppLogSink = platformSink,
    retention: Int = 128,
    payloadLimit: Int = 2048,
) {
    private val lock = LogLock()
    private var selectedLevel = initialLevel
    private val retained = ArrayDeque<AppLogEntry>()
    private val retainedLimit = retention.coerceIn(1, 1024)
    private val detailLimit = payloadLimit.coerceIn(16, 8192)

    var level: LogLevel
        get() = lock.locked { selectedLevel }
        set(value) { lock.locked { selectedLevel = value } }

    fun isEnabled(level: LogLevel): Boolean = lock.locked { level.ordinal <= selectedLevel.ordinal }
    fun history(): List<AppLogEntry> = lock.locked { retained.toList() }

    fun info(component: String, event: String, fields: Map<String, String> = emptyMap()) =
        record(LogLevel.INFO, component, event, fields)

    fun debug(component: String, event: String, fields: Map<String, String> = emptyMap()) =
        record(LogLevel.DEBUG, component, event, fields)

    fun error(component: String, event: String, cause: Throwable, fields: Map<String, String> = emptyMap()) =
        record(LogLevel.ERROR, component, event, fields, cause)

    /** A refused or failed operation without an exception: the outcome is the whole evidence. */
    fun error(component: String, event: String, fields: Map<String, String>) =
        record(LogLevel.ERROR, component, event, fields)

    /**
     * Payload is evaluated only at TRACE. Supply known credentials for free-form diagnostics;
     * standard credential fields/headers/token formats are redacted at every level as well.
     * Normal metadata must never contain prompts, names, answers, bodies, URLs or storage keys.
     */
    fun trace(component: String, event: String, fields: Map<String, String> = emptyMap(),
              knownSecrets: Set<String> = emptySet(), payload: () -> String) {
        if (!isEnabled(LogLevel.TRACE)) return
        val detail = try { redact(payload(), knownSecrets).bounded(detailLimit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            record(LogLevel.ERROR, "logging", "trace_payload_failed", mapOf("operation" to event), failure)
            return
        }
        record(LogLevel.TRACE, component, event, fields, detail = detail)
    }

    private fun record(level: LogLevel, component: String, event: String, fields: Map<String, String>,
                       cause: Throwable? = null, detail: String? = null) {
        if (!isEnabled(level)) return
        val entry = try {
            AppLogEntry(platformEpochMillis(), level, code(component), code(event), safeFields(fields),
                cause?.let(::causeTypes).orEmpty(), detail,
                cause?.let(::sanitizedMessage), cause?.let(::sanitizedStack))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            // Sanitation and platform initialization belong to diagnostics too. Do not retry the
            // failing sanitizer or expose raw event/context while constructing the fallback.
            AppLogEntry(0, LogLevel.ERROR, "logging", "entry_preparation_failed", emptyMap(), causeTypes(failure))
        }
        retain(entry)
        try { sink.write(entry) }
        catch (failure: Throwable) {
            // Logging owns sink failures: preserve the operation's original exception/state.
            val diagnostic = AppLogEntry(entry.timestampMillis, LogLevel.ERROR, "logging", "sink_failed",
                mapOf("operation" to entry.event), causeTypes(failure))
            retain(diagnostic)
            try { fallbackSink.write(diagnostic) }
            catch (fallbackFailure: Throwable) {
                // If even the platform sink fails, the bounded in-memory diagnostic remains inspectable.
                retain(diagnostic.copy(event = "fallback_sink_failed", causeTypes = causeTypes(fallbackFailure)))
            }
        }
    }

    private fun retain(entry: AppLogEntry) = lock.locked {
        while (retained.size >= retainedLimit) retained.removeFirst()
        retained.addLast(entry)
    }
}

/** Project-wide facade independent of the application DI graph. */
object AppLog {
    private val logger = AppLogger()
    var level: LogLevel
        get() = logger.level
        set(value) { logger.level = value }
    fun history(): List<AppLogEntry> = logger.history()
    fun isEnabled(level: LogLevel): Boolean = logger.isEnabled(level)
    fun info(component: String, event: String, fields: Map<String, String> = emptyMap()) = logger.info(component, event, fields)
    fun debug(component: String, event: String, fields: Map<String, String> = emptyMap()) = logger.debug(component, event, fields)
    fun error(component: String, event: String, cause: Throwable, fields: Map<String, String> = emptyMap()) =
        logger.error(component, event, cause, fields)

    fun error(component: String, event: String, fields: Map<String, String>) =
        logger.error(component, event, fields)
    fun trace(component: String, event: String, fields: Map<String, String> = emptyMap(),
              knownSecrets: Set<String> = emptySet(), payload: () -> String) = logger.trace(component, event, fields, knownSecrets, payload)
}

private val platformSink = AppLogSink { platformWriteLog(it.line(), it.level == LogLevel.ERROR) }

/**
 * Diagnostics must not be lost because a caller forgot to raise the level, so normal builds
 * record up to DEBUG. Per-token stream payloads stay behind explicit TRACE; an unparseable or
 * unsanitized field degrades to `[redacted]` rather than dropping the whole entry.
 */
internal expect fun defaultLogLevel(): LogLevel
internal expect class LogLock() { fun <T> locked(block: () -> T): T }
internal expect fun platformEpochMillis(): Long
internal expect fun platformWriteLog(line: String, error: Boolean)

private const val MAX_SCAN = 65_536
private val ids = setOf("operationId", "correlationId", "visitId", "sessionId", "projectId", "requestId", "profileId", "entityId", "journalId", "windowId", "tabId", "planId", "draftId")
private val numeric = setOf("attempt", "generation", "count", "durationMs", "elapsedMs", "bytes", "version", "limit", "entries", "index", "cursor", "epoch")
/**
 * Числовые метрики без перечисления по имени: длительности (`…Ms`), счётчики токенов
 * и вызовов (`…Tokens`, `…Calls`, `…Count`), доли и пределы (`…Percent`, `…Limit`).
 * Владелец операции добавляет метрику вместе с именем, а не правкой общего allowlist,
 * поэтому имя попадает в журнал, а его значение всё равно проходит проверку на число:
 * не-число под метрическим именем остаётся `[redacted]`.
 */
private val numericMetricSuffixes = listOf("Ms", "Tokens", "Calls", "Count", "Percent", "Limit")

private fun isNumericKey(key: String): Boolean =
    key in numeric || (key.length <= 40 && numericMetricSuffixes.any { suffix -> key.endsWith(suffix) && key != suffix })
private val metadata = setOf("operation", "action", "status", "reason", "strategy", "storageArea", "format", "phase", "result", "provider", "model", "route", "routeKind", "component", "section", "from", "to", "source", "target", "capability", "recovery", "outcome", "enabled", "mode", "backend", "kind", "scope", "tool", "category", "failure")
private val machineCode by lazy { Regex("[A-Za-z0-9_./:+-]{1,160}") }
private val eventCode by lazy { Regex("[A-Za-z][A-Za-z0-9_.-]{0,79}") }

private fun safeFields(fields: Map<String, String>): Map<String, String> = buildMap {
    fields.entries.asSequence().filter { it.key in ids || isNumericKey(it.key) || it.key in metadata }.take(24).forEach { (key, value) ->
        put(key, when {
            key in ids -> opaqueId(value)
            isNumericKey(key) -> value.takeIf { it.length <= 20 && it.all { c -> c in '0'..'9' || c == '-' } } ?: "[redacted]"
            value.length > 160 || !machineCode.matches(value) -> "[redacted]"
            else -> redact(value, emptySet()).bounded(160)
        })
    }
}

private fun code(value: String): String =
    if (eventCode.matches(value) && redact(value, emptySet()) == value) value else "redacted"

/** Stable opaque IDs preserve correlation while hiding accidental compound keys and user text. */
private fun opaqueId(value: String): String {
    var hash = -3750763034362895579L
    value.take(4096).forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
    return "id-" + hash.toULong().toString(16).padStart(16, '0')
}

private fun causeTypes(cause: Throwable): List<String> = buildList {
    val seen = mutableListOf<Throwable>()
    var current: Throwable? = cause
    while (size < 6) {
        val next = current ?: break
        if (seen.any { it === next }) break
        // The emergency path must work even when a platform's Regex engine failed to initialize.
        add(next::class.simpleName?.takeIf { name -> name.length in 1..80 &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.-" } } ?: "Throwable")
        seen += next
        current = try { next.cause } catch (failure: Throwable) {
            if (size < 6) add("CauseInspectionFailure")
            null
        }
    }
}

/**
 * Exception messages and frames may embed request content, credentials or user text, so they pass
 * the same redactor as TRACE payloads and stay bounded. The redacted result is bounded after
 * redaction, never before, so a credential cannot survive in an unexamined tail. A failure of the
 * sanitizer or of the platform stack renderer degrades to `null` instead of losing the whole
 * entry: cause types and fields remain recorded. The exception instance is never modified.
 */
private const val MESSAGE_SCAN_LIMIT = 4_096
private const val MESSAGE_RESULT_LIMIT = 512
private const val STACK_FRAME_LIMIT = 40
private const val STACK_LENGTH_LIMIT = 8_192

private fun sanitizedMessage(cause: Throwable): String? = try {
    val raw = cause.message ?: return null
    redact(raw.take(MESSAGE_SCAN_LIMIT), emptySet()).bounded(MESSAGE_RESULT_LIMIT)
} catch (failure: Throwable) { null }

private fun sanitizedStack(cause: Throwable): String? = try {
    // toString() itself may fail; rendering degrades to the class name and the frames only.
    val header = try { cause.toString() } catch (failure: Throwable) {
        causeTypes(cause).firstOrNull() ?: "Throwable"
    }
    val frames = try {
        cause.stackTraceToString().lineSequence().filter { it.isNotBlank() }.take(STACK_FRAME_LIMIT)
    } catch (failure: Throwable) { emptySequence() }
    val rendered = buildString {
        append(header)
        frames.forEach { append('\n').append(it) }
    }.take(STACK_LENGTH_LIMIT)
    redact(rendered, emptySet()).ifBlank { null }
} catch (failure: Throwable) { null }

// Kotlin/JS enables Unicode regex mode: literal quotes must not use identity escapes (\").
// Lazy construction keeps platform regex failures inside the entry-preparation boundary.
private val headers by lazy { Regex("""(?:authorization|proxy-authorization|cookie|set-cookie)\s*:[^\r\n]*""", RegexOption.IGNORE_CASE) }
private val assignments by lazy { Regex("""(["']?(?:api[_-]?key|apikey|key|authorization|proxy[_-]?authorization|cookie|set[_-]?cookie|credential|credentials|access[_-]?token|refresh[_-]?token|id[_-]?token|token|password|passphrase|secret|client[_-]?secret|private[_-]?key|answer|secret[_-]?answer|secret[_-]?value)["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}]+)""", RegexOption.IGNORE_CASE) }
private val bearer by lazy { Regex("""\b(?:Bearer|Basic)\s+[A-Za-z0-9+/_.=-]+""", RegexOption.IGNORE_CASE) }
private val keys by lazy { Regex("""\b(?:sk-|sk_|gsk_|AIza|xox[baprs]-)[A-Za-z0-9_-]{8,}""") }
private val jwt by lazy { Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""") }
private val urls by lazy { Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE) }

private fun redact(value: String, knownSecrets: Set<String>): String {
    // A bounded redactor must refuse excess context instead of leaking an unexamined credential.
    if (knownSecrets.size > 128) return "[redacted secret set exceeds limit]"
    // Refuse a partial scan that could leave a prefix of an unusually large known secret.
    if (knownSecrets.any { it.length > MAX_SCAN }) return "[redacted oversized secret]"
    var safe = value.take(MAX_SCAN)
    knownSecrets.asSequence().filter { it.isNotEmpty() }.take(128).sortedByDescending { it.length }.forEach { secret ->
        if (secret.length <= MAX_SCAN) safe = safe.replace(secret, "[redacted]").take(MAX_SCAN)
    }
    safe = headers.replace(safe, "[redacted header]")
    safe = assignments.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = bearer.replace(safe, "[redacted authorization]")
    safe = keys.replace(safe, "[redacted key]")
    safe = jwt.replace(safe, "[redacted token]")
    return urls.replace(safe, "[redacted URL]")
}

private fun String.bounded(limit: Int): String = if (length <= limit) this else take(limit - 12) + " [truncated]"
private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    } }
    append('"')
}
