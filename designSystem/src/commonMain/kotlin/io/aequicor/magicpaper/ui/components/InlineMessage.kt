package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.designsystem.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    // One container covers ordinary messages; very long articles retain bounded lazy rows.
    private val grouped = source.length <= MESSAGE_PREVIEW_CHARS &&
        (document?.inlineBlocks?.size ?: ranges.size) <= 16
    private val markdownGroups = document?.let { parsed ->
        if (parsed.blocks.isEmpty()) emptyList()
        else if (grouped) listOf(parsed.blocks)
        else parsed.inlineBlocks
    }
    private var selectedIndex by mutableIntStateOf(-1)
    internal fun selectAll(index: Int) { selectedIndex = index }
    val size: Int get() = markdownGroups?.size ?: if (grouped && ranges.isNotEmpty()) 1 else ranges.size

    /** An empty answer (a question asked with only an attachment, or an answer interrupted before its
     * first chunk) parses into no fragments at all. Callers keep one blank fragment for it, so every
     * accessor must stay total over that fragment instead of failing the whole composition. */
    val isEmpty: Boolean get() = size == 0

    /** Opaque structural key for lazy reuse; paragraphs should not recycle table or heading trees. */
    fun contentType(index: Int): String = markdownGroups?.getOrNull(index)?.firstOrNull {
        it.type != org.intellij.markdown.MarkdownTokenTypes.EOL &&
            it.type != org.intellij.markdown.MarkdownTokenTypes.WHITE_SPACE
    }?.type?.toString() ?: "plain-text"

    @Composable
    fun Content(index: Int, style: TextStyle = LocalPaperTypography.current.body,
        color: Color = LocalPaperColors.current.text) {
        val shared = LocalPaperMessageSelectionOwner.current
        val content: @Composable () -> Unit = {
            if (selectedIndex >= 0) {
                if (selectedIndex == index) PaperMarkdownSelectedSource(source, style = style.copy(color = color)) { selectedIndex = -1 }
            } else {
                // Lazy fragments already bound visible work. Preserve selection/layout rather
                // than rebuilding the text tree at both ends of every scroll gesture.
                val parsed = document
                if (parsed != null) {
                    val nodes = markdownGroups?.getOrNull(index)
                    if (nodes != null) MarkdownDocumentBody(parsed, nodes, selectable = shared == null,
                        onSelectAll = if (shared == null) ({ selectedIndex = index }) else null)
                } else if (ranges.isNotEmpty()) {
                    val text: @Composable () -> Unit = {
                        if (grouped) Column {
                            ranges.forEach { range -> PaperText(source.substring(range), style = style, color = color) }
                        } else ranges.getOrNull(index)?.let { range ->
                            PaperText(source.substring(range), style = style, color = color)
                        }
                    }
                    if (shared == null) SelectionContainer(modifier = Modifier.onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.A &&
                            (event.isCtrlPressed || event.isMetaPressed)) {
                            selectedIndex = index
                            true
                        } else false
                    }) { text() } else text()
                }
            }
        }
        if (shared == null) content() else {
            DisposableEffect(shared, this@PaperInlineMessageParts, index) {
                onDispose { shared.release(this@PaperInlineMessageParts, index) }
            }
            Box(Modifier.fillMaxWidth().pointerInput(shared, this@PaperInlineMessageParts, index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    shared.activate(this@PaperInlineMessageParts, index)
                }
            }) { content() }
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
