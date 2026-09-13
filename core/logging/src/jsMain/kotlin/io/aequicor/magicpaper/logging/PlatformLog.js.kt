package io.aequicor.magicpaper.logging

@JsName("console")
private external object LogConsole {
    fun error(line: String)
    fun log(line: String)
}
internal actual fun platformEpochMillis(): Long = kotlin.js.Date.now().toLong()
internal actual fun platformWriteLog(line: String, error: Boolean) {
    if (error) LogConsole.error(line) else LogConsole.log(line)
}
