package io.aequicor.magicpaper.ui

import androidx.compose.runtime.*
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.ui.screens.PluginsScreen

class DefaultPluginsComponent(
    private val service: PluginService,
    private val input: PluginsInput,
    private val onOutput: (PluginsOutput) -> Unit,
) : PluginsComponent {
    override val state get() = service.state
    override fun togglePlugin(id: String, enabled: Boolean) = service.togglePlugin(id, enabled)
    @Composable override fun Content() {
        val current by state.collectAsState()
        PluginsScreen(current, input.pluginId, ::togglePlugin,
            onPlugin = { onOutput(PluginsOutput.OpenPlugin(it)) },
            onOverview = { onOutput(PluginsOutput.Overview) },
            onCoding = { onOutput(PluginsOutput.Coding) })
    }
}
class DefaultPluginsComponentFactory(private val service: PluginService) : PluginsComponent.Factory {
    override fun create(context: ComponentContext, input: PluginsInput, onOutput: (PluginsOutput) -> Unit): PluginsComponent =
        DefaultPluginsComponent(service, input, onOutput)
}
