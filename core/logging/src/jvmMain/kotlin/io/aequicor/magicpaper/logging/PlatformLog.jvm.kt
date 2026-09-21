package io.aequicor.magicpaper.logging

internal actual class LogLock actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
internal actual fun platformEpochMillis(): Long = System.currentTimeMillis()
/**
 * Diagnostics are written as UTF-8 bytes rather than through the stream's own encoder. A Windows
 * console code page that cannot represent Cyrillic replaces every such character, and a replaced
 * cause message cannot be recovered from the log afterwards; a reader's charset mismatch can.
 */
internal actual fun platformWriteLog(line: String, error: Boolean) {
    val stream = if (error) System.err else System.out
    stream.write((line + System.lineSeparator()).toByteArray(Charsets.UTF_8))
    stream.flush()
}

/** `MAGICPAPER_LOG_LEVEL` or the `magicpaper.log.level` property selects ERROR/INFO/DEBUG/TRACE. */
internal actual fun defaultLogLevel(): LogLevel {
    val requested = System.getProperty("magicpaper.log.level")?.takeIf { it.isNotBlank() }
        ?: System.getenv("MAGICPAPER_LOG_LEVEL")?.takeIf { it.isNotBlank() }
    return LogLevel.entries.firstOrNull { it.name.equals(requested?.trim(), ignoreCase = true) } ?: LogLevel.INFO
}
