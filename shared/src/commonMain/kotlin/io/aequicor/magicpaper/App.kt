package io.aequicor.magicpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.di.MagicPaperDependencies
import io.aequicor.magicpaper.di.createMagicPaperDependencies
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.Screen
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.components.ModelSwitcherDialog
import io.aequicor.magicpaper.ui.components.MagicPaperBackground
import io.aequicor.magicpaper.ui.screens.ChatScreen
import io.aequicor.magicpaper.ui.screens.CodingScreen
import io.aequicor.magicpaper.ui.screens.DocsScreen
import io.aequicor.magicpaper.ui.screens.PluginsScreen
import io.aequicor.magicpaper.ui.screens.SessionsPanel
import io.aequicor.magicpaper.ui.screens.SettingsScreen
import io.aequicor.magicpaper.ui.screens.WelcomeScreen
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import io.aequicor.magicpaper.ui.window.LocalWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import io.aequicor.magicpaper.ui.window.WindowDragArea
import kotlinx.coroutines.delay

/** Корневой композиционный узел: тема + каркас. */
@Composable
fun App(deps: MagicPaperDependencies = remember { createMagicPaperDependencies() }) {
    MagicPaperTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // Edge-to-edge на всех платформах: контент не залезает под статусбар,
                // вырез, навбар и клавиатуру (внутри — только зона приложения).
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
        ) {
            val state by deps.viewModel.state.collectAsState()
            // Нативный прозрачный тайтлбар (macOS): фон дотянут до самого верха
            // окна, а контент вытолкнут из-под высоты тайтлбара. Отступ слева
            // под «светофор» применяется только к верхним панелям (см. их код).
            val titleBarTop = LocalWindowTitleBarInsets.current.calculateTopPadding()
            Column(modifier = Modifier.fillMaxSize().padding(top = titleBarTop)) {
                if (state.showWelcome) {
                    WelcomeScreen(deps.viewModel, state)
                } else {
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
                    if (state.modelSwitcherOpen && state.screen == Screen.CHAT) {
                        ModelSwitcherDialog(
                            vm = deps.viewModel,
                            profiles = state.availableLlmProfiles,
                            activeProfileId = state.settings.activeLlmProfileId,
                            sessionProfileId = state.current?.llmProfileId,
                            onDismiss = { deps.viewModel.toggleModelSwitcher(false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainArea(vm: MagicPaperViewModel, state: UiState) {
    Box(Modifier.fillMaxSize()) {
        if (state.screen == Screen.CHAT || state.screen == Screen.CODING) {
            MagicPaperBackground(state.settings.paperAnimationEnabled, Modifier.matchParentSize())
        }
        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = state.sessionsPanelOpen && state.screen == Screen.CHAT) {
                Row {
                    SessionsPanel(vm, state.sessions, state.current?.id)
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (state.screen) {
                    Screen.CHAT -> ChatScreen(vm, state)
                    Screen.CODING -> CodingScreen(
                        vm,
                        state.coding,
                        state.codingPanelPlugin,
                        profiles = state.availableLlmProfiles,
                        activeProfileId = state.settings.activeLlmProfileId,
                    )
                    Screen.PLUGINS -> PluginsScreen(vm, state.plugins, state.pluginStates) {
                        ActivePlugins(state.plugins, state.pluginStates)
                    }
                    Screen.DOCS -> DocsScreen(vm, state.docsArticles, state.docsQuery)
                    Screen.SETTINGS -> SettingsScreen(vm, state)
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: MagicPaperViewModel, screen: Screen) {
    // Разгруженный бар: разделы живут в настройках, здесь только панели и шестерёнка.
    // На десктопе (окно без системных декораций) средняя часть бара — зона
    // перетаскивания окна, а справа появляются кнопки свернуть/развернуть/закрыть.
    // Интерактивные кнопки живут ВНЕ зоны перетаскивания, чтобы клик не
    // пересекался с жестом переноса окна.
    val chrome = LocalWindowChrome.current
    // На macOS: нативный «светофор» поверх контента — сдвигаем кнопки от него.
    val layoutDirection = LocalLayoutDirection.current
    val trafficLights = LocalWindowTitleBarInsets.current.calculateLeftPadding(layoutDirection)
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = trafficLights.coerceAtLeast(12.dp),
            end = if (chrome != null) 6.dp else 12.dp,
            top = 4.dp,
            bottom = 4.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { vm.toggleSessionsPanel() },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        ) { Text("☰", style = MaterialTheme.typography.titleMedium) }
        WindowDragArea(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("MagicPaper", style = MaterialTheme.typography.titleMedium)
                Text(
                    screen.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = { vm.open(if (screen == Screen.SETTINGS) Screen.CHAT else Screen.SETTINGS) },
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        ) {
            Text(
                "⚙",
                style = MaterialTheme.typography.titleMedium,
                color = if (screen == Screen.SETTINGS) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (chrome != null) WindowButtons(chrome)
    }
}

/** Кнопки управления окном на десктопе: свернуть, развернуть, закрыть. */
@Composable
private fun WindowButtons(chrome: io.aequicor.magicpaper.ui.window.WindowChrome) {
    WindowButton("─") { chrome.minimize() }
    WindowButton("▢") { chrome.toggleMaximize() }
    WindowButton("✕", danger = true) { chrome.close() }
}

@Composable
private fun WindowButton(glyph: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Подзаголовок в шапке: где мы находимся. */
private val Screen.subtitle: String
    get() = when (this) {
        Screen.CHAT -> "Шалость удалась"
        Screen.CODING -> "Проекты и код"
        Screen.PLUGINS -> "Плагины и панели"
        Screen.DOCS -> "Справочник"
        Screen.SETTINGS -> "Настройки и разделы"
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
