package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Long-form reading typography, scoped so surrounding navigation keeps its compact density. */
internal val LocalPaperResearchReading = staticCompositionLocalOf { false }

@Composable
public fun PaperResearchReading(content: @Composable () -> Unit) {
    val typography = LocalPaperTypography.current
    CompositionLocalProvider(
        LocalPaperTypography provides typography.copy(
            // A compact book rhythm keeps prose readable without spreading each
            // paragraph across the viewport. Use the same sturdy face for headings.
            body = typography.body.copy(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
            headline = typography.headline.copy(
                fontFamily = PaperFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
            title = typography.title.copy(
                fontFamily = PaperFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                lineHeight = 25.sp,
            ),
        ),
        LocalPaperResearchReading provides true,
        content = content,
    )
}

/** Comfortable line length for long-form prose; callers may still shrink with the window. */
public val PaperResearchReadingMeasure: Dp = 720.dp

/** A bordered paper sheet used by the three-column research workspace. */
@Composable
public fun PaperResearchPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalPaperColors.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier.clip(shape)
            .background(colors.surface.copy(alpha = .94f), shape)
            .border(1.dp, colors.border.copy(alpha = .55f), shape),
        content = content,
    )
}

/** A question is a compact numbered navigation item, with room for two lines. */
@Composable
public fun PaperResearchQuestionRow(number: Int, title: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalPaperColors.current
    Row(modifier.fillMaxWidth().heightIn(min = LocalPaperPlatformPolicy.current.density.controlHeight)
        .background(if (selected) colors.selected else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
        .paperClickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Tab; this.selected = selected; contentDescription = "Вопрос $number: $title" }
        .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
        PaperText(number.toString(), Modifier.widthIn(min = 16.dp), role = PaperTextRole.CHROME,
            color = colors.secondaryText, maxLines = 1)
        PaperText(title, Modifier.weight(1f), role = PaperTextRole.CHROME, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One hover surface with independent selection, website and menu actions.
 * The menu aligns with the checkbox; metadata keeps the full text-column width. */
@Composable
public fun PaperResearchSourceRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, keepActionsVisible: Boolean = false,
    detail: String? = null, file: Boolean = false, icon: ImageBitmap? = null,
    readProblem: String? = null, readProblemLabel: String? = null, onReadProblem: (() -> Unit)? = null,
    onOpenWebsite: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalPaperColors.current
    val controlHeight = maxOf(LocalPaperPlatformPolicy.current.density.controlHeight,
        with(LocalDensity.current) { LocalPaperTypography.current.chrome.lineHeight.toDp() })
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var actionFocused by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    var touch by remember { mutableStateOf(false) }
    val showActions = hovered || keepActionsVisible || touch ||
        LocalPaperPlatformPolicy.current.platform == PaperPlatform.ANDROID ||
        (actionFocused && inputMode.inputMode == InputMode.Keyboard)
    CompositionLocalProvider(LocalPaperChildHoverFeedback provides false) {
        Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) colors.hover else Color.Transparent).hoverable(interaction)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.type == PointerType.Touch }) touch = true
                }
            }, verticalAlignment = Alignment.Top) {
            PaperCheck(checked, onCheckedChange, Modifier.heightIn(min = controlHeight)
                .semantics { contentDescription = "Использовать источник: $title" }, enabled)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    PaperText(title, Modifier.weight(1f).heightIn(min = LocalPaperPlatformPolicy.current.density.controlHeight)
                        .paperClickable(enabled = enabled) { onCheckedChange(!checked) }
                        .padding(horizontal = 2.dp, vertical = 3.dp), role = PaperTextRole.CHROME, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = when {
                            readProblem != null -> colors.error
                            checked -> colors.text
                            else -> colors.secondaryText
                        })
                    // Reserve the menu slot, so hover and keyboard focus never reflow the title.
                    Row(Modifier.heightIn(min = controlHeight).onFocusChanged { actionFocused = it.hasFocus }.graphicsLayer {
                        alpha = if (showActions) 1f else 0f
                    }, verticalAlignment = Alignment.CenterVertically, content = trailing)
                }
                Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (detail != null) Row(Modifier.fillMaxWidth().then(
                            if (file || onOpenWebsite == null) Modifier else Modifier
                                .heightIn(min = LocalPaperPlatformPolicy.current.density.controlHeight)
                                .semantics { contentDescription = "Открыть сайт: $detail" }
                                .paperClickable(enabled = enabled, role = Role.Button, onClick = onOpenWebsite)),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (icon != null && !file) PaperImage(icon, null, Modifier.size(14.dp), scale = PaperImageScale.FIT)
                            else PaperResearchSourceIcon(file)
                            PaperText(detail, Modifier.weight(1f), style = LocalPaperTypography.current.chrome.copy(
                                fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                                color = LocalPaperColors.current.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (readProblem != null) PaperTooltip(readProblem) {
                        Box(Modifier
                            .then(if (onReadProblem == null) Modifier else Modifier.heightIn(min = LocalPaperPlatformPolicy.current.density.controlHeight)
                                .paperClickable(enabled = enabled, onClick = onReadProblem))
                            .padding(start = 6.dp).semantics {
                                contentDescription = if (onReadProblem != null) "Прочитать в браузере: $readProblem" else readProblem
                                if (onReadProblem != null) role = Role.Button
                            }, contentAlignment = Alignment.Center) {
                            PaperText(readProblemLabel ?: "Ошибка",
                                style = LocalPaperTypography.current.chrome.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                                color = colors.error, maxLines = 1)
                        }
                    }
                    // Unreadable sources can be removed directly, independently of the hover-only menu.
                    if (readProblem != null && onRemove != null) PaperTooltip("Убрать источник") {
                        PaperIconButton("Убрать источник: $title", onRemove,
                            Modifier.padding(start = 4.dp), enabled = enabled) {
                            PaperDeleteIcon(tint = colors.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaperResearchSourceIcon(file: Boolean) {
    val color = LocalPaperColors.current.secondaryText
    Canvas(Modifier.size(14.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
        if (file) {
            val page = Path().apply {
                moveTo(size.width * .25f, size.height * .1f)
                lineTo(size.width * .6f, size.height * .1f)
                lineTo(size.width * .8f, size.height * .3f)
                lineTo(size.width * .8f, size.height * .9f)
                lineTo(size.width * .25f, size.height * .9f)
                close()
                moveTo(size.width * .6f, size.height * .1f)
                lineTo(size.width * .6f, size.height * .3f)
                lineTo(size.width * .8f, size.height * .3f)
            }
            drawPath(page, color, style = stroke)
        } else {
            drawCircle(color, size.width * .4f, style = stroke)
            drawOval(color, Offset(size.width * .3f, size.height * .1f),
                androidx.compose.ui.geometry.Size(size.width * .4f, size.height * .8f), style = stroke)
            drawLine(color, Offset(size.width * .1f, size.height * .5f), Offset(size.width * .9f, size.height * .5f), stroke.width)
        }
    }
}

/** Overflow glyph with stable geometry, independent of font fallback and text scale. */
@Composable
public fun PaperMoreIcon(modifier: Modifier = Modifier) {
    val color = LocalPaperColors.current.text
    Canvas(modifier.size(16.dp)) {
        for (x in listOf(.2f, .5f, .8f)) drawCircle(color, 1.2.dp.toPx(), Offset(size.width * x, size.height / 2))
    }
}

/** Selection, disclosure and file addition are independent sibling actions. */
@Composable
public fun PaperResearchSourceGroupHeader(title: String, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, selectedCount: Int = 0, totalCount: Int = 0,
    onSelectionChange: (Boolean) -> Unit = {}, enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = LocalPaperColors.current
    val allSelected = totalCount > 0 && selectedCount == totalCount
    val partiallySelected = selectedCount > 0 && selectedCount < totalCount
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalPaperChildHoverFeedback provides false) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (hovered) colors.hover else Color.Transparent).hoverable(interaction),
                verticalAlignment = Alignment.CenterVertically) {
                PaperCheck(allSelected, onSelectionChange, Modifier.semantics {
                    contentDescription = if (allSelected) "Снять выбор со всех: $title" else "Выбрать все: $title"
                    stateDescription = when {
                        allSelected -> "Выбраны все"
                        partiallySelected -> "Выбрана часть"
                        else -> "Ничего не выбрано"
                    }
                }, enabled = enabled && totalCount > 0, indeterminate = partiallySelected)
                PaperAction(onToggle, Modifier.weight(1f).semantics {
                    contentDescription = if (expanded) "Свернуть: $title" else "Развернуть: $title"
                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                }.onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                        if (event.type == KeyEventType.KeyDown && expanded != (event.key == Key.DirectionRight)) onToggle()
                        true
                    } else false
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PaperText(title, Modifier.weight(1f), role = PaperTextRole.CHROME,
                            color = colors.secondaryText, maxLines = 2)
                        Canvas(Modifier.width(12.dp).height(12.dp)) {
                            val path = Path().apply {
                                if (expanded) {
                                    moveTo(size.width * .2f, size.height * .35f)
                                    lineTo(size.width * .5f, size.height * .65f)
                                    lineTo(size.width * .8f, size.height * .35f)
                                } else {
                                    moveTo(size.width * .35f, size.height * .2f)
                                    lineTo(size.width * .65f, size.height * .5f)
                                    lineTo(size.width * .35f, size.height * .8f)
                                }
                            }
                            drawPath(path, colors.secondaryText, style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
                        }
                    }
                }
                trailing()
            }
        }
        if (expanded) PaperDivider()
    }
}

/** Pointer-sized drag lane between research panes; the visible grip stays deliberately quiet. */
@Composable
public fun PaperResearchResizeHandle(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    reverseDirection: Boolean = false,
) {
    val color = LocalPaperColors.current.border
    val density = LocalDensity.current
    fun update(delta: Float) {
        val directed = if (reverseDirection) -delta else delta
        onValueChange((value + directed).coerceIn(valueRange))
    }
    Box(
        modifier.width(12.dp).fillMaxHeight()
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { target -> onValueChange(target.coerceIn(valueRange)); true }
            }
            .draggable(rememberDraggableState { delta -> update(with(density) { delta.toDp().value }) }, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).background(color, RoundedCornerShape(6.dp)))
    }
}

