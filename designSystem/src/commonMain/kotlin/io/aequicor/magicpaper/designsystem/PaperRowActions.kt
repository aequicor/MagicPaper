package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Keep row height stable while hidden actions give their width back to the title. */
@Composable
fun PaperHoverActions(visible: Boolean, content: @Composable () -> Unit) {
    Layout(
        modifier = if (visible) Modifier else Modifier.clearAndSetSemantics {},
        content = { Row(verticalAlignment = Alignment.CenterVertically) { content() } },
    ) { measurables, constraints ->
        val actions = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(if (visible) actions.width else 0, actions.height) {
            if (visible) actions.placeRelative(0, 0)
        }
    }
}

/** An accessible row menu whose actions share the standard Paper menu behavior. */
@Composable
fun PaperRowMenu(open: Boolean, onOpenChange: (Boolean) -> Unit, entries: List<Pair<String, () -> Unit>>) {
    Box {
        PaperIconButton(label = "Действия", onClick = { onOpenChange(true) }) {
            PaperText("⋯", style = LocalPaperTypography.current.label, color = LocalPaperColors.current.secondaryText)
        }
        if (open) {
            PaperMenuHost(expanded = true, onDismissRequest = { onOpenChange(false) }) {
                entries.forEach { (label, action) ->
                    PaperRichMenuAction(text = { PaperText(label) }, onClick = { onOpenChange(false); action() })
                }
            }
        }
    }
}
