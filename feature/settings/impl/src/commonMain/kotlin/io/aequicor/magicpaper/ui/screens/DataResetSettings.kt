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

@Composable
internal fun ResetSessionsAction(onConfirm: () -> Unit) =
    DataResetAction("Сбросить сессии", "Сбросить сессии?", SESSIONS_RESET_CONSEQUENCES, onConfirm)

@Composable
internal fun WipeDataAction(onConfirm: () -> Unit) =
    DataResetAction("Стереть данные", "Стереть данные?", WIPE_CONSEQUENCES, onConfirm)

/** Erasing cannot be undone, so the action asks once and names exactly what goes and what stays. */
@Composable
private fun DataResetAction(label: String, title: String, consequences: List<String>, onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    PaperAction(onClick = { confirming = true }) { PaperText(label) }
    if (confirming) DataResetDialog(
        title = title,
        consequences = consequences,
        confirmLabel = label,
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

internal val SESSIONS_RESET_CONSEQUENCES = listOf(
    "Удалятся проекты, кодинг-сессии и чаты вместе с планами, черновиками и созданными изображениями.",
    "Настройки, профили поставщиков с ключами, модели, навыки и вход в подписку сохранятся.",
    "Операции, завершение которых не подтверждено, будут забыты.",
    "Вернуть удалённое нельзя.",
)

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

@Preview(name = "Reset sessions", group = "Data reset", widthDp = 900, heightDp = 600)
@Preview(name = "Reset sessions · narrow", group = "Data reset", widthDp = 390, heightDp = 800)
@Preview(name = "Reset sessions · 200% text", group = "Data reset", widthDp = 390, heightDp = 1000, fontScale = 2f)
@Composable internal fun ResetSessionsDialogPreview() {
    PaperTheme { DataResetDialog("Сбросить сессии?", SESSIONS_RESET_CONSEQUENCES, "Сбросить сессии", {}, {}) }
}
