package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.WindowScope
import androidx.compose.foundation.window.WindowDraggableArea

/**
 * Область содержимого окна (даёт доступ к `window`). Предоставляется
 * точкой входа desktopApp внутри `Window { ... }`.
 */
val LocalWindowScope = staticCompositionLocalOf<WindowScope?> { null }

@Composable
actual fun WindowDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    val scope = LocalWindowScope.current
    if (scope == null) {
        // Вне окна (например, превью) — просто контейнер.
        Box(modifier, propagateMinConstraints = true) { content() }
    } else {
        // Официальная реализация Compose Desktop: нативный перенос окна через
        // JBR там, где он доступен (на macOS — как у системных окон), и
        // AWT-фолбэк. Работает и на нативных декорациях с прозрачным
        // тайтлбаром (контент нарисован поверх и перехватывает мышь).
        scope.WindowDraggableArea(modifier = modifier) { content() }
    }
}
