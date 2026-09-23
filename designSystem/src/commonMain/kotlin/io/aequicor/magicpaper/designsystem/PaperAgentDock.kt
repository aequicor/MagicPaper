package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.aequicor.magicpaper.ui.components.PaperChatScrollItem
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

/** Collapsed tab width: wide enough to grab, narrow enough not to cover the screen edge. */
public val PaperAgentDockCollapsedWidth: Dp = 26.dp

/** Collapsed tab height: a comfortable pointer and touch target. */
public val PaperAgentDockCollapsedHeight: Dp = 72.dp

public val PaperAgentDockExpandedWidth: Dp = 360.dp
public val PaperAgentDockExpandedHeight: Dp = 460.dp

/**
 * Hover dwell before the tab opens. A pointer crossing the screen edge on its way to another
 * application must not throw a 360 dp panel over that work.
 */
public const val PaperAgentDockExpandDelayMillis: Long = 250L

/** Grace period after the pointer leaves, so travelling inside the panel does not retract it. */
public const val PaperAgentDockCollapseDelayMillis: Long = 400L

public enum class PaperDockAuthor { USER, AGENT }

/** One transcript row. Text is Markdown for the agent and literal for the reader's own input. */
public data class PaperDockMessage(val id: String, val author: PaperDockAuthor, val text: String)

/**
 * Everything the dock shows about one session. [liveDetail] is the run's current step; it is
 * what makes a collapsed or freshly expanded dock reflect work in progress rather than the
 * last saved message.
 */
public data class PaperAgentDockModel(
    val statusLabel: String,
    val sessionLabel: String,
    val messages: List<PaperDockMessage> = emptyList(),
    val liveDetail: String? = null,
    val busy: Boolean = false,
    val canSend: Boolean = true,
    /**
     * Composer placeholder, supplied by the host so an unavailable composer can say why
     * instead of inviting input that would be discarded.
     */
    val inputPlaceholder: String = "Сообщение агенту…",
    /** Identity of the shown session: switching sessions resets the transcript anchor. */
    val transcriptKey: Any? = null,
)

