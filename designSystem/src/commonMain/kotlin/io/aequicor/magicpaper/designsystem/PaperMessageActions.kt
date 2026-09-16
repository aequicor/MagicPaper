package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

/** A stable trailing action lane; secondary click never intercepts text selection's primary drag. */
@Composable
public fun PaperMessageActions(
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    historyEnabled: Boolean = true,
    forkEnabled: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val platform = LocalPaperPlatformPolicy.current.platform
    var touch by remember { mutableStateOf(platform == PaperPlatform.ANDROID) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(interaction).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.type == PointerType.Touch }) touch = true
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
                    event.changes.none { it.isConsumed }) {
                    open = true
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(end = LocalPaperPlatformPolicy.current.density.controlHeight + 4.dp)) { content() }
        Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.TopEnd).wrapContentSize(unbounded = true)) {
                // Keep the opener in focus traversal even while hidden from the pointer.
                PaperIconButton(label = "Действия с сообщением", onClick = { open = true },
                    modifier = Modifier.onFocusChanged { focused = it.hasFocus }
                        .graphicsLayer { alpha = if (hovered || focused || open || touch) 1f else 0f }) {
                    PaperText("⋯", role = PaperTextRole.LABEL)
                }
                PaperMenu(open, { open = false }, buildList {
                    add(PaperMenuItem("Копировать целиком", onClick = onCopy))
                    onEdit?.let { add(PaperMenuItem("Редактировать", historyEnabled, onClick = it)) }
                    onFork?.let { add(PaperMenuItem("Форк до этого сообщения", forkEnabled, onClick = it)) }
                    onDelete?.let { add(PaperMenuItem("Удалить из истории и контекста", historyEnabled, destructive = true, onClick = it)) }
                })
            }
        }
    }
}

@Preview(name = "User", group = "Message actions", widthDp = 390, heightDp = 130)
@Composable
internal fun PaperMessageActionsPreview() = PaperTheme {
    Column(Modifier.padding(12.dp)) {
        PaperMessageActions({}, {}, {}, {}) { PaperText("Сообщение пользователя") }
    }
}

@Preview(name = "Agent and busy", group = "Message actions", widthDp = 390, heightDp = 180)
@Composable
internal fun PaperAgentMessageActionsPreview() = PaperTheme {
    Column(Modifier.padding(12.dp)) {
        PaperMessageActions({}, onFork = {}, onDelete = {}) { PaperText("Ответ агента") }
        PaperMessageActions({}, {}, {}, {}, historyEnabled = false) { PaperText("Агент работает…") }
    }
}
