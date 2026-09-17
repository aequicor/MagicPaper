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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import io.aequicor.magicpaper.designsystem.PaperTitleBarLaneGap
import io.aequicor.magicpaper.designsystem.paperChatTopShadow
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import androidx.compose.foundation.layout.widthIn
import io.aequicor.magicpaper.designsystem.PaperLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ChatMessage
import io.aequicor.magicpaper.domain.ChatRole
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProfileResolver
import io.aequicor.magicpaper.ui.DefaultChatComponent
import io.aequicor.magicpaper.ui.ChatState
import io.aequicor.magicpaper.ui.components.MessageAttachments
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
import io.aequicor.magicpaper.ui.components.PaperChatScrollToBottomButton
import io.aequicor.magicpaper.ui.components.paperChatScrollInput
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.fullCopyText
import io.aequicor.magicpaper.domain.ExecutionIntent
import io.aequicor.magicpaper.ui.components.MessageHistoryActions
import io.aequicor.magicpaper.designsystem.PaperResearchReading
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.paperResearchMessage
import io.aequicor.magicpaper.designsystem.PaperContentEntrance
import io.aequicor.magicpaper.designsystem.PaperResearchReadingMeasure
import io.aequicor.magicpaper.designsystem.paperResearchComposerAlignment
import io.aequicor.magicpaper.designsystem.PaperResearchSourceLink
import io.aequicor.magicpaper.designsystem.PaperResearchSourcesDisclosure
import io.aequicor.magicpaper.designsystem.PaperResearchFollowUps
import io.aequicor.magicpaper.logging.AppLog

