package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import io.aequicor.magicpaper.designsystem.*
import org.intellij.markdown.ast.ASTNode

@Composable
fun PaperChatMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false, streaming: Boolean = false,
    scrollable: Boolean = false) {
    val selectWholeMessage = LocalPaperWholeMessageSelection.current
    var selectingAll by remember(text) { mutableStateOf(false) }
    if (selectingAll) {
        PaperSelectedMessageSource(text, modifier, if (compact) LocalPaperTypography.current.label else LocalPaperTypography.current.body) {
            selectingAll = false
        }
        return
    }
    val displayedText = rememberPaperStreamingText(text, streaming && text.length <= MESSAGE_PREVIEW_CHARS)
    val document = rememberChatMarkdownDocument(displayedText, streaming)
    if (document == null) {
        PaperText("Подготавливаю сообщение…", modifier, role = PaperTextRole.LABEL)
        return
    }
    if (scrollable) {
        MarkdownDocumentBody(document, document.blocks, modifier, compact, lazy = true, followEnd = true,
            selectable = !selectWholeMessage, onSelectAll = { selectingAll = true })
    } else {
        PaperMessagePreview(text, text.length > MESSAGE_PREVIEW_CHARS || document.preview.size < document.blocks.size, modifier, preview = {
            MarkdownDocumentBody(document, document.preview, Modifier, compact,
                selectable = !selectWholeMessage, onSelectAll = { selectingAll = true })
        }, reader = { readerModifier -> MarkdownDocumentBody(document, document.blocks, readerModifier, compact,
            selectable = !selectWholeMessage, onSelectAll = { selectingAll = true }) })
    }
}

@Composable
internal fun MarkdownDocumentBody(document: ChatMarkdownDocument, nodes: List<ASTNode>, modifier: Modifier = Modifier,
    compact: Boolean = false, lazy: Boolean = false, followEnd: Boolean = false, selectable: Boolean = true,
    onSelectAll: (() -> Unit)? = null) {
    val list = if (lazy) rememberLazyListState(initialFirstVisibleItemIndex = if (followEnd) Int.MAX_VALUE else 0) else null
    if (followEnd && list != null) paperStickToBottom(list)
    PaperMarkdownBody(document.document, nodes, modifier, compact, list, selectable, onSelectAll)
}
