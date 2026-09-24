package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.components.LocalPaperHideSystemSteps
import io.aequicor.magicpaper.ui.components.PaperChatMarkdown
import io.aequicor.magicpaper.ui.components.PaperChatPlainText
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
import io.aequicor.magicpaper.ui.components.PaperSessionContextMessage
import io.aequicor.magicpaper.ui.components.paperChatDisclosure
import io.aequicor.magicpaper.ui.components.paperChatScrollInput
import io.aequicor.magicpaper.ui.components.paperStickToBottom
import kotlinx.coroutines.delay

/**
 * The docked agent panel a host pins to a screen edge while its own window is away.
 *
 * Collapsed, it is a single activity indicator: the shape, tone and pulse are the sidebar's,
 * so one session never reads as two different states. Hovering expands it into the session
 * transcript and composer; leaving retracts it. The host owns the window: it supplies the
 * geometry, moves the window on [onDragBy] and decides when the dock is visible at all.
 */

/**
 * Transparent ring the window reserves around the card so the elevation shadow is drawn in
 * full. A shadow clipped by the window edge reads as a dirty rim, which is exactly what an
 * edge dock must never show.
 */
public val PaperAgentDockShadowMargin: Dp = 8.dp

/**
 * Window footprints, shadow ring included: the host sizes its window from these, and the
 * visible card floats [PaperAgentDockShadowMargin] inside. Collapsed tab: a 26 dp capsule.
 */
public val PaperAgentDockCollapsedWidth: Dp = 42.dp

/** Collapsed tab height: a comfortable pointer and touch target. */
public val PaperAgentDockCollapsedHeight: Dp = 88.dp

public val PaperAgentDockExpandedWidth: Dp = 448.dp
public val PaperAgentDockExpandedHeight: Dp = 476.dp

/** The reader may shrink the card, but never below a usable conversation. */
public val PaperAgentDockMinSize: Dp = 320.dp

/**
 * Hover dwell before the tab opens. A pointer crossing the screen edge on its way to another
 * application must not throw a 360 dp panel over that work.
 */
public const val PaperAgentDockExpandDelayMillis: Long = 250L

/** Grace period after the pointer leaves, so travelling inside the panel does not retract it. */
public const val PaperAgentDockCollapseDelayMillis: Long = 400L

/** Width of the session rail inside the expanded card: enough for a name and its dot. */
private val DockSessionRailWidth = 148.dp

/** The invisible strip on the free edges that resizes the expanded card. */
private val DockResizeGrip = 6.dp

public enum class PaperDockAuthor { USER, AGENT }

/** The step kinds the application transcript shows, without naming the domain enum. */
public enum class PaperDockStepKind { ANSWER, THINKING, TOOL, ERROR, INFO }

public data class PaperDockStep(
    val id: String,
    val kind: PaperDockStepKind,
    val title: String,
    val tool: String = "",
    val running: Boolean = false,
    val ok: Boolean = true,
)

/**
 * One transcript row, mirroring the application window's treatment: system notices and context
 * packets keep their own surfaces, an agent answer carries its step timeline (reasoning,
 * tool calls, errors), and a finished answer can still be waiting for a manual check.
 */
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

/**
 * One row of the dock's session list. The tone is the session's own, the same one the sidebar
 * shows, so selecting a session here never repaints its state in another colour.
 */
public data class PaperDockSession(
    val id: String,
    val name: String,
    val tone: PaperActivityTone,
    val running: Boolean = false,
    val selected: Boolean = false,
    /** The row's key value: what the session is doing right now, or the question it waits on. */
    val activityLabel: String? = null,
    /** How long the session has been in its current state: "3 мин", "2 ч". */
    val ageLabel: String? = null,
    /** The session needs the reader: it sorts to the top and feeds the tab's badge. */
    val needsYou: Boolean = false,
)

/**
 * Everything the dock shows: the workspace's sessions and the chat of the selected one.
 * [liveDetail] is the selected run's current step; it is what makes a collapsed or freshly
 * expanded dock reflect work in progress rather than the last saved message.
 */
