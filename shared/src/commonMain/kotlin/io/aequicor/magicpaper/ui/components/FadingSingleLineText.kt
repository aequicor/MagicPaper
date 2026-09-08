package io.aequicor.magicpaper.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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

/** Fade overflowing ink into the existing paper, including translucent selected rows. */
@Composable
internal fun FadingSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    var overflowing by remember(text, style) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflowing && size.width > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
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
            },
        color = color,
        fontWeight = fontWeight,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { overflowing = it.didOverflowWidth },
    )
}
