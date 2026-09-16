package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

/** Actions belong to the whole message, including collapsed text and activity. */
@Composable
public fun PaperMessageActions(
    onCopy: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    historyEnabled: Boolean = true,
    forkEnabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperButton("Копировать целиком", onCopy, modifier = Modifier.weight(1f, fill = false), kind = PaperButtonKind.SECONDARY)
        if (onEdit != null || onFork != null || onDelete != null) Box {
            PaperIconButton(label = "Действия с сообщением", onClick = { open = true }) {
                PaperText("⋯", role = PaperTextRole.LABEL)
            }
            PaperMenu(open, { open = false }, buildList {
                onEdit?.let { add(PaperMenuItem("Редактировать", historyEnabled, onClick = it)) }
                onFork?.let { add(PaperMenuItem("Форк до этого сообщения", forkEnabled, onClick = it)) }
                onDelete?.let { add(PaperMenuItem("Удалить из истории и контекста", historyEnabled, destructive = true, onClick = it)) }
            })
        }
    }
}

@Preview(name = "User", group = "Message actions", widthDp = 390, heightDp = 130)
@Composable
internal fun PaperMessageActionsPreview() = PaperTheme {
    Column(Modifier.padding(12.dp)) {
        PaperText("Сообщение пользователя")
        PaperMessageActions({}, {}, {}, {})
    }
}

@Preview(name = "Agent and busy", group = "Message actions", widthDp = 390, heightDp = 180)
@Composable
internal fun PaperAgentMessageActionsPreview() = PaperTheme {
    Column(Modifier.padding(12.dp)) {
        PaperMessageActions({}, onFork = {}, onDelete = {})
        PaperMessageActions({}, {}, {}, {}, historyEnabled = false)
    }
}
