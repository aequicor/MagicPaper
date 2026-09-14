package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.*

fun attachmentGlyph(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGE -> "🖼"
    AttachmentKind.TEXT -> "📄"
    AttachmentKind.FILE -> "📦"
}

data class RequestPinEntry(val pin: RequestPin, val isRequest: Boolean)

/** Count and navigation share the same list of messages that still exist in this chat. */
fun requestPinEntries(groups: List<RequestPinGroup>, messageIds: Set<String>): List<RequestPinEntry> =
    groups.flatMap { group ->
        listOf(RequestPinEntry(group.request, true)) + group.clarifications.map { RequestPinEntry(it, false) }
    }.filter { it.pin.messageId in messageIds }.distinctBy { it.pin.messageId }

fun requestPinNumbers(groups: List<RequestPinGroup>, messageIds: Set<String>): Map<String, Int> =
    requestPinEntries(groups, messageIds).mapIndexed { index, entry -> entry.pin.messageId to index + 1 }.toMap()
