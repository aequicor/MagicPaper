package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*

/** Application-owned capabilities for ordinary conversations, independent of native engines. */
interface ChatToolSessions {
    val questions: RuntimeQuestionnaireService
    suspend fun create(session: ChatSession, requestId: String, settings: AppSettings, allowSearch: Boolean): ToolSession
}
