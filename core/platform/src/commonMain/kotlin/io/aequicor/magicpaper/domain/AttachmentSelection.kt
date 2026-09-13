package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.aequicor.magicpaper.logging.AppLog

/** Screen-owned picker work; selected bytes are handed to the durable entity draft. */
class AttachmentSelection(private val picker: FilePicker, private val scope: CoroutineScope) {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun dismissError() { _error.value = null }
    fun pickAttachments(alreadyAttached: Int, onResult: (List<Attachment>) -> Unit) {
        if (!picker.supported) { _error.value = "Вложения на этой платформе пока не поддерживаются."; return }
        read(alreadyAttached, picker::pickFiles, onResult)
    }
    fun pasteAttachments(alreadyAttached: Int, onResult: (List<Attachment>) -> Unit): Boolean {
        val reader = picker.clipboardFiles() ?: return false
        read(alreadyAttached, reader, onResult)
        return true
    }
    private fun read(count: Int, reader: suspend () -> List<PickedFile>, onResult: (List<Attachment>) -> Unit) {
        val operationId = io.aequicor.magicpaper.util.Id.new()
        AppLog.info("attachments", "read.started", mapOf("operationId" to operationId))
        scope.launch {
            try {
                val room = (MAX_ATTACHMENTS_PER_MESSAGE - count).coerceAtLeast(0)
                val files = reader()
                val accepted = files.take(room).filter { it.bytes.size.toLong() <= picker.maxFileBytes }
                onResult(accepted.map { Attachment.fromBytes(it.name, it.mimeType, it.bytes) })
                if (files.size > room) _error.value = "Не больше $MAX_ATTACHMENTS_PER_MESSAGE вложений на сообщение."
                else if (accepted.size != files.size) _error.value = "Размер вложения превышает допустимый."
                AppLog.info("attachments", "read.finished", mapOf("operationId" to operationId, "outcome" to if (accepted.size == files.size) "accepted" else "validation"))
            } catch (e: CancellationException) { AppLog.info("attachments", "read.cancelled", mapOf("operationId" to operationId)); throw e }
            catch (e: Exception) {
                AppLog.error("attachments", "read.failed", e, mapOf("operationId" to operationId))
                _error.value = "Не удалось прочитать вложение. Выберите файл повторно."
            }
        }
    }
}
