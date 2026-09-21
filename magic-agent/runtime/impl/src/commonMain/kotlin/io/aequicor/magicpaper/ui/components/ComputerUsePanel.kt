package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.paperClickable
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
fun renderComputerUsePanel(
    state: ComputerUseState,
    sessionId: String,
    running: Boolean,
    onEnable: (ComputerAccess) -> Unit,
    onDisable: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    val owns = state.sessionId == sessionId
    val enabled = owns && (state.access != ComputerAccess.OFF || state.applicationAccess != ComputerAccess.OFF)
    val other = state.sessionId != null && !owns
    PaperPanel(color = if (enabled) LocalPaperColors.current.successSurface else androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperText(if (enabled) {
                    when {
                        state.access != ComputerAccess.OFF && state.applicationAccess != ComputerAccess.OFF -> "Компьютер и приложение включены"
                        state.access == ComputerAccess.CONTROL -> "Экран, мышь и клавиатура включены"
                        state.access == ComputerAccess.SCREEN -> "Просмотр экрана включён"
                        state.applicationAccess == ComputerAccess.CONTROL -> "Управление приложением в фоне"
                        else -> "Просмотр приложения включён"
                    }
                } else if (other) "Компьютер занят другой сессией" else "Настройки → Управление компьютером",
                    modifier = Modifier.weight(1f), style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome)
                if (enabled) {
                    if (state.access != ComputerAccess.OFF) PaperAction(onPreview, enabled = !state.busy) { PaperText("Снимок", style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome) }
                    PaperAction(onDisable) { PaperText("Отключить", style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome) }
                } else if (owns && state.busy) {
                    PaperAction(onDisable) { PaperText("Отмена", style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome) }
                }
            }
            if (!other && state.detail.isNotBlank()) {
                PaperText(state.detail, style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome,
                    color = if (state.error) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
                if (state.error && !state.busy) PaperAction(onSettings) { PaperText("Системные настройки", style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome) }
            }
            if (owns) state.preview?.let { attachment ->
                val bitmap = rememberAttachmentBitmap(attachment)
                if (bitmap != null) {
                    PaperImage(bitmap, "Последний снимок, доступный агенту",
                        modifier = Modifier.height(72.dp).fillMaxWidth()
                            .paperClickable { expanded = true })
                    if (expanded) PaperModal(onDismissRequest = { expanded = false },
                        title = { PaperText("Последний снимок экрана", role = PaperTextRole.TITLE) },
                        text = { PaperImage(bitmap, "Последний снимок экрана", Modifier.fillMaxWidth()) },
                        confirmButton = { PaperAction({ expanded = false }) { PaperText("Закрыть", style = io.aequicor.magicpaper.designsystem.LocalPaperTypography.current.chrome) } })
                }
            }
        }
    }
}
