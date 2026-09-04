package io.aequicor.magicpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.di.MagicPaperDependencies
import io.aequicor.magicpaper.di.createMagicPaperDependencies
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.Screen
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.screens.ChatScreen
import io.aequicor.magicpaper.ui.screens.DocsScreen
import io.aequicor.magicpaper.ui.screens.PluginsScreen
import io.aequicor.magicpaper.ui.screens.SessionsPanel
import io.aequicor.magicpaper.ui.screens.SettingsScreen
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import kotlinx.coroutines.delay

/** Корневой композиционный узел: тема + каркас. */
@Composable
fun App(deps: MagicPaperDependencies = remember { createMagicPaperDependencies() }) {
    MagicPaperTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val state by deps.viewModel.state.collectAsState()
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(deps.viewModel, state.screen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(modifier = Modifier.weight(1f)) {
                    MainArea(deps.viewModel, state)
                    Notice(
                        state.notice,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onDismiss = { deps.viewModel.dismissNotice() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainArea(vm: MagicPaperViewModel, state: UiState) {
    Row(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = state.sessionsPanelOpen && state.screen == Screen.CHAT) {
            Row {
                SessionsPanel(vm, state.sessions, state.current?.id)
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state.screen) {
                Screen.CHAT -> ChatScreen(vm, state.current, state.busy)
                Screen.PLUGINS -> PluginsScreen(vm, state.plugins, state.pluginStates) {
                    ActivePlugins(state.plugins, state.pluginStates)
                }
                Screen.DOCS -> DocsScreen(vm, state.docsArticles, state.docsQuery)
                Screen.SETTINGS -> SettingsScreen(vm, state.settings, state.storageInfo)
            }
        }
    }
}

@Composable
private fun TopBar(vm: MagicPaperViewModel, screen: Screen) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { vm.toggleSessionsPanel() }) { Text("☰") }
        Column(modifier = Modifier.weight(1f)) {
            Text("MagicPaper", style = MaterialTheme.typography.titleMedium)
            Text(
                "Шалость удалась",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavButton("Чат", screen == Screen.CHAT) { vm.open(Screen.CHAT) }
        NavButton("Плагины", screen == Screen.PLUGINS) { vm.open(Screen.PLUGINS) }
        NavButton("Справка", screen == Screen.DOCS) { vm.open(Screen.DOCS) }
        NavButton("Настройки", screen == Screen.SETTINGS) { vm.open(Screen.SETTINGS) }
    }
}

@Composable
private fun NavButton(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Панели включённых плагинов: интерфейс расширяется их суммой. */
@Composable
private fun ActivePlugins(plugins: List<MagicPlugin>, states: Map<String, PluginState>) {
    val enabledIds = states.filterValues { it.enabled }.keys
    Column {
        Text("Активные панели", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val active = plugins.filter { it.id in enabledIds }
        if (active.isEmpty()) {
            Text(
                "Все плагины выключены. Включите нужный выше.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        active.forEach { plugin ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                plugin.Content()
            }
        }
    }
}

@Composable
private fun Notice(notice: String?, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
    ) {
        LaunchedEffect(notice) {
            delay(4000)
            onDismiss()
        }
        Box(
            modifier = Modifier
                .padding(16.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(notice.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
