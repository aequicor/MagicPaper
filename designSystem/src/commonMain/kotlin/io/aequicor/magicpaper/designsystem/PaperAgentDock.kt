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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
 * Collapsed, it is a compact status list, the way a desktop agent monitor shows its agents:
 * one line per session with something to report, its dot, its name and what it is doing. The
 * list leaves ages to the open panel, so the narrow list gives its width to the names. The dots
 * are the sidebar's, so one session never reads as two
 * different states. Hovering expands it into the session rail, transcript and composer;
 * leaving retracts it. The host owns the window: it supplies the geometry, moves the window
 * on [onDragBy], follows [onCollapsedHeightChange] and decides when the dock is visible at all.
 */

/**
 * Transparent ring the window reserves around the card so the elevation shadow is drawn in
 * full. A shadow clipped by the window edge reads as a dirty rim, which is exactly what an
 * edge dock must never show.
 */
public val PaperAgentDockShadowMargin: Dp = 8.dp

/**
 * Window footprints, shadow ring included: the host sizes its window from these, and the
 * visible card floats [PaperAgentDockShadowMargin] inside. The compact list keeps one width,
 * so its columns do not move while sessions come and go, and only as much of it as a short
 * session name and its activity need.
 */
public val PaperAgentDockCollapsedWidth: Dp = 240.dp

/**
 * Compact footprint to open with: the header and one session. The list then reports its real
 * height through `onCollapsedHeightChange`, since rows and text scale decide it.
 */
public val PaperAgentDockCollapsedHeight: Dp = 104.dp

/** Sessions the compact list names; the rest are counted in one line, so the list stays compact. */
public const val PaperAgentDockCompactRows: Int = 5

public val PaperAgentDockExpandedWidth: Dp = 448.dp
public val PaperAgentDockExpandedHeight: Dp = 476.dp

/** The reader may shrink the card, but never below a usable conversation. */
public val PaperAgentDockMinSize: Dp = 320.dp

/**
 * Hover dwell before the list opens. A pointer crossing the list on its way to another
 * application must not throw a 448 dp panel over that work; the list is wider than an edge tab,
 * so the crossing takes longer.
 */
public const val PaperAgentDockExpandDelayMillis: Long = 350L

/** Grace period after the pointer leaves, so travelling inside the panel does not retract it. */
public const val PaperAgentDockCollapseDelayMillis: Long = 500L

/** Width of the session rail inside the expanded card: enough for a name and its dot. */
private val DockSessionRailWidth = 148.dp

/** The invisible strip on the free edges that resizes the expanded card. */
private val DockResizeGrip = 6.dp

/** The card's silhouette in both states, so collapsing and expanding read as one surface. */
private val DockCardShape = RoundedCornerShape(12.dp)

/**
 * The compact list's leading column: the header's workspace dot and every row's session dot
 * centre on one guide, and every title starts on the next.
 */
private val DockCompactDotSlot = 20.dp

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
    /** Offered under an ERROR step; activating it reports this step's [id] to the host. */
    val recovery: PaperDockRecovery? = null,
)

/** An action that resolves a failure, named by the host; [pending] while the host carries it out. */
public data class PaperDockRecovery(val label: String, val pendingLabel: String, val pending: Boolean = false)

/** Where a drag handle sits in the window; read by its gesture, never drawn, so no state. */
private class DockHandleOrigin { var offset: Offset = Offset.Zero }

/** Step actions reach the rows through the transcript without threading through every layout level. */
private class DockRecoveryHandlers(val onRecovery: (String) -> Unit, val onCancel: (String) -> Unit)

private val LocalDockRecovery = staticCompositionLocalOf { DockRecoveryHandlers({}, {}) }

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
    /** The session's own status, said when there is no [activityLabel] to show. */
    val statusLabel: String = "",
    /**
     * When the session entered its current state, in epoch milliseconds: the open panel's header
     * counts the selected session's time from it against the host's `nowMillis`, so a waiting
     * session's age keeps moving.
     */
    val stateSinceMillis: Long? = null,
    /** The session needs the reader: it sorts to the top, is tinted and feeds the badge. */
    val needsYou: Boolean = false,
)