/** Research workspace with shared sources and independently resumable questions. */
@Composable
fun ChatScreen(vm: DefaultChatComponent, state: ChatState) {
    // Клавиатуру уже учитывает корневой windowInsetsPadding(WindowInsets.safeDrawing) —
    // ime входит в safeDrawing, поэтому отдельный imePadding здесь не нужен.
    val usage = vm.usage?.state?.collectAsState()?.value
    val profile = ProfileResolver.resolve(state.current, state.settings, state.availableLlmProfiles)
    val draft = state.current?.id?.let { state.drafts[it] }
    val context = usage?.contexts?.get("chat:${state.current?.id}")?.takeIf { it.model == profile?.modelId }
    ResearchWorkspace(vm, state) {
        PaperResearchReading {
            MessagesList(state.current, state.busy, draft = draft, modifier = Modifier.fillMaxSize(),
                onEdit = { id, text -> vm.editMessage(checkNotNull(state.current).id, id, text) },
                onDelete = { id -> vm.deleteMessage(checkNotNull(state.current).id, id) },
                onFork = { id -> vm.forkSession(checkNotNull(state.current).id, id) },
                onFollowUp = { id, question -> vm.sendFollowUp(checkNotNull(state.current).id, id, question) },
                onPause = vm::pause, onResume = { vm.resume("", emptyList()) }, footer = {
                    Composer(
                        draftSession = vm.composerDraft,
                        enabled = true,
                        resolvedProfile = profile,
                        contextUsage = context,
                        contextCompacting = draft?.steps?.any { it.systemEvent?.phase == CompactionPhase.STARTED && it.running } == true,
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
    draft: CodingDraft? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onEdit: (suspend (String, String) -> Result<Unit>)? = null,
    onDelete: (suspend (String) -> Result<Unit>)? = null,
    onFork: (suspend (String?) -> Result<String>)? = null,
    onFollowUp: ((String, String) -> Unit)? = null,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    floatingControlsBottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.runtime.key(session?.id) {
        rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
    },
    footer: @Composable () -> Unit = {},
) {
    val liveSteps = draft?.steps ?: session?.pendingActivity.orEmpty()
    val pendingId = session?.pendingRun?.responseId?.ifBlank { null } ?: CHAT_WORKING_STATUS_KEY
    val liveMessage = if (busy || session?.pendingRun != null) ChatMessage(pendingId, ChatRole.AGENT,
        researchReply(liveSteps.filter { it.kind == CodingStepKind.ANSWER }.joinToString("\n\n") { it.title }, streaming = true).text,
        session?.updatedAt ?: 0, researchActivity = liveSteps.researchActivity()) else null
    val savedMessages = remember(session?.messages) {
        session?.messages.orEmpty().map { message ->
            val reply = message.researchReply()
            if (message.text == reply.text && message.followUps == reply.followUps) message
            else message.copy(text = reply.text, followUps = reply.followUps)
        }
    }
    val messages = savedMessages + listOfNotNull(liveMessage)
    val historyEnabled = !busy && session?.pendingRun?.intent != ExecutionIntent.RUN && session?.queuedPrompts.orEmpty().isEmpty()
    // Держим конец ленты (открыли чат — видно последнее сообщение; ответ агента
    // дорастает — видно его конец, а не начало). Вверх открутили — не мешаем.
    val scroll = paperStickToBottom(listState, session?.id)
    // Messages run edge-to-edge behind the title bar: the frost band blurs them
    // there and ends in a hairline; below it the transcript stays sharp, with a
    // depth shadow once scrolled. Research has no sticky message or pin overlay.
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
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(0.dp) }
    // External overlays and the measured footer reserve the same bottom lane.
    val transcriptBottomPadding = maxOf(bottomContentPadding, footerHeight + 4.dp)
    val controlsBottomPadding = maxOf(floatingControlsBottomPadding, footerHeight - 8.dp)
    Box(modifier = Modifier.fillMaxWidth().then(modifier)) {
        if (messages.isNotEmpty() || busy) {
            PaperLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().paperChatScrollInput(scroll)
                    .paperChatTopShadow(scrolled, topOffset = topInset),
                contentPadding = PaddingValues(start = 12.dp, top = laneTop + 12.dp, end = 12.dp, bottom = transcriptBottomPadding),
                verticalArrangement = Arrangement.Top,
            ) {
                items(fragments, key = { it.key }, contentType = { it.contentType }) { fragment ->
                    val message = fragment.message
                    PaperChatScrollItem(scroll, fragment.key) {
                        MessageBubble(message,
                            fragment = fragment, working = message.id == pendingId && busy,
                            paused = message.id == pendingId && !busy && session?.pendingRun != null,
                            failed = message.id == pendingId && draft?.failedMessage != null,
                            showFollowUps = message.id == savedMessages.lastOrNull()?.id,
                            onFollowUp = onFollowUp?.takeIf { historyEnabled && session?.pendingRun == null &&
                                message.id == savedMessages.lastOrNull()?.id }?.let { send -> { question -> send(message.id, question) } },
                            onPause = onPause, onResume = onResume, actions = { content ->
                                MessageHistoryActions(message.id, message.text, { message.fullCopyText() }, historyEnabled,
                                    onEdit = onEdit?.takeIf { message.role == ChatRole.USER }?.let { action -> { text -> action(message.id, text) } },
                                    onDelete = onDelete?.let { action -> { action(message.id) } },
                                    onFork = onFork?.takeIf { message.id != pendingId }?.let { action -> { action(message.id) } }, content = content)
                            })
                    }
                }

            }
        }
        PaperChatScrollToBottomButton(scroll, Modifier.align(Alignment.BottomEnd)
            .padding(end = 8.dp, bottom = controlsBottomPadding))
        Column(Modifier.align(paperResearchComposerAlignment(messages.isEmpty() && !busy))
            .widthIn(max = PaperResearchReadingMeasure).fillMaxWidth()
            .padding(horizontal = 16.dp)
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
    val contentType = Triple(message.role, parts?.contentType(index), first to last)
}

@Composable
private fun MessageBubble(message: ChatMessage,
    fragment: ChatMessageFragment = ChatMessageFragment(message),
    working: Boolean = false, paused: Boolean = false, failed: Boolean = false,
    onPause: (() -> Unit)? = null, onResume: (() -> Unit)? = null,
    onFollowUp: ((String) -> Unit)? = null,
    showFollowUps: Boolean = false,
    actions: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
    val isUser = message.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (fragment.first) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.widthIn(max = PaperResearchReadingMeasure).fillMaxWidth()) {
            actions {
                Column(modifier = Modifier.fillMaxWidth()
                        .paperResearchMessage(fragment.first, fragment.last, isUser),
                ) {
                    if (fragment.first && !isUser && (message.researchActivity.isNotEmpty() || working || paused)) {
                        ResearchActivity(message.researchActivity, working, paused, failed, onPause, onResume,
                            answering = message.text.isNotBlank())
                    }
                    if (fragment.parts != null) {
                        fragment.parts.Content(fragment.index)
                    } else if (message.text.isNotEmpty()) PaperText("Подготавливаю сообщение…", role = PaperTextRole.LABEL)
                    if (!isUser && fragment.last && message.sources.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        ResearchSourceFootnotes(message)
                    }
                    if (isUser && fragment.last) MessageAttachments(message.attachments)
                    if (!isUser && fragment.last && showFollowUps && message.followUps.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        PaperResearchFollowUps(message.followUps, { onFollowUp?.invoke(it) }, enabled = onFollowUp != null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResearchSourceFootnotes(message: ChatMessage) {
    val uriHandler = LocalUriHandler.current
    var openError by remember(message.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val sources = remember(message.sources) { message.sources.distinctBy { it.url } }
    Column {
        PaperDivider()
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PaperResearchSourcesDisclosure(sources.size, expanded, { expanded = it })
            if (openError) PaperText("Не удалось открыть источник. Повторите попытку.", role = PaperTextRole.LABEL, color = LocalPaperColors.current.error)
            if (expanded) sources.forEachIndexed { index, source ->
                PaperResearchSourceLink(source.title.ifBlank { source.url }, {
                    try { uriHandler.openUri(source.url) }
                    catch (failure: Exception) {
                        AppLog.error("chat", "citation.open.failed", failure,
                            mapOf("messageId" to message.id, "citationIndex" to (index + 1).toString()))
                        openError = true
                    }
                }, number = index + 1)
            }
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
    contextUsage: ContextUsageSnapshot? = null,
    contextCompacting: Boolean = false,
    resolvedProfile: LlmProfile? = ProfileResolver.resolve(session, AppSettings(activeLlmProfileId = activeProfileId), profiles),
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
    ResearchComposer(
        state = draft, enabled = enabled, busy = busy, paused = paused,
        profile = resolvedProfile,
        contextUsage = contextUsage, contextCompacting = contextCompacting,
        placeholder = if (session?.messages.isNullOrEmpty()) "Сформулируйте вопрос…" else "Уточните вопрос или продолжите исследование…",
        onOpenSwitcher = onOpenSwitcher,
        onSend = { text, attachments -> accepted(onSend, text, attachments) },
        onResume = { text, attachments -> accepted(onResume, text, attachments) },
        onClarify = { text, attachments -> accepted(onClarify, text, attachments) },
        onPause = onPause, onPickAttachments = onPickAttachments, onPasteAttachments = onPasteAttachments,
        engine = session?.engine ?: defaultEngine,
        onEngineChange = onEngineChange?.takeIf {
            session == null || (session.messages.isEmpty() && session.pendingRun == null && session.nativeSessionId.isBlank())
        },
    )
}
