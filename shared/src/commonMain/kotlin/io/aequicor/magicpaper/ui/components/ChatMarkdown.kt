package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.PaperMarkdown
import org.intellij.markdown.ast.ASTNode

/** Shared owns streaming and cached document parsing; Paper owns Markdown rendering. */
@Composable
fun ChatMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    streaming: Boolean = false,
    scrollable: Boolean = false,
) {
    val displayedText = rememberStreamingText(text, streaming && text.length <= MESSAGE_PREVIEW_CHARS)
    val document = rememberChatMarkdownDocument(displayedText, streaming)
    if (document == null) {
        PaperMarkdown("Подготавливаю сообщение…", modifier, compact = true)
        return
    }
    MarkdownDocumentBody(document, document.blocks, modifier, compact, lazy = scrollable, followEnd = scrollable)
}

/** Preserves the lazy-item call contract used by InlineMessage. */
@Composable
internal fun MarkdownDocumentBody(
    document: ChatMarkdownDocument,
    nodes: List<ASTNode>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    lazy: Boolean = false,
    followEnd: Boolean = false,
) {
    PaperMarkdown(document.source, modifier.fillMaxWidth(), compact)
}
