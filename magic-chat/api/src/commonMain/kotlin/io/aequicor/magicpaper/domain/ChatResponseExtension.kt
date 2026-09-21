package io.aequicor.magicpaper.domain

/**
 * Optional model/application integration, installed by the host. Preparing captures the
 * user's current selection synchronously; it must not start work or write storage.
 * The chat owns request acceptance and invokes the prepared response only afterwards.
 */
interface ChatResponseExtension {
    fun prepare(session: ChatSession, text: String, requestId: String): PreparedChatResponse?
}

/** A request-scoped model port. Its implementation owns captured external context. */
interface PreparedChatResponse {
    /** Stable identity to reuse on the next request; it carries no execution capability. */
    val bindingId: String?
    suspend fun answer(history: List<ChatMessage>, profile: LlmProfile?, attachments: List<Attachment>): SessionAnswer
}
