package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

interface ChatService {
    val state: StateFlow<ChatState>
    val requestPins: RequestPinService?
    fun updateConfiguration(settings: AppSettings, profiles: List<LlmProfile>, subscriptionAvailable: Boolean, subscriptionSignedIn: Boolean = false)
    suspend fun start()
    fun activate(id: String?)
    fun setVisible(visible: Boolean)
    fun dismissNotice()
    fun newSession()
    fun selectSession(id: String)
    suspend fun newQuestion(): Result<Unit>
    suspend fun selectQuestion(id: String): Result<Unit>
    suspend fun addWebsite(chatId: String, url: String): Result<Unit>
    suspend fun addResources(chatId: String, attachments: List<Attachment>): Result<Unit>
    suspend fun removeResource(chatId: String, resourceId: String): Result<Unit>
    fun deleteSession(id: String)
    fun archiveSession(id: String)
    fun restoreSession(id: String)
    suspend fun editMessage(sessionId: String, messageId: String, text: String): Result<Unit>
    suspend fun deleteMessage(sessionId: String, messageId: String): Result<Unit>
    suspend fun forkSession(sessionId: String, throughMessageId: String? = null): Result<String>
    fun send(text: String, attachments: List<Attachment> = emptyList())
    fun pause()
    fun resume(text: String = "", attachments: List<Attachment> = emptyList())
    fun clarify(text: String, attachments: List<Attachment> = emptyList())
    fun selectChatModel(selection: ModelSelection)
    fun selectChatEngine(engine: CodingEngine)
    suspend fun close()
}