public data class PaperAgentDockModel(
    val sessions: List<PaperDockSession> = emptyList(),
    /** Status of the selected session, for the header and the collapsed tab's accessible name. */
    val statusLabel: String = "",
    val messages: List<PaperDockMessage> = emptyList(),
    val liveDetail: String? = null,
    val busy: Boolean = false,
    val canSend: Boolean = true,
    /**
     * Composer placeholder, supplied by the host so an unavailable composer can say why
     * instead of inviting input that would be discarded.
     */
    val inputPlaceholder: String = "Сообщение агенту…",
    /** The question the selected session is waiting on; the dock surfaces it above the chat. */
    val pendingQuestion: String? = null,
    /** Sessions that need the reader: the collapsed tab's single glanceable number. */
    val attentionCount: Int = 0,
    /** Identity of the shown session: switching sessions resets the transcript anchor. */
    val transcriptKey: Any? = null,
)

/**
 * @param expandDelayMillis hover dwell before expanding; a pointer crossing the edge must not
 *   throw a 360 dp panel over the user's other work.
 * @param collapseDelayMillis grace period after the pointer leaves, so travelling inside the
 *   panel does not retract it.
 */
@Composable
public fun PaperAgentDock(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    model: PaperAgentDockModel,
    modifier: Modifier = Modifier,
    /** Which screen edge the host pinned the window to: the resize grip sits on the free side. */
    dockedToStart: Boolean = true,
    indicator: @Composable () -> Unit,
    input: String = "",
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenMainWindow: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onResizeWidthBy: (Float) -> Unit = {},
    onResizeHeightBy: (Float) -> Unit = {},
    /** The application's paper-animation setting: the dock's background is the window's own. */
    animateBackground: Boolean = true,
    onDragStart: (Float, Float) -> Unit = { _, _ -> },
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    expandDelayMillis: Long = PaperAgentDockExpandDelayMillis,
    collapseDelayMillis: Long = PaperAgentDockCollapseDelayMillis,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Who owns the open panel. A pointer that leaves retracts its own panel, but a keyboard or
    // touch activation has no pointer to leave with: only the reader closes that one, otherwise
    // activating the tab would flash the panel and take it away again.
    var openedByPointer by remember { mutableStateOf(false) }
    // A drag must never fight the hover logic: without this, holding the tab for the dwell
    // would explode it to full size under the moving cursor, mid-drag.
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, expanded, dragging) {
        if (!hovered || expanded || dragging) return@LaunchedEffect
        // Dwell before opening: a pointer crossing the edge on its way to another application
        // must not throw a 360 dp panel over that work.
        delay(expandDelayMillis)
        if (hovered) {
            openedByPointer = true
            onExpandedChange(true)
        }
    }
    LaunchedEffect(hovered, expanded, dragging) {
        if (!expanded) {
            openedByPointer = false
            return@LaunchedEffect
        }
        // An open panel under the pointer belongs to the pointer again.
        if (hovered) {
            openedByPointer = true
            return@LaunchedEffect
        }
        if (!openedByPointer || dragging) return@LaunchedEffect
        // Grace period, so travelling across the panel does not retract it.
        delay(collapseDelayMillis)
        if (!hovered) {
            openedByPointer = false
            onExpandedChange(false)
        }
    }

    // The host moves its own window. It receives the pointer's position inside this window,
    // never deltas: the window travels under the cursor while dragging, so a delta measured
    // against the moving origin would feed back and make the panel jerk.
    val drag = Modifier.pointerInput(onDragStart, onDragBy, onDragEnd) {
        detectDragGestures(
            onDragStart = { start ->
                dragging = true
                onDragStart(start.x, start.y)
            },
            onDragEnd = {
                dragging = false
                onDragEnd()
            },
            onDragCancel = {
                dragging = false
                onDragEnd()
            },
        ) { change, _ ->
            change.consume()
            onDragBy(change.position.x, change.position.y)
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
            // Hover belongs to the whole window, in both states: tracking it only on the card
            // would drop the pointer the moment the panel grew, and retract it under the reader.
            .hoverable(hover)
            .onPreviewKeyEvent { event ->
                // Escape retracts the panel from whichever control holds focus. The host keeps
                // the composer draft, so retracting never costs the reader what they typed.
                if (expanded && event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    onExpandedChange(false)
                    true
                } else false
            },
    ) {
        // The card floats inside the shadow ring: one silhouette, fully rounded, no edge the
        // window can clip and no hand-drawn outline fighting the surface. The surface is the
        // application's canvas with its animated paper, so the dock reads as the same window.
        PaperSurface(
            Modifier.padding(PaperAgentDockShadowMargin).fillMaxSize(),
            kind = PaperSurfaceKind.CANVAS,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 4.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                // The dock is read while another application holds the keyboard focus, so its
                // paper counts as foreground while the reader hovers it, not only while the
                // dock window itself is focused.
                val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
                PaperBackground(animateBackground, Modifier.matchParentSize(),
                    foreground = hovered || windowFocused)
                if (expanded) {
                    DockExpanded(model, colors, spacing, indicator, input, onInputChange, onSend, onStop,
                        onOpenMainWindow, onSelectSession, onResizeWidthBy, onResizeHeightBy,
                        { onExpandedChange(false) }, drag, dockedToStart)
                } else {
                    DockCollapsed(model, colors, indicator, drag) {
                        // An explicit activation is not pointer-owned, so it survives having no pointer.
                        openedByPointer = false
                        onExpandedChange(true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockCollapsed(
    model: PaperAgentDockModel,
    colors: PaperColors,
    indicator: @Composable () -> Unit,
    drag: Modifier,
    onExpand: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .paperClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "Открыть панель агента",
                shape = RoundedCornerShape(12.dp),
                onClick = onExpand,
            )
            .then(drag)
            .semantics {
                val selected = model.sessions.firstOrNull { it.selected }
                contentDescription = "Агент · ${selected?.name.orEmpty()}: ${model.statusLabel}"
            },
        contentAlignment = Alignment.Center,
    ) {
        // One glanceable column, Live-Activity style: how many sessions need the reader,
        // above the workspace's aggregate state.
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            if (model.attentionCount > 0) {
                Box(Modifier.background(colors.action, CircleShape)
                    .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                    .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    PaperText("${model.attentionCount}", role = PaperTextRole.CHROME, color = colors.actionOn)
                }
            }
            indicator()
        }
    }
}

@Composable
private fun DockExpanded(
    model: PaperAgentDockModel,
    colors: PaperColors,
    spacing: PaperSpacing,
    indicator: @Composable () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onSelectSession: (String) -> Unit,
    onResizeWidthBy: (Float) -> Unit,
    onResizeHeightBy: (Float) -> Unit,
    onCollapse: () -> Unit,
    drag: Modifier,
    dockedToStart: Boolean,
) {
    Row(Modifier.fillMaxSize()) {
        // The window's own anatomy in miniature: a session rail beside the conversation, so
        // switching sessions never means leaving the panel. The rail doubles as a handle:
        // rows keep their clicks, a drag past the slop moves the whole window.
        DockSessionList(model, onSelectSession,
            Modifier.width(DockSessionRailWidth).fillMaxHeight().then(drag))
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            DockConversation(model, colors, spacing, indicator, input, onInputChange, onSend, onStop,
                onOpenMainWindow, onCollapse, drag, Modifier.fillMaxSize())
            // Resize grips on the two free edges; they sit over the card's padding, not controls.
            Box(Modifier.align(if (dockedToStart) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight().width(DockResizeGrip)
                .semantics { contentDescription = "Изменить ширину панели" }
                .pointerInput(onResizeWidthBy) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onResizeWidthBy(amount.x)
                    }
                })
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(DockResizeGrip)
                .semantics { contentDescription = "Изменить высоту панели" }
                .pointerInput(onResizeHeightBy) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onResizeHeightBy(amount.y)
                    }
                })
        }
    }
}

