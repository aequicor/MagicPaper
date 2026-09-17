package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Quiet corner at rest; a circular expand/collapse action on hover or keyboard focus. */
@Composable
public fun PaperComposerExpandButton(expanded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = if (expanded) "Свернуть поле ввода" else "Развернуть поле ввода"
    val colors = LocalPaperColors.current
    val policy = LocalPaperPlatformPolicy.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val showAction = hovered || pressed || (focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard) ||
        policy.platform == PaperPlatform.ANDROID
    PaperTooltip(label) {
        Box(modifier.size(policy.density.controlHeight).semantics {
            contentDescription = label
            stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
        }.paperClickable(role = Role.Button, interactionSource = source, shape = CircleShape,
            showHoverFeedback = false, onClick = onClick)) {
            Canvas(Modifier.matchParentSize()) {
                if (showAction) {
                    drawCircle(colors.hover, radius = 12.dp.toPx())
                    val edge = 14.dp.toPx()
                    val origin = center - Offset(edge / 2, edge / 2)
                    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(colors.secondaryText,
                        origin + Offset(edge * x1, edge * y1), origin + Offset(edge * x2, edge * y2),
                        1.2.dp.toPx(), StrokeCap.Round)
                    for (flip in listOf(false, true)) {
                        fun arrow(x1: Float, y1: Float, x2: Float, y2: Float) {
                            if (flip) line(1-x1, 1-y1, 1-x2, 1-y2) else line(x1, y1, x2, y2)
                        }
                        arrow(.58f, .42f, .88f, .12f)
                        if (expanded) { arrow(.58f, .12f, .58f, .42f); arrow(.58f, .42f, .88f, .42f) }
                        else { arrow(.58f, .12f, .88f, .12f); arrow(.88f, .12f, .88f, .42f) }
                    }
                } else {
                    drawArc(colors.secondaryText.copy(alpha = .75f), 270f, 90f, useCenter = false,
                        topLeft = Offset(size.width - 16.dp.toPx(), 4.dp.toPx()),
                        size = Size(12.dp.toPx(), 12.dp.toPx()), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Preview(name = "Composer corner", group = "Research composer", widthDp = 360, heightDp = 140)
@Preview(name = "Composer corner large text", group = "Research composer", widthDp = 360, heightDp = 200, fontScale = 2f)
@Composable
internal fun PaperComposerCornerPreview(buttonModifier: Modifier = Modifier) = PaperTheme {
    var expanded by remember { mutableStateOf(false) }
    PaperSurface(Modifier.fillMaxSize()) {
        Box(Modifier.padding(12.dp)) {
            PaperWorkspaceComposer(document = true) {
                Row(Modifier.fillMaxWidth()) {
                    PaperText("Продолжить исследование…", Modifier.weight(1f).padding(8.dp), role = PaperTextRole.CHROME)
                    PaperComposerExpandButton(expanded, buttonModifier.offset(x = 8.dp, y = (-4).dp)) { expanded = !expanded }
                }
                PaperText(if (expanded) "Развёрнуто" else "Свёрнуто", Modifier.padding(8.dp), role = PaperTextRole.LABEL)
            }
        }
    }
}