/**
 * Everything the dock shows: the workspace's sessions and the chat of the selected one.
 * [liveDetail] is the selected run's current step; it is what makes a collapsed or freshly
 * expanded dock reflect work in progress rather than the last saved message.
 */
public data class PaperAgentDockModel(
    val sessions: List<PaperDockSession> = emptyList(),
    /** What the sessions belong to, named in the compact list's header: the open project. */
    val workspaceTitle: String = "",
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
 * Pointer positions and resize deltas are reported in dp, the unit Compose Desktop sizes its
 * windows in (one dp is one AWT window unit on every display scale), so a host moves and sizes
 * its window with them directly.
 *
 * @param nowMillis the host's wall clock in epoch milliseconds; the open panel counts the
 *   selected session's age from its [PaperDockSession.stateSinceMillis] against it. Zero hides it.
 * @param onCollapsedHeightChange the window footprint height, shadow ring included, the compact
 *   list needs for its current rows at the current text scale.
 * @param expandDelayMillis hover dwell before expanding; a pointer crossing the list must not
 *   throw the full panel over the user's other work.
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
    nowMillis: Long = 0L,
    input: String = "",
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenMainWindow: () -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    /** A step's [PaperDockRecovery] was activated or, while pending, cancelled; the argument is the step's id. */
    onRecovery: (String) -> Unit = {},
    onCancelRecovery: (String) -> Unit = {},
    onResizeWidthBy: (Float) -> Unit = {},
    onResizeHeightBy: (Float) -> Unit = {},
    onCollapsedHeightChange: (Dp) -> Unit = {},
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
    val density = LocalDensity.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Who owns the open panel. A pointer that leaves retracts its own panel, but a keyboard or
    // touch activation has no pointer to leave with: only the reader closes that one, otherwise
    // activating the list would flash the panel and take it away again.
    var openedByPointer by remember { mutableStateOf(false) }
    // A drag must never fight the hover logic: without this, holding the list for the dwell
    // would explode it to full size under the moving cursor, mid-drag.
    var dragging by remember { mutableStateOf(false) }
    // The compact row under the pointer when the dwell completes is the session the reader
    // reached for, so the panel opens on its conversation.
    var pointedSession by remember { mutableStateOf<String?>(null) }
    val latestSelect by rememberUpdatedState(onSelectSession)
    val latestHeight by rememberUpdatedState(onCollapsedHeightChange)

    LaunchedEffect(hovered, expanded, dragging) {
        // Rows leave the composition with the list and never report the pointer leaving them.
        if (expanded) pointedSession = null
        if (!hovered || expanded || dragging) return@LaunchedEffect
        // Dwell before opening: a pointer crossing the list on its way to another application
        // must not throw the full panel over that work.
        delay(expandDelayMillis)
        if (hovered) {
            pointedSession?.let(latestSelect)
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
    // against the moving origin would feed back and make the panel jerk. Every handle reports
    // in the window's frame, not its own, so the host can tell which edge and display the
    // pointer is over wherever the drag started.
    val drag = Modifier.composed {
        val handle = remember { DockHandleOrigin() }
        Modifier.onGloballyPositioned { handle.offset = it.positionInRoot() }
            .pointerInput(onDragStart, onDragBy, onDragEnd) {
                fun report(point: Offset, to: (Float, Float) -> Unit) {
                    val inWindow = handle.offset + point
                    to(inWindow.x.toDp().value, inWindow.y.toDp().value)
                }
                detectDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        report(start, onDragStart)
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
                    report(change.position, onDragBy)
                }
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
        if (expanded) {
            DockCard(Modifier.padding(PaperAgentDockShadowMargin).fillMaxSize(), animateBackground, hovered) {
                val latestRecovery by rememberUpdatedState(onRecovery)
                val latestCancel by rememberUpdatedState(onCancelRecovery)
                val recovery = remember { DockRecoveryHandlers({ latestRecovery(it) }, { latestCancel(it) }) }
                CompositionLocalProvider(LocalDockRecovery provides recovery) {
                    DockExpanded(model, colors, spacing, indicator, nowMillis, input, onInputChange, onSend, onStop,
                        onOpenMainWindow, onSelectSession, onResizeWidthBy, onResizeHeightBy,
                        { onExpandedChange(false) }, drag, dockedToStart)
                }
            }
        } else {
            DockCard(
                Modifier.fillMaxWidth()
                    // Measured without a height limit, so the list reports what its rows need
                    // even while the host's window still has the previous content's height.
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { size -> latestHeight(with(density) { size.height.toDp() }) }
                    .padding(PaperAgentDockShadowMargin),
                animateBackground, hovered,
            ) {
                DockCompact(
                    model = model,
                    indicator = indicator,
                    drag = drag,
                    onOpen = {
                        // An explicit activation is not pointer-owned, so it survives having no pointer.
                        openedByPointer = false
                        onExpandedChange(true)
                    },
                    onOpenSession = { id ->
                        onSelectSession(id)
                        openedByPointer = false
                        onExpandedChange(true)
                    },
                    onPointAt = { id, pointed ->
                        if (pointed) pointedSession = id
                        else if (pointedSession == id) pointedSession = null
                    },
                )
            }
        }
    }
}

/**
 * The card floats inside the shadow ring: one silhouette, fully rounded, no edge the window can
 * clip and no hand-drawn outline fighting the surface. The surface is the application's canvas
 * with its animated paper, so the dock reads as the same window in both states.
 */
@Composable
private fun DockCard(
    modifier: Modifier,
    animateBackground: Boolean,
    hovered: Boolean,
    content: @Composable () -> Unit,
) {
    PaperSurface(modifier, kind = PaperSurfaceKind.CANVAS, shape = DockCardShape, shadowElevation = 4.dp) {
        Box(Modifier.fillMaxSize()) {
            // The dock is read while another application holds the keyboard focus, so its
            // paper counts as foreground while the reader hovers it, not only while the
            // dock window itself is focused.
            val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
            PaperBackground(animateBackground, Modifier.matchParentSize(),
                foreground = hovered || windowFocused)
            content()
        }
    }
}

/**
 * The resting state: a status board of the sessions that have something to report. A quiet
 * session (ready, nothing unread) is left to the open panel's rail, so the list names only
 * work in progress, questions and results. The whole list is a drag handle; rows keep their
 * clicks, since a drag starts only past the touch slop.
 */
@Composable
private fun DockCompact(
    model: PaperAgentDockModel,
    indicator: @Composable () -> Unit,
    drag: Modifier,
    onOpen: () -> Unit,
    onOpenSession: (String) -> Unit,
    onPointAt: (String, Boolean) -> Unit,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val reporting = model.sessions.filter { it.tone != PaperActivityTone.READY }
    val shown = reporting.take(PaperAgentDockCompactRows)
    Column(Modifier.fillMaxWidth().then(drag).padding(vertical = spacing.xxs)) {
        DockCompactHeader(model, indicator, onOpen)
        shown.forEach { session ->
            key(session.id) { DockCompactRow(session, onOpenSession, onPointAt) }
        }
        val hidden = reporting.size - shown.size
        val footer = when {
            reporting.isEmpty() -> "Нет активных сессий"
            hidden > 0 -> "Ещё $hidden"
            else -> null
        }
        if (footer != null) {
            PaperText(footer, role = PaperTextRole.CHROME, color = colors.secondaryText, maxLines = 1,
                modifier = Modifier.padding(start = spacing.sm + DockCompactDotSlot + spacing.xs,
                    end = spacing.sm, top = spacing.xxs, bottom = spacing.xxs))
        }
    }
}

/** The list's title line: the workspace's one dot, its name and how many sessions need the reader. */
@Composable
private fun DockCompactHeader(
    model: PaperAgentDockModel,
    indicator: @Composable () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = spacing.xxs)
            .heightIn(min = 32.dp)
            .paperClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "Открыть панель агента",
                shape = shape,
                onClick = onOpen,
            )
            .semantics {
                val selected = model.sessions.firstOrNull { it.selected }
                contentDescription = "Агент · ${selected?.name.orEmpty()}: ${model.statusLabel}"
            }
            .padding(horizontal = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(DockCompactDotSlot), contentAlignment = Alignment.Center) { indicator() }
        Spacer(Modifier.width(spacing.xs))
        PaperFadingText(model.workspaceTitle.ifBlank { "MagicPaper" }, Modifier.weight(1f),
            color = colors.secondaryText, style = LocalPaperTypography.current.chrome)
        if (model.attentionCount > 0) {
            Spacer(Modifier.width(spacing.xs))
            Box(Modifier.background(colors.action, CircleShape)
                .sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                .padding(horizontal = 5.dp)
                .semantics { contentDescription = "Ждут вас: ${model.attentionCount}" },
                contentAlignment = Alignment.Center) {
                PaperText("${model.attentionCount}", role = PaperTextRole.CHROME, color = colors.actionOn)
            }
        }
    }
}

