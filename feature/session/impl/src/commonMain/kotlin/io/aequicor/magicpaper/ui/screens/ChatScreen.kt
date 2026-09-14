package io.aequicor.magicpaper.ui.screens
import androidx.compose.runtime.CompositionLocalProvider
import io.aequicor.magicpaper.ui.components.PaperInlineMessageParts
import io.aequicor.magicpaper.ui.components.PaperMessageExpansion
import io.aequicor.magicpaper.ui.components.LocalPaperMessageExpansion
import io.aequicor.magicpaper.ui.components.rememberPaperInlineMessageParts
import io.aequicor.magicpaper.ui.components.PaperPreserveInlineExpansion
import io.aequicor.magicpaper.ui.components.PaperCollapseMessage


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.MAX_ATTACHMENTS_PER_MESSAGE
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ModelDefaults
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.domain.PinConversation
import io.aequicor.magicpaper.domain.RequestPinGroup
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.PaperChatMarkdown
import io.aequicor.magicpaper.ui.components.PaperChatPlainText
import io.aequicor.magicpaper.ui.components.MessageAttachments
import io.aequicor.magicpaper.ui.components.PendingAttachmentsRow
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
import io.aequicor.magicpaper.ui.components.PaperChatScrollToBottomButton
import io.aequicor.magicpaper.ui.components.RequestPinsOverlay
import io.aequicor.magicpaper.ui.components.MessagePinColumn
import io.aequicor.magicpaper.ui.components.requestPinNumbers
import io.aequicor.magicpaper.ui.components.paperChatScrollInput
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperComposer
import io.aequicor.magicpaper.designsystem.PaperComposerField
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperProgress
import io.aequicor.magicpaper.designsystem.PaperProgressKind
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole

/** Экран чата: лента сообщений и поле заклинаний. */
@Composable
fun ChatScreen(vm: DefaultChatComponent, state: ChatState) {
    // Клавиатуру уже учитывает корневой windowInsetsPadding(WindowInsets.safeDrawing) —
    // ime входит в safeDrawing, поэтому отдельный imePadding здесь не нужен.
    Column(modifier = Modifier.fillMaxSize()) {
        val pins = vm.requestPins?.groups?.collectAsState()?.value.orEmpty()
        MessagesList(state.current, state.busy, modifier = Modifier.weight(1f),
            pins = state.current?.let { pins[PinConversation(it.id)] }.orEmpty())
        Composer(
            draftSession = vm.composerDraft,
            enabled = true,
            busy = state.busy,
            paused = state.current?.pendingRun != null && !state.busy,
            onPause = vm::pause,
            onResume = vm::resume,
            onClarify = vm::clarify,
            session = state.current,
            profiles = state.availableLlmProfiles,
            activeProfileId = state.settings.activeLlmProfileId,
            onSend = { text, attachments -> vm.send(text, attachments) },
            onOpenSwitcher = { vm.toggleModelSwitcher(true) },
            onPickAttachments = { already, onPicked -> vm.pickAttachments(already, onPicked) },
            onPasteAttachments = { already, onPicked -> vm.pasteAttachments(already, onPicked) },
        )
    }
}

@Composable
internal fun MessagesList(session: ChatSession?, busy: Boolean, modifier: Modifier = Modifier,
    pins: List<RequestPinGroup> = emptyList(),
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.runtime.key(session?.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    },
) {
    val messages = session?.messages.orEmpty()
    // Держим конец ленты (открыли чат — видно последнее сообщение; ответ агента
    // дорастает — видно его конец, а не начало). Вверх открутили — не мешаем.
    val scroll = paperStickToBottom(listState, session?.id)
    var expandedMessages by rememberSaveable(session?.id) { mutableStateOf(emptyList<String>()) }
    val expandedParts = buildMap<String, PaperInlineMessageParts> {
        messages.filter { it.id in expandedMessages }.forEach { message ->
            androidx.compose.runtime.key(message.id) {
                rememberPaperInlineMessageParts(message.text, message.role != ChatRole.USER)?.let { put(message.id, it) }
            }
        }
    }
    val fragments = remember(messages, expandedParts) {
        messages.flatMap { message ->
            val parts = expandedParts[message.id]
            if (parts == null || parts.size == 0) listOf(ChatMessageFragment(message))
            else (0 until parts.size).map { ChatMessageFragment(message, parts, it) }
        }
    }
    val indices = remember(fragments) {
        fragments.mapIndexedNotNull { index, fragment ->
            if (fragment.index == 0) fragment.message.id to index else null
        }.toMap()
    }
    PaperPreserveInlineExpansion(expandedParts, scroll)
    val pinNumbers = remember(pins, indices) { requestPinNumbers(pins, indices.keys) }
    var browserMessageId by remember(scroll) { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxWidth().then(modifier)) {
        if (messages.isEmpty()) {
            EmptyHint()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().paperChatScrollInput(scroll),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                items(fragments, key = { it.key }, contentType = { it.message.role }) { fragment ->
                    val message = fragment.message
                    PaperChatScrollItem(scroll, fragment.key) {
                        CompositionLocalProvider(LocalPaperMessageExpansion provides PaperMessageExpansion(message.text,
                            { expandedMessages = expandedMessages + message.id })) {
                            MessageBubble(message, pinNumbers[message.id], { browserMessageId = message.id },
                                fragment = fragment, onCollapse = {
                                    listState.requestScrollToItem(indices.getValue(message.id))
                                    expandedMessages = expandedMessages - message.id
                                })
                        }
                    }
                }
            }
        }
        RequestPinsOverlay(pins, indices, listState, scroll, Modifier.align(Alignment.TopEnd),
            browserMessageId = browserMessageId, onCloseBrowser = { browserMessageId = null })
        PaperChatScrollToBottomButton(scroll, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp))
        AnimatedVisibility(
            visible = busy,
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PaperProgress(modifier = Modifier.size(14.dp), kind = PaperProgressKind.CIRCULAR, label = "Чары плетутся")
                Spacer(Modifier.width(8.dp))
                PaperText(
                    text = "Чары плетутся…",
                    role = PaperTextRole.BODY,
                    color = LocalPaperColors.current.secondaryText,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PaperText("✦", role = PaperTextRole.HEADLINE, color = LocalPaperColors.current.action)
        Spacer(Modifier.height(8.dp))
        PaperText(
            "Свиток пуст. Задайте вопрос — и бумага ответит.",
            role = PaperTextRole.BODY,
            color = LocalPaperColors.current.secondaryText,
        )
    }
}

