package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionContextMessage(id: String, text: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Text("Системное сообщение", style = MaterialTheme.typography.labelLarge)
            Text("Контекст сессии: системный промпт, навыки и настройки", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Показать контекст") }
            if (expanded) SelectionContainer {
                // Instruction text is data: do not interpret Markdown images or links.
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
