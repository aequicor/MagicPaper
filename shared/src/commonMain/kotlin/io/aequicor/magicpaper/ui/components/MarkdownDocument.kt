package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.*
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode

internal class ChatMarkdownDocument(val state: State.Success, val blocks: List<ASTNode>) {
    val source: String get() = state.content
    val preview: List<ASTNode> = markdownPreviewBlocks(blocks)
}

/** Bounded cache survives lazy-item disposal; scrolling back does not parse the same answer again. */
internal object ChatMarkdownDocuments {
    private val recent = MutableStateFlow<List<ChatMarkdownDocument>>(emptyList())
    fun cached(source: String): ChatMarkdownDocument? = recent.value.firstOrNull { it.source == source }

    suspend fun load(source: String, cache: Boolean): ChatMarkdownDocument {
        cached(source)?.let { return it }
        val document = withContext(Dispatchers.Default) {
            val state = parseMarkdownFlow(source).filterIsInstance<State.Success>().first()
            ChatMarkdownDocument(state, markdownRenderBlocks(state.node, state.content))
        }
        if (cache && source.length <= 1_000_000) recent.update { old ->
            var chars = 0
            (listOf(document) + old.filterNot { it.source == source }).take(6).takeWhile {
                chars += it.source.length
                chars <= 1_000_000
            }
        }
        return document
    }
}

@Composable
internal fun rememberChatMarkdownDocument(source: String, streaming: Boolean): ChatMarkdownDocument? {
    val latest by rememberUpdatedState(source)
    val live by rememberUpdatedState(streaming)
    var document by remember { mutableStateOf(ChatMarkdownDocuments.cached(source)) }
    LaunchedEffect(Unit) {
        // Finish one parse while new chunks conflate, rather than cancelling long parses
        // on every token and leaving a large streaming message permanently unrendered.
        snapshotFlow { latest to live }.collect { (text, active) ->
            val parsed = ChatMarkdownDocuments.load(text, cache = !active)
            if (latest.startsWith(text)) document = parsed
        }
    }
    return document
}
