package io.aequicor.magicpaper.logging

internal actual class LogLock actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
internal actual fun platformEpochMillis(): Long = System.currentTimeMillis()
internal actual fun platformWriteLog(line: String, error: Boolean) {
    if (error) android.util.Log.e("MagicPaper", line) else android.util.Log.i("MagicPaper", line)
}

/** Debug builds and logcat are verbose by design; release keeps the same DEBUG default. */
internal actual fun defaultLogLevel(): LogLevel = LogLevel.INFO