/**
 * @param dockedToStart true when the panel hugs the leading screen edge, so only the inner
 *   corners are rounded and the panel grows away from that edge.
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
    dockedToStart: Boolean = true,
    indicator: @Composable () -> Unit,
    input: String = "",
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onOpenMainWindow: () -> Unit = {},
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    expandDelayMillis: Long = PaperAgentDockExpandDelayMillis,
    collapseDelayMillis: Long = PaperAgentDockCollapseDelayMillis,
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val corner = 12.dp
    val shape = if (dockedToStart) {
        RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = corner, bottomEnd = corner)
    } else {
        RoundedCornerShape(topStart = corner, bottomStart = corner, topEnd = 0.dp, bottomEnd = 0.dp)
    }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Who owns the open panel. A pointer that leaves retracts its own panel, but a keyboard or
    // touch activation has no pointer to leave with: only the reader closes that one, otherwise
    // activating the tab would flash the panel and take it away again.
    var openedByPointer by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, expanded) {
        if (!hovered || expanded) return@LaunchedEffect
        // Dwell before opening: a pointer crossing the edge on its way to another application
        // must not throw a 360 dp panel over that work.
        delay(expandDelayMillis)
        if (hovered) {
            openedByPointer = true
            onExpandedChange(true)
        }
    }
    LaunchedEffect(hovered, expanded) {
        if (!expanded) {
            openedByPointer = false
            return@LaunchedEffect
        }
        // An open panel under the pointer belongs to the pointer again.
        if (hovered) {
            openedByPointer = true
            return@LaunchedEffect
        }
        if (!openedByPointer) return@LaunchedEffect
        // Grace period, so travelling across the panel does not retract it.
        delay(collapseDelayMillis)
        if (!hovered) {
            openedByPointer = false
            onExpandedChange(false)
        }
    }

    val drag = Modifier.pointerInput(onDragBy, onDragEnd) {
        detectDragGestures(
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
        ) { change, amount ->
            change.consume()
            onDragBy(amount.x, amount.y)
        }
    }

    PaperSurface(
        modifier = modifier.fillMaxSize()
            // Hover belongs to the whole panel, in both states: tracking it only on the tab
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
        kind = PaperSurfaceKind.RAISED,
        shape = shape,
        shadowElevation = 6.dp,
    ) {
        if (expanded) {
            DockExpanded(model, colors, spacing, indicator, input, onInputChange, onSend, onStop,
                onOpenMainWindow, { onExpandedChange(false) }, drag)
        } else {
            DockCollapsed(model, colors, shape, indicator, drag) {
                // An explicit activation is not pointer-owned, so it survives having no pointer.
                openedByPointer = false
                onExpandedChange(true)
            }
        }
    }
}

@Composable
private fun DockCollapsed(
    model: PaperAgentDockModel,
    colors: PaperColors,
    shape: RoundedCornerShape,
    indicator: @Composable () -> Unit,
    drag: Modifier,
    onExpand: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .clip(shape)
            // A hairline keeps the tab legible over a light desktop without a heavy frame.
            .border(1.dp, colors.border, shape)
            .paperClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "Открыть панель агента",
                shape = shape,
                onClick = onExpand,
            )
            .then(drag)
            .semantics {
                contentDescription = "Агент · ${model.sessionLabel}: ${model.statusLabel}"
            },
        contentAlignment = Alignment.Center,
    ) {
        indicator()
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
    onCollapse: () -> Unit,
    drag: Modifier,
) {
    Column(Modifier.fillMaxSize()) {
        // The header doubles as the drag handle, the way a title bar does.
        Row(
            Modifier.fillMaxWidth().then(drag).padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) { indicator() }
            Spacer(Modifier.width(spacing.xs))
            Column(Modifier.weight(1f)) {
                PaperFadingText(model.sessionLabel, style = LocalPaperTypography.current.label,
                    color = colors.text, marqueeOnHover = true)
                PaperFadingText(model.statusLabel, style = LocalPaperTypography.current.chrome,
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
    if (message.author == PaperDockAuthor.USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .background(colors.userMessageSurface, RoundedCornerShape(10.dp))
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
            ) {
                // The reader's own input is literal text: rendering it as Markdown would
                // reformat what they typed.
                PaperText(message.text, style = LocalPaperTypography.current.body, color = colors.text)
            }
        }
    } else {
        Box(
            Modifier.fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(10.dp))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        ) {
            PaperMarkdown(message.text, compact = true)
        }
    }
}

@Preview(name = "Dock collapsed · working", group = "Agent dock", widthDp = 26, heightDp = 72)
@Composable
public fun PaperAgentDockCollapsedPreview() = PaperTheme {
    PaperAgentDock(expanded = false, onExpandedChange = {}, dockedToStart = true,
        model = PaperAgentDockModel(statusLabel = "работает", sessionLabel = "Восстановление сессий"),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "работает", running = true, size = 12.dp) })
}

@Preview(name = "Dock expanded · streaming", group = "Agent dock", widthDp = 360, heightDp = 460)
@Composable
public fun PaperAgentDockExpandedPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {}, dockedToStart = true,
        model = PaperAgentDockModel(
            statusLabel = "работает",
            sessionLabel = "Восстановление дочерних сессий после сбоя",
            busy = true,
            liveDetail = "Читает SessionOrganismStore.kt",
            transcriptKey = "session-1",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.USER, "Почему сессии теряются после падения?"),
                PaperDockMessage("2", PaperDockAuthor.AGENT,
                    "Журнал восстанавливается **до** `CodingService.start()`, поэтому точка останова остаётся невидимой.\n\n- порядок фаз в `AppRuntime`\n- `recovery` не публикуется"),
                PaperDockMessage("3", PaperDockAuthor.USER, "Исправь порядок."),
            ),
        ),
        indicator = { PaperActivityIndicator(PaperActivityTone.WORKING, "работает", running = true, size = 12.dp) },
        input = "Проверь ещё и планирование")
}

@Preview(name = "Dock expanded · waiting", group = "Agent dock", widthDp = 360, heightDp = 460)
@Composable
public fun PaperAgentDockWaitingPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {}, dockedToStart = false,
        model = PaperAgentDockModel(
            statusLabel = "Ждём вашего ответа",
            sessionLabel = "Индикатор always-on-top",
            transcriptKey = "session-2",
            canSend = false,
            inputPlaceholder = "Ответьте на вопрос в окне MagicPaper",
            messages = listOf(
                PaperDockMessage("1", PaperDockAuthor.AGENT, "Нужно уточнение: показывать панель в покое или только во время прогона?"),
            ),
        ),
        indicator = { PaperActivityIndicator(PaperActivityTone.ATTENTION, "Ждём вашего ответа", size = 12.dp) })
}

@Preview(name = "Dock expanded · empty", group = "Agent dock", widthDp = 360, heightDp = 460)
@Composable
public fun PaperAgentDockEmptyPreview() = PaperTheme {
    PaperAgentDock(expanded = true, onExpandedChange = {}, dockedToStart = true,
        model = PaperAgentDockModel(statusLabel = "ждёт запроса", sessionLabel = "Новая сессия", canSend = false,
            inputPlaceholder = "Сессия недоступна для ввода"),
        indicator = { PaperActivityIndicator(PaperActivityTone.READY, "ждёт запроса", size = 12.dp) })
}
