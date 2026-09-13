package io.aequicor.magicpaper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.aequicor.magicpaper.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Skills keep their established plugin IDs and render through the plugin SPI. */
class DefaultSkillsComponent(
    context: ComponentContext,
    private val service: PluginService,
    private val input: SkillsInput,
    private val onOutput: (SkillsOutput) -> Unit,
) : SkillsComponent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SkillsState(input.pluginId))
    override val state = mutableState.asStateFlow()
    init {
        context.lifecycle.doOnDestroy { scope.cancel() }
        scope.launch { service.state.collect { current ->
            val plugin = current.plugins.firstOrNull { it.id == input.pluginId }
            mutableState.value = SkillsState(input.pluginId, plugin?.title ?: "Навыки",
                available = plugin != null, enabled = plugin != null && current.pluginStates[plugin.id]?.enabled != false)
        } }
    }
    override fun enable() = service.togglePlugin(input.pluginId, true)
    @Composable override fun Content() {
        val plugins by service.state.collectAsState()
        val plugin = plugins.plugins.firstOrNull { it.id == input.pluginId }
        Column(Modifier.padding(16.dp)) {
            PaperText(plugin?.title ?: "Навыки", style = LocalPaperTypography.current.headline)
            plugins.error?.let { PaperText(it, color = LocalPaperColors.current.error) }
            when {
                !plugins.loaded -> PaperText("Загрузка…")
                plugin == null -> PaperText("Плагин недоступен")
                plugins.pluginStates[plugin.id]?.enabled == false -> {
                    PaperText("Плагин выключен")
                    PaperButton("Включить", ::enable)
                }
                else -> plugin.Content()
            }
        }
    }
}
class DefaultSkillsComponentFactory(private val service: PluginService) : SkillsComponent.Factory {
    override fun create(context: ComponentContext, input: SkillsInput, onOutput: (SkillsOutput) -> Unit): SkillsComponent =
        DefaultSkillsComponent(context, service, input, onOutput)
}
