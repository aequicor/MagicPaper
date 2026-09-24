package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Recovery states", group = "Recovery action", widthDp = 360, heightDp = 160)
@Preview(name = "Recovery narrow", group = "Recovery action", widthDp = 240, heightDp = 160)
@Composable
internal fun PaperRecoveryActionPreview() {
    PaperTheme {
        PaperSurface {
            Column {
                PaperRecoveryAction("Войти в Claude Code", "Подтвердите вход в браузере", pending = false, onAction = {}, onCancel = {})
                PaperRecoveryAction("Войти в Claude Code", "Подтвердите вход в браузере", pending = true, onAction = {}, onCancel = {})
            }
        }
    }
}
