package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.ui.window.*

/** Outline sidebar symbol matching the system window/sidebar convention. */
@Composable
public fun PaperSidebarIcon(modifier: Modifier = Modifier) = PaperPanelIcon(PaperPanelSide.LEFT, modifier)

/** A zero host toolbar height means fullscreen: no controls, drag region or focus targets.
 * The host and content insets share that same value, so hiding chrome leaves no empty lane. */
@Composable
public fun PaperAppTitleBar(sidebarVisible: Boolean, onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val height = LocalWindowToolbarHeight.current ?: 56.dp
    if (height <= 0.dp) return
    val direction = LocalLayoutDirection.current
    val insets = LocalWindowTitleBarInsets.current
    WindowTitleBarArea(modifier.fillMaxWidth().height(height)) {
        Row(Modifier.statusBarsPadding().fillMaxSize().padding(
            start = insets.calculateLeftPadding(direction).coerceAtLeast(8.dp),
            end = insets.calculateRightPadding(direction).coerceAtLeast(8.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            val label = if (sidebarVisible) "Скрыть список сессий" else "Показать список сессий"
            PaperTooltip(label) {
                PaperIconButton(label, onToggleSidebar, Modifier.semantics {
                    stateDescription = if (sidebarVisible) "Развёрнуто" else "Свёрнуто"
                }) { PaperSidebarIcon() }
            }
            WindowDragArea(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    PaperText("MagicPaper", role = PaperTextRole.CHROME, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
    }
}

@Preview(name = "Windowed", group = "App title bar", widthDp = 640, heightDp = 80)
@Preview(name = "Narrow", group = "App title bar", widthDp = 320, heightDp = 80)
@Preview(name = "Large text", group = "App title bar", widthDp = 480, heightDp = 80, fontScale = 2f)
@Composable
internal fun PaperAppTitleBarPreview(fullscreen: Boolean = false) = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalWindowToolbarHeight provides if (fullscreen) 0.dp else 40.dp) {
            PaperAppTitleBar(true, {}) {
                PaperIconButton("Расходы", {}) { PaperText("$", role = PaperTextRole.CHROME) }
                PaperIconButton("Настройки", {}) { PaperText("⚙", role = PaperTextRole.CHROME) }
            }
        }
    }
}

@Preview(name = "Fullscreen", group = "App title bar", widthDp = 640, heightDp = 80)
@Composable
internal fun PaperAppTitleBarFullscreenPreview() = PaperAppTitleBarPreview(fullscreen = true)
