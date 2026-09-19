package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Backend of an ordinary chat turn: everything the chat service needs and nothing more.
 *
 * Two implementations exist. Desktop answers through the native engine, which gives the
 * model a tool loop (media generation, native questionnaire, engine-side attachment
 * reading); other platforms answer over HTTP, where [LlmGateway] completes a message list
 * and cannot call a tool. Until a gateway can run tool-calling chains, the two are not
 * interchangeable, so the choice belongs to the platform that composes the application.
 */
interface ChatBackend {
    /** Ordinary chat uses the selected session backend in its private workspace. */
    fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment> = emptyList()): Flow<CodingEvent> =
        flowOf(CodingEvent.Failed("Движок чата недоступен на этой платформе."), CodingEvent.Finished)

    /** Прервать прогон конкретной сессии (остановить её процесс агента). */
    fun abort(sessionId: String)

    /** Reconcile a prior run before reusing its workspace after application restart. */
    suspend fun reconcile(sessionId: String) = Unit

    suspend fun deleteChatSession(session: ChatSession) { abort(session.id); reconcile(session.id) }
}
