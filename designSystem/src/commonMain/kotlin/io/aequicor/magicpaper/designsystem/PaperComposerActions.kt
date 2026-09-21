package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Frequent attachment and settings actions stay visible independently of the settings shelf. */
@Composable
public fun PaperComposerActions(expanded: Boolean, onAttach: () -> Unit, onToggleOptions: () -> Unit,
    modifier: Modifier = Modifier, optionsModifier: Modifier = Modifier, attachEnabled: Boolean = true,
    showOptions: Boolean = true) {
    BoxWithConstraints(modifier) {
        val wrap = maxWidth < 232.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperAction(onAttach, enabled = attachEnabled, modifier = Modifier.semantics { contentDescription = "Прикрепить файлы" }) {
                    ComposerActionIcon(attachment = true)
                    Spacer(Modifier.width(6.dp))
                    PaperText("Прикрепить", role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
                }
                if (!wrap && showOptions) PaperVerticalDivider(Modifier.padding(horizontal = 4.dp).height(14.dp))
            }
            if (showOptions) PaperAction(onToggleOptions, optionsModifier.semantics {
                contentDescription = if (expanded) "Скрыть параметры" else "Показать параметры"
                stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
            }) {
                ComposerActionIcon(attachment = false)
                Spacer(Modifier.width(6.dp))
                PaperText("Параметры", role = PaperTextRole.CHROME,
                    color = if (expanded) LocalPaperColors.current.action else LocalPaperColors.current.secondaryText)
            }
        }
    }
}

@Composable
private fun ComposerActionIcon(attachment: Boolean) {
    val ink = LocalPaperColors.current.secondaryText
    Canvas(Modifier.size(16.dp)) {
        scale(size.width / 16f, size.height / 16f, pivot = Offset.Zero) {
            if (attachment) {
                val clip = Path().apply {
                    moveTo(11.7f, 6.5f); lineTo(6.4f, 12.2f)
                    cubicTo(3.2f, 15.7f, -.7f, 11.8f, 2.6f, 8.3f)
                    lineTo(8.4f, 2.2f); cubicTo(10.8f, -.3f, 14.4f, 2.6f, 12.1f, 5.1f)
                    lineTo(6.4f, 11.1f); cubicTo(5.1f, 12.5f, 3.6f, 11f, 4.9f, 9.6f)
                    lineTo(10f, 4.1f)
                }
                drawPath(clip, ink, style = Stroke(1.2f, cap = StrokeCap.Round))
            } else {
                listOf(3f to 5f, 8f to 11f, 13f to 6f).forEach { (y, x) ->
                    drawLine(ink, Offset(1f, y), Offset(x - 2f, y), 1.2f, StrokeCap.Round)
                    drawLine(ink, Offset(x + 2f, y), Offset(15f, y), 1.2f, StrokeCap.Round)
                    drawCircle(ink, 1.8f, Offset(x, y), style = Stroke(1.2f))
                }
            }
        }
    }
}
