package io.aequicor.magicpaper.logging

internal actual class LogLock actual constructor() {
    private val monitor = Any()
    actual fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}
internal actual fun platformEpochMillis(): Long = System.currentTimeMillis()
internal actual fun platformWriteLog(line: String, error: Boolean) {
    if (error) System.err.println(line) else System.out.println(line)
}
