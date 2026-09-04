package io.aequicor.magicpaper.domain

import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.toJsString

/** В Kotlin/Wasm нужны явные преобразования в/из JsString. */
internal actual fun String.toBlobPart(): JsAny = toJsString()

internal actual fun jsAnyToString(value: JsAny?): String? = (value as? JsString)?.toString()
