package io.aequicor.magicpaper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.di.MagicPaperDependencies
import io.aequicor.magicpaper.di.createMagicPaperDependencies
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperIconButton
import io.aequicor.magicpaper.designsystem.PaperPanel
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.domain.PluginState
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.ui.MagicPaperViewModel
import io.aequicor.magicpaper.ui.Screen
import io.aequicor.magicpaper.ui.UiState
import io.aequicor.magicpaper.ui.components.ModelSwitcherDialog
import io.aequicor.magicpaper.ui.components.LocalHideSystemSteps
import io.aequicor.magicpaper.ui.components.MagicPaperBackground
import io.aequicor.magicpaper.ui.screens.ChatScreen
import io.aequicor.magicpaper.ui.screens.CodingScreen
import io.aequicor.magicpaper.ui.screens.DocsScreen
import io.aequicor.magicpaper.ui.screens.PluginsScreen
import io.aequicor.magicpaper.ui.screens.SessionsPanel
import io.aequicor.magicpaper.ui.screens.SettingsScreen
import io.aequicor.magicpaper.ui.screens.WelcomeScreen
import io.aequicor.magicpaper.ui.window.LocalWindowChrome
import io.aequicor.magicpaper.ui.window.LocalWindowTitleBarInsets
import io.aequicor.magicpaper.ui.window.LocalWindowToolbarHeight
import io.aequicor.magicpaper.ui.window.WindowDragArea
import io.aequicor.magicpaper.ui.window.WindowTitleBarArea
import kotlinx.coroutines.delay

/** Корневой композиционный узел: тема + каркас. */
@Composable
fun App(deps: MagicPaperDependencies = remember { createMagicPaperDependencies() }) {
    PaperTheme {
        PaperSurface(
            modifier = Modifier.fillMaxSize(),
            kind = PaperSurfaceKind.CANVAS,
        ) {
            val state by deps.viewModel.state.collectAsState()
            Box(Modifier.fillMaxSize()) {
                // Один непрерывный лист под всем интерфейсом, включая прозрачный
                // системный тайтлбар. Безопасные отступы относятся только к контенту.
                MagicPaperBackground(state.settings.paperAnimationEnabled, Modifier.matchParentSize())
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (state.showWelcome) {
                        Box(Modifier.padding(top = LocalWindowTitleBarInsets.current.calculateTopPadding())) {
                            WelcomeScreen(deps.viewModel, state)
                        }
                    } else {
                        TopBar(deps.viewModel, state.screen)
                        PaperDivider()
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
                if (!state.showWelcome && state.modelSwitcherOpen && state.screen == Screen.CHAT) {
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

@Composable
private fun MainArea(vm: MagicPaperViewModel, state: UiState) = CompositionLocalProvider(
    LocalHideSystemSteps provides state.settings.hideSystemSteps,
) {
    Box(Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(visible = state.sessionsPanelOpen && state.screen == Screen.CHAT) {
                Row {
                    SessionsPanel(vm, state.sessions, state.current?.id)
                    PaperDivider(Modifier.fillMaxHeight().width(1.dp))
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
                        ActivePlugins(state.plugins, state.pluginStates, vm::openPlanningChat)
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
    val chrome = LocalWindowChrome.current
    val desktopHeight = LocalWindowToolbarHeight.current
    val toolbarHeight = desktopHeight ?: 56.dp
    val buttonSize = if (desktopHeight != null) 28.dp else 48.dp
    // Резервируем только боковые зоны системных кнопок. Сам тулбар занимает
    // их строку, а не добавляет ещё один ряд под прозрачным тайтлбаром macOS.
    val layoutDirection = LocalLayoutDirection.current
    val nativeInsets = LocalWindowTitleBarInsets.current
    val nativeStart = nativeInsets.calculateLeftPadding(layoutDirection)
    val nativeEnd = nativeInsets.calculateRightPadding(layoutDirection)
    WindowTitleBarArea(modifier = Modifier.fillMaxWidth().height(toolbarHeight)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(
                start = nativeStart.coerceAtLeast(8.dp),
                end = nativeEnd.coerceAtLeast(8.dp),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIconButton(
                label = "Показать или скрыть боковую панель",
                onClick = { vm.toggleSessionsPanel() },
            ) { PaperText("☰", role = PaperTextRole.CHROME) }
            WindowDragArea(modifier = Modifier.weight(1f).height(toolbarHeight)) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperText("MagicPaper", role = PaperTextRole.CHROME, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    PaperText(
                        screen.subtitle,
                        modifier = Modifier.weight(1f),
                        role = PaperTextRole.CHROME,
                        color = LocalPaperColors.current.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PaperIconButton(
                label = if (screen == Screen.SETTINGS) "Вернуться в чат" else "Настройки",
                selected = screen == Screen.SETTINGS,
                onClick = { vm.open(if (screen == Screen.SETTINGS) Screen.CHAT else Screen.SETTINGS) },
            ) { PaperText("⚙", role = PaperTextRole.CHROME) }
            if (chrome != null) WindowButtons(chrome)
        }
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
    PaperIconButton("Управление окном", onClick, Modifier.size(40.dp)) {
        PaperText(glyph, role = PaperTextRole.TITLE, color = if (danger) LocalPaperColors.current.error else LocalPaperColors.current.secondaryText)
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
private fun ActivePlugins(plugins: List<MagicPlugin>, states: Map<String, PluginState>, openPlanning: () -> Unit) {
    val enabledIds = states.filterValues { it.enabled }.keys
    Column {
        PaperText("Активные панели", role = PaperTextRole.TITLE)
        Spacer(Modifier.height(4.dp))
        val active = plugins.filter { it.id in enabledIds }
        if (active.isEmpty()) {
            PaperText(
                "Все плагины выключены. Включите нужный выше.",
                color = LocalPaperColors.current.secondaryText,
            )
        }
        active.forEach { plugin ->
            PaperPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp).padding(12.dp)) {
                if (plugin.id == "coding-planning") PaperButton("Открыть планирование в чате проекта", openPlanning, kind = PaperButtonKind.QUIET)
                else plugin.Content()
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
        PaperPanel(Modifier.padding(16.dp).padding(horizontal = 16.dp, vertical = 10.dp), kind = PaperSurfaceKind.RAISED) {
            PaperText(notice.orEmpty())
        }
    }
}
