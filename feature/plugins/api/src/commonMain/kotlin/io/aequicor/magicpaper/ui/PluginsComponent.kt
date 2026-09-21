package io.aequicor.magicpaper.ui

import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import kotlinx.coroutines.flow.StateFlow

data class PluginsState(
    val plugins: List<MagicPlugin> = emptyList(),
    val pluginStates: Map<String, PluginState> = emptyMap(),
    val loaded: Boolean = false,
    val error: String? = null,
)
/** Application lifetime service; navigation never cancels an in-flight preference write. */
interface PluginService : io.aequicor.magicpaper.plugins.PluginPreferences {
    val state: StateFlow<PluginsState>
    suspend fun start()
    fun togglePlugin(id: String, enabled: Boolean)
    suspend fun prepareForReset()
    suspend fun removeProjectDrafts(projectId: String, planIds: Set<String>?)
    suspend fun close()
}
data class PluginsInput(val pluginId: String? = null)
sealed interface PluginsOutput {
    data class OpenPlugin(val id: String) : PluginsOutput
    data object Overview : PluginsOutput
    data object Coding : PluginsOutput
}
interface PluginsComponent {
    val state: StateFlow<PluginsState>
    fun togglePlugin(id: String, enabled: Boolean)
    @Composable fun Content()
    fun interface Factory {
        fun create(context: ComponentContext, input: PluginsInput, onOutput: (PluginsOutput) -> Unit): PluginsComponent
    }
}
