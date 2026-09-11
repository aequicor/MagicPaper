package io.aequicor.magicpaper.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
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
 * [hoverActions] renders additional controls (archive, menu) on hover.
 * [childCount] controls whether the disclosure arrow is shown.
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
    trailing: (@Composable (Boolean) -> Unit)? = null,
    hoverActions: (@Composable (Boolean) -> Unit)? = null,
    keepActionsVisible: Boolean = false,
    childCount: Int = 0,
) {
    val colors = LocalPaperColors.current
    val shape = RoundedCornerShape(6.dp)
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    
    PaperTooltip(title) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = LocalPaperPlatformPolicy.current.density.rowHeight)
                .clip(shape)
                .background(
                    if (active) colors.selected.copy(alpha = 0.55f) else Color.Transparent
                )
                .hoverable(hoverInteraction)
                .paperClickable(
                    role = Role.Button,
                    onClickLabel = if (active && childCount > 0) {
                        if (expanded) "Свернуть задачу" else "Раскрыть задачу"
                    } else null,
                    shape = shape,
                    onClick = {
                        // Click on active session toggles expansion; otherwise opens zygote
                        if (active && childCount > 0) onToggle() else onClick?.invoke()
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
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Spacer(Modifier.width(8.dp))
            PaperText(
                title,
                modifier = Modifier.weight(1f),
                role = PaperTextRole.CHROME,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
            if (trailing != null) {
                val spaceBeforeTrailing by animateDpAsState(
                    if (isHovered) 4.dp else 8.dp
                )
                Spacer(Modifier.width(spaceBeforeTrailing))
                trailing.invoke(isHovered)
                if (isHovered) {
                    Spacer(Modifier.width(8.dp))
                }
            }
            HoverActions(visible = isHovered || keepActionsVisible) {
                hoverActions?.invoke(isHovered || keepActionsVisible)
                if (childCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = if (expanded) "Свернуть задачу" else "Раскрыть задачу" }
                        .paperClickable(onClick = onToggle),
                        contentAlignment = Alignment.Center) {
                        PaperText(if (expanded) "▾" else "▸", color = colors.action)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Hides its content from layout and accessibility when [visible] is false,
 * while preserving the measured size for a smooth slide-in on reveal.
 */
@Composable
private fun HoverActions(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 100))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
