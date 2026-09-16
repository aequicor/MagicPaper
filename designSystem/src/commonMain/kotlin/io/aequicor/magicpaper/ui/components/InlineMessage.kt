package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the owning lazy list may expand its primary text into list items. */
data class PaperMessageExpansion(val source: String, val expand: () -> Unit)
val LocalPaperMessageExpansion = staticCompositionLocalOf<PaperMessageExpansion?> { null }

class PaperInlineMessageParts internal constructor(
    val source: String,
    internal val document: ChatMarkdownDocument?,
    val ranges: List<IntRange>,
) {
    val size: Int get() = document?.blocks?.size ?: ranges.size

    @Composable
    fun Content(index: Int, style: TextStyle = LocalPaperTypography.current.body,
        color: Color = LocalPaperColors.current.text) {
        val scrolling = LocalPaperChatScrolling.current
        if (document != null) MarkdownDocumentBody(document, listOf(document.blocks[index]), selectable = !scrolling)
        else if (scrolling) {
            PaperText(source.substring(ranges[index]), style = style, color = color)
        } else {
            androidx.compose.foundation.text.selection.SelectionContainer {
                PaperText(source.substring(ranges[index]), style = style, color = color)
            }
        }
    }
}

/** Called at list scope only for explicitly expanded messages. Parsing stays off the UI thread. */
@Composable
fun rememberPaperInlineMessageParts(source: String, markdown: Boolean): PaperInlineMessageParts? {
    if (markdown) {
        val document = rememberChatMarkdownDocument(source, streaming = false)
        return remember(document) { document?.let { PaperInlineMessageParts(it.source, it, emptyList()) } }
    }
    val parts by produceState<PaperInlineMessageParts?>(null, source) {
        value = withContext(Dispatchers.Default) { PaperInlineMessageParts(source, null, textBlockRanges(source)) }
    }
    return parts
}

@Composable
fun PaperCollapseMessage(onCollapse: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        PaperText("Свернуть", Modifier.align(Alignment.CenterEnd).paperChatDisclosure(onToggle = onCollapse)
            .padding(horizontal = 12.dp, vertical = 12.dp),
            style = LocalPaperTypography.current.label, color = LocalPaperColors.current.action)
    }
}

/** Async parsing must not re-enable bottom following between the click and the new layout. */
@Composable
fun PaperPreserveInlineExpansion(parts: Map<String, PaperInlineMessageParts>, scroll: PaperChatScrollState) {
    val ready = remember(scroll) { mutableSetOf<String>() }
    SideEffect {
        ready.retainAll(parts.keys)
        if (parts.keys.any { it !in ready }) scroll.onUserScroll(1f)
        ready.addAll(parts.keys)
    }
}
