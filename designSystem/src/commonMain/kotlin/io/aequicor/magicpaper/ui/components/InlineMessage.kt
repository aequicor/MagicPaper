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
    val size: Int get() = document?.inlineBlocks?.size ?: ranges.size

    /** An empty answer (a question asked with only an attachment, or an answer interrupted before its
     * first chunk) parses into no fragments at all. Callers keep one blank fragment for it, so every
     * accessor must stay total over that fragment instead of failing the whole composition. */
    val isEmpty: Boolean get() = size == 0

    /** Opaque structural key for lazy reuse; paragraphs should not recycle table or heading trees. */
    fun contentType(index: Int): String = document?.inlineBlocks?.getOrNull(index)?.firstOrNull {
        it.type != org.intellij.markdown.MarkdownTokenTypes.EOL &&
            it.type != org.intellij.markdown.MarkdownTokenTypes.WHITE_SPACE
    }?.type?.toString() ?: "plain-text"

    @Composable
    fun Content(index: Int, style: TextStyle = LocalPaperTypography.current.body,
        color: Color = LocalPaperColors.current.text) {
        // Lazy fragments already bound visible work. Preserve selection/layout rather
        // than rebuilding the text tree at both ends of every scroll gesture.
        val parsed = document
        if (parsed != null) parsed.inlineBlocks.getOrNull(index)?.let { MarkdownDocumentBody(parsed, it) }
        else ranges.getOrNull(index)?.let { range ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                PaperText(source.substring(range), style = style, color = color)
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
