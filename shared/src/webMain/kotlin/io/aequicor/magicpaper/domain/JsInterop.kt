package io.aequicor.magicpaper.domain

import kotlin.js.JsAny

/** Межплатформенные преобразования Kotlin <-> JS значения (актуалы в jsMain/wasmJsMain). */
internal expect fun String.toBlobPart(): JsAny

internal expect fun jsAnyToString(value: JsAny?): String?
