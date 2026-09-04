package io.aequicor.magicpaper.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** В браузере окно перетаскивает система — область прозрачна. */
@Composable
actual fun WindowDragArea(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier, propagateMinConstraints = true) { content() }
}
