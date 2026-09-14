package io.aequicor.magicpaper.logging

internal actual class LogLock actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
internal actual fun platformEpochMillis(): Long = System.currentTimeMillis()
internal actual fun platformWriteLog(line: String, error: Boolean) {
    if (error) System.err.println(line) else System.out.println(line)
}

/** `MAGICPAPER_LOG_LEVEL` or the `magicpaper.log.level` property selects ERROR/INFO/DEBUG/TRACE. */
internal actual fun defaultLogLevel(): LogLevel {
    val requested = System.getProperty("magicpaper.log.level")?.takeIf { it.isNotBlank() }
        ?: System.getenv("MAGICPAPER_LOG_LEVEL")?.takeIf { it.isNotBlank() }
    return LogLevel.entries.firstOrNull { it.name.equals(requested?.trim(), ignoreCase = true) } ?: LogLevel.INFO
}
