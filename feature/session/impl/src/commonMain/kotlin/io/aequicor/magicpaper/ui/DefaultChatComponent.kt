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

class DefaultChatComponent internal constructor(
    context: ComponentContext,
    private val service: DefaultChatService,
    private val input: ChatInput,
    filePicker: FilePicker,
    private val onOutput: (ChatOutput) -> Unit,
    internal val workspacePresentation: ChatWorkspacePresentationStore,
) : ChatComponent, ChatService by service {
    constructor(
        context: ComponentContext,
        service: DefaultChatService,
        input: ChatInput,
        filePicker: FilePicker,
        onOutput: (ChatOutput) -> Unit,
    ) : this(context, service, input, filePicker, onOutput, ChatWorkspacePresentationStore())
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
    private val workspacePresentation = ChatWorkspacePresentationStore()
    override fun create(context: ComponentContext, input: ChatInput, onOutput: (ChatOutput) -> Unit): ChatComponent =
        DefaultChatComponent(context, service, input, filePicker, onOutput, workspacePresentation)
}

internal data class ChatWorkspacePresentation(
    val questionsExpanded: Boolean = true,
    val sourcesExpanded: Boolean = true,
    val questionsWidth: Float = 252f,
    val sourcesWidth: Float = 304f,
)

/** Shared by visit components so a notebook keeps its panel configuration while sessions change. */
internal class ChatWorkspacePresentationStore {
    private val values = mutableStateMapOf<String, ChatWorkspacePresentation>()

    fun state(notebookId: String?): ChatWorkspacePresentation =
        notebookId?.let { values[it] } ?: ChatWorkspacePresentation()

    fun update(notebookId: String?, change: (ChatWorkspacePresentation) -> ChatWorkspacePresentation) {
        if (notebookId != null) values[notebookId] = change(state(notebookId))
    }
}
