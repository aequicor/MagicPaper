package io.aequicor.magicpaper.ui

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.essenty.lifecycle.doOnPause
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.ui.screens.*
import kotlinx.coroutines.*

class DefaultChatComponent(
    context: ComponentContext,
    private val service: DefaultChatService,
    private val input: ChatInput,
    filePicker: FilePicker,
    private val onOutput: (ChatOutput) -> Unit,
) : ChatComponent, ChatService by service {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val picker = AttachmentSelection(filePicker, scope)
    val composerDraft get() = service.composerDraft(state.value.current?.id ?: input.sessionId)
    init {
        context.lifecycle.doOnResume { service.activate(input.sessionId) }
        context.lifecycle.doOnPause { service.setVisible(false) }
        context.lifecycle.doOnDestroy { scope.cancel() }
    }
    fun pickAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pickAttachments(count, result)
    fun pasteAttachments(count: Int, result: (List<Attachment>) -> Unit) = picker.pasteAttachments(count, result)
    fun toggleModelSwitcher(open: Boolean) { if (open) onOutput(ChatOutput.ModelSwitcher) }
    fun openModelsSettings() = onOutput(ChatOutput.Models)
    override fun onAction(action: ChatAction) {
        when(action) {
            is ChatAction.Send -> send(action.text, action.attachments)
            is ChatAction.SelectModel -> selectChatModel(action.selection)
        }
    }
    @Composable override fun Content() {
        val current by state.collectAsState()
        val error by picker.error.collectAsState()
        error?.let { PaperText(it) }
        if (input.sessionId != null && current.sessions.none { it.id == input.sessionId }) PaperText("Чат не найден")
        else ChatScreen(this, current)
    }
}

class DefaultChatComponentFactory(private val service: DefaultChatService, private val filePicker: FilePicker) : ChatComponent.Factory {
    override fun create(context: ComponentContext, input: ChatInput, onOutput: (ChatOutput) -> Unit): ChatComponent =
        DefaultChatComponent(context, service, input, filePicker, onOutput)
}
