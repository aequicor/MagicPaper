package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.*
import io.aequicor.magicpaper.ui.components.textBlockRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes

/** Small code needs the same text contrast as body copy, including comments and literals. */
internal val paperCodeSyntaxTheme: SyntaxTheme = PaperColors().let { colors ->
    fun Color.rgb() = toArgb() and 0xFFFFFF
    SyntaxTheme(key = "paper", code = colors.text.rgb(), keyword = colors.action.rgb(),
        string = colors.success.rgb(), literal = colors.activityBlueEdge.rgb(),
        comment = colors.secondaryText.rgb(), metadata = colors.systemText.rgb(),
        multilineComment = colors.secondaryText.rgb(), punctuation = colors.text.rgb(),
        mark = colors.error.rgb())
}

@Composable
internal fun PaperMarkdownCodeElement(model: MarkdownComponentModel) {
    // Each block/revision owns its mutable builder while highlighting runs off the UI thread.
    key(model.content, model.node) {
        val builder = remember { Highlights.Builder().theme(paperCodeSyntaxTheme) }
        val slice = model.node as? PaperMarkdownSlice
        val node = slice?.original ?: model.node
        val render: @Composable (String, String?, TextStyle) -> Unit = { fullCode, info, style ->
            val metadata = remember(info) { paperCodeInfo(info) }
            PaperMarkdownCode(code = slice?.code?.let { model.content.substring(it) } ?: fullCode,
                language = metadata.language, title = metadata.title ?: (node as? PaperNamedCodeNode)?.title,
                style = style, highlightsBuilder = builder, copyCode = fullCode)
        }
        if (node.type == MarkdownElementTypes.CODE_FENCE)
            MarkdownCodeFence(model.content, node, model.typography.code, block = render)
        else MarkdownCodeBlock(model.content, node, model.typography.code, block = render)
    }
}

/** A fence has one header, background and copy action, independently of its rendering budget. */
@Composable
internal fun PaperMarkdownCode(code: String, language: String?, style: TextStyle,
    highlightsBuilder: Highlights.Builder, title: String? = null, copyCode: String = code) {
    val highlighted by produceState(AnnotatedString(code), code, language, highlightsBuilder) {
        value = withContext(Dispatchers.Default) {
            val builder = highlightsBuilder.code(code)
            language?.let(SyntaxLanguage::getByName)?.let(builder::language)
            val spans = builder.build().getHighlights()
            buildAnnotatedString {
                append(code)
                spans.forEach { span ->
                    val appearance = when (span) {
                        is ColorHighlight -> SpanStyle(color = Color(span.rgb).copy(alpha = 1f))
                        is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
                    }
                    addStyle(appearance, span.location.start, span.location.end)
                }
            }
        }
    }
    val codeStyle = style.copy(fontFamily = PaperFonts.code, fontSize = 10.sp, lineHeight = 14.sp)
    val longCode = remember(code) { code.length > 16_384 || code.count { it == '\n' } >= 64 }
    val clipboard = LocalClipboardManager.current
    val spacing = LocalPaperSpacing.current
    // Markdown already separates blocks, including their intervening EOL nodes.
    MarkdownCodeBackground(LocalPaperColors.current.raisedSurface,
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        showHeader = false, language = language, code = copyCode) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = title ?: language?.uppercase() ?: "Код"
                Box(Modifier.weight(1f)) {
                    PaperTooltip(label) {
                        PaperText(label, style = LocalPaperTypography.current.code.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (title != null && language != null) PaperText(language.uppercase(),
                    style = LocalPaperTypography.current.chrome.copy(fontSize = 10.sp, lineHeight = 14.sp))
                PaperTooltip("Копировать код") {
                    PaperIconButton("Копировать код", { clipboard.setText(AnnotatedString(copyCode)) }) {
                        PaperText("⧉", style = LocalPaperTypography.current.label.copy(fontSize = 16.sp))
                    }
                }
            }
            PaperDivider(color = LocalPaperColors.current.border.copy(alpha = .45f))
            if (longCode) PaperLongCode(highlighted, codeStyle)
            else {
                val scroll = rememberScrollState()
                PaperScrollViewport(scroll, horizontal = true) {
                    MarkdownBasicText(text = highlighted, modifier = Modifier.horizontalScroll(scroll)
                        .padding(horizontal = spacing.xs, vertical = spacing.xxs), style = codeStyle)
                }
            }
        }
    }
}

/** Keep huge snippets lazy inside a single card. Only visible bounded text runs are laid out;
 * shared horizontal position and full-code copying survive their disposal during scrolling. */
@Composable
private fun PaperLongCode(code: AnnotatedString, style: TextStyle) {
    val spacing = LocalPaperSpacing.current
    val ranges = remember(code.text) { paperCodeRanges(code.text) }
    val longestLine = remember(code.text, ranges) {
        ranges.asSequence().flatMap { code.text.substring(it).lineSequence() }.maxByOrNull { it.length }.orEmpty()
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measuredWidth = remember(longestLine, style, density) {
        with(density) { measurer.measure(longestLine, style, softWrap = false).size.width.toDp() + 16.dp }
    }
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxWidth().height(448.dp)) {
        val width = maxOf(maxWidth, measuredWidth)
        PaperScrollViewport(horizontal, Modifier.fillMaxSize(), horizontal = true) {
            LazyColumn(Modifier.horizontalScroll(horizontal).width(width).fillMaxHeight(), state = vertical,
                contentPadding = PaddingValues(horizontal = spacing.xs, vertical = spacing.xxs)) {
                items(ranges.size, key = { it }) { index ->
                    val range = ranges[index]
                    // One Text already ends its final line; don't add a second visual blank line.
                    val end = range.last + 1 - if (code[range.last] == '\n') 1 else 0
                    MarkdownBasicText(text = code.subSequence(range.first, end), modifier = Modifier.fillMaxWidth(), style = style)
                }
            }
        }
        PaperScrollbar(vertical, Modifier.matchParentSize(), reverseLayout = false)
    }
}

/** Prefer real line boundaries: performance slices must not split an ordinary code statement.
 * Pathological individual lines still wrap in bounded runs, with all source text retained. */
internal fun paperCodeRanges(code: String): List<IntRange> = buildList {
    var start = 0
    var end = 0
    var lines = 0
    while (end < code.length) {
        val newline = code.indexOf('\n', end)
        val next = if (newline < 0) code.length else newline + 1
        if (end > start && (next - start > 1536 || lines >= 32)) {
            add(start until end)
            start = end
            lines = 0
        }
        if (next - end > 4096) {
            addAll(textBlockRanges(code, end, next))
            start = next
            lines = 0
        } else lines++
        end = next
    }
    if (start < end) add(start until end)
}
