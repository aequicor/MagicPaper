package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Known, estimated, unavailable and actively compacting context share the same focusable control. */
@Composable
public fun PaperContextIndicator(
    fraction: Float?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compacting: Boolean = false,
) {
    val colors = LocalPaperColors.current
    val rotation = if (compacting) {
        val animation = rememberInfiniteTransition(label = "Context compaction")
        animation.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "Context compaction rotation",
        )
    } else null
    val state = if (compacting) "Сжатие контекста" else label
    PaperIconButton("Заполненность контекста: $state", onClick, modifier.semantics {
        stateDescription = state
        if (!compacting && fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
    }) {
        Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).drawWithCache {
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val left = inset
            val top = inset
            val right = size.width - inset
            val bottom = size.height - inset
            val radius = minOf(right - left, bottom - top) / 2
            // Start at twelve o'clock. Length-based progress remains accurate when
            // a longer label stretches the circle into a capsule.
            val track = Path().apply {
                moveTo(size.width / 2, top)
                lineTo(right - radius, top)
                arcTo(Rect(right - 2 * radius, top, right, top + 2 * radius), -90f, 90f, false)
                lineTo(right, bottom - radius)
                arcTo(Rect(right - 2 * radius, bottom - 2 * radius, right, bottom), 0f, 90f, false)
                lineTo(left + radius, bottom)
                arcTo(Rect(left, bottom - 2 * radius, left + 2 * radius, bottom), 90f, 90f, false)
                lineTo(left, top + radius)
                arcTo(Rect(left, top, left + 2 * radius, top + 2 * radius), 180f, 90f, false)
                close()
            }
            val measure = PathMeasure().apply { setPath(track, false) }
            val progress = Path()
            onDrawBehind {
                drawPath(track, colors.border, style = stroke)
                val amount = if (compacting) 105f / 360f else fraction?.coerceIn(0f, 1f) ?: 0f
                if (amount > 0f) {
                    val start = (rotation?.value ?: 0f) / 360f * measure.length
                    val end = start + amount * measure.length
                    progress.reset()
                    measure.getSegment(start, minOf(end, measure.length), progress)
                    if (end > measure.length) measure.getSegment(0f, end - measure.length, progress)
                    drawPath(progress, if (!compacting && (fraction ?: 0f) >= .9f) colors.error else colors.action, style = stroke)
                }
            }
        }, contentAlignment = Alignment.Center) {
            PaperText(label, Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                style = LocalPaperTypography.current.chrome.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1)
        }
    }
}

/** Same body typography and width as an agent message; system content is always left aligned. */
@Composable
public fun PaperSystemMessage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPaperColors.current
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Start) {
        PaperPanel(Modifier.widthIn(max = 820.dp), color = colors.systemSurface,
            shape = RoundedCornerShape(12.dp)) {
            CompositionLocalProvider(LocalPaperColors provides colors.copy(text = colors.systemText, secondaryText = colors.systemText)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}
