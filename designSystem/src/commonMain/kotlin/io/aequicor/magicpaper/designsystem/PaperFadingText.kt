package io.aequicor.magicpaper.designsystem


import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Fade overflowing ink into the existing paper, including translucent selected rows.
 * With [marqueeOnHover] a single-line overflow scrolls automatically while the
 * text is hovered, so a long title stays readable without truncation or a tooltip.
 */
@Composable
public fun PaperFadingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalPaperTypography.current.body,
    maxLines: Int = 1,
    marqueeOnHover: Boolean = false,
) {
    var overflowing by remember(text, style, maxLines) { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val scrolling = marqueeOnHover && maxLines == 1 && overflowing && hovered
    PaperText(
        text = text,
        modifier = modifier
            .then(if (marqueeOnHover && maxLines == 1) Modifier.hoverable(hoverInteraction) else Modifier)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflowing && size.width > 0f) {
                    drawRect(
                        brush = if (maxLines > 1) Brush.verticalGradient(
                            listOf(Color.White, Color.Transparent),
                            startY = (size.height - 16.dp.toPx()).coerceAtLeast(0f), endY = size.height,
                        ) else Brush.horizontalGradient(
                            0f to Color.White,
                            0.35f to Color.White.copy(alpha = 0.8f),
                            0.7f to Color.White.copy(alpha = 0.25f),
                            1f to Color.Transparent,
                            startX = (size.width - 24.dp.toPx()).coerceAtLeast(0f),
                            endX = size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .then(
                if (scrolling) {
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 250,
                        repeatDelayMillis = 800,
                    )
                } else Modifier,
            ),
        color = color,
        fontWeight = fontWeight,
        style = style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
        // Под прокруткой текст измеряется без ограничения ширины; переполнение
        // фиксируется по обычной раскладке и не сбрасывается во время анимации.
        onTextLayout = { if (!scrolling) overflowing = it.didOverflowWidth || it.didOverflowHeight },
    )
}
