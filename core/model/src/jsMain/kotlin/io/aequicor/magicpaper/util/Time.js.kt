package io.aequicor.magicpaper.util

import kotlin.js.Date

internal actual fun currentTimeMillis(): Long = Date.now().toLong()
