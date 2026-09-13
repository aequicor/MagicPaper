package io.aequicor.magicpaper.util

import kotlin.js.toLong

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

internal actual fun currentTimeMillis(): Long = jsDateNow().toLong()
