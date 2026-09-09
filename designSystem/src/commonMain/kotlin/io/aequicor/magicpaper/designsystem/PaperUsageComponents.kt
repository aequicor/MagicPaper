package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** A branded icon, independent of emoji fonts on the host OS. */
@Composable
public fun PaperCoinIcon(modifier: Modifier = Modifier) {
    val ink = LocalPaperColors.current.action
    Canvas(modifier.size(20.dp)) {
        drawCircle(ink, radius = size.minDimension * .42f, style = Stroke(1.6.dp.toPx()))
        drawCircle(ink, radius = size.minDimension * .30f, style = Stroke(.8.dp.toPx()))
        val mark = Path().apply {
            moveTo(size.width * .62f, size.height * .37f)
            cubicTo(size.width * .31f, size.height * .24f, size.width * .28f, size.height * .49f, size.width * .50f, size.height * .50f)
            cubicTo(size.width * .75f, size.height * .51f, size.width * .65f, size.height * .77f, size.width * .36f, size.height * .63f)
        }
        drawPath(mark, ink, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
        drawLine(ink, Offset(size.width * .5f, size.height * .26f), Offset(size.width * .5f, size.height * .76f), 1.dp.toPx())
    }
}

/** Known, estimated and unavailable context share the same focusable control. */
@Composable
public fun PaperContextIndicator(fraction: Float?, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    var focused by remember { mutableStateOf(false) }
    val focus = modifier.onFocusChanged { focused = it.isFocused }.then(
        if (focused) Modifier.border(2.dp, colors.action, RoundedCornerShape(6.dp)) else Modifier)
    PaperIconButton("Заполненность контекста: $label", onClick, focus.semantics {
        stateDescription = label
        if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
    }) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = Stroke(2.dp.toPx())
                drawCircle(colors.border, style = stroke)
                if (fraction != null) drawArc(if (fraction >= .9f) colors.error else colors.action,
                    -90f, 360f * fraction.coerceIn(0f, 1f), false, style = stroke)
            }
            PaperText(label, role = PaperTextRole.LABEL)
        }
    }
}

/** Same body typography and width as an agent message; system content is always left aligned. */
@Composable
public fun PaperSystemMessage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPaperColors.current
    Row(modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Start) {
        PaperPanel(Modifier.widthIn(max = 680.dp), color = colors.systemSurface,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)) {
            CompositionLocalProvider(LocalPaperColors provides colors.copy(text = colors.systemText, secondaryText = colors.systemText)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), content = content)
            }
        }
    }
}
