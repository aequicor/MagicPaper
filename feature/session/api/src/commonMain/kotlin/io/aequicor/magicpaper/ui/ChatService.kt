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
    fun deleteSession(id: String)
    fun send(text: String, attachments: List<Attachment> = emptyList())
    fun pause()
    fun resume(text: String = "", attachments: List<Attachment> = emptyList())
    fun clarify(text: String, attachments: List<Attachment> = emptyList())
    fun selectChatModel(selection: ModelSelection)
    fun selectChatEngine(engine: CodingEngine)
    suspend fun close()
}
