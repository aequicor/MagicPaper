package io.aequicor.magicpaper.plugins.builtin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin

/** Плагин «Заметки»: простые заклинания памяти, живущие в состоянии. */
object NotesPlugin : MagicPlugin {
    override val id = "notes"
    override val title = "Заметки"
    override val description = "Короткие заклинания памяти рядом с чатом."
    override val icon = "✎"

    @Composable
    override fun Content() {
        var notes by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(icon + " " + title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Записать мысль…") },
                minLines = 3,
                maxLines = 8,
            )
            Text(
                "Заметки хранятся локально и входят в экспорт профиля.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
