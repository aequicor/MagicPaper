package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.aequicor.magicpaper.ui.theme.MagicFonts

/**
 * Markdown ответа агента: заголовки, списки, таблицы, ссылки и блоки кода
 * с подсветкой синтаксиса. У блока кода — шапка с языком и кнопкой копирования.
 * Парсинг асинхронный: предыдущий текст остаётся видимым до готовности нового.
 */
@Composable
fun ChatMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false, streaming: Boolean = false,
    scrollable: Boolean = false) {
    val displayedText = rememberStreamingText(text, streaming && text.length <= MESSAGE_PREVIEW_CHARS)
    val document = rememberChatMarkdownDocument(displayedText, streaming)
    if (document == null) {
        Text("Подготавливаю сообщение…", modifier, style = MaterialTheme.typography.bodySmall)
        return
    }
    if (scrollable) {
        MarkdownDocumentBody(document, document.blocks, modifier, compact, lazy = true, followEnd = true)
    } else {
        MessagePreview(text, text.length > MESSAGE_PREVIEW_CHARS || document.preview.size < document.blocks.size, modifier, preview = {
            MarkdownDocumentBody(document, document.preview, Modifier, compact)
        }, reader = { readerModifier -> MarkdownDocumentBody(document, document.blocks, readerModifier, compact) })
    }
}

@Composable
internal fun MarkdownDocumentBody(document: ChatMarkdownDocument, nodes: List<ASTNode>, modifier: Modifier = Modifier,
    compact: Boolean = false, lazy: Boolean = false, followEnd: Boolean = false) {
    val bodyStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
    val highlightsBuilder = remember {
        Highlights.Builder().theme(SyntaxThemes.default(darkMode = false))
    }
    val components = remember(highlightsBuilder) {
        markdownComponents(
            // M3-чекбоксы для task-листов — как в дефолте m3-модуля,
            // который затирается кастомным набором компонентов.
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            unorderedList = { model ->
                if ((model.node as? MarkdownBlockNode)?.listContinuation == true)
                    MarkdownListItems(model.content, model.node, model.listDepth, bullet = { _, _, _ -> Text(" ") })
                else MarkdownBulletList(model.content, model.node, model.typography.text, model.listDepth)
            },
            orderedList = { model ->
                val start = (model.node as? MarkdownBlockNode)?.listNumber
                if (start == null) MarkdownOrderedList(model.content, model.node, model.typography.text, model.listDepth)
                else MarkdownListItems(model.content, model.node, model.listDepth, bullet = { index, _, _ ->
                    val label = "${start + index}."
                    Text(if ((model.node as? MarkdownBlockNode)?.listContinuation == true) " ".repeat(label.length) else label,
                        Modifier.padding(end = 6.dp), style = model.typography.text)
                })
            },
            codeFence = {
                if ((it.node as? MarkdownBlockNode)?.code != null) MarkdownCodePart(it, highlightsBuilder)
                else MarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlightsBuilder = highlightsBuilder,
                    // Шапка блока: имя языка + кнопка «скопировать».
                    showHeader = true,
                )
            },
            codeBlock = {
                if ((it.node as? MarkdownBlockNode)?.code != null) MarkdownCodePart(it, highlightsBuilder)
                else MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlightsBuilder = highlightsBuilder,
                    showHeader = true,
                )
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
                h1 = if (compact) bodyStyle else MaterialTheme.typography.titleLarge,
                h2 = if (compact) bodyStyle else MaterialTheme.typography.titleMedium,
                h3 = if (compact) bodyStyle else MaterialTheme.typography.titleMedium,
                h4 = if (compact) bodyStyle else MaterialTheme.typography.bodyLarge,
                h5 = if (compact) bodyStyle else MaterialTheme.typography.bodyLarge,
                h6 = if (compact) bodyStyle else MaterialTheme.typography.bodyMedium,
                text = bodyStyle,
                code = MaterialTheme.typography.bodyMedium.copy(fontFamily = MagicFonts.code),
                inlineCode = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = MagicFonts.code,
                    fontSize = TextUnit.Unspecified,
                ),
                quote = bodyStyle.plus(SpanStyle(fontStyle = FontStyle.Italic)),
            ),
            components = components,
            success = { state, markdownComponents, contentModifier ->
                if (lazy) {
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (followEnd) Int.MAX_VALUE else 0)
                    if (followEnd) stickToBottom(list)
                    LazyColumn(state = list, modifier = contentModifier) {
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
    val node = model.node as MarkdownBlockNode
    val range = node.code!!
    val language = node.original.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { model.content.substring(it.startOffset, it.endOffset).take(80) }
    val clipboard = LocalClipboardManager.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(language.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = {
                val lines = node.original.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.CODE_LINE }
                clipboard.setText(AnnotatedString(if (lines.isEmpty()) "" else model.content.substring(lines.first().startOffset, lines.last().endOffset)))
            }) { Text("Копировать код") }
        }
        MarkdownHighlightedCode(model.content.substring(range), language, model.typography.code, highlightsBuilder, showHeader = false)
    }
}
