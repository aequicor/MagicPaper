package io.aequicor.magicpaper.ui.screens
import io.aequicor.magicpaper.ui.components.PaperInlineMessageParts
import io.aequicor.magicpaper.ui.components.rememberPaperInlineMessageParts


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import io.aequicor.magicpaper.designsystem.PaperTitleBarLaneGap
import io.aequicor.magicpaper.designsystem.paperChatTopShadow
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.PinConversation
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.MessageAttachments
import io.aequicor.magicpaper.ui.components.CodingModelChip
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
import io.aequicor.magicpaper.ui.components.PaperChatScrollToBottomButton
import io.aequicor.magicpaper.ui.components.RequestPinsOverlay
import io.aequicor.magicpaper.ui.components.MessagePinColumn
import io.aequicor.magicpaper.ui.components.requestPinNumbers
import io.aequicor.magicpaper.ui.components.paperChatScrollInput
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperActivityIndicator
import io.aequicor.magicpaper.designsystem.PaperActivityTone
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.fullCopyText
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.ui.components.MessageHistoryActions
import io.aequicor.magicpaper.ui.components.ForkSessionAction
import io.aequicor.magicpaper.designsystem.PaperResearchReading
import io.aequicor.magicpaper.designsystem.paperResearchMessage
import io.aequicor.magicpaper.designsystem.PaperContentEntrance
import io.aequicor.magicpaper.designsystem.paperResearchComposerAlignment

/** Research workspace with shared sources and independently resumable questions. */
@Composable
fun ChatScreen(vm: DefaultChatComponent, state: ChatState) {
    // Клавиатуру уже учитывает корневой windowInsetsPadding(WindowInsets.safeDrawing) —
    // ime входит в safeDrawing, поэтому отдельный imePadding здесь не нужен.
    val pins = vm.requestPins?.groups?.collectAsState()?.value.orEmpty()
    ResearchWorkspace(vm, state) {
        PaperResearchReading {
            MessagesList(state.current, state.busy, modifier = Modifier.fillMaxSize(),
                onEdit = { id, text -> vm.editMessage(checkNotNull(state.current).id, id, text) },
                onDelete = { id -> vm.deleteMessage(checkNotNull(state.current).id, id) },
                onFork = { id -> vm.forkSession(checkNotNull(state.current).id, id) },
                pins = state.current?.let { pins[PinConversation(it.id)] }.orEmpty(), footer = {
                    Composer(
                        draftSession = vm.composerDraft,
                        enabled = true,
                        busy = state.busy,
                        paused = state.current?.pendingRun != null && !state.busy,
                        onPause = vm::pause,
                        onResume = vm::resume,
                        onClarify = vm::clarify,
                        session = state.current,
                        profiles = state.modelPickerProfiles,
                        activeProfileId = state.settings.activeLlmProfileId,
                        onSend = { text, attachments -> vm.send(text, attachments) },
                        onOpenSwitcher = { vm.toggleModelSwitcher(true) },
                        defaultEngine = state.settings.defaultCodingEngine,
                        onEngineChange = vm::selectChatEngine,
                        onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
                        onPasteAttachments = { already, onPicked -> vm.pasteAttachments(already, onPicked) },
                    )
                })
        }
    }
}