/** The session rail: the sidebar's own row, narrowed to a dock column. */
@Composable
private fun DockSessionList(
    model: PaperAgentDockModel,
    onSelectSession: (String) -> Unit,
    modifier: Modifier,
) {
    val spacing = LocalPaperSpacing.current
    val colors = LocalPaperColors.current
    Column(modifier) {
        PaperText("Сессии", role = PaperTextRole.CHROME, color = colors.secondaryText,
            modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.xxs))
        if (model.sessions.isEmpty()) {
            PaperText("Нет сессий", role = PaperTextRole.CHROME, color = colors.disabled,
                modifier = Modifier.padding(horizontal = spacing.xs))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(model.sessions, key = { it.id }) { session ->
                    PaperSessionRow(
                        title = session.name,
                        subtitle = session.activityLabel ?: session.ageLabel,
                        onClick = { onSelectSession(session.id) },
                        selected = session.selected,
                        indicator = {
                            PaperActivityIndicator(session.tone, session.name, running = session.running)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DockConversation(
    model: PaperAgentDockModel,
    colors: PaperColors,
    spacing: PaperSpacing,
    indicator: @Composable () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenMainWindow: () -> Unit,
    onCollapse: () -> Unit,
    drag: Modifier,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // The header doubles as the drag handle, the way a title bar does.
        Row(
            Modifier.fillMaxWidth().then(drag).padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { indicator() }
            Spacer(Modifier.width(spacing.xs))
            Column(Modifier.weight(1f)) {
                val selectedSession = model.sessions.firstOrNull { it.selected }
                PaperFadingText(selectedSession?.name.orEmpty(),
                    style = LocalPaperTypography.current.label, color = colors.text, marqueeOnHover = true)
                PaperFadingText(
                    listOfNotNull(model.statusLabel, selectedSession?.activityLabel,
                        selectedSession?.ageLabel).joinToString(" · "),
                    style = LocalPaperTypography.current.chrome,
                    color = colors.secondaryText, marqueeOnHover = true)
            }
            Spacer(Modifier.width(spacing.xs))
            if (model.busy) {
                PaperIconButton(label = "Остановить прогон", onClick = onStop) {
                    PaperText("■", role = PaperTextRole.CHROME)
                }
            }
            PaperIconButton(label = "Открыть окно MagicPaper", onClick = onOpenMainWindow) {
                PaperText("⤢", role = PaperTextRole.CHROME)
            }
            PaperIconButton(label = "Свернуть панель", onClick = onCollapse) {
                PaperText("‹", role = PaperTextRole.CHROME)
            }
        }

        DockTranscript(model, colors, spacing, indicator)

        PaperWorkspaceComposer(Modifier.fillMaxWidth().padding(spacing.sm)) {
            // The composer surface is a column; the editor and its action share one row inside it.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                PaperPromptField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = model.inputPlaceholder,
                    modifier = Modifier.weight(1f),
                    enabled = model.canSend,
                    maxLines = 4,
                    onSubmit = onSend,
                )
                PaperIconButton(
                    label = "Отправить сообщение",
                    onClick = onSend,
                    enabled = model.canSend && input.isNotBlank(),
                ) { PaperText("↑", role = PaperTextRole.CHROME) }
            }
        }
    }
}

@Composable
private fun ColumnScope.DockTranscript(
    model: PaperAgentDockModel,
    colors: PaperColors,
    spacing: PaperSpacing,
    indicator: @Composable () -> Unit,
) {
    if (model.messages.isEmpty() && model.liveDetail == null) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            PaperText("Пока нет сообщений", style = LocalPaperTypography.current.body, color = colors.secondaryText,
                modifier = Modifier.padding(spacing.lg))
        }
        return
    }
    val listState = rememberLazyListState()
    // Follow-end scrolling is the design system's job: a growing answer must not throw the
    // reader back to the top of the message, and a reader who scrolled up keeps their place.
    val scroll = paperStickToBottom(listState, resetKey = model.transcriptKey)
    model.pendingQuestion?.let { question ->
        // The peek principle: what the session waits on sits above the history, not buried in it.
        PaperPanel(Modifier.fillMaxWidth().padding(bottom = spacing.xs), kind = PaperSurfaceKind.RAISED) {
            Column(Modifier.padding(spacing.xs)) {
                PaperText("Ждёт вашего ответа", role = PaperTextRole.LABEL, color = colors.action)
                PaperText(question, style = LocalPaperTypography.current.body, color = colors.text)
            }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth().paperChatScrollInput(scroll),
        contentPadding = PaddingValues(horizontal = spacing.sm, vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        items(model.messages, key = { it.id }) { message ->
            PaperChatScrollItem(scroll, message.id) { DockMessageRow(message) }
        }
        model.liveDetail?.let { detail ->
            item(key = "live-detail") {
                Row(Modifier.fillMaxWidth().padding(top = spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) { indicator() }
                    Spacer(Modifier.width(spacing.xs))
                    PaperFadingText(detail, style = LocalPaperTypography.current.chrome,
                        color = colors.secondaryText, marqueeOnHover = true)
                }
            }
        }
    }
}

@Composable
private fun DockMessageRow(message: PaperDockMessage) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    if (message.systemContext) {
        PaperSessionContextMessage(message.id, message.text)
        return
    }
    if (message.systemNotice) {
        PaperSystemMessage {
            PaperText("Системное сообщение", role = PaperTextRole.LABEL)
            PaperChatPlainText(message.text, color = colors.systemText)
        }
        return
    }
    val isUser = message.author == PaperDockAuthor.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        // The window's own conversation surface: the same bubble, the same markdown, so a
        // message reads identically here and in the application.
        Box(Modifier.widthIn(max = 320.dp).paperConversationMessage(isUser, true, true)) {
            if (isUser) {
                PaperChatPlainText(message.text)
            } else {
                Column {
                    message.steps.forEach { step -> DockStepRow(step) }
                    if (message.text.isNotBlank()) PaperChatMarkdown(message.text, compact = true)
                    if (message.failed && message.steps.none { it.kind == PaperDockStepKind.ERROR }) {
                        PaperChatPlainText("✕ Не удалось завершить работу агента.", color = colors.error)
                    }
                    if (message.needsVerification) {
                        PaperText("Нужна ручная проверка", role = PaperTextRole.LABEL,
                            color = colors.secondaryText, modifier = Modifier.padding(top = spacing.xxs))
                    }
                }
            }
        }
    }
}

