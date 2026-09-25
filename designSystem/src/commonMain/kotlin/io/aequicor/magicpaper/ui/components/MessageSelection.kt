package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/** A shared selection owner lets visible lazy fragments participate in one drag selection. */
@Composable
fun PaperMessageSelectionContainer(content: @Composable () -> Unit) {
    val owner = remember { PaperMessageSelectionOwner() }
    SelectionContainer(Modifier.pointerInput(owner) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            owner.clear()
        }
    }.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.A &&
            (event.isCtrlPressed || event.isMetaPressed)) owner.selectAll() else false
    }) {
        CompositionLocalProvider(LocalPaperMessageSelectionOwner provides owner, content = content)
    }
}

internal class PaperMessageSelectionOwner {
    private var active: Pair<PaperInlineMessageParts, Int>? = null

    fun activate(parts: PaperInlineMessageParts, index: Int) {
        active = parts to index
    }

    fun release(parts: PaperInlineMessageParts, index: Int) {
        if (active?.first === parts && active?.second == index) active = null
    }

    fun clear() { active = null }

    fun selectAll(): Boolean {
        val (parts, index) = active ?: return false
        parts.selectAll(index)
        return true
    }
}

internal val LocalPaperMessageSelectionOwner = staticCompositionLocalOf<PaperMessageSelectionOwner?> { null }
