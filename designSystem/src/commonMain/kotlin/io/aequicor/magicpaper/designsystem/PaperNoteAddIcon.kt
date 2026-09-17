package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.resources.Res
import io.aequicor.magicpaper.designsystem.resources.note_add
import org.jetbrains.compose.resources.painterResource

/** Paper outline adaptation of note_add: 16 dp / 1.2 dp stroke, matching the toolbar. */
@Composable
public fun PaperNoteAddIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current.let {
    // Quiet actions share toolbar ink; primary and disabled buttons keep their content contrast.
    if (it == LocalPaperColors.current.text) LocalPaperColors.current.secondaryText else it
}) {
    Icon(painterResource(Res.drawable.note_add), contentDescription = null,
        modifier = modifier.size(16.dp), tint = tint)
}

@Preview(name = "Create and add actions", group = "Paper icons", widthDp = 300, heightDp = 230)
@Preview(name = "Create actions large text", group = "Paper icons", widthDp = 340, heightDp = 300, fontScale = 2f)
@Composable
internal fun PaperNoteAddPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Новый чат", {}, Modifier.fillMaxWidth(), kind = PaperButtonKind.QUIET,
                leadingIcon = { PaperNoteAddIcon(Modifier.testTag("chat-create-icon")) })
            PaperButton("Новая сессия", {}, Modifier.fillMaxWidth(), leadingIcon = { PaperNoteAddIcon() })
            PaperButton("Новый вопрос", {}, Modifier.fillMaxWidth(), kind = PaperButtonKind.SECONDARY,
                leadingIcon = { PaperNoteAddIcon() })
            PaperButton("Новая сессия", {}, Modifier.fillMaxWidth(), enabled = false,
                leadingIcon = { PaperNoteAddIcon() })
            PaperIconButton("Добавить ресурс", {}) { PaperNoteAddIcon() }
        }
    }
}

@Preview(name = "Toolbar and new outlines", group = "Paper icons", widthDp = 320, heightDp = 170)
@Preview(name = "Outlines large text", group = "Paper icons", widthDp = 320, heightDp = 220, fontScale = 2f)
@Composable
internal fun PaperOutlineIconsPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperText("Панель сессий", role = PaperTextRole.LABEL)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperToolbarButton(PaperToolbarIcon.Filter, "Фильтр", 32.dp, onClick = {})
                PaperToolbarButton(PaperToolbarIcon.Search, "Поиск", 32.dp, onClick = {})
                PaperToolbarButton(PaperToolbarIcon.Archive, "Архив", 32.dp, onClick = {})
            }
            PaperText("Иконки действий", role = PaperTextRole.LABEL)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperIconButton("Список сессий", {}, Modifier.size(32.dp)) { PaperSidebarIcon() }
                PaperIconButton("Список вопросов", {}, Modifier.size(32.dp)) { PaperPanelIcon(PaperPanelSide.LEFT) }
                PaperIconButton("Источники", {}, Modifier.size(32.dp)) { PaperPanelIcon(PaperPanelSide.RIGHT) }
                PaperIconButton("Добавить ресурс", {}, Modifier.size(32.dp)) { PaperNoteAddIcon() }
                PaperComposerOptionsToggle(false, {}, Modifier.size(32.dp))
                PaperComposerOptionsToggle(true, {}, Modifier.size(32.dp))
            }
        }
    }
}