/** A compact remnant of a collapsed research pane that keeps its frequent actions reachable. */
@Composable
public fun PaperResearchRail(
    title: String,
    count: Int,
    expandLabel: String,
    expandGlyph: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    PaperResearchPane(modifier) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaperTooltip(expandLabel) {
                PaperIconButton(expandLabel, onExpand) {
                    PaperText(expandGlyph, role = PaperTextRole.TITLE, color = LocalPaperColors.current.action)
                }
            }
            Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) {
                PaperText(
                    title,
                    Modifier.rotate(-90f),
                    role = PaperTextRole.LABEL,
                    color = LocalPaperColors.current.secondaryText,
                    maxLines = 1,
                )
            }
            PaperResearchCountBadge(count.toString())
            PaperDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            actions()
        }
    }
}

/** A labelled icon action intended for [PaperResearchRail]. */
@Composable
public fun PaperResearchRailAction(
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PaperTooltip(label, modifier) {
        PaperIconButton(label, onClick, enabled = enabled) {
            PaperText(glyph, role = PaperTextRole.CHROME, color = LocalPaperColors.current.action)
        }
    }
}

/** Small numerical marker shared by panel counters, sources and citations. */
@Composable
public fun PaperResearchCountBadge(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
) {
    Box(
        modifier = modifier.sizeIn(minWidth = 26.dp, minHeight = 26.dp)
            .background(
                if (selected) LocalPaperColors.current.selected else LocalPaperColors.current.raisedSurface,
                CircleShape,
            ).padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        PaperText(text, role = PaperTextRole.LABEL)
    }
}