@Composable
private fun DockStepRow(step: PaperDockStep) {
    val colors = LocalPaperColors.current
    when (step.kind) {
        PaperDockStepKind.ANSWER -> PaperChatMarkdown(step.title, compact = true)
        PaperDockStepKind.ERROR -> PaperChatPlainText("✕ ${step.title}", color = colors.error,
            modifier = Modifier.padding(vertical = 2.dp))
        PaperDockStepKind.INFO -> {
            if (!LocalPaperHideSystemSteps.current) {
                PaperChatPlainText(step.title, color = colors.secondaryText,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        PaperDockStepKind.THINKING -> DockThinkingRow(step)
        PaperDockStepKind.TOOL -> DockToolRow(step)
    }
}

/** Collapsed reasoning, exactly as the transcript shows it: a quiet row, text on disclosure. */
@Composable
private fun DockThinkingRow(step: PaperDockStep) {
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    PaperWorkSurface(Modifier.padding(vertical = 2.dp), expanded = expanded) {
        Row(Modifier.fillMaxWidth().paperChatDisclosure(interaction) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperText("·", color = LocalPaperColors.current.border)
            Spacer(Modifier.width(8.dp))
            PaperText("Размышление агента", color = LocalPaperColors.current.secondaryText,
                modifier = Modifier.weight(1f))
            PaperText(if (expanded) "▴" else "▾", color = LocalPaperColors.current.secondaryText)
        }
        if (expanded) {
            PaperChatMarkdown(step.title,
                Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp), compact = true)
        }
    }
}

/** A tool call: one quiet line with its command, a marker while it runs or fails. */
@Composable
private fun DockToolRow(step: PaperDockStep) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperText(if (step.running) "…" else if (step.ok) "⌁" else "✕", role = PaperTextRole.CHROME,
            color = if (step.ok) colors.secondaryText else colors.error)
        Spacer(Modifier.width(spacing.xs))
        PaperFadingText(step.title, style = LocalPaperTypography.current.chrome,
            color = if (step.ok) colors.secondaryText else colors.error,
            marqueeOnHover = true, modifier = Modifier.weight(1f))
    }
}