/**
 * One session: its dot, its name, and below what it is doing. A session that waits on the
 * reader carries the attention tint across the whole row, so a question can never hide among
 * running work.
 */
@Composable
private fun DockCompactRow(
    session: PaperDockSession,
    onOpenSession: (String) -> Unit,
    onPointAt: (String, Boolean) -> Unit,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val chrome = LocalPaperTypography.current.chrome
    val shape = RoundedCornerShape(8.dp)
    val latestPointAt by rememberUpdatedState(onPointAt)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = spacing.xxs)
            .clip(shape)
            .background(if (session.needsYou) colors.activityYellow.copy(alpha = 0.3f) else Color.Transparent)
            // Tracked while the pointer event is dispatched, not from the hover flow, so the
            // row is known by the time the dock's dwell decides to open.
            .pointerInput(session.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> latestPointAt(session.id, true)
                            PointerEventType.Exit -> latestPointAt(session.id, false)
                        }
                    }
                }
            }
            .paperClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "Открыть сессию",
                shape = shape,
            ) { onOpenSession(session.id) }
            .semantics { selected = session.selected }
            .padding(horizontal = spacing.xs, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(DockCompactDotSlot).heightIn(min = 16.dp), contentAlignment = Alignment.Center) {
            PaperActivityIndicator(session.tone, session.statusLabel.ifBlank { session.name }, running = session.running)
        }
        Spacer(Modifier.width(spacing.xs))
        Column(Modifier.weight(1f)) {
            PaperFadingText(session.name, color = colors.text, style = chrome, marqueeOnHover = true)
            (session.activityLabel ?: session.statusLabel).takeIf { it.isNotBlank() }?.let { detail ->
                PaperFadingText(detail, color = colors.secondaryText, style = chrome, marqueeOnHover = true)
            }
        }
    }
}