@Composable
internal fun MessagesList(session: ChatSession?, busy: Boolean, modifier: Modifier = Modifier,
    pins: List<RequestPinGroup> = emptyList(),
    onEdit: (suspend (String, String) -> Result<Unit>)? = null,
    onDelete: (suspend (String) -> Result<Unit>)? = null,
    onFork: (suspend (String?) -> Result<String>)? = null,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    floatingControlsBottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.runtime.key(session?.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    },
    footer: @Composable () -> Unit = {},
) {
    val messages = session?.messages.orEmpty()
    val historyEnabled = !busy && session?.pendingRun?.intent != ExecutionIntent.RUN && session?.queuedPrompts.orEmpty().isEmpty()
    // Держим конец ленты (открыли чат — видно последнее сообщение; ответ агента
    // дорастает — видно его конец, а не начало). Вверх открутили — не мешаем.
    val scroll = paperStickToBottom(listState, session?.id)
    // Messages run edge-to-edge behind the title bar: the frost band blurs them
    // there and ends in a hairline; below it the transcript stays sharp, with a
    // depth shadow once scrolled. Pinned messages keep a lane below the hairline.
    val topInset = LocalWindowToolbarHeight.current ?: 56.dp
    val laneTop = topInset + PaperTitleBarLaneGap
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    // Research is read as a full document. Parsing stays off the UI thread and the outer
    // lazy list composes only visible fragments, including for book-length answers.
    val messageParts = buildMap<String, PaperInlineMessageParts> {
        messages.forEach { message ->
            androidx.compose.runtime.key(message.id) {
                rememberPaperInlineMessageParts(message.text, message.role != ChatRole.USER)?.let { put(message.id, it) }
            }
        }
    }
    val fragments = remember(messages, messageParts) {
        messages.flatMap { message ->
            val parts = messageParts[message.id]
            if (parts == null || parts.size == 0) listOf(ChatMessageFragment(message))
            else (0 until parts.size).map { ChatMessageFragment(message, parts, it) }
        }
    }
    val indices = remember(fragments, onFork != null) {
        fragments.mapIndexedNotNull { index, fragment ->
            if (fragment.index == 0) fragment.message.id to (index + if (onFork != null) 1 else 0) else null
        }.toMap()
    }
    val pinNumbers = remember(pins, indices) { requestPinNumbers(pins, indices.keys) }
    var browserMessageId by remember(scroll) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    // External overlays and the measured footer reserve the same bottom lane.
    val transcriptBottomPadding = maxOf(bottomContentPadding, footerHeight + 4.dp)
    val controlsBottomPadding = maxOf(floatingControlsBottomPadding, footerHeight - 8.dp)
    Box(modifier = Modifier.fillMaxWidth().then(modifier)) {
        if (messages.isNotEmpty() || busy) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().paperChatScrollInput(scroll)
                    .paperChatTopShadow(scrolled, topOffset = topInset),
                contentPadding = PaddingValues(start = 8.dp, top = laneTop + 12.dp, end = 8.dp, bottom = transcriptBottomPadding),
                verticalArrangement = Arrangement.Top,
            ) {
                if (onFork != null) item(key = "fork-session") {
                    ForkSessionAction(true) { onFork(null) }
                }
                items(fragments, key = { it.key }, contentType = { it.message.role }) { fragment ->
                    val message = fragment.message
                    PaperChatScrollItem(scroll, fragment.key) {
                        MessageBubble(message, pinNumbers[message.id], { browserMessageId = message.id },
                            fragment = fragment, actions = {
                                MessageHistoryActions(message.id, message.text, { message.fullCopyText() }, historyEnabled,
                                    onEdit = onEdit?.takeIf { message.role == ChatRole.USER }?.let { action -> { text -> action(message.id, text) } },
                                    onDelete = onDelete?.let { action -> { action(message.id) } },
                                    onFork = onFork?.let { action -> { action(message.id) } })
                            })
                    }
                }
                if (busy) item(key = CHAT_WORKING_STATUS_KEY, contentType = "status") {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperActivityIndicator(PaperActivityTone.WORKING, "Исследую вопрос", running = true)
                        PaperText("Исследую вопрос…", role = PaperTextRole.CHROME,
                            color = LocalPaperColors.current.secondaryText)
                    }
                }
            }
        }
        RequestPinsOverlay(pins, indices, listState, scroll, Modifier.align(Alignment.TopEnd).offset(y = laneTop),
            browserMessageId = browserMessageId, onCloseBrowser = { browserMessageId = null })
        PaperChatScrollToBottomButton(scroll, Modifier.align(Alignment.BottomEnd)
            .padding(end = 8.dp, bottom = controlsBottomPadding))
        Column(Modifier.align(paperResearchComposerAlignment(messages.isEmpty() && !busy))
            .widthIn(max = 860.dp).fillMaxWidth()
            .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }) {
            if (messages.isEmpty() && !busy) PaperContentEntrance(animate = true) { EmptyHint() }
            footer()
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PaperText(
            "Что будем исследовать?",
            role = PaperTextRole.HEADLINE,
        )
        Spacer(Modifier.height(8.dp))
        PaperText("Задайте вопрос и добавьте источники", role = PaperTextRole.CHROME,
            color = LocalPaperColors.current.secondaryText)
    }
}

private const val CHAT_WORKING_STATUS_KEY = "chat-working-status"

private data class ChatMessageFragment(val message: ChatMessage, val parts: PaperInlineMessageParts? = null, val index: Int = 0) {
    val key: String get() = if (index == 0) message.id else "${message.id}:text:$index"
    val first: Boolean get() = index == 0
    val last: Boolean get() = parts == null || index == parts.size - 1
}

