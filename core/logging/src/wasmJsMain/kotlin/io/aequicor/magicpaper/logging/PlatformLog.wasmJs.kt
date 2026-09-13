package io.aequicor.magicpaper.logging

import kotlin.js.toLong

@JsFun("() => Date.now()")
private external fun nowMillis(): Double
@JsFun("(line, error) => { if (error) console.error(line); else console.log(line); }")
private external fun writeConsole(line: String, error: Boolean)
internal actual fun platformEpochMillis(): Long = nowMillis().toLong()
internal actual fun platformWriteLog(line: String, error: Boolean) = writeConsole(line, error)
