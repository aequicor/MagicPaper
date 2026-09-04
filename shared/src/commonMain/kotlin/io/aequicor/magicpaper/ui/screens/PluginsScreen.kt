package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Экран плагинов: список фич с переключателями. */
@Composable
fun PluginsScreen(
    vm: MagicPaperViewModel,
    plugins: List<MagicPlugin>,
    states: Map<String, PluginState>,
    activeContent: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Плагины", style = MaterialTheme.typography.titleLarge)
        Text(
            "Интерфейс расширяется включёнными плагинами.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(plugins, key = { it.id }) { plugin ->
                val enabled = states[plugin.id]?.enabled ?: true
                PluginRow(plugin, enabled) { vm.togglePlugin(plugin.id, !enabled) }
            }
        }
        Spacer(Modifier.height(12.dp))
        activeContent()
    }
}

@Composable
internal fun PluginRow(plugin: MagicPlugin, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(plugin.icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(plugin.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = { onToggle() })
    }
}
