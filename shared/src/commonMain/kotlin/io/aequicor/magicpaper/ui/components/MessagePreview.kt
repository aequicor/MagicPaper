package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The chat keeps a bounded preview; the full reader has its own finite, lazy viewport. */
@Composable
internal fun MessagePreview(
    source: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    reader: @Composable (Modifier) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val limit = with(LocalDensity.current) { 360.dp.roundToPx() }
    Column(modifier) {
        Box(Modifier.heightIn(max = 360.dp).clipToBounds()) {
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { contentHeight = it.height }) { preview() }
        }
        if (truncated || contentHeight > limit) TextButton(onClick = { open = true }) { Text("Читать полностью") }
    }
    if (open) MessageReaderDialog(source, { open = false }, reader)
}

@Suppress("DEPRECATION")
@Composable
private fun MessageReaderDialog(source: String, onDismiss: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.92f).widthIn(max = 1000.dp).fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Сообщение целиком", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(source)) }) { Text("Копировать") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                HorizontalDivider()
                content(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}

/** Plain messages and tool output need the same bound as Markdown, including unbroken lines. */
@Composable
internal fun ChatPlainText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    val previewText = remember(text) {
        var end = minOf(text.length, MESSAGE_PREVIEW_CHARS)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val prefix = text.substring(0, end)
        val ranges = textBlockRanges(prefix)
        prefix.substring(0, ranges.take(4).lastOrNull()?.let { it.last + 1 } ?: 0)
    }
    MessagePreview(text, previewText.length < text.length, modifier, preview = {
        SelectionContainer { Text(previewText, style = style, color = color) }
    }, reader = { readerModifier ->
        val ranges = remember(text) { textBlockRanges(text) }
        SelectionContainer {
            LazyColumn(readerModifier) {
                items(ranges.size) { index -> Text(text.substring(ranges[index]), style = style, color = color) }
            }
        }
    })
}
