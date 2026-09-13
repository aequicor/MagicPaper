package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.PickedFile
import io.aequicor.magicpaper.domain.jsAnyToString
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.dom.events.Event

/**
 * Браузерный выбор файлов для вложений (общий для Kotlin/JS и Kotlin/Wasm):
 * скрытый input с множественным выбором; содержимое читаем data-URL'ом —
 * строки одинаково проходят межплатформенный мост в обоих целях.
 */
class BrowserFilePicker : FilePicker {

    override val supported: Boolean = true

    /** История живёт в localStorage — потолок жёсткий, чтобы не превысить квоту. */
    override val maxFileBytes: Long = 3L * 1024 * 1024

    override suspend fun pickFiles(): List<PickedFile> {
        val selected = selectFiles() ?: return emptyList()
        return selected.mapNotNull { file -> readFile(file) }
    }

    /** Диалог выбора; пустой список — пользователь отменил. */
    private suspend fun selectFiles(): List<File>? = suspendCoroutine { continuation ->
        try {
            val input = document.createElement("input").unsafeCast<HTMLInputElement>()
            input.type = "file"
            input.multiple = true
            input.onchange = { _: Event ->
                val files = input.files
                val count = files?.length ?: 0
                val list = if (count == 0) {
                    emptyList()
                } else {
                    buildList {
                        for (i in 0 until count) files?.item(i)?.let { add(it) }
                    }
                }
                continuation.resume(list)
            }
            input.click()
        } catch (_: Throwable) {
            continuation.resume(null)
        }
    }

    /** Содержимое одного файла через data-URL (base64). */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun readFile(file: File): PickedFile? = suspendCoroutine { continuation ->
        try {
            val reader = FileReader()
            reader.onload = {
                val dataUrl = jsAnyToString(reader.result)
                val base64 = dataUrl?.substringAfter(',', "")
                val bytes = base64?.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Base64.decode(it) }.getOrNull() }
                continuation.resume(bytes?.let { PickedFile(file.name, file.type, it) })
            }
            reader.onerror = { _: Event -> continuation.resume(null) }
            reader.readAsDataURL(file)
        } catch (_: Throwable) {
            continuation.resume(null)
        }
    }
}
