package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A task disclosure in a navigation tree. Activating the header only changes
 * expansion; [active] indicates that the current selection belongs to the group.
 * Left/Right collapse/expand idempotently. Content follows the tree's leading
 * guide while the disclosure, optional status and title share a vertical centre.
 * The full title remains available to accessibility and in a tooltip at any scale.
 * [onClick] opens the zygote session when the title area is clicked; expansion
 * is still toggled via the disclosure arrow or keyboard. [trailing] renders
 * an optional side action (e.g. immunity diamond) after the title.
 */
@Composable
public fun PaperTreeGroupHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalPaperColors.current
    val shape = RoundedCornerShape(6.dp)
    PaperTooltip(title) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight)
                .background(if (active) colors.selected else colors.raisedSurface, shape)
                .paperClickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Свернуть задачу" else "Раскрыть задачу",
                    shape = shape,
                    onClick = {
                        // Arrow toggles expansion; title click opens the zygote if onClick provided
                        if (onClick != null) onClick() else onToggle()
                    },
                )
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown &&
                                expanded != (event.key == Key.DirectionRight)
                            ) onToggle()
                            true
                        }
                        else -> false
                    }
                }
                .semantics {
                    contentDescription = "Задача: $title"
                    stateDescription = if (expanded) "Развёрнута" else "Свёрнута"
                    selected = active
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperText(
                if (expanded) "▾" else "▸",
                modifier = Modifier.widthIn(min = 16.dp)
                    .paperClickable(onClick = onToggle)
                    .clearAndSetSemantics {},
                role = PaperTextRole.CHROME,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
            leading?.invoke()
            PaperText(
                title,
                modifier = Modifier.weight(1f),
                role = PaperTextRole.CHROME,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            trailing?.invoke()
        }
    }
}
