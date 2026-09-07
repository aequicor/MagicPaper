package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
    if (scope == null || nativeTitleBar != null) {
        // Вне окна (например, превью) — просто контейнер.
        // На Windows переносом управляет вся нативная область тайтлбара.
        Box(modifier, propagateMinConstraints = true) { content() }
    } else {
        // Официальная реализация Compose Desktop: нативный перенос окна через
        // JBR там, где он доступен (на macOS — как у системных окон), и
        // AWT-фолбэк. Работает и на нативных декорациях с прозрачным
        // тайтлбаром (контент нарисован поверх и перехватывает мышь).
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
