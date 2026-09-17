package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.text.TextFieldScrollState
import androidx.compose.foundation.text.rememberTextFieldScrollState
import androidx.compose.foundation.rememberScrollbarAdapter

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun PaperBasicTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier,
    enabled: Boolean, textStyle: TextStyle,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions,
    singleLine: Boolean, maxLines: Int, minLines: Int,
    visualTransformation: VisualTransformation,
    interactionSource: MutableInteractionSource, cursorBrush: Brush,
    contentDescription: String?,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit) {
    val scroll = rememberTextFieldScrollState(if (singleLine) Orientation.Horizontal else Orientation.Vertical)
    PaperTextFieldViewport(modifier, scroll, singleLine) { viewport ->
        BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth().semantics { contentDescription?.let { this.contentDescription = it } }, enabled = enabled,
            textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            visualTransformation = visualTransformation, interactionSource = interactionSource, cursorBrush = cursorBrush, scrollState = scroll,
            decorationBox = { inner -> decorationBox {
                Box(viewport) { inner() }
            } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun PaperBasicTextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier,
    enabled: Boolean, textStyle: TextStyle,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions,
    singleLine: Boolean, maxLines: Int, minLines: Int,
    visualTransformation: VisualTransformation,
    interactionSource: MutableInteractionSource, cursorBrush: Brush,
    contentDescription: String?,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit) {
    val scroll = rememberTextFieldScrollState(if (singleLine) Orientation.Horizontal else Orientation.Vertical)
    PaperTextFieldViewport(modifier, scroll, singleLine) { viewport ->
        BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth().semantics { contentDescription?.let { this.contentDescription = it } }, enabled = enabled,
            textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            visualTransformation = visualTransformation, interactionSource = interactionSource, cursorBrush = cursorBrush, scrollState = scroll,
            decorationBox = { inner -> decorationBox {
                Box(viewport) { inner() }
            } })
    }
}

/** Keep the scrollbar outside BasicTextField's decoration hit target: dragging it must not
 * move the caret or start a text selection. The measured inner viewport excludes labels. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaperTextFieldViewport(modifier: Modifier, scroll: TextFieldScrollState, horizontal: Boolean,
    content: @Composable (Modifier) -> Unit) {
    val coordinates = remember { TextFieldViewportCoordinates() }
    val density = LocalDensity.current
    Box(modifier.onGloballyPositioned { coordinates.container = it; coordinates.update() },
        propagateMinConstraints = true) {
        content(Modifier.onGloballyPositioned { coordinates.editor = it; coordinates.update() })
        coordinates.bounds?.let { bounds ->
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })) {
                    PaperScrollbar(rememberScrollbarAdapter(scroll), Modifier.matchParentSize(), horizontal, scroll)
                }
            }
        }
    }
}

/** Keep geometry local: scrolling an ancestor must not recompose every text field just
 * because its window position changed, or shorten its scrollbar to the clipped bounds. */
private class TextFieldViewportCoordinates {
    var container: LayoutCoordinates? = null
    var editor: LayoutCoordinates? = null
    var bounds by mutableStateOf<Rect?>(null)
        private set

    fun update() {
        val parent = container ?: return
        val text = editor ?: return
        if (parent.isAttached && text.isAttached) bounds = parent.localBoundingBoxOf(text, clipBounds = false)
    }
}
