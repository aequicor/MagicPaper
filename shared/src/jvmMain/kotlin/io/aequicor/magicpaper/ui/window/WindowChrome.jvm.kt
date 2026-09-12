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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.WindowScope

/**
 * Область содержимого окна (даёт доступ к `window`). Предоставляется
 * точкой входа desktopApp внутри `Window { ... }`.
 */
val LocalWindowScope = staticCompositionLocalOf<WindowScope?> { null }

/** Active only for a Windows frame configured through JBR WindowDecorations. */
val LocalWindowsTitleBarController = staticCompositionLocalOf<WindowsTitleBarController?> { null }

@Composable
actual fun WindowDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    val scope = LocalWindowScope.current
    val nativeTitleBar = LocalWindowsTitleBarController.current
    val isMac = System.getProperty("os.name").startsWith("Mac")
    if (scope == null || nativeTitleBar != null) {
        // Вне окна (например, превью) — просто контейнер.
        // На Windows с JBR переносом управляет вся нативная область тайтлбара.
        Box(modifier, propagateMinConstraints = true) { content() }
    } else if (isMac) {
        // macOS: WindowDraggableArea + double-click-to-maximize fallback.
        scope.WindowDraggableArea(modifier = modifier.titleBarDoubleClick { DesktopWindowChrome(scope.window).toggleMaximize() }) { content() }
    } else {
        // Linux: официальная реализация Compose Desktop — нативный перенос окна.
        scope.WindowDraggableArea(modifier = modifier) { content() }
    }
}

@Composable
actual fun WindowTitleBarArea(modifier: Modifier, content: @Composable () -> Unit) {
    val nativeTitleBar = LocalWindowsTitleBarController.current
    if (nativeTitleBar == null) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }

    val density = LocalDensity.current
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            // JBR expects the bottom edge relative to the client area's top,
            // expressed in AWT logical pixels rather than physical pixels.
            val bottomPx = coordinates.boundsInWindow().bottom
            nativeTitleBar.updateHeight(bottomPx / density.density)
        },
        propagateMinConstraints = true,
    ) {
        // The background receives events only where no foreground Compose control
        // owns them. Those events are handed back to Windows as non-client hits.
        Box(
            Modifier
                .matchParentSize()
                .nativeTitleBarMouseEvents(nativeTitleBar),
        )
        content()
    }
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

private fun Modifier.nativeTitleBarMouseEvents(
    titleBar: WindowsTitleBarController,
): Modifier = pointerInput(titleBar) {
    awaitPointerEventScope {
        var inUserControl = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val consumed = event.changes.any { it.isConsumed }
            if (!consumed && !inUserControl) {
                titleBar.forceClientHitTest(false)
            } else {
                if (event.type == PointerEventType.Press) inUserControl = true
                if (event.type == PointerEventType.Release) inUserControl = false
                titleBar.forceClientHitTest(true)
            }
        }
    }
}
