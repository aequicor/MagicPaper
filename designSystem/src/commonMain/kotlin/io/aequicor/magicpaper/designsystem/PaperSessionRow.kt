package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared navigation row. Selection opens a session; disclosure never selects it.
 * Actions remain reachable by keyboard and touch, without relying on hover.
 * Depth changes indentation only; status/type belongs in the fixed indicator slot.
 */
@Composable
public fun PaperSessionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    depth: Int = 0,
    subtitle: String? = null,
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    keepActionsVisible: Boolean = false,
    indicator: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalPaperColors.current
    val spacing = LocalPaperSpacing.current
    val shape = RoundedCornerShape(6.dp)
    val rowInteraction = remember { MutableInteractionSource() }
    val contentInteraction = remember { MutableInteractionSource() }
    val hovered by rowInteraction.collectIsHoveredAsState()
    val focused by contentInteraction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth()
            .padding(start = spacing.xs + spacing.sm * depth.coerceIn(0, 3), end = spacing.xs)
            .heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight)
            .clip(shape)
            .background(if (selected) colors.selected else Color.Transparent)
            .hoverable(rowInteraction)
            .padding(horizontal = spacing.xs, vertical = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sibling controls keep Enter on the focused action; nesting them inside
        // the selectable button would let its preview key handler select instead.
        Row(
            Modifier.weight(1f).heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight)
                .paperClickable(role = Role.Button, interactionSource = contentInteraction, onClick = onClick)
                .onPreviewKeyEvent { event ->
                    if (expanded != null && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                        if (event.type == KeyEventType.KeyDown && expanded != (event.key == Key.DirectionRight)) onToggle()
                        true
                    } else false
                }
                .semantics {
                    this.selected = selected
                    if (expanded != null) stateDescription = if (expanded) "Развёрнута" else "Свёрнута"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(spacing.md), contentAlignment = Alignment.Center) { indicator() }
            Spacer(Modifier.width(spacing.xs))
            Column(Modifier.weight(1f)) {
                PaperFadingText(title, color = colors.text, style = LocalPaperTypography.current.chrome, marqueeOnHover = true)
                if (subtitle != null) PaperFadingText(
                    subtitle, style = LocalPaperTypography.current.chrome,
                    color = if (selected) colors.text else colors.secondaryText, marqueeOnHover = true,
                )
            }
        }
        if (expanded != null) PaperIconButton(
            label = if (expanded) "Свернуть: $title" else "Раскрыть: $title",
            onClick = onToggle,
        ) { PaperText(if (expanded) "▾" else "▸", role = PaperTextRole.CHROME) }
        PaperHoverActions(visible = hovered || focused || keepActionsVisible) { actions() }
    }
}
