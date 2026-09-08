package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.ComputerAccess
import io.aequicor.magicpaper.domain.ComputerUseState

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
    Surface(color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (enabled) {
                    if (state.access == ComputerAccess.CONTROL) "Экран, мышь и клавиатура включены" else "Просмотр экрана включён"
                } else if (other) "Экран занят другой сессией" else "Доступ к экрану выключен",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (enabled) {
                    TextButton(onClick = onPreview, enabled = !state.busy) { Text("Снимок") }
                    TextButton(onClick = onDisable) { Text("Отключить") }
                } else if (owns && state.busy) {
                    TextButton(onClick = onDisable) { Text("Отмена") }
                } else {
                    TextButton(onClick = { chooser = true }, enabled = !running && !other) { Text("Включить…") }
                }
            }
            if (!other && state.detail.isNotBlank()) {
                Text(state.detail, style = MaterialTheme.typography.bodySmall,
                    color = if (state.access == ComputerAccess.OFF && !state.busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.access == ComputerAccess.OFF && !state.busy) TextButton(onClick = onSettings) { Text("Системные настройки") }
            }
            if (owns) state.preview?.let { attachment ->
                val bitmap = rememberAttachmentBitmap(attachment)
                if (bitmap != null) {
                    Image(bitmap, "Последний снимок, доступный агенту", contentScale = ContentScale.Fit,
                        modifier = Modifier.height(72.dp).fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .clickable { expanded = true })
                    if (expanded) AlertDialog(
                        onDismissRequest = { expanded = false },
                        title = { Text("Последний снимок экрана") },
                        text = { Image(bitmap, "Последний снимок экрана", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
                        confirmButton = { TextButton(onClick = { expanded = false }) { Text("Закрыть") } },
                    )
                }
            }
        }
    }
    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text("Доступ к компьютеру") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Агент получит снимки экрана, включая видимые окна других приложений. Снимки передаются выбранной модели. Доступ действует только для этой сессии до завершения запроса или отключения.")
                Text("Выберите модель с поддержкой изображений и инструментов.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { chooser = false; onEnable(ComputerAccess.SCREEN) }) { Text("Только просмотр экрана") }
                TextButton(onClick = { chooser = false; onEnable(ComputerAccess.CONTROL) }) { Text("Экран, мышь и клавиатура") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { chooser = false }) { Text("Отмена") } },
    )
}
