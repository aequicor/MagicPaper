package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.components.PaperChatMarkdown
import io.aequicor.magicpaper.ui.components.PaperChatPlainText
import io.aequicor.magicpaper.ui.components.PaperSessionContextMessage
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import kotlinx.coroutines.delay

/** The native host animates between these fixed footprints. Session updates never resize a window. */
public val PaperAgentDockCollapsedWidth: Dp = 304.dp
public val PaperAgentDockCollapsedHeight: Dp = 152.dp
public val PaperAgentDockExpandedWidth: Dp = 560.dp
public val PaperAgentDockExpandedHeight: Dp = 600.dp
public val PaperAgentDockShadowMargin: Dp = 8.dp
public const val PaperAgentDockCompactRows: Int = 3
public const val PaperAgentDockExpandDelayMillis: Long = 260L
public const val PaperAgentDockCollapseDelayMillis: Long = 480L

public enum class PaperDockAuthor { USER, AGENT }
public enum class PaperDockStepKind { ANSWER, THINKING, TOOL, ERROR, INFO }

public data class PaperDockRecovery(val label: String, val pendingLabel: String, val pending: Boolean = false)

public data class PaperDockStep(
    val id: String,
    val kind: PaperDockStepKind,
    val title: String,
    val tool: String = "",
    val running: Boolean = false,
    val ok: Boolean = true,
    val recovery: PaperDockRecovery? = null,
)

public data class PaperDockMessage(
    val id: String,
    val author: PaperDockAuthor,
    val text: String,
    val systemNotice: Boolean = false,
    val systemContext: Boolean = false,
    val failed: Boolean = false,
    val steps: List<PaperDockStep> = emptyList(),
    val needsVerification: Boolean = false,
)

public data class PaperDockSession(
    val id: String,
    val name: String,
    val tone: PaperActivityTone,
    val running: Boolean = false,
    val selected: Boolean = false,
    val activityLabel: String? = null,
    val statusLabel: String = "",
    val needsYou: Boolean = false,
)

public data class PaperAgentDockModel(
    val sessions: List<PaperDockSession> = emptyList(),
    val workspaceTitle: String = "",
    val statusLabel: String = "",
    val messages: List<PaperDockMessage> = emptyList(),
    val liveDetail: String? = null,
    val busy: Boolean = false,
    val canSend: Boolean = true,
    val inputPlaceholder: String = "Сообщение агенту…",
    val pendingQuestion: String? = null,
    val attentionCount: Int = 0,
    val transcriptKey: Any? = null,
    val operationError: String? = null,
    val hasEarlierMessages: Boolean = false,
)

/**
 * Floating agent surface. The card remains one coherent Paper surface while its native window
 * changes size. Hover opens after a short dwell; a drag freezes expansion until release. Keyboard
 * opening is sticky, and focus in the composer keeps a pointer-opened panel available.
 */
