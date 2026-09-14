package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.flow.StateFlow

data class ChatInput(val sessionId: String? = null)
sealed interface ChatOutput { data object Models : ChatOutput; data object ModelSwitcher : ChatOutput }
sealed interface ChatAction {
    data class Send(val text: String, val attachments: List<Attachment> = emptyList()) : ChatAction
    data class SelectModel(val selection: ModelSelection) : ChatAction
}

/** Public component boundary; its renderer is supplied by the owning feature. */
interface ChatComponent {
    val state: StateFlow<ChatState>
    fun onAction(action: ChatAction)
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: ChatInput, onOutput: (ChatOutput) -> Unit): ChatComponent
    }
}
