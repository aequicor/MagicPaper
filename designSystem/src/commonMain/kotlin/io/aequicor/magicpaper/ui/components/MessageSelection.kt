package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/** The entire agent response can span many lazy rows, including collapsed tool calls. */
@Composable
fun PaperMessageSelectionTarget(onSelectAll: (() -> Unit)?, content: @Composable () -> Unit) {
    val owner = LocalPaperMessageSelectionOwner.current
    if (owner == null || onSelectAll == null) {
        content()
        return
    }
    val action = rememberUpdatedState(onSelectAll)
    val token = remember { Any() }
    DisposableEffect(owner, token) { onDispose { owner.releaseGroup(token) } }
    Box(Modifier.fillMaxWidth().pointerInput(owner, token) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            owner.activateGroup(token) { action.value() }
        }
    }) {
        CompositionLocalProvider(LocalPaperWholeMessageSelection provides true, content = content)
    }
}

internal class PaperMessageSelectionOwner {
    private sealed interface Target {
        data class Part(val parts: PaperInlineMessageParts, val index: Int) : Target
        class Group(val token: Any, val action: () -> Unit) : Target
    }
    private var active: Target? = null

    fun activate(parts: PaperInlineMessageParts, index: Int) {
        if (active !is Target.Group) active = Target.Part(parts, index)
    }

    fun activateGroup(token: Any, action: () -> Unit) {
        active = Target.Group(token, action)
    }

    fun release(parts: PaperInlineMessageParts, index: Int) {
        val target = active
        if (target is Target.Part && target.parts === parts && target.index == index) active = null
    }

    fun releaseGroup(token: Any) {
        val target = active
        if (target is Target.Group && target.token === token) active = null
    }

    fun clear() { active = null }

    fun selectAll(): Boolean {
        when (val target = active ?: return false) {
            is Target.Part -> target.parts.selectAll(target.index)
            is Target.Group -> target.action()
        }
        return true
    }
}

internal val LocalPaperMessageSelectionOwner = staticCompositionLocalOf<PaperMessageSelectionOwner?> { null }
internal val LocalPaperWholeMessageSelection = staticCompositionLocalOf { false }
