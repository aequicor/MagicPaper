package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

@Composable
public fun PaperComputerControlBar(detail: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    PaperSurface(modifier, kind = PaperSurfaceKind.SELECTED) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PaperText("Управление компьютером", role = PaperTextRole.CHROME)
                PaperText(detail, role = PaperTextRole.CHROME, maxLines = 2)
            }
            PaperButton("Остановить", onStop, kind = PaperButtonKind.DESTRUCTIVE)
        }
    }
}

/** Finite feedback only; no idle animation or screenshot bytes in the renderer. */
@Composable
public fun PaperComputerFeedback(sequence: Long, cursor: Offset?, clicked: Boolean, modifier: Modifier = Modifier) {
    val fade = remember { Animatable(0f) }
    LaunchedEffect(sequence) { fade.snapTo(1f); fade.animateTo(0f, tween(700)) }
    PaperComputerFeedbackFrame(fade.value, cursor, clicked, modifier)
}

@Composable
public fun PaperComputerFeedbackFrame(progress: Float, cursor: Offset?, clicked: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    Canvas(modifier) {
        val alpha = progress.coerceIn(0f, 1f)
        // Lilac halo and a narrow high-contrast rim. Transparent center leaves the target readable.
        for (step in 1..5) drawRoundRect(colors.action.copy(alpha = alpha * 0.035f),
            cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke((step * 5).dp.toPx()))
        drawRoundRect(colors.action.copy(alpha = alpha * 0.8f), cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(3.dp.toPx()))
        cursor?.let { point ->
            val center = Offset(point.x.dp.toPx(), point.y.dp.toPx())
            val radius = (if (clicked) 12 + (1 - alpha) * 24 else 14f).dp.toPx()
            drawCircle(colors.actionOn.copy(alpha = alpha), radius + 2.dp.toPx(), center, style = Stroke(5.dp.toPx()))
            drawCircle(colors.action.copy(alpha = alpha), radius, center, style = Stroke(3.dp.toPx()))
            drawCircle(colors.action.copy(alpha = alpha * 0.18f), radius, center)
        }
    }
}

@Preview(name = "Computer controls", group = "Computer use", widthDp = 480, heightDp = 80)
@Composable
public fun PaperComputerControlPreview() { PaperTheme { PaperComputerControlBar("Нажатие мышью", {}) } }

@Preview(name = "Capture and click", group = "Computer use", widthDp = 480, heightDp = 320)
@Composable
public fun PaperComputerFeedbackPreview() { PaperTheme { PaperSurface { Box(Modifier.fillMaxSize()) {
    PaperText("Рабочий экран", Modifier.align(Alignment.Center))
    PaperComputerFeedbackFrame(0.8f, Offset(140f, 160f), true, Modifier.fillMaxSize())
} } } }
