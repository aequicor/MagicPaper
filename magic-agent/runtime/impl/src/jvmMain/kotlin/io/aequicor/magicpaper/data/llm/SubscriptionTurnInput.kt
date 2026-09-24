package io.aequicor.magicpaper.data.llm

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.json.*

/**
 * A conversation as one plain request to an engine answering on a subscription: the recent dialogue as a labelled
 * transcript with text attachments inline, then the last message's images as data URLs.
 */
internal fun subscriptionTurnInput(messages: List<LlmMessage>, contextMessages: Int): JsonArray = buildJsonArray {
    val conversational = messages.filter { it.role != LlmChatRole.SYSTEM }
    val history = conversational.takeLast(contextMessages.coerceIn(1, 100))
    val transcript = history.joinToString("\n\n") { message ->
        val role = if (message.role == LlmChatRole.USER) "Пользователь" else "Ассистент"
        buildString {
            append(role).append(": ").append(message.content)
            message.attachments.filter { it.kind == AttachmentKind.TEXT }.forEach { attachment ->
                append("\n\nФайл ").append(attachment.name).append(":\n").append(attachment.decodeText())
            }
        }
    }
    add(buildJsonObject { put("type", "text"); put("text", transcript) })
    conversational.lastOrNull()?.attachments
        ?.filter { it.kind == AttachmentKind.IMAGE }
        ?.forEach { attachment ->
            add(buildJsonObject {
                put("type", "image")
                put("url", "data:${attachment.mimeType};base64,${attachment.dataBase64}")
            })
        }
}