@Composable
public fun PaperAgentDock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    model: PaperAgentDockModel,
    indicator: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    input: String = "",
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenMainWindow: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onRecovery: (String) -> Unit = {},
    onCancelRecovery: (String) -> Unit = {},
    animate: Boolean = true,
    onDragStart: (Float, Float) -> Unit = { _, _ -> },
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onNudgeBy: (Float, Float) -> Unit = { _, _ -> },
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var keyboardOpen by remember { mutableStateOf(false) }
    val latestExpansion by rememberUpdatedState(onExpandedChange)

    LaunchedEffect(hovered, dragging, pressing, expanded, keyboardOpen, focused) {
        if (dragging || pressing) return@LaunchedEffect
        if (hovered && !expanded) {
            delay(PaperAgentDockExpandDelayMillis)
            latestExpansion(true)
        } else if (!hovered && expanded && !keyboardOpen && !focused) {
            delay(PaperAgentDockCollapseDelayMillis)
            latestExpansion(false)
        }
    }

    val dragHandle: @Composable (Modifier) -> Unit = { handleModifier ->
        DockDragHandle(handleModifier, onDragStart, onDragBy, onDragEnd, onNudgeBy) { dragging = it }
    }
    PaperSurface(
        modifier = modifier.fillMaxSize().padding(PaperAgentDockShadowMargin)
            .hoverable(hoverSource)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) pressing = true
                        else if (event.changes.none { it.pressed }) pressing = false
                    }
                }
            }
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent {
                if (expanded && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    keyboardOpen = false
                    dragging = false
                    onDragEnd()
                    onExpandedChange(false)
                    true
                } else false
            },
        color = LocalPaperColors.current.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
    ) {
        Crossfade(expanded, animationSpec = tween(if (animate) 150 else 0), label = "Agent panel content") { open ->
            if (open) ExpandedDock(
                model, indicator, dragHandle, input, onInputChange, onSend, onStop,
                onOpenMainWindow, onSelectSession, onRecovery, onCancelRecovery,
                onCollapse = { keyboardOpen = false; onExpandedChange(false) },
            ) else CompactDock(model, indicator, dragHandle) { sessionId ->
                sessionId?.let(onSelectSession)
                keyboardOpen = true
                onExpandedChange(true)
            }
        }
    }
}

private class DragOrigin { var value: Offset = Offset.Zero }

/** Drag coordinates are converted from Compose pixels to native window units only once. */
@Composable
private fun DockDragHandle(
    modifier: Modifier,
    onStart: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onEnd: () -> Unit,
    onNudge: (Float, Float) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val density = LocalDensity.current.density
    val origin = remember { DragOrigin() }
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val nudge by rememberUpdatedState(onNudge)
    val dragging by rememberUpdatedState(onDragging)
    var keyboardMove by remember { mutableStateOf(false) }
    fun windowPoint(local: Offset): Offset = (origin.value + local) / density
    Box(
        modifier.onGloballyPositioned { origin.value = it.positionInRoot() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { point ->
                        dragging(true)
                        windowPoint(point).let { start(it.x, it.y) }
                    },
                    onDragEnd = { dragging(false); end() },
                    onDragCancel = { dragging(false); end() },
                ) { change, _ ->
                    windowPoint(change.position).let { move(it.x, it.y) }
                    change.consume()
                }
            }
            .paperClickable(onClickLabel = "Переместить панель агентов") {
                keyboardMove = !keyboardMove
                dragging(keyboardMove)
                if (!keyboardMove) end()
            }
            .onPreviewKeyEvent { event ->
                if (!keyboardMove || event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.Escape -> { keyboardMove = false; dragging(false); end(); true }
                    Key.DirectionLeft -> { nudge(-24f, 0f); true }
                    Key.DirectionRight -> { nudge(24f, 0f); true }
                    Key.DirectionUp -> { nudge(0f, -24f); true }
                    Key.DirectionDown -> { nudge(0f, 24f); true }
                    else -> false
                }
            }
            .onFocusChanged { state ->
                if (!state.isFocused && keyboardMove) { keyboardMove = false; dragging(false); end() }
            }
            .semantics { contentDescription = if (keyboardMove)
                "Перемещение панели: стрелки двигают, Enter завершает" else "Переместить панель агентов" },
        contentAlignment = Alignment.CenterStart,
    ) {
        PaperText(if (keyboardMove) "Стрелки для перемещения" else "⋮⋮ MagicPaper",
            role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompactDock(
    model: PaperAgentDockModel,
    indicator: @Composable () -> Unit,
    dragHandle: @Composable (Modifier) -> Unit,
    onOpen: (String?) -> Unit,
) {
    val colors = LocalPaperColors.current
    val largeText = LocalDensity.current.fontScale >= 1.35f
    val visibleRows = if (largeText) 2 else PaperAgentDockCompactRows
    val hiddenSessions = (model.sessions.size - visibleRows).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            indicator()
            Spacer(Modifier.width(8.dp))
            dragHandle(Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(6.dp))
            if (model.attentionCount > 0) {
                PaperText(if (largeText) "! ${model.attentionCount}" else "Внимание: ${model.attentionCount}",
                    modifier = Modifier.semantics {
                        contentDescription = "${model.attentionCount} сессий требуют внимания"
                    }, role = PaperTextRole.CHROME, color = colors.error)
                Spacer(Modifier.width(4.dp))
            }
            PaperIconButton("Раскрыть панель агентов", onClick = { onOpen(null) }, modifier = Modifier.size(32.dp)) {
                PaperText(if (hiddenSessions > 0) "+$hiddenSessions" else "↗",
                    role = PaperTextRole.CHROME, color = colors.action)
            }
        }
        if (model.sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                PaperText("Нет активных сессий", role = PaperTextRole.LABEL, color = colors.secondaryText)
            }
        } else {
            model.sessions.take(visibleRows).forEach { session ->
                CompactSessionRow(session) { onOpen(session.id) }
            }
        }
    }
}