@Composable
private fun MessageBubble(message: ChatMessage, pinNumber: Int? = null, onShowPins: () -> Unit = {},
    fragment: ChatMessageFragment = ChatMessageFragment(message),
    actions: @Composable () -> Unit = {}) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (fragment.first) 8.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        MessagePinColumn(
            number = pinNumber.takeIf { isUser && fragment.last }, onClick = onShowPins,
            modifier = Modifier
                .widthIn(max = 820.dp)
                .fillMaxWidth()
                .paperResearchMessage(fragment.first, fragment.last),
        ) {
            if (fragment.first) {
                PaperText(if (isUser) "Вопрос" else "Исследование", role = PaperTextRole.CHROME,
                    color = LocalPaperColors.current.secondaryText)
                Spacer(Modifier.height(12.dp))
            }
            if (fragment.parts != null) {
                fragment.parts.Content(fragment.index)
            } else if (message.text.isNotEmpty()) PaperText("Подготавливаю сообщение…", role = PaperTextRole.LABEL)
            if (isUser && fragment.last) MessageAttachments(message.attachments)
            if (fragment.last && message.sources.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PaperDivider()
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Column {
                        PaperText(
                            "Источники:",
                            role = PaperTextRole.LABEL,
                            color = LocalPaperColors.current.secondaryText,
                        )
                        message.sources.forEach { hit ->
                            PaperText(
                                text = "• ${hit.title} — ${hit.url}",
                                role = PaperTextRole.BODY,
                                color = LocalPaperColors.current.action,
                            )
                        }
                    }
                }
            }
            if (fragment.last) actions()
        }
    }
}

/** Чип текущей модели в композиции: тап открывает переключатель источника. */
@Composable
private fun ModelChip(
    session: ChatSession?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    onClick: () -> Unit,
) {
    val resolved = ProfileResolver.resolve(session, io.aequicor.magicpaper.domain.AppSettings(activeLlmProfileId = activeProfileId), profiles)
    CodingModelChip(resolved, overridden = session?.llmProfileId != null, onClick = onClick)
}

@Composable
internal fun Composer(
    enabled: Boolean,
    session: ChatSession?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    onSend: (String, List<Attachment>) -> Unit,
    onOpenSwitcher: () -> Unit,
    defaultEngine: CodingEngine = CodingEngine.PI,
    onEngineChange: ((CodingEngine) -> Unit)? = null,
    onPickAttachments: (Int, (List<Attachment>) -> Unit) -> Unit,
    onPasteAttachments: (Int, (List<Attachment>) -> Unit) -> Boolean = { _, _ -> false },
    draftSession: io.aequicor.magicpaper.data.storage.DraftSession<io.aequicor.magicpaper.domain.ComposerDraftData>? = null,
    busy: Boolean = false,
    paused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: (String, List<Attachment>) -> Unit = onSend,
    onClarify: (String, List<Attachment>) -> Unit = onSend,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val draft = remember(session?.id, draftSession) {
        io.aequicor.magicpaper.ui.components.CodingComposerDraft(draftSession, scope)
    }
    fun accepted(action: (String, List<Attachment>) -> Unit, text: String, attachments: List<Attachment>) {
        action(text, attachments)
        // Standalone previews have no service to acknowledge durable acceptance.
        if (draftSession == null) { draft.text.value = ""; draft.attachments.value = emptyList() }
    }
    draft.error.value?.let { PaperText("Не удалось сохранить черновик", color = LocalPaperColors.current.error) }
    CodingComposer(
        state = draft, enabled = enabled, busy = busy,
        promptPlaceholder = if (session?.messages.isNullOrEmpty()) "Сформулируйте вопрос…" else "Уточните вопрос или продолжите исследование…",
        controls = { ModelChip(session, profiles, activeProfileId, onOpenSwitcher) },
        onSend = { text, attachments -> accepted(onSend, text, attachments) },
        onResume = if (paused) { text, attachments -> accepted(onResume, text, attachments) } else null,
        onClarify = { text, attachments -> accepted(onClarify, text, attachments) },
        onAbort = onPause, onPickAttachments = onPickAttachments, onPasteAttachments = onPasteAttachments,
        engine = session?.engine ?: defaultEngine,
        onEngineChange = onEngineChange?.takeIf {
            session == null || (session.messages.isEmpty() && session.pendingRun == null && session.nativeSessionId.isBlank())
        },
    )
}
