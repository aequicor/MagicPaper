package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.designsystem.paperTextStyle

/** Preview work stays bounded; chat hosts expand the document into their own lazy items. */
@Composable
internal fun MessagePreview(
    source: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    reader: @Composable (Modifier) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val limit = with(LocalDensity.current) { 360.dp.roundToPx() }
    val expansion = LocalMessageExpansion.current?.takeIf { it.source == source }
    Column(modifier) {
        if (expanded) {
            reader(Modifier.fillMaxWidth())
            CollapseMessage { expanded = false }
        } else Box {
            val clipped = truncated || contentHeight > limit
            val fade = if (clipped) Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Mask only the finite preview, never allocate a layer for the hidden text.
                    drawRect(Brush.radialGradient(
                        0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.White,
                        center = Offset(size.width, size.height), radius = 180.dp.toPx()),
                        blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent),
                        startY = (size.height - 12.dp.toPx()).coerceAtLeast(0f), endY = size.height),
                        blendMode = BlendMode.DstIn)
                } else Modifier
            Box(Modifier.heightIn(max = 360.dp).clipToBounds().then(fade)) {
                Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { contentHeight = it.height }) { preview() }
            }
            if (clipped) PaperText("Читать далее",
                Modifier.align(Alignment.BottomEnd).chatDisclosure {
                    if (expansion != null) expansion.expand() else expanded = true
                }.padding(horizontal = 12.dp, vertical = 12.dp),
                role = PaperTextRole.LABEL, color = LocalPaperColors.current.action)
        }
    }
}

/** Plain messages and tool output need the same bound as Markdown, including unbroken lines. */
@Composable
internal fun ChatPlainText(text: String, modifier: Modifier = Modifier, style: TextStyle = paperTextStyle(PaperTextRole.BODY),
    color: Color = LocalPaperColors.current.text) {
    val previewText = remember(text) {
        var end = minOf(text.length, MESSAGE_PREVIEW_CHARS)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val prefix = text.substring(0, end)
        val ranges = textBlockRanges(prefix)
        prefix.substring(0, ranges.take(4).lastOrNull()?.let { it.last + 1 } ?: 0)
    }
    MessagePreview(text, previewText.length < text.length, modifier, preview = {
        SelectionContainer { PaperText(previewText, style = style, color = color) }
    }, reader = { readerModifier ->
        val ranges = remember(text) { textBlockRanges(text) }
        SelectionContainer {
            Column(readerModifier) {
                ranges.forEach { range -> PaperText(text.substring(range), style = style, color = color) }
            }
        }
    })
}