@Composable
private fun CompactSessionRow(session: PaperDockSession, onClick: () -> Unit) {
    val colors = LocalPaperColors.current
    PaperAction(onClick, modifier = Modifier.fillMaxWidth(),
        accessibilityLabel = "${session.name}, ${session.statusLabel}",
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
        PaperActivityIndicator(session.tone, session.statusLabel, running = session.running, size = 10.dp)
        Spacer(Modifier.width(8.dp))
        PaperText(session.name, Modifier.weight(1f), role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        PaperText(session.statusLabel, Modifier.widthIn(max = 96.dp), role = PaperTextRole.CHROME,
            color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ExpandedDock(
    model: PaperAgentDockModel,
    indicator: @Composable () -> Unit,
    dragHandle: @Composable (Modifier) -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onSelectSession: (String) -> Unit,
    onRecovery: (String) -> Unit,
    onCancelRecovery: (String) -> Unit,
    onCollapse: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            indicator()
            Spacer(Modifier.width(8.dp))
            dragHandle(Modifier.weight(1f).fillMaxHeight())
            PaperButton("Открыть", onOpenMainWindow, kind = PaperButtonKind.QUIET,
                accessibilityLabel = "Открыть окно MagicPaper")
            Spacer(Modifier.width(4.dp))
            PaperButton("Свернуть", onCollapse, kind = PaperButtonKind.QUIET)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            if (maxWidth < 480.dp) {
                val short = maxHeight < 420.dp
                Column {
                    LazyRow(Modifier.fillMaxWidth().height(if (short) 48.dp else 62.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(model.sessions, key = { it.id }) { session ->
                            SessionChoice(session, Modifier.width(148.dp), onSelectSession, showSecondary = !short)
                        }
                    }
                    Conversation(model, input, onInputChange, onSend, onStop,
                        onOpenMainWindow, onRecovery, onCancelRecovery, Modifier.weight(1f),
                        showHeader = !short)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(Modifier.width(174.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(model.sessions, key = { it.id }) { session ->
                            SessionChoice(session, Modifier.fillMaxWidth(), onSelectSession)
                        }
                    }
                    Conversation(model, input, onInputChange, onSend, onStop,
                        onOpenMainWindow, onRecovery, onCancelRecovery, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SessionChoice(session: PaperDockSession, modifier: Modifier, onSelect: (String) -> Unit,
    showSecondary: Boolean = true) {
    val colors = LocalPaperColors.current
    PaperSurface(modifier.semantics { selected = session.selected },
        color = if (session.selected) colors.selected else colors.canvas, shape = PaperShapes.control) {
        PaperAction(onClick = { onSelect(session.id) }, modifier = Modifier.fillMaxWidth(),
            accessibilityLabel = "${session.name}, ${session.statusLabel}",
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
            PaperActivityIndicator(session.tone, session.statusLabel, running = session.running)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                PaperText(session.name, role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showSecondary) PaperText(session.activityLabel ?: session.statusLabel, role = PaperTextRole.CHROME,
                    color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Conversation(
    model: PaperAgentDockModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onRecovery: (String) -> Unit,
    onCancelRecovery: (String) -> Unit,
    modifier: Modifier,
    showHeader: Boolean = true,
) {
    val colors = LocalPaperColors.current
    val selected = model.sessions.firstOrNull { it.selected }
    Column(modifier) {
        if (showHeader) Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            PaperText(selected?.name ?: "Сессия", role = PaperTextRole.TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PaperText(selected?.statusLabel ?: model.statusLabel, role = PaperTextRole.LABEL, color = colors.secondaryText)
        }
        if (model.pendingQuestion != null) {
            PaperSurface(Modifier.fillMaxWidth().padding(bottom = 6.dp), kind = PaperSurfaceKind.ERROR) {
                Column(Modifier.padding(8.dp)) {
                    PaperText(model.pendingQuestion, role = PaperTextRole.LABEL, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PaperButton("Ответить в окне", onOpenMainWindow, kind = PaperButtonKind.QUIET)
                }
            }
        }
        model.operationError?.let { error ->
            PaperSurface(Modifier.fillMaxWidth().padding(bottom = 6.dp), kind = PaperSurfaceKind.ERROR) {
                PaperText(error, Modifier.padding(8.dp), role = PaperTextRole.LABEL, color = colors.error)
            }
        }
        val listState = rememberLazyListState()
        paperStickToBottom(listState, model.transcriptKey)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
            if (model.hasEarlierMessages) item(key = "earlier") {
                PaperButton("Ранние сообщения в окне", onOpenMainWindow, kind = PaperButtonKind.QUIET)
            }
            if (model.messages.isEmpty()) item(key = "empty") {
                PaperText("Сообщений пока нет", role = PaperTextRole.LABEL, color = colors.secondaryText)
            }
            items(model.messages, key = { it.id }) { message ->
                DockMessage(message, onRecovery, onCancelRecovery)
            }
            if (model.liveDetail != null) item(key = "live") {
                PaperSurface(kind = PaperSurfaceKind.RAISED) {
                    PaperText(model.liveDetail, Modifier.padding(8.dp), role = PaperTextRole.LABEL, maxLines = 3)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PaperInput(value = input, onValueChange = onInputChange, modifier = Modifier.weight(1f),
                enabled = model.canSend, minLines = 2, maxLines = 4,
                placeholder = { PaperText(model.inputPlaceholder, role = PaperTextRole.LABEL, color = colors.secondaryText) })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PaperButton("Отправить", onSend, enabled = model.canSend && input.isNotBlank())
                if (model.busy) PaperButton("Стоп", onStop, kind = PaperButtonKind.SECONDARY)
            }
        }
    }
}

@Composable
private fun DockMessage(message: PaperDockMessage, onRecovery: (String) -> Unit, onCancelRecovery: (String) -> Unit) {
    val colors = LocalPaperColors.current
    if (message.systemContext) {
        PaperSessionContextMessage(message.id, message.text)
        return
    }
    var showSteps by remember(message.id) { mutableStateOf(message.failed) }
    val background = when {
        message.systemNotice -> colors.systemSurface
        message.failed -> colors.errorSurface
        message.author == PaperDockAuthor.USER -> colors.userMessageSurface
        else -> colors.agentMessageSurface
    }
    PaperSurface(Modifier.fillMaxWidth(), color = background, shape = PaperShapes.panel) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PaperText(if (message.author == PaperDockAuthor.USER) "Вы" else "Агент",
                role = PaperTextRole.CHROME, color = colors.secondaryText)
            if (message.text.isNotBlank()) {
                if (message.author == PaperDockAuthor.AGENT && !message.systemNotice) PaperChatMarkdown(message.text, compact = true)
                else PaperChatPlainText(message.text)
            }
            if (message.steps.isNotEmpty()) {
                PaperButton(if (showSteps) "Скрыть ход" else "Ход · ${message.steps.size}",
                    onClick = { showSteps = !showSteps }, kind = PaperButtonKind.QUIET)
                if (showSteps) message.steps.forEach { step ->
                    Column {
                        PaperText(step.title, role = PaperTextRole.LABEL,
                            color = if (step.kind == PaperDockStepKind.ERROR || !step.ok) colors.error else colors.secondaryText,
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                        step.recovery?.let { recovery ->
                            PaperButton(if (recovery.pending) recovery.pendingLabel else recovery.label,
                                onClick = { if (recovery.pending) onCancelRecovery(step.id) else onRecovery(step.id) },
                                kind = PaperButtonKind.SECONDARY)
                        }
                    }
                }
            }
            if (message.needsVerification) PaperText("Нужна проверка", role = PaperTextRole.LABEL, color = colors.action)
        }
    }
}

private val previewSessions = listOf(
    PaperDockSession("one", "Сборка проекта", PaperActivityTone.WORKING, true, true, "Проверяет тесты", "работает"),
    PaperDockSession("two", "Окно настроек", PaperActivityTone.ATTENTION, statusLabel = "ждёт ответа", needsYou = true),
    PaperDockSession("three", "Длинное имя сессии для проверки переполнения", PaperActivityTone.UNREAD, statusLabel = "новое"),
)

@Preview(name = "Agent overlay · compact", widthDp = 304, heightDp = 152)
@Composable public fun PaperAgentDockCompactPreview() = PaperTheme {
    PaperAgentDock(false, {}, PaperAgentDockModel(previewSessions, attentionCount = 1),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "Агенты работают", running = true, size = 14.dp) })
}

@Preview(name = "Agent overlay · conversation", widthDp = 560, heightDp = 600)
@Composable public fun PaperAgentDockConversationPreview() = PaperTheme {
    PaperAgentDock(true, {}, PaperAgentDockModel(previewSessions,
        messages = listOf(
            PaperDockMessage("u", PaperDockAuthor.USER, "Проверь сборку и исправь ошибку"),
            PaperDockMessage("a", PaperDockAuthor.AGENT, "Проверяю проект. После сборки покажу результат."),
        ), liveDetail = "Выполняет Gradle", busy = true, transcriptKey = "one"),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "Агенты работают", running = true, size = 14.dp) },
        input = "Посмотри ещё на Windows")
}

@Preview(name = "Agent overlay · narrow and waiting", widthDp = 400, heightDp = 560)
@Composable public fun PaperAgentDockNarrowPreview() = PaperTheme {
    PaperAgentDock(true, {}, PaperAgentDockModel(previewSessions.map { it.copy(selected = it.id == "two") },
        pendingQuestion = "Какой вариант интерфейса выбрать?", canSend = false,
        inputPlaceholder = "Ответьте в окне MagicPaper", transcriptKey = "two"),
        indicator = { PaperActivityIndicator(PaperActivityTone.ATTENTION, "Нужен ответ", size = 14.dp) })
}

@Preview(name = "Agent overlay · empty", widthDp = 304, heightDp = 152)
@Composable public fun PaperAgentDockEmptyPreview() = PaperTheme {
    PaperAgentDock(false, {}, PaperAgentDockModel(),
        indicator = { PaperActivityIndicator(PaperActivityTone.READY, "Нет активных агентов", size = 14.dp) })
}

@Preview(name = "Agent overlay · large text", widthDp = 304, heightDp = 152, fontScale = 2f)
@Composable public fun PaperAgentDockLargeTextPreview() = PaperTheme {
    PaperAgentDock(false, {}, PaperAgentDockModel(previewSessions, attentionCount = 1),
        indicator = { PaperActivityIndicator(PaperActivityTone.ATTENTION, "Нужен ответ", size = 14.dp) })
}
