package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val noChatQuestionnaires = MutableStateFlow<List<UserInteractionRequest>>(emptyList()).asStateFlow()

/**
 * Backend of an ordinary chat turn: everything the chat service needs and nothing more.
 *
 * The provider backend implements this boundary independently of the native adapter.
 * A provider backend does not acquire runtime installation or project-execution
 * capabilities. Persisted desktop identities remain values in the conversation.
 */
interface ChatBackend {
    /** Exact saved output only: implementations must not prepare context or start provider/tool work here. */
    suspend fun inspectSavedResponse(request: ChatMachine.RunRef): ChatSavedResponse = ChatSavedResponse.Missing
    val questionnaires: StateFlow<List<UserInteractionRequest>> get() = noChatQuestionnaires
    suspend fun respondQuestionnaire(id: String, answers: List<PlanningAnswer>) {
        error("Опросник недоступен")
    }
    /** Reply using the persisted conversation and the selected provider profile. */
    fun runChat(session: ChatSession, prompt: String, profile: LlmProfile?, attachments: List<Attachment> = emptyList()): Flow<CodingEvent> =
        flowOf(CodingEvent.Failed("Движок чата недоступен на этой платформе."), CodingEvent.Finished)

    /** Cancel only the active request owned by this conversation. */
    fun abort(sessionId: String)

    /** Reconcile backend-owned resources before continuing a prior request. */
    suspend fun reconcile(sessionId: String) = Unit

    suspend fun deleteChatSession(session: ChatSession) { abort(session.id); reconcile(session.id) }
}
