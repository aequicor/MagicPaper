package io.aequicor.magicpaper.designsystem


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import io.aequicor.magicpaper.designsystem.paperClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

public enum class PaperToolbarIcon { Sidebar, Settings, Archive }

/** Монохромные значки без зависимости от платформенного emoji-шрифта. */
@Composable
public fun PaperToolbarButton(
    icon: PaperToolbarIcon,
    label: String,
    size: Dp,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val ink = if (selected) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText
    PaperIconButton(label, onClick, Modifier.size(size), selected = selected) {
        val gear = remember {
            Path().apply {
                // Восемь зубцов вокруг круглой ступицы, в координатах 16 × 16.
                repeat(64) { point ->
                    val angle = point * PI / 32 - PI / 2
                    val radius = if (point % 8 in 2..5) 7.0 else 5.5
                    val x = (8 + cos(angle) * radius).toFloat()
                    val y = (8 + sin(angle) * radius).toFloat()
                    if (point == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
        Canvas(Modifier.size(16.dp)) {
            scale(this.size.width / 16f, this.size.height / 16f, pivot = Offset.Zero) {
                val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                when (icon) {
                    PaperToolbarIcon.Sidebar -> {
                        drawRoundRect(ink, Offset(1f, 2f), Size(14f, 12f), CornerRadius(2f), style = stroke)
                        drawLine(ink, Offset(5.5f, 2f), Offset(5.5f, 14f), strokeWidth = 1.2f)
                    }
                    PaperToolbarIcon.Archive -> {
                        drawRoundRect(ink, Offset(2f, 5f), Size(12f, 9f), CornerRadius(1f), style = stroke)
                        drawRoundRect(ink, Offset(1f, 2f), Size(14f, 3f), CornerRadius(0.5f), style = stroke)
                        drawLine(ink, Offset(6f, 8f), Offset(10f, 8f), strokeWidth = 1.2f, cap = StrokeCap.Round)
                    }
                    PaperToolbarIcon.Settings -> {
                        drawPath(gear, ink, style = stroke)
                        drawCircle(ink, radius = 2.3f, center = Offset(8f, 8f), style = stroke)
                    }
                }
            }
        }
    }
}
