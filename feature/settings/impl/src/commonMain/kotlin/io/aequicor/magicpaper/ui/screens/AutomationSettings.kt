package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.ComputerAccess

@Composable
internal fun AutomationSettings(settings: AppSettings, computerEnabled: Boolean = true, applicationEnabled: Boolean = true, saving: Boolean = false, onChange: (AppSettings) -> Unit) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperText("Компьютер и приложения", role = PaperTextRole.TITLE)
            PaperText("Снимки и содержимое окон передаются выбранной модели. Доступ действует в чатах и задачах до завершения запроса или остановки.", role = PaperTextRole.LABEL)
            AccessChoice("Весь компьютер", settings.computerAccess, computerEnabled && !saving) { onChange(settings.copy(computerAccess = it)) }
            PaperText("Управление использует вашу мышь, клавиатуру и буфер обмена. В это время не работайте на компьютере.", role = PaperTextRole.LABEL)
            PaperDivider()
            AccessChoice("Приложение в фоне", settings.applicationAccess, applicationEnabled && !saving) { onChange(settings.copy(applicationAccess = it)) }
            PaperText("macOS 14+ и Windows. Без захвата мыши и клавиатуры; доступны только действия, поддерживаемые программой. Программа может сама открыть диалог или активировать окно.", role = PaperTextRole.LABEL)
            PaperText("Изменение доступа отключает текущий запрос от компьютера. Новое разрешение применяется при следующей отправке; восстановление после сбоя доступа не выдаёт.", role = PaperTextRole.LABEL)
            if (!computerEnabled || !applicationEnabled) PaperText("Недоступные режимы отключены для этой платформы", role = PaperTextRole.LABEL)
            if (saving) PaperText("Сохранение…", role = PaperTextRole.LABEL)
        }
    }
}

@Composable
private fun AccessChoice(title: String, selected: ComputerAccess, enabled: Boolean, onChange: (ComputerAccess) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperText(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ComputerAccess.entries.forEach { access ->
                val label = when (access) {
                    ComputerAccess.OFF -> "Выключено"
                    ComputerAccess.SCREEN -> "Просмотр"
                    ComputerAccess.CONTROL -> "Управление"
                }
                PaperChoice(selected == access, { onChange(access) }, label,
                    modifier = Modifier.semantics { contentDescription = "$title: $label" }, enabled = enabled)
            }
        }
    }
}
