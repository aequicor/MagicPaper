package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*

@Composable
internal fun SessionContextMessage(id: String, text: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    PaperPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), kind = PaperSurfaceKind.RAISED) {
        Column(Modifier.padding(12.dp)) {
            PaperText("Системное сообщение", role = PaperTextRole.LABEL)
            PaperText("Контекст сессии: системный промпт, навыки и настройки")
            PaperAction(onClick = { expanded = !expanded }) { PaperText(if (expanded) "Свернуть" else "Показать контекст") }
            if (expanded) SelectionContainer {
                // Instruction text is data: do not interpret Markdown images or links.
                PaperText(text)
            }
        }
    }
}
