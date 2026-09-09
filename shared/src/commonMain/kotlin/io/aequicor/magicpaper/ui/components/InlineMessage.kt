package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the owning lazy list may expand its primary text into list items. */
internal data class MessageExpansion(val source: String, val expand: () -> Unit)
internal val LocalMessageExpansion = staticCompositionLocalOf<MessageExpansion?> { null }

internal class InlineMessageParts(
    val source: String,
    val document: ChatMarkdownDocument?,
    val ranges: List<IntRange>,
) {
    val size: Int get() = document?.blocks?.size ?: ranges.size

    @Composable
    fun Content(index: Int, style: TextStyle = MaterialTheme.typography.bodyLarge,
        color: Color = MaterialTheme.colorScheme.onSurface) {
        if (document != null) MarkdownDocumentBody(document, listOf(document.blocks[index]))
        else androidx.compose.foundation.text.selection.SelectionContainer {
            Text(source.substring(ranges[index]), style = style, color = color)
        }
    }
}

/** Called at list scope only for explicitly expanded messages. Parsing stays off the UI thread. */
@Composable
internal fun rememberInlineMessageParts(source: String, markdown: Boolean): InlineMessageParts? {
    if (markdown) {
        val document = rememberChatMarkdownDocument(source, streaming = false)
        return remember(document) { document?.let { InlineMessageParts(it.source, it, emptyList()) } }
    }
    val parts by produceState<InlineMessageParts?>(null, source) {
        value = withContext(Dispatchers.Default) { InlineMessageParts(source, null, textBlockRanges(source)) }
    }
    return parts
}

@Composable
internal fun CollapseMessage(onCollapse: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Text("Свернуть", Modifier.align(Alignment.CenterEnd).chatDisclosure(onToggle = onCollapse)
            .padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Async parsing must not re-enable bottom following between the click and the new layout. */
@Composable
internal fun PreserveInlineExpansion(parts: Map<String, InlineMessageParts>, scroll: ChatScrollState) {
    val ready = remember(scroll) { mutableSetOf<String>() }
    SideEffect {
        ready.retainAll(parts.keys)
        if (parts.keys.any { it !in ready }) scroll.onUserScroll(1f)
        ready.addAll(parts.keys)
    }
}
