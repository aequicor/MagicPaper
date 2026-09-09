package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public enum class PaperActivityTone { READY, WORKING, ATTENTION, QUEUED }

/** Pastel fill stays saturated; a darker edge keeps small indicators visible on paper. */
@Composable
public fun PaperActivityIndicator(tone: PaperActivityTone, label: String, modifier: Modifier = Modifier,
    running: Boolean = false, size: Dp = 10.dp) {
    val colors = LocalPaperColors.current
    val (fill, edge) = when (tone) {
        PaperActivityTone.READY -> colors.activityGreen to colors.success
        PaperActivityTone.ATTENTION -> colors.activityYellow to colors.activityYellowEdge
        PaperActivityTone.WORKING -> colors.activityRed to colors.error
        PaperActivityTone.QUEUED -> colors.raisedSurface to colors.secondaryText
    }
    val scale = if (running) {
        val animation = rememberInfiniteTransition(label = "Activity")
        val pulse by animation.animateFloat(.82f, 1f,
            infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "Activity pulse")
        pulse
    } else 1f
    Canvas(modifier.size(size.coerceAtLeast(10.dp)).semantics { contentDescription = label }) {
        val stroke = 1.dp.toPx()
        val radius = (this.size.minDimension / 2 - stroke / 2) * scale
        drawCircle(fill, radius)
        drawCircle(edge, radius, style = Stroke(stroke))
    }
}
