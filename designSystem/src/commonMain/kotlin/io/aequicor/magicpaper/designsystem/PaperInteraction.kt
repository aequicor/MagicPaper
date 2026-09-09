package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One outline for the hit region, full-surface feedback and keyboard focus. */
public fun Modifier.paperClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    state: PaperControlState = PaperControlState.NORMAL,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val active = enabled && state != PaperControlState.DISABLED && state != PaperControlState.BUSY
    paperFeedback(source, shape, active, state)
        .onPreviewKeyEvent { event ->
            // Consume both halves of Enter so Foundation cannot activate it twice.
            if (active && event.key == Key.Enter) {
                if (event.type == KeyEventType.KeyDown) onClick()
                true
            } else false
        }
        .clickable(source, indication = null, enabled = active, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

/** Also used by editable/toggle controls that own their own input handling. */
internal fun Modifier.paperFeedback(
    source: MutableInteractionSource,
    shape: Shape,
    enabled: Boolean = true,
    state: PaperControlState = PaperControlState.NORMAL,
    showFocus: Boolean = true,
    showPress: Boolean = true,
): Modifier = composed {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val colors = LocalPaperColors.current
    var pointerFocus by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) pointerFocus = false }
    clip(shape)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) pointerFocus = true
                }
            }
        }
        .onPreviewKeyEvent { pointerFocus = false; false }
        .drawWithContent {
        drawContent()
        val outline = shape.createOutline(size, layoutDirection, this)
        if (enabled) {
            if (showPress && (pressed || state == PaperControlState.PRESSED)) drawOutline(outline, colors.pressed)
            else if (hovered || state == PaperControlState.HOVER) drawOutline(outline, colors.hover)
            if (focused && showFocus && !pointerFocus || state == PaperControlState.FOCUSED) {
                // A light separator keeps the focus ring visible on a dark primary button.
                drawOutline(outline, colors.surface, style = Stroke(8.dp.toPx()))
                drawOutline(outline, colors.focus, style = Stroke(4.dp.toPx()))
            }
        }
    }
}

/** Whole-row switch/checkbox semantics for settings and consent groups. */
public fun Modifier.paperToggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role = Role.Checkbox,
    onValueChange: (Boolean) -> Unit,
): Modifier = paperClickable(enabled = enabled, role = role, onClick = { onValueChange(!value) })
    .semantics { toggleableState = if (value) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
