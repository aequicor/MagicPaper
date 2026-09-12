package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.window.WindowScope

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
        // Compose WindowDraggableArea + macOS double-click-to-maximize fallback.
        scope.WindowDraggableArea(modifier = if (System.getProperty("os.name").startsWith("Mac")) {
            modifier.titleBarDoubleClick { DesktopWindowChrome(scope.window).toggleMaximize() }
        } else modifier) { content() }
    }
}

@Composable
actual fun WindowTitleBarArea(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, propagateMinConstraints = true) { content() }
}

/** macOS: double-click по пустому месту тайтлбара разворачивает окно. */
private fun Modifier.titleBarDoubleClick(onDoubleClick: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var lastClickTime = 0L
        var lastClickPosition = androidx.compose.ui.geometry.Offset.Zero
        var pressPosition = androidx.compose.ui.geometry.Offset.Zero
        var dragged = false
        var primaryPress = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            if (event.type == PointerEventType.Press) {
                primaryPress = event.buttons.isPrimaryPressed
                pressPosition = change.position
                dragged = false
            }
            if ((change.position - pressPosition).getDistance() > viewConfiguration.touchSlop) dragged = true
            if (event.type == PointerEventType.Release && primaryPress) {
                primaryPress = false
                val elapsed = change.uptimeMillis - lastClickTime
                if (!dragged && elapsed in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                    (change.position - lastClickPosition).getDistance() <= viewConfiguration.touchSlop) {
                    onDoubleClick()
                    lastClickTime = 0L
                } else {
                    lastClickTime = if (dragged) 0L else change.uptimeMillis
                    lastClickPosition = change.position
                }
            }
        }
    }
}
