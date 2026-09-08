package io.aequicor.magicpaper.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.intellij.markdown.MarkdownElementTypes as Element
import org.intellij.markdown.MarkdownTokenTypes as Token
import org.intellij.markdown.flavours.gfm.GFMElementTypes as Gfm
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GfmToken
import kotlin.test.*

class MarkdownBlocksTest {
    private fun document(source: String) = runBlocking { ChatMarkdownDocuments.load(source, cache = false) }
    private fun settings(doc: ChatMarkdownDocument) = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(SpanStyle()), codeSpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
        annotator = markdownAnnotator(),
        referenceLinkHandler = doc.state.referenceLinkHandler,
    )

    @Test fun longInlineFormattingRetainsEveryCharacterAndItsStyle() {
        val body = "fragment😀 ".repeat(700).trimEnd()
        for (markup in listOf("**$body**", "*$body*", "~~$body~~", "`$body`", "[$body](https://example.com)", "[$body][ref]\n\n[ref]: https://example.com")) {
            val doc = document(markup)
            val parts = doc.blocks.filter { it.type == Element.PARAGRAPH }.map {
                markup.buildMarkdownAnnotatedString(it, TextStyle.Default, settings(doc))
            }
            assertTrue(parts.size > 3)
            // Inline code has library-added padding; compare the actual content without spaces.
            assertEquals(body.replace(" ", ""), parts.joinToString("") { it.text }.replace(" ", ""), "Lost text in ${markup.take(20)}")
            if (markup.startsWith("**")) assertTrue(parts.all { part -> part.spanStyles.any { it.item.fontWeight == FontWeight.Bold } })
            if (markup.startsWith("[")) assertTrue(parts.all { part ->
                part.getLinkAnnotations(0, part.length).isNotEmpty()
            }, "Links must survive every slice")
        }
    }

    @Test fun fencedIndentedAndUnclosedCodeKeepTheWholeBodyInBoundedParts() {
        val code = (1..1000).joinToString("\n") { "println(\"line $it 😀\")" }
        for (source in listOf("```kotlin\n$code\n```", "```kotlin\n$code", code.lineSequence().joinToString("\n") { "    $it" })) {
            val doc = document(source)
            val original = doc.state.node.children.first { it.type == Element.CODE_FENCE || it.type == Element.CODE_BLOCK }
            val content = original.children.filter { it.type == Token.CODE_FENCE_CONTENT || it.type == Token.CODE_LINE }
            val expected = source.substring(content.first().startOffset, content.last().endOffset)
            val ranges = doc.blocks.filterIsInstance<MarkdownBlockNode>().mapNotNull { it.code }
            assertTrue(ranges.size > 20)
            assertTrue(ranges.all { it.count() <= MESSAGE_BLOCK_CHARS })
            assertEquals(expected, ranges.joinToString("") { source.substring(it) })
        }
    }

    @Test fun orderedListNumbersAndTableHeadersSurvivePartitioning() {
        val list = document((7..106).joinToString("\n") { "$it. Item $it **formatted**" })
        assertEquals((7..106).toList(), list.blocks.filterIsInstance<MarkdownBlockNode>().map { it.listNumber })
        val table = document("| Name | Value |\n| :--- | ---: |\n" + (1..100).joinToString("\n") { "| Row $it | **$it** |" })
        assertEquals(100, table.blocks.filter { it.type == Gfm.TABLE }.sumOf { node -> node.children.count { it.type == Gfm.ROW } })
        assertTrue(table.blocks.filter { it.type == Gfm.TABLE }.all { node ->
            node.children.count { it.type == Gfm.HEADER } == 1 && node.children.count { it.type == Gfm.ROW } in 1..16
        })
    }

    @Test fun plaintextChunksPreserveEmojiAndBoundShortLinesAndUnbrokenText() {
        for (text in listOf("😀".repeat(10000), "x\n".repeat(10000), "abc ".repeat(10000))) {
            val ranges = textBlockRanges(text)
            assertEquals(text, ranges.joinToString("") { text.substring(it) })
            assertTrue(ranges.all { it.count() <= MESSAGE_BLOCK_CHARS && !text[it.last].isHighSurrogate() })
            assertTrue(ranges.all { range -> text.substring(range).count { it == '\n' } <= 32 })
        }
    }

    @Test fun emptyAndIncompleteStreamingMarkdownCanAlwaysBeLoaded() = runBlocking {
        for (source in listOf("", "**unfinished", "```kotlin\nval a =", "[label](", "| Header |\n| --- |")) {
            val doc = withTimeout(5000) { ChatMarkdownDocuments.load(source, cache = false) }
            assertEquals(source, doc.source)
        }
    }

    @Test fun hugeTableCellsRetainTheirColumnAndAllText() {
        val body = "large-cell ".repeat(1000)
        val doc = document("| First | Second |\n| --- | --- |\n| $body | ending |")
        val rows = doc.blocks.flatMap { it.children }.filter { it.type == Gfm.ROW }
        assertTrue(rows.size > 3)
        assertTrue(rows.all { it.children.count { cell -> cell.type == GfmToken.CELL } == 2 })
        val text = rows.flatMap { it.children }.filter { it.type == GfmToken.CELL }.joinToString("") {
            doc.source.buildMarkdownAnnotatedString(it, TextStyle.Default, settings(doc)).text
        }
        assertEquals((body + "ending").replace(" ", ""), text.replace(" ", ""))
    }
}
