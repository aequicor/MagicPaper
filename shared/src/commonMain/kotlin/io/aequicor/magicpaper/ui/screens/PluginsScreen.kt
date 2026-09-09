package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*

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
        PaperText("Плагины", style = LocalPaperTypography.current.headline)
        PaperText(
            "Интерфейс расширяется включёнными плагинами.",
            style = LocalPaperTypography.current.body,
            color = LocalPaperColors.current.secondaryText,
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
            .clip(PaperShapes.panel)
            .background(LocalPaperColors.current.surface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperText(plugin.icon, style = LocalPaperTypography.current.title)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            PaperText(plugin.title, style = LocalPaperTypography.current.body)
            PaperText(
                plugin.description,
                style = LocalPaperTypography.current.body,
                color = LocalPaperColors.current.secondaryText,
            )
        }
        PaperToggle(checked = enabled, onCheckedChange = { onToggle() })
    }
}