private val previewSessions = listOf(
    PaperDockSession("s1", "Восстановление дочерних сессий", PaperActivityTone.WORKING, running = true, selected = true),
    PaperDockSession("s2", "Индикатор always-on-top", PaperActivityTone.ATTENTION),
    PaperDockSession("s3", "Панель агента", PaperActivityTone.UNREAD),
    PaperDockSession("s4", "Старая задача", PaperActivityTone.READY),
)

@Preview(name = "Dock collapsed · working", group = "Agent dock", widthDp = 42, heightDp = 88)
@Composable
public fun PaperAgentDockCollapsedPreview() = PaperTheme {
    PaperAgentDock(expanded = false, onExpandedChange = {},
        model = PaperAgentDockModel(sessions = previewSessions, statusLabel = "работает"),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "работает", running = true, size = 16.dp) })
}

@Preview(name = "Dock expanded · streaming", group = "Agent dock", widthDp = 448, heightDp = 476)
@Composable
public fun PaperAgentDockExpandedPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {},
        model = PaperAgentDockModel(
            sessions = previewSessions,
            statusLabel = "работает",
            busy = true,
            liveDetail = "Читает SessionOrganismStore.kt",
            transcriptKey = "s1",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.USER, "Почему сессии теряются после падения?"),
                PaperDockMessage("2", PaperDockAuthor.AGENT,
                    "Журнал восстанавливается **до** `CodingService.start()`, поэтому точка останова остаётся невидимой.\n\n- порядок фаз в `AppRuntime`\n- `recovery` не публикуется"),
                PaperDockMessage("3", PaperDockAuthor.USER, "Исправь порядок."),
            ),
        ),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "работает", running = true, size = 16.dp) },
        input = "Проверь ещё и планирование")
}

@Preview(name = "Dock expanded · waiting", group = "Agent dock", widthDp = 448, heightDp = 476)
@Composable
public fun PaperAgentDockWaitingPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {},
        model = PaperAgentDockModel(
            sessions = previewSessions.map { if (it.id == "s2") it.copy(selected = true) else it.copy(selected = false) },
            statusLabel = "Ждём вашего ответа",
            transcriptKey = "s2",
            canSend = false,
            inputPlaceholder = "Ответьте на вопрос в окне MagicPaper",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.AGENT, "Нужно уточнение: показывать панель в покое или только во время прогона?"),
            ),
        ),
        indicator = { PaperActivityIndicator(PaperActivityTone.ATTENTION, "Ждём вашего ответа", size = 16.dp) })
}

@Preview(name = "Dock expanded · empty", group = "Agent dock", widthDp = 448, heightDp = 476)
@Composable
public fun PaperAgentDockEmptyPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {},
        model = PaperAgentDockModel(sessions = emptyList(), statusLabel = "ждёт запроса", canSend = false,
            inputPlaceholder = "Сессия недоступна для ввода"),
        indicator = { PaperActivityIndicator(PaperActivityTone.READY, "ждёт запроса", size = 16.dp) })
}
