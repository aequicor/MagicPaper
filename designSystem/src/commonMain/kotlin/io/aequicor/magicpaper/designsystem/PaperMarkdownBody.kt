package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.MarkdownTokenTypes
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.LazyListState

/** Parsed document retained across lazy item disposal; renderer state stays private. */
public class PaperMarkdownDocument internal constructor(internal val state: State.Success) {
    public val source: String get() = state.content
    public val node: ASTNode get() = state.node
}

public suspend fun parsePaperMarkdown(source: String): PaperMarkdownDocument =
    PaperMarkdownDocument(parseMarkdownFlow(source).filterIsInstance<State.Success>().first())

/** Bounded document slice with its original syntax and numbering preserved. */
public interface PaperMarkdownSlice : ASTNode {
    public val original: ASTNode
    public val listNumber: Int?
    public val listContinuation: Boolean
    public val code: IntRange?
}

@Composable
public fun PaperMarkdownBody(document: PaperMarkdownDocument, nodes: List<ASTNode>, modifier: Modifier = Modifier,
    compact: Boolean = false, listState: LazyListState? = null) {
    val bodyStyle = if (compact) LocalPaperTypography.current.label else LocalPaperTypography.current.body
    val components = remember {
        markdownComponents(
            // M3-чекбоксы для task-листов — как в дефолте m3-модуля,
            // который затирается кастомным набором компонентов.
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            unorderedList = { model ->
                if ((model.node as? PaperMarkdownSlice)?.listContinuation == true)
                    MarkdownListItems(model.content, model.node, model.listDepth, bullet = { _, _, _ -> PaperText(" ") })
                else MarkdownBulletList(model.content, model.node, model.typography.text, model.listDepth)
            },
            orderedList = { model ->
                val start = (model.node as? PaperMarkdownSlice)?.listNumber
                if (start == null) MarkdownOrderedList(model.content, model.node, model.typography.text, model.listDepth)
                else MarkdownListItems(model.content, model.node, model.listDepth, bullet = { index, _, _ ->
                    val label = "${start + index}."
                    PaperText(if ((model.node as? PaperMarkdownSlice)?.listContinuation == true) " ".repeat(label.length) else label,
                        Modifier.padding(end = 6.dp), style = model.typography.text)
                })
            },
            codeFence = {
                // The renderer mutates this builder on Dispatchers.Default. Each block and
                // source revision owns both the builder and the async result state, so old
                // work cannot highlight another block or publish into a newer revision.
                key(it.content, it.node) {
                    val highlightsBuilder = remember {
                        Highlights.Builder().theme(SyntaxThemes.default(darkMode = false))
                    }
                    if ((it.node as? PaperMarkdownSlice)?.code != null) MarkdownCodePart(it, highlightsBuilder)
                    else MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        highlightsBuilder = highlightsBuilder,
                        // Шапка блока: имя языка + кнопка «скопировать».
                        showHeader = true,
                    )
                }
            },
            codeBlock = {
                key(it.content, it.node) {
                    val highlightsBuilder = remember {
                        Highlights.Builder().theme(SyntaxThemes.default(darkMode = false))
                    }
                    if ((it.node as? PaperMarkdownSlice)?.code != null) MarkdownCodePart(it, highlightsBuilder)
                    else MarkdownHighlightedCodeBlock(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        highlightsBuilder = highlightsBuilder,
                        showHeader = true,
                    )
                }
            },
        )
    }
    SelectionContainer {
        Markdown(
            state = document.state,
            modifier = modifier.fillMaxWidth(),
            // В бабле чата дисплейные заголовки ни к чему — приглушаем до типографики чата.
            // Код — фирменный моно (иначе библиотека пинит системный моноширинный);
            // цитаты — курсив, подтянет literata_italic из стека.
            typography = markdownTypography(
                h1 = if (compact) bodyStyle else LocalPaperTypography.current.headline,
                h2 = if (compact) bodyStyle else LocalPaperTypography.current.title,
                h3 = if (compact) bodyStyle else LocalPaperTypography.current.title,
                h4 = if (compact) bodyStyle else LocalPaperTypography.current.body,
                h5 = if (compact) bodyStyle else LocalPaperTypography.current.body,
                h6 = if (compact) bodyStyle else LocalPaperTypography.current.body,
                text = bodyStyle,
                paragraph = bodyStyle, ordered = bodyStyle, bullet = bodyStyle, list = bodyStyle, table = bodyStyle,
                code = LocalPaperTypography.current.body.copy(fontFamily = PaperFonts.code),
                inlineCode = LocalPaperTypography.current.body.copy(
                    fontFamily = PaperFonts.code,
                    fontSize = TextUnit.Unspecified,
                ),
                quote = bodyStyle.plus(SpanStyle(fontStyle = FontStyle.Italic)),
            ),
            components = components,
            success = { state, markdownComponents, contentModifier ->
                if (listState != null) {
                    LazyColumn(state = listState, modifier = contentModifier) {
                        items(nodes.size, contentType = { nodes[it].type }) { index ->
                            MarkdownElement(nodes[index], markdownComponents, state.content)
                        }
                    }
                } else Column(contentModifier) {
                    nodes.forEach { node -> MarkdownElement(node, markdownComponents, state.content) }
                }
            },
        )
    }
}

@Composable
@Suppress("DEPRECATION")
private fun MarkdownCodePart(model: MarkdownComponentModel, highlightsBuilder: Highlights.Builder) {
    val node = model.node as PaperMarkdownSlice
    val range = node.code!!
    val language = node.original.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { model.content.substring(it.startOffset, it.endOffset).take(80) }
    val clipboard = LocalClipboardManager.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PaperText(language.orEmpty(), Modifier.weight(1f), style = LocalPaperTypography.current.label)
            PaperAction(onClick = {
                val lines = node.original.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.CODE_LINE }
                clipboard.setText(AnnotatedString(if (lines.isEmpty()) "" else model.content.substring(lines.first().startOffset, lines.last().endOffset)))
            }) { PaperText("Копировать код") }
        }
        MarkdownHighlightedCode(model.content.substring(range), language, model.typography.code, highlightsBuilder, showHeader = false)
    }
}
