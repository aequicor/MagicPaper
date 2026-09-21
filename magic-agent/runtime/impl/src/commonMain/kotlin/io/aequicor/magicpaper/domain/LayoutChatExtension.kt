package io.aequicor.magicpaper.domain

/** Native artifact authoring is an installed response capability, never part of HTTP chat. */
class LayoutChatExtension(
    private val agent: LayoutChatAgent,
    private val project: (String?) -> CodingProject?,
) : ChatResponseExtension {
    override fun prepare(session: ChatSession, text: String, requestId: String): PreparedChatResponse? {
        if (!isLayoutRequest(text, session.layoutProjectId != null)) return null
        // Capture the whole project, including its path, before any coroutine can suspend.
        // A later sidebar selection cannot redirect an accepted artifact request.
        val captured = project(session.layoutProjectId)
        return object : PreparedChatResponse {
            override val bindingId = session.layoutProjectId ?: captured?.id
            override suspend fun answer(history: List<ChatMessage>, profile: LlmProfile?, attachments: List<Attachment>) =
                agent.answer(captured, session.id, requestId, text, history, profile, attachments)
        }
    }
}
