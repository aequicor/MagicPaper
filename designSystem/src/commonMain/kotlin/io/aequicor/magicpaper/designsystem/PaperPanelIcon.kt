package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Physical edge of the panel, unchanged when it is collapsed. */
public enum class PaperPanelSide { LEFT, RIGHT }

/** Shared sidebar outline: 16 dp with a 1.2 dp stroke; the enclosing control owns the label. */
@Composable
public fun PaperPanelIcon(side: PaperPanelSide, modifier: Modifier = Modifier) {
    val color = LocalPaperColors.current.secondaryText
    Canvas(modifier.size(16.dp)) {
        val scale = size.minDimension / 16f
        drawRoundRect(color, Offset(scale, 2f * scale), Size(14f * scale, 12f * scale),
            CornerRadius(2f * scale), style = Stroke(1.2f * scale))
        val divider = if (side == PaperPanelSide.LEFT) 5.5f else 10.5f
        drawLine(color, Offset(divider * scale, 2f * scale), Offset(divider * scale, 14f * scale), 1.2f * scale)
    }
}
