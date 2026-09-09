package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar

/** OS-owned menu; domain actions are supplied by the host window. */
@Composable
public fun FrameWindowScope.PaperCommandMenu(onSettings: () -> Unit, onClose: () -> Unit) {
    val policy = rememberPaperPlatformPolicy()
    val mac = policy.platform == PaperPlatform.MACOS
    if (policy.supportsNativeMenuBar) MenuBar {
        Menu("Файл", mnemonic = 'Ф') {
            Item("Настройки…", onClick = onSettings, shortcut = KeyShortcut(Key.Comma, meta = mac, ctrl = !mac))
            Separator()
            Item("Закрыть окно", onClick = onClose,
                shortcut = if (mac) KeyShortcut(Key.W, meta = true) else KeyShortcut(Key.F4, alt = true))
        }
    }
}
