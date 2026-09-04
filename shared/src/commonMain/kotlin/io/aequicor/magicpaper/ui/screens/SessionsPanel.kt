package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Боковая панель со списком свитков (сессий). */
@Composable
fun SessionsPanel(vm: MagicPaperViewModel, sessions: List<ChatSession>, currentId: String?) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight(),
    ) {
        Text(
            "Свитки",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = session.id == currentId,
                    onClick = { vm.selectSession(session.id) },
                    onDelete = { vm.deleteSession(session.id) },
                )
            }
        }
        TextButton(onClick = { vm.newSession() }, modifier = Modifier.padding(8.dp)) {
            Text("✦ Новый свиток")
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                session.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        TextButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
