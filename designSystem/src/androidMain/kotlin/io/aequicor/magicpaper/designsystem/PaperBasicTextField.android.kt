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

@Composable
internal actual fun PaperBasicTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier,
    enabled: Boolean, textStyle: TextStyle,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions,
    singleLine: Boolean, maxLines: Int, minLines: Int,
    visualTransformation: VisualTransformation,
    interactionSource: MutableInteractionSource, cursorBrush: Brush,
    contentDescription: String?,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit) {
    BasicTextField(value = value, onValueChange = onValueChange, modifier = modifier.semantics { contentDescription?.let { this.contentDescription = it } }, enabled = enabled,
        textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        visualTransformation = visualTransformation, interactionSource = interactionSource, cursorBrush = cursorBrush, decorationBox = decorationBox)
}

@Composable
internal actual fun PaperBasicTextField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, modifier: Modifier,
    enabled: Boolean, textStyle: TextStyle,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions,
    singleLine: Boolean, maxLines: Int, minLines: Int,
    visualTransformation: VisualTransformation,
    interactionSource: MutableInteractionSource, cursorBrush: Brush,
    contentDescription: String?,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit) {
    BasicTextField(value = value, onValueChange = onValueChange, modifier = modifier.semantics { contentDescription?.let { this.contentDescription = it } }, enabled = enabled,
        textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        visualTransformation = visualTransformation, interactionSource = interactionSource, cursorBrush = cursorBrush, decorationBox = decorationBox)
}