/** Folded-page brand mark rendered with vectors so every desktop host gets the same glyph. */
@Composable
public fun PaperBrandMark(modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    Canvas(modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp)) {
        val left = Path().apply {
            moveTo(size.width * .12f, size.height * .10f)
            lineTo(size.width * .48f, size.height * .28f)
            lineTo(size.width * .48f, size.height * .90f)
            lineTo(size.width * .12f, size.height * .70f)
            close()
        }
        val right = Path().apply {
            moveTo(size.width * .88f, size.height * .10f)
            lineTo(size.width * .52f, size.height * .28f)
            lineTo(size.width * .52f, size.height * .90f)
            lineTo(size.width * .88f, size.height * .70f)
            close()
        }
        drawPath(left, colors.accentSurface)
        drawPath(right, colors.action.copy(alpha = .72f))
        drawLine(
            colors.text.copy(alpha = .24f),
            Offset(size.width * .5f, size.height * .28f),
            Offset(size.width * .5f, size.height * .9f),
            1.dp.toPx(),
        )
    }
}

/** The user gets a compact prompt card; the answer reads directly on the surrounding paper pane. */
@Composable
public fun Modifier.paperResearchMessage(first: Boolean, last: Boolean, user: Boolean): Modifier {
    val radius = 4.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    // A question is a reader's note; the response is the page itself. Keeping the
    // answer unboxed makes long text scan like a chapter instead of a message bubble.
    val surface = if (user) LocalPaperColors.current.selected.copy(alpha = .35f)
        else androidx.compose.ui.graphics.Color.Transparent
    return padding(
        start = 12.dp,
        top = if (first) if (user) 6.dp else 12.dp else 0.dp,
        end = 12.dp,
        bottom = if (last) if (user) 6.dp else 8.dp else 0.dp,
    )
        .background(surface, shape)
        .padding(horizontal = 12.dp)
        .padding(top = if (first) if (user) 8.dp else 4.dp else 0.dp,
            bottom = if (last) if (user) 12.dp else 8.dp else 0.dp)
}

/** Keeps the same editor/focus while moving from the welcome page to the response dock.
 * Compose's MotionDurationScale applies to this finite, interruptible transition. */
@Composable
public fun paperResearchComposerAlignment(centered: Boolean): Alignment {
    val bias by animateFloatAsState(if (centered) 0f else 1f, tween(220), label = "Research composer position")
    return BiasAlignment(0f, bias)
}
