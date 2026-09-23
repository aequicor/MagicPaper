package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*

/** Erasing cannot be undone, so the action asks once and names exactly what goes. */
@Composable
internal fun WipeDataAction(onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    PaperAction(onClick = { confirming = true }) { PaperText("Стереть данные") }
    if (confirming) DataResetDialog(
        title = "Стереть данные?",
        consequences = WIPE_CONSEQUENCES,
        confirmLabel = "Стереть данные",
        onConfirm = { confirming = false; onConfirm() },
        onDismiss = { confirming = false },
    )
}

@Composable
internal fun DataResetDialog(title: String, consequences: List<String>, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PaperDialog(title, onDismiss, modifier = Modifier.widthIn(max = 560.dp), dismissLabel = "Отмена") {
        Column(verticalArrangement = Arrangement.spacedBy(LocalPaperSpacing.current.xs)) {
            consequences.forEach { PaperText(it) }
            PaperButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), kind = PaperButtonKind.DESTRUCTIVE)
        }
    }
}

internal val WIPE_CONSEQUENCES = listOf(
    "Удалятся проекты, кодинг-сессии и чаты, все настройки, профили поставщиков с ключами и вход в подписку.",
    "Операции, завершение которых не подтверждено, будут забыты.",
    "Вернуть данные нельзя.",
)

@Preview(name = "Wipe data", group = "Data reset", widthDp = 900, heightDp = 600)
@Preview(name = "Wipe data · narrow", group = "Data reset", widthDp = 390, heightDp = 700)
@Preview(name = "Wipe data · 200% text", group = "Data reset", widthDp = 390, heightDp = 900, fontScale = 2f)
@Composable internal fun WipeDataDialogPreview() {
    PaperTheme { DataResetDialog("Стереть данные?", WIPE_CONSEQUENCES, "Стереть данные", {}, {}) }
}
