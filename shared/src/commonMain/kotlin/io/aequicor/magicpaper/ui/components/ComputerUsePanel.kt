package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.ComputerAccess
import io.aequicor.magicpaper.domain.ComputerUseState
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperAction
import io.aequicor.magicpaper.designsystem.PaperImage
import io.aequicor.magicpaper.designsystem.PaperModal
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole

@Composable
fun ComputerUsePanel(
    state: ComputerUseState,
    sessionId: String,
    running: Boolean,
    onEnable: (ComputerAccess) -> Unit,
    onDisable: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
) {
    var chooser by remember(sessionId) { mutableStateOf(false) }
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val owns = state.sessionId == sessionId
    val enabled = owns && state.access != ComputerAccess.OFF
    val other = state.sessionId != null && !owns
    PaperPanel(kind = if (enabled) PaperSurfaceKind.SELECTED else PaperSurfaceKind.RAISED) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText(if (enabled) {
                    if (state.access == ComputerAccess.CONTROL) "Экран, мышь и клавиатура включены" else "Просмотр экрана включён"
                } else if (other) "Экран занят другой сессией" else "Доступ к экрану выключен",
                    modifier = Modifier.weight(1f), role = PaperTextRole.LABEL)
                if (enabled) {
                    PaperAction(onPreview, enabled = !state.busy) { PaperText("Снимок", role = PaperTextRole.LABEL) }
                    PaperAction(onDisable) { PaperText("Отключить", role = PaperTextRole.LABEL) }
                } else if (owns && state.busy) {
                    PaperAction(onDisable) { PaperText("Отмена", role = PaperTextRole.LABEL) }
                } else {
                    PaperAction({ chooser = true }, enabled = !running && !other) { PaperText("Включить…", role = PaperTextRole.LABEL) }
                }
            }
            if (!other && state.detail.isNotBlank()) {
                PaperText(state.detail, role = PaperTextRole.LABEL,
                    color = if (state.access == ComputerAccess.OFF && !state.busy) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
                if (state.access == ComputerAccess.OFF && !state.busy) PaperAction(onSettings) { PaperText("Системные настройки", role = PaperTextRole.LABEL) }
            }
            if (owns) state.preview?.let { attachment ->
                val bitmap = rememberAttachmentBitmap(attachment)
                if (bitmap != null) {
                    PaperImage(bitmap, "Последний снимок, доступный агенту",
                        modifier = Modifier.height(72.dp).fillMaxWidth()
                            .clickable { expanded = true })
                    if (expanded) PaperModal(onDismissRequest = { expanded = false },
                        title = { PaperText("Последний снимок экрана", role = PaperTextRole.TITLE) },
                        text = { PaperImage(bitmap, "Последний снимок экрана", Modifier.fillMaxWidth()) },
                        confirmButton = { PaperAction({ expanded = false }) { PaperText("Закрыть", role = PaperTextRole.LABEL) } })
                }
            }
        }
    }
    if (chooser) PaperModal(
        onDismissRequest = { chooser = false },
        title = { PaperText("Доступ к компьютеру", role = PaperTextRole.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText("Агент получит снимки экрана, включая видимые окна других приложений. Снимки передаются выбранной модели. Доступ действует только для этой сессии до завершения запроса или отключения.")
                PaperText("Выберите модель с поддержкой изображений и инструментов.", role = PaperTextRole.LABEL)
                PaperAction({ chooser = false; onEnable(ComputerAccess.SCREEN) }) { PaperText("Только просмотр экрана", role = PaperTextRole.LABEL) }
                PaperAction({ chooser = false; onEnable(ComputerAccess.CONTROL) }) { PaperText("Экран, мышь и клавиатура", role = PaperTextRole.LABEL) }
            }
        },
        confirmButton = { PaperAction({ chooser = false }) { PaperText("Отмена", role = PaperTextRole.LABEL) } },
    )
}