private fun PaperDockSession.elapsedLabel(nowMillis: Long): String? =
    stateSinceMillis?.takeIf { nowMillis > 0L }?.let { paperDockElapsed(nowMillis - it) }

/**
 * Time in the current state, read the way a status board counts it: 0:42, 12:05, 1:04:09.
 * A day or more is said in days: seconds stop mattering long before that.
 */
internal fun paperDockElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val days = seconds / 86_400
    if (days > 0) return "$days д"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

@Composable
private fun DockExpanded(
    model: PaperAgentDockModel,
    colors: PaperColors,
    spacing: PaperSpacing,
    indicator: @Composable () -> Unit,
    nowMillis: Long,
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
            DockConversation(model, colors, spacing, indicator, nowMillis, input, onInputChange, onSend, onStop,
                onOpenMainWindow, onCollapse, drag, Modifier.fillMaxSize())
            // Resize grips on the two free edges; they sit over the card's padding, not controls.
            Box(Modifier.align(if (dockedToStart) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight().width(DockResizeGrip)
                .semantics { contentDescription = "Изменить ширину панели" }
                .pointerInput(onResizeWidthBy) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onResizeWidthBy(amount.x.toDp().value)
                    }
                })
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(DockResizeGrip)
                .semantics { contentDescription = "Изменить высоту панели" }
                .pointerInput(onResizeHeightBy) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onResizeHeightBy(amount.y.toDp().value)
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
                        subtitle = session.activityLabel ?: session.statusLabel.takeIf { it.isNotBlank() },
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
    nowMillis: Long,
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
                        selectedSession?.elapsedLabel(nowMillis)).joinToString(" · "),
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
            // Back to the compact list, not off the screen: the minimise mark says so.
            PaperIconButton(label = "Свернуть панель", onClick = onCollapse) {
                PaperText("—", role = PaperTextRole.CHROME)
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
        PaperDockStepKind.ERROR -> {
            PaperChatPlainText("✕ ${step.title}", color = colors.error, modifier = Modifier.padding(vertical = 2.dp))
            step.recovery?.let { recovery ->
                val handlers = LocalDockRecovery.current
                PaperRecoveryAction(recovery.label, recovery.pendingLabel, recovery.pending,
                    { handlers.onRecovery(step.id) }, { handlers.onCancel(step.id) })
            }
        }
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

/** Preview clock: every age below is counted from here. */
private const val PreviewNow = 1_000_000_000L

private val previewSessions = listOf(
    PaperDockSession("s2", "Индикатор always-on-top", PaperActivityTone.ATTENTION,
        activityLabel = "Показывать панель в покое?", statusLabel = "Ждём вашего ответа",
        stateSinceMillis = PreviewNow - 42_000, needsYou = true),
    PaperDockSession("s1", "Восстановление дочерних сессий", PaperActivityTone.WORKING, running = true, selected = true,
        activityLabel = "Читает SessionOrganismStore.kt", statusLabel = "работает",
        stateSinceMillis = PreviewNow - 211_000),
    PaperDockSession("s3", "Панель агента", PaperActivityTone.UNREAD,
        statusLabel = "Работа завершена · результат не прочитан", stateSinceMillis = PreviewNow - 3_849_000),
    PaperDockSession("s4", "Старая задача", PaperActivityTone.READY, statusLabel = "ждёт запроса"),
)

@Preview(name = "Dock collapsed · status list", group = "Agent dock", widthDp = 240, heightDp = 200)
@Composable
public fun PaperAgentDockCollapsedPreview() = PaperTheme {
    PaperAgentDock(expanded = false, onExpandedChange = {},
        model = PaperAgentDockModel(sessions = previewSessions, workspaceTitle = "MagicPaper",
            statusLabel = "работает", attentionCount = 2),
        nowMillis = PreviewNow,
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "работает", running = true, size = 16.dp) })
}

@Preview(name = "Dock collapsed · overflow", group = "Agent dock", widthDp = 240, heightDp = 300)
@Composable
public fun PaperAgentDockOverflowPreview() = PaperTheme {
    val many = (1..8).map { index ->
        PaperDockSession("w$index", "Этап $index · миграция журнала", PaperActivityTone.WORKING, running = true,
            activityLabel = "Прогон выполняется", stateSinceMillis = PreviewNow - index * 61_000L)
    }
    PaperAgentDock(expanded = false, onExpandedChange = {},
        model = PaperAgentDockModel(sessions = many, workspaceTitle = "MagicPaper", statusLabel = "работает"),
        nowMillis = PreviewNow,
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
        nowMillis = PreviewNow,
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
