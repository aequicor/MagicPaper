package io.aequicor.magicpaper.domain

import kotlin.js.JsAny

/** В Kotlin/JS строки и есть JS-строки. */
internal actual fun String.toBlobPart(): JsAny = this

internal actual fun jsAnyToString(value: JsAny?): String? = value as? String
