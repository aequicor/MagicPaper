package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

interface ChatService {
    val state: StateFlow<ChatState>
    val requestPins: RequestPinService?
    val mediaGeneration: MediaGenerationService? get() = null
    fun setMediaToolEnabled(sessionId: String, kind: MediaKind, enabled: Boolean)
    fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean = false)
    suspend fun start()
    fun activate(id: String?)
    fun setVisible(visible: Boolean)
    fun dismissNotice()
    fun newSession()
    fun selectSession(id: String)
    suspend fun newQuestion(): Result<Unit>
    suspend fun selectQuestion(id: String): Result<Unit>
    suspend fun addWebsite(questionId: String, url: String, scope: ResearchResourceScope = ResearchResourceScope.SHARED): Result<Unit>
    suspend fun searchResources(query: String): Result<List<SearchHit>>
    suspend fun addSearchResult(questionId: String, hit: SearchHit, scope: ResearchResourceScope = ResearchResourceScope.SHARED): Result<Unit>
    suspend fun addResources(questionId: String, attachments: List<Attachment>, scope: ResearchResourceScope = ResearchResourceScope.SHARED): Result<Unit>
    suspend fun removeResource(questionId: String, resourceId: String, scope: ResearchResourceScope = ResearchResourceScope.SHARED): Result<Unit>
    suspend fun setResourceEnabled(questionId: String, resourceKey: String, enabled: Boolean): Result<Unit>
    /** Changes the whole selection in one saved edit, for this question only. */
    suspend fun setResourcesEnabled(questionId: String, resourceKeys: Set<String>, enabled: Boolean): Result<Unit>
    suspend fun shareResource(questionId: String, resourceId: String): Result<Unit>
    fun openSourceBrowser(questionId: String, resourceKey: String)
    fun readSourceBrowser()
    fun dismissSourceBrowser()
    fun deleteSession(id: String)
    fun archiveSession(id: String)
    fun restoreSession(id: String)
    suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit>
    suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit>
    suspend fun forkSession(sessionId: String, throughMessageId: String? = null): Result<String>
    fun send(text: String, attachments: List<Attachment> = emptyList())
    /** Sends a displayed continuation in its original question, preserving the composer draft. */
    fun sendFollowUp(sessionId: String, messageId: String, question: String)
    fun pause()
    fun resume(text: String = "", attachments: List<Attachment> = emptyList())
    fun clarify(text: String, attachments: List<Attachment> = emptyList())
    fun selectChatModel(selection: ModelSelection)
    fun selectChatEngine(engine: CodingEngine)
    suspend fun close()
}
