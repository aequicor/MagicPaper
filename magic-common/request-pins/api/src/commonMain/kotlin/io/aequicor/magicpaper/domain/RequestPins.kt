package io.aequicor.magicpaper.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A separate namespace keeps ordinary chats and coding sessions independent. */
@Serializable
data class PinConversation(val sessionId: String, val projectId: String? = null)

/** Only readable text and attachment names enter analysis; delivery status and tools do not. */
@Serializable
data class PinMessage(
    val id: String,
    val text: String,
    val input: Boolean,
    val author: String = "Пользователь",
    val attachments: List<String> = emptyList(),
    val pinnable: Boolean = true,
    val clarification: Boolean = false,
)

fun ChatSession.pinMessages(): List<PinMessage> = messages.map {
    PinMessage(it.id, it.text, it.role == ChatRole.USER, attachments = it.attachments.map { file -> file.name })
}

fun List<CodingMessage>.pinMessages(planningMode: Boolean = false): List<PinMessage> {
    val deliveries = mutableSetOf<String>()
    return filter { it.handoff == null && !it.systemContext && !it.systemNotice }.mapNotNull { message ->
        val input = message.role == CodingRole.USER
        val delivery = message.deliveryId ?: message.route?.deliveryId
        if (input && delivery != null && !deliveries.add(delivery)) return@mapNotNull null
        // AGENT records, including routed questions, provide context but are never pinned.
        PinMessage(message.id, message.text, input,
            author = message.route?.source?.takeUnless { it.sessionId == "user" }?.let {
                it.subtitle.ifBlank { it.name }
            }?.takeIf { it.isNotBlank() } ?: "Пользователь",
            attachments = message.attachments.map { it.name },
            // Questions and unclassified inputs remain context. The orchestrator's saved
            // decision owns eligibility; a second model only summarizes eligible requests.
            pinnable = !planningMode || message.planning?.inputIntent in setOf(
                UserTurnIntent.REFINE, UserTurnIntent.CLARIFY, UserTurnIntent.ANSWER,
                UserTurnIntent.INSTRUCT, UserTurnIntent.SCHEDULE),
            clarification = planningMode && message.planning?.inputIntent in setOf(
                UserTurnIntent.CLARIFY, UserTurnIntent.ANSWER, UserTurnIntent.INSTRUCT))
    }
}

@Serializable
data class RequestPinRecord(
    val source: PinMessage,
    val summary: String,
    val newRequest: Boolean = true,
    val analysed: Boolean = false,
)

data class RequestPin(val messageId: String, val summary: String, val author: String)
data class RequestPinGroup(val request: RequestPin, val clarifications: List<RequestPin> = emptyList())

interface RequestPinRepository {
    fun load(conversation: PinConversation): List<RequestPinRecord>
    fun save(conversation: PinConversation, records: List<RequestPinRecord>)
    fun delete(conversation: PinConversation)
    fun clear()
}

interface RequestPinService {
    val groups: kotlinx.coroutines.flow.StateFlow<Map<PinConversation, List<RequestPinGroup>>>
    val failures: kotlinx.coroutines.flow.StateFlow<Map<PinConversation, String>>
    fun isTracking(conversation: PinConversation): Boolean
    fun sync(conversation: PinConversation, messages: List<PinMessage>, profile: LlmProfile?, reopened: Boolean = false)
    fun remove(conversation: PinConversation)
    fun clear()
}