private data class ChatMessageFragment(val message: ChatMessage, val parts: PaperInlineMessageParts? = null, val index: Int = 0) {
    val key: String get() = if (index == 0) message.id else "${message.id}:text:$index"
    val first: Boolean get() = index == 0
    val last: Boolean get() = parts == null || index == parts.size - 1
}

@Composable
private fun MessageBubble(message: ChatMessage, pinNumber: Int? = null, onShowPins: () -> Unit = {},
    fragment: ChatMessageFragment = ChatMessageFragment(message), onCollapse: () -> Unit = {}) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (fragment.first) 10.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        PaperPanel(
            kind = if (isUser) PaperSurfaceKind.SELECTED else PaperSurfaceKind.PANEL,
            modifier = Modifier
                // На узких экранах бабл не должна занимать всю ширину —
                // 100% не даёт читаемой строки.
                .widthIn(max = 560.dp)
                .then(if (fragment.parts != null) Modifier.fillMaxWidth() else Modifier)
                .clip(
                    // «хвост» бабла со стороны автора: верхний угол у его края — почти острый.
                    RoundedCornerShape(
                        topStart = if (!fragment.first) 0.dp else if (isUser) 20.dp else 6.dp,
                        topEnd = if (!fragment.first) 0.dp else if (isUser) 6.dp else 20.dp,
                        bottomStart = if (fragment.last) 20.dp else 0.dp,
                        bottomEnd = if (fragment.last) 20.dp else 0.dp,
                    )
                )
                ,
        ) {
            MessagePinColumn(number = pinNumber.takeIf { isUser && fragment.last }, onClick = onShowPins,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (fragment.first) 10.dp else 0.dp,
                    bottom = if (fragment.last) 10.dp else 0.dp)) {
            if (fragment.parts != null) {
                fragment.parts.Content(fragment.index)
                if (fragment.last) PaperCollapseMessage(onCollapse)
            } else if (isUser) {
                // Пользователь пишет обычный текст — без разметки.
                PaperChatPlainText(message.text)
                // Прикреплённые файлы: миниатюры изображений, файлы чипами.

            } else {
                // Ответ агента рендерим как markdown: заголовки, списки,
                // блоки кода с подсветкой синтаксиса и кнопкой копирования.
                PaperChatMarkdown(message.text)
            }
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
            }
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
    val overridden = session?.llmProfileId != null
    PaperAction(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        if (resolved == null) {
            PaperText(
                "✦ Источник не подключён",
                role = PaperTextRole.BODY,
                color = LocalPaperColors.current.error,
            )
        } else {
            // Подпись с учётом словаря модели: при подмене уровня видно «х-выс→выс».
            val effortGlyph = resolved.effortLabel(
                ModelDefaults.capability(resolved),
                resolved.modelId,
            )
            PaperText(
                "${if (overridden) "◌ " else ""}✦ ${resolved.shortLabel} · $effortGlyph ▾",
                role = PaperTextRole.BODY,
                color = LocalPaperColors.current.action,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun Composer(
    enabled: Boolean,
    session: ChatSession?,
    profiles: List<LlmProfile>,
    activeProfileId: String,
    onSend: (String, List<Attachment>) -> Unit,
    onOpenSwitcher: () -> Unit,
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
        controls = { ModelChip(session, profiles, activeProfileId, onOpenSwitcher) },
        onSend = { text, attachments -> accepted(onSend, text, attachments) },
        onResume = if (paused) { text, attachments -> accepted(onResume, text, attachments) } else null,
        onClarify = { text, attachments -> accepted(onClarify, text, attachments) },
        onAbort = onPause, onPickAttachments = onPickAttachments, onPasteAttachments = onPasteAttachments,
        directAttachmentAction = true,
    )
}
