package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Worktree states", group = "Menu setting", widthDp = 360, heightDp = 220)
@Preview(name = "Worktree narrow", group = "Menu setting", widthDp = 260, heightDp = 220)
@Composable
internal fun PaperMenuToggleInfoPreview() {
    PaperTheme {
        PaperSurface {
            Column {
                PaperMenuToggleInfo("Worktree", true, true, "Работа в отдельной Git-копии", {})
                PaperMenuToggleInfo("Worktree", false, true, "Работа в отдельной Git-копии", {})
                PaperMenuToggleInfo("Worktree", true, false, "Режим можно изменить после завершения текущей задачи", {})
                PaperMenuToggleInfo("Worktree", false, false, "В папке нет Git-репозитория", {})
            }
        }
    }
}
