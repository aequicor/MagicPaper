package io.aequicor.magicpaper.domain

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.FileReader
import org.w3c.dom.url.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.toJsArray
import kotlin.js.unsafeCast

/**
 * Браузерный мост профиля (общий для Kotlin/JS и Kotlin/Wasm):
 * экспорт — скачивание JSON-файла, импорт — выбор файла через диалог браузера.
 */
class BrowserProfileBridge : ProfileBridge {

    override val supportsFilePicker = true

    override suspend fun export(json: String): Boolean {
        val parts = arrayOf<JsAny?>(json.toBlobPart()).toJsArray()
        val blob = Blob(parts, BlobPropertyBag(type = "application/json"))
        val url = URL.createObjectURL(blob)
        val anchor = document.createElement("a").unsafeCast<HTMLAnchorElement>()
        anchor.href = url
        anchor.download = "magicpaper-profile.json"
        anchor.click()
        URL.revokeObjectURL(url)
        return true
    }

    override suspend fun import(): String? = suspendCoroutine { continuation ->
        try {
            val input = document.createElement("input").unsafeCast<HTMLInputElement>()
            input.type = "file"
            input.accept = "application/json,.json"
            input.onchange = {
                val file = input.files?.item(0)
                if (file == null) {
                    continuation.resume(null)
                } else {
                    val reader = FileReader()
                    reader.onload = { continuation.resume(jsAnyToString(reader.result)) }
                    reader.onerror = { continuation.resume(null) }
                    reader.readAsText(file)
                }
            }
            input.click()
        } catch (_: Throwable) {
            continuation.resume(null)
        }
    }
}
