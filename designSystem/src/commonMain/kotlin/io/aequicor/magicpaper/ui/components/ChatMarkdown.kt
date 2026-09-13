package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.*
import org.intellij.markdown.ast.ASTNode

@Composable
fun PaperChatMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false, streaming: Boolean = false,
    scrollable: Boolean = false) {
    val displayedText = rememberPaperStreamingText(text, streaming && text.length <= MESSAGE_PREVIEW_CHARS)
    val document = rememberChatMarkdownDocument(displayedText, streaming)
    if (document == null) {
        PaperText("Подготавливаю сообщение…", modifier, role = PaperTextRole.LABEL)
        return
    }
    if (scrollable) {
        MarkdownDocumentBody(document, document.blocks, modifier, compact, lazy = true, followEnd = true)
    } else {
        PaperMessagePreview(text, text.length > MESSAGE_PREVIEW_CHARS || document.preview.size < document.blocks.size, modifier, preview = {
            MarkdownDocumentBody(document, document.preview, Modifier, compact)
        }, reader = { readerModifier -> MarkdownDocumentBody(document, document.blocks, readerModifier, compact) })
    }
}

@Composable
internal fun MarkdownDocumentBody(document: ChatMarkdownDocument, nodes: List<ASTNode>, modifier: Modifier = Modifier,
    compact: Boolean = false, lazy: Boolean = false, followEnd: Boolean = false) {
    val list = if (lazy) rememberLazyListState(initialFirstVisibleItemIndex = if (followEnd) Int.MAX_VALUE else 0) else null
    if (followEnd && list != null) paperStickToBottom(list)
    PaperMarkdownBody(document.document, nodes, modifier, compact, list)
}
