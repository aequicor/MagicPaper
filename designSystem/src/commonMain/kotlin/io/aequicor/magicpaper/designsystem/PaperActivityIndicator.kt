package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public enum class PaperActivityTone { READY, WORKING, ATTENTION, QUEUED }

/**
 * Indicator silhouette. [DIAMOND] carries the same tones and motion as [CIRCLE]
 * and marks an independent runtime root (immunity) beside circular session dots.
 */
public enum class PaperActivityShape { CIRCLE, DIAMOND }

/**
 * Pastel fill stays saturated; a darker edge keeps small indicators visible on paper.
 * Every [PaperActivityShape] resolves the same pair, so a silhouette never changes meaning.
 */
public fun PaperColors.activityIndicatorColors(tone: PaperActivityTone): Pair<Color, Color> = when (tone) {
    PaperActivityTone.READY -> activityGreen to success
    PaperActivityTone.ATTENTION -> activityYellow to activityYellowEdge
    PaperActivityTone.WORKING -> activityRed to error
    PaperActivityTone.QUEUED -> raisedSurface to secondaryText
}

/** Indicator silhouette and tone; [running] adds the pulse on top of both shapes. */
@Composable
public fun PaperActivityIndicator(tone: PaperActivityTone, label: String, modifier: Modifier = Modifier,
    running: Boolean = false, size: Dp = 10.dp, shape: PaperActivityShape = PaperActivityShape.CIRCLE) {
    PaperActivityGlyph(tone, modifier.semantics { contentDescription = label }, running, size, shape)
}

/**
 * The [DIAMOND] silhouette as an action: the glyph stays decorative while this button owns
 * the platform hit area, focus and selection. [selected] keeps the raised background after
 * the pointer leaves, so an opened independent root stays discoverable.
 */
@Composable
public fun PaperActivityIndicatorButton(
    tone: PaperActivityTone,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: PaperActivityShape = PaperActivityShape.DIAMOND,
    selected: Boolean = false,
    enabled: Boolean = true,
    running: Boolean = false,
    size: Dp = 10.dp,
) {
    PaperIconButton(label = label, onClick = onClick, modifier = modifier, enabled = enabled,
        selected = selected) {
        PaperActivityGlyph(tone, Modifier, running, size, shape)
    }
}

@Composable
private fun PaperActivityGlyph(tone: PaperActivityTone, modifier: Modifier, running: Boolean,
    size: Dp, shape: PaperActivityShape) {
    val (fill, edge) = LocalPaperColors.current.activityIndicatorColors(tone)
    val scale = if (running) {
        val animation = rememberInfiniteTransition(label = "Activity")
        val pulse by animation.animateFloat(.82f, 1f,
            infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "Activity pulse")
        pulse
    } else 1f
    Canvas(modifier.size(size.coerceAtLeast(10.dp))) {
        val stroke = 1.dp.toPx()
        val radius = (this.size.minDimension / 2 - stroke / 2) * scale
        when (shape) {
            PaperActivityShape.CIRCLE -> {
                drawCircle(fill, radius)
                drawCircle(edge, radius, style = Stroke(stroke))
            }
            PaperActivityShape.DIAMOND -> {
                val diamond = Path().apply {
                    moveTo(this@Canvas.size.width / 2, this@Canvas.size.height / 2 - radius)
                    lineTo(this@Canvas.size.width / 2 + radius, this@Canvas.size.height / 2)
                    lineTo(this@Canvas.size.width / 2, this@Canvas.size.height / 2 + radius)
                    lineTo(this@Canvas.size.width / 2 - radius, this@Canvas.size.height / 2)
                    close()
                }
                drawPath(diamond, fill)
                drawPath(diamond, edge, style = Stroke(stroke))
            }
        }
    }
}
