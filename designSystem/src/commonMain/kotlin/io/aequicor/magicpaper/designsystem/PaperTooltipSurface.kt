package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/** Both tooltip APIs share oak and warm ivory, including default PaperText in slots. */
@Composable
internal fun TooltipScope.PaperTooltipSurface(content: @Composable () -> Unit) {
    val colors = LocalPaperColors.current
    CompositionLocalProvider(LocalPaperColors provides colors.copy(
        text = colors.tooltipText, secondaryText = colors.tooltipText, action = colors.tooltipText,
    )) {
        PlainTooltip(containerColor = colors.tooltipSurface, contentColor = colors.tooltipText,
            shape = RoundedCornerShape(6.dp), content = content)
    }
}
