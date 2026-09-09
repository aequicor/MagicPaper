package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A message with a compact pin action in a reserved trailing gutter.
 * The action never adds a footer row or determines the message height.
 * Unpinned messages retain their full content width; text remains free to wrap and scale.
 */
@Composable
public fun PaperPinnedMessage(number: Int?, onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        Column(Modifier.padding(end = if (number != null) 24.dp else 0.dp), content = content)
        if (number != null) {
            Box(Modifier.matchParentSize()) {
                MessagePinButton(number, onClick, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

@Composable
private fun MessagePinButton(number: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalPaperColors.current.text.copy(alpha = .6f)
    Box(
        modifier = modifier.size(20.dp).clip(CircleShape)
            .paperClickable(shape = CircleShape, role = Role.Button, onClickLabel = "Открыть список закреплений", onClick = onClick)
            .semantics { contentDescription = "Закреплённое сообщение №$number. Открыть список" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val path = Path().apply {
                moveTo(size.width * .3f, size.height * .12f)
                lineTo(size.width * .7f, size.height * .12f)
                lineTo(size.width * .64f, size.height * .44f)
                lineTo(size.width * .8f, size.height * .62f)
                lineTo(size.width * .2f, size.height * .62f)
                lineTo(size.width * .36f, size.height * .44f)
                close()
            }
            drawPath(path, ink, style = Stroke(width = 1.4.dp.toPx()))
            drawLine(ink, Offset(size.width * .5f, size.height * .62f),
                Offset(size.width * .5f, size.height * .92f), 1.4.dp.toPx(), StrokeCap.Round)
        }
    }
}

