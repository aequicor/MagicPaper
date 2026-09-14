package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch

/** The explanatory action remains focusable when changing the setting is unavailable. */
@Composable
public fun PaperMenuToggleInfo(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    information: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    // This menu action sits at the window edge; keep its explanation within the available popup bounds.
    val position = remember { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            val above = anchorBounds.top - popupContentSize.height
            val y = (if (above >= 0) above else anchorBounds.bottom)
                .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
            return IntOffset(x, y)
        }
    } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .paperClickable(enabled = enabled, role = Role.Checkbox, onClick = { onCheckedChange(!checked) })
            .heightIn(min = LocalPaperPlatformPolicy.current.density.controlHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperText(label, modifier = Modifier.weight(1f), color = if (enabled) LocalPaperColors.current.text else LocalPaperColors.current.secondaryText)
            PaperText(if (checked) "✓" else "", modifier = Modifier.width(18.dp))
        }
        TooltipBox(positionProvider = position, state = tooltip, focusable = false,
            tooltip = { PaperTooltipSurface { PaperText(information, modifier = Modifier.widthIn(max = 240.dp), role = PaperTextRole.CHROME) } }) {
            PaperIconButton(label = "$label: информация", onClick = { scope.launch { tooltip.show() } },
                modifier = Modifier.onFocusChanged { if (it.isFocused) scope.launch { tooltip.show() } else tooltip.dismiss() }) {
                PaperText("ⓘ", role = PaperTextRole.CHROME)
            }
        }
    }
}
