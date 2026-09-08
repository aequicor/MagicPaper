package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.TextUnit
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
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
    val bodyStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge
    val displayedText = rememberStreamingText(text, streaming)
    // Loading would briefly collapse the message on every streamed chunk and move the scroll anchor.
    val markdownState = rememberMarkdownState(displayedText, retainState = true)
    val highlightsBuilder = remember {
        Highlights.Builder().theme(SyntaxThemes.default(darkMode = false))
    }
    val components = remember(highlightsBuilder) {
        markdownComponents(
            // M3-чекбоксы для task-листов — как в дефолте m3-модуля,
            // который затирается кастомным набором компонентов.
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            codeFence = {
                MarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlightsBuilder = highlightsBuilder,
                    // Шапка блока: имя языка + кнопка «скопировать».
                    showHeader = true,
                )
            },
            codeBlock = {
                MarkdownHighlightedCodeBlock(
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
            markdownState = markdownState,
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
                if (scrollable) {
                    val nodes = remember(state.node) { state.node.children }
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
                    stickToBottom(list)
                    LazyColumn(state = list, modifier = contentModifier) {
                        items(nodes, key = { it.startOffset }, contentType = { it.type }) { node ->
                            MarkdownElement(node, markdownComponents, state.content)
                        }
                    }
                } else MarkdownSuccess(state, markdownComponents, contentModifier)
            },
        )
    }
}
