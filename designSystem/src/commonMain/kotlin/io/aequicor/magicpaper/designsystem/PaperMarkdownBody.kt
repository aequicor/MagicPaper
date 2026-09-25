package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import com.mikepenz.markdown.model.markdownPadding
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.LazyListState

/** Parsed document retained across lazy item disposal; renderer state stays private. */
public class PaperMarkdownDocument internal constructor(internal val state: State.Success) {
    public val source: String get() = state.content
    public val node: ASTNode get() = state.node
}

public suspend fun parsePaperMarkdown(source: String): PaperMarkdownDocument =
    PaperMarkdownDocument(parseMarkdownFlow(source).filterIsInstance<State.Success>().first().let {
        it.copy(node = paperCodeTitles(it.node, source))
    })

/** Bounded document slice with its original syntax and numbering preserved. */
public interface PaperMarkdownSlice : ASTNode {
    public val original: ASTNode
    public val listNumber: Int?
    public val listContinuation: Boolean
    public val code: IntRange?
}

@Composable
public fun PaperMarkdownBody(document: PaperMarkdownDocument, nodes: List<ASTNode>, modifier: Modifier = Modifier,
    compact: Boolean = false, listState: LazyListState? = null, selectable: Boolean = true,
    onSelectAll: (() -> Unit)? = null) {
    val bodyStyle = if (compact) LocalPaperTypography.current.label else LocalPaperTypography.current.body
    var selectingAll by remember(document.source) { mutableStateOf(false) }
    if (selectingAll) {
        PaperMarkdownSelectedSource(document.source, modifier, bodyStyle) { selectingAll = false }
        return
    }
    val reading = LocalPaperResearchReading.current && !compact
    val components = remember {
        markdownComponents(
            // M3-чекбоксы для task-листов — как в дефолте m3-модуля,
            // который затирается кастомным набором компонентов.
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
            table = { PaperMarkdownTable(it) },
            unorderedList = { model ->
                if ((model.node as? PaperMarkdownSlice)?.listContinuation == true)
                    MarkdownListItems(model.content, model.node, model.listDepth, bullet = { _, _, _ -> PaperText(" ") })
                else MarkdownBulletList(model.content, model.node, model.typography.text, model.listDepth)
            },
            blockQuote = { model ->
                if (LocalPaperResearchReading.current) {
                    PaperPanel(
                        modifier = Modifier.fillMaxWidth(),
                        color = LocalPaperColors.current.successSurface,
                    ) {
                        MarkdownBlockQuote(
                            content = model.content,
                            node = model.node,
                            style = model.typography.text,
                        )
                    }
                } else {
                    MarkdownBlockQuote(model.content, model.node, model.typography.quote)
                }
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
            codeFence = { PaperMarkdownCodeElement(it) },
            codeBlock = { PaperMarkdownCodeElement(it) },
        )
    }
    val body: @Composable () -> Unit = {
        Markdown(
            state = document.state,
            modifier = modifier.fillMaxWidth(),
            padding = if (reading) markdownPadding(block = 4.dp, list = 2.dp,
                listItemTop = 2.dp, listItemBottom = 2.dp) else markdownPadding(),
            // В бабле чата дисплейные заголовки ни к чему — приглушаем до типографики чата.
            // Код — фирменный моно (иначе библиотека пинит системный моноширинный);
            // цитаты — курсив, подтянет literata_italic из стека.
            typography = markdownTypography(
                h1 = if (compact) bodyStyle else LocalPaperTypography.current.headline,
                h2 = if (compact) bodyStyle else LocalPaperTypography.current.title,
                h3 = if (reading) bodyStyle.copy(fontWeight = FontWeight.SemiBold)
                    else if (compact) bodyStyle else LocalPaperTypography.current.title,
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
                    PaperLazyColumn(state = listState, modifier = contentModifier) {
                        items(nodes.size, key = { it }, contentType = { nodes[it].type }) { index ->
                            MarkdownElement(nodes[index], markdownComponents, state.content)
                        }
                    }
                } else Column(contentModifier) {
                    nodes.forEach { node -> MarkdownElement(node, markdownComponents, state.content) }
                }
            },
        )
    }
    if (selectable) SelectionContainer(modifier = Modifier.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.A &&
            (event.isCtrlPressed || event.isMetaPressed)) {
            if (onSelectAll != null) onSelectAll() else selectingAll = true
            true
        } else false
    }) { body() } else body()
}

/** Compose's SelectionContainer does not implement the select-all keyboard command.
 * A read-only text field supplies native selection and copy for the complete source,
 * including blocks that a lazy renderer has not composed. */
@Composable
internal fun PaperMarkdownSelectedSource(source: String, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalPaperTypography.current.body, onDismiss: () -> Unit) {
    var value by remember(source) { mutableStateOf(TextFieldValue(source, TextRange(0, source.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    BasicTextField(value = value, onValueChange = { value = it.copy(text = source) }, readOnly = true,
        textStyle = style, modifier = modifier.fillMaxWidth().focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) hadFocus = true else if (hadFocus) onDismiss()
            }.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else false
            })
    LaunchedEffect(focus) { focus.requestFocus() }
}
