package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.designsystem.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.PluginsState

@Composable
fun PluginsScreen(
    state: PluginsState,
    pluginId: String?,
    onToggle: (String, Boolean) -> Unit,
    onPlugin: (String) -> Unit,
    onOverview: () -> Unit,
    onCoding: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PaperText("Плагины", style = LocalPaperTypography.current.headline)
        state.error?.let { PaperText(it, color = LocalPaperColors.current.error) }
        Spacer(Modifier.height(12.dp))
        if (!state.loaded) { PaperText("Загрузка…"); return@Column }
        if (pluginId != null) {
            PaperButton("Все плагины", onOverview, kind = PaperButtonKind.QUIET)
            Spacer(Modifier.height(12.dp))
            val plugin = state.plugins.firstOrNull { it.id == pluginId }
            if (plugin == null) PaperText("Плагин недоступен")
            else {
                val enabled = state.pluginStates[plugin.id]?.enabled ?: true
                PluginRow(plugin, enabled, { onToggle(plugin.id, !enabled) })
                if (enabled) PluginContent(plugin, onCoding)
                else {
                    PaperText("Плагин выключен")
                    PaperButton("Включить", { onToggle(plugin.id, true) })
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(state.plugins, key = { it.id }) { plugin ->
                    val enabled = state.pluginStates[plugin.id]?.enabled ?: true
                    PluginRow(plugin, enabled, { onToggle(plugin.id, !enabled) }, { onPlugin(plugin.id) })
                }
            }
            Spacer(Modifier.height(12.dp))
            PaperText("Активные панели", role = PaperTextRole.TITLE)
            val active = state.plugins.filter { state.pluginStates[it.id]?.enabled != false }
            if (active.isEmpty()) PaperText("Все плагины выключены. Включите нужный выше.")
            active.forEach { plugin ->
                PaperPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp).padding(12.dp)) {
                    PluginContent(plugin, onCoding)
                }
            }
        }
    }
}

@Composable
private fun PluginContent(plugin: MagicPlugin, onCoding: () -> Unit) {
    if (plugin.id == "coding-planning") PaperButton("Открыть проекты", onCoding, kind = PaperButtonKind.QUIET)
    else plugin.Content()
}

@Composable
internal fun PluginRow(plugin: MagicPlugin, enabled: Boolean, onToggle: () -> Unit, onOpen: (() -> Unit)? = null) {
    PaperPluginRow(plugin.title, plugin.description, plugin.icon, enabled, onToggle, onOpen)
}
