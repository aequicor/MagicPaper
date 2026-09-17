package io.aequicor.magicpaper.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import com.mikepenz.markdown.model.markdownAnnotator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.LeafASTNode
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
        referenceLinkHandler = runBlocking { parseMarkdownFlow(doc.source).filterIsInstance<State.Success>().first().referenceLinkHandler },
    )

    @Test fun inlineGroupsPreserveNodeIdentityAndKeepSpacingWithItsVisibleBlock() {
        val content = document("First paragraph.\n\n## Heading\n\nLast paragraph.")
            .blocks.filterNot { it.type == Token.EOL || it.type == Token.WHITE_SPACE }
        val leadingSpace = LeafASTNode(Token.WHITE_SPACE, 0, 1)
        val leadingLine = LeafASTNode(Token.EOL, 1, 2)
        val firstLine = LeafASTNode(Token.EOL, 3, 4)
        val firstSpace = LeafASTNode(Token.WHITE_SPACE, 4, 5)
        val secondLine = LeafASTNode(Token.EOL, 6, 7)
        val trailingSpace = LeafASTNode(Token.WHITE_SPACE, 8, 9)
        val trailingLine = LeafASTNode(Token.EOL, 9, 10)
        val original = listOf(leadingSpace, leadingLine, content[0], firstLine, firstSpace,
            content[1], secondLine, content[2], trailingSpace, trailingLine)

        val groups = markdownInlineBlocks(original)

        assertEquals(3, groups.size, "Spacing must not become an independent message/menu row")
        assertSameNodes(original, groups.flatten())
        assertSameNodes(listOf(leadingSpace, leadingLine, content[0], firstLine, firstSpace), groups[0])
        assertSameNodes(listOf(content[1], secondLine), groups[1])
        assertSameNodes(listOf(content[2], trailingSpace, trailingLine), groups[2])
    }

    @Test fun emptyAndShortSpacingOnlyInlineDocumentsHaveNoExtraRows() {
        assertTrue(markdownInlineBlocks(emptyList()).isEmpty())
        val spacing = listOf(LeafASTNode(Token.EOL, 0, 1), LeafASTNode(Token.WHITE_SPACE, 1, 4),
            LeafASTNode(Token.EOL, 4, 5))

        val groups = markdownInlineBlocks(spacing)

        assertEquals(1, groups.size, "A short spacing-only document needs only one fragment")
        assertSameNodes(spacing, groups.single())
    }

    @Test fun thousandsOfBlankLinesKeepInlineCompositionBoundedWithoutLosingNodes() {
        val spacing = (0..1024).map { LeafASTNode(Token.EOL, it, it + 1) }
        val content = document("First paragraph.\n\nLast paragraph.").blocks
            .filterNot { it.type == Token.EOL || it.type == Token.WHITE_SPACE }
        val surroundedContent = spacing.take(400) + content.first() + spacing.drop(400).take(400) +
            content.last() + spacing.drop(800)
        for (original in listOf(spacing, surroundedContent)) {
            val groups = markdownInlineBlocks(original)

            assertSameNodes(original, groups.flatten())
            assertTrue(groups.size > 1, "Long blank runs must remain lazy")
            assertTrue(groups.all { it.size in 1..32 }, "One fragment must not compose an unbounded number of spacers")
            assertTrue(groups.all { group ->
                group.count { it.type != Token.EOL && it.type != Token.WHITE_SPACE } <= 1
            }, "Capping blank runs must not merge independent content blocks")
        }
    }

    @Test fun inlineGroupingRetainsLongParagraphListAndTablePartitionBoundaries() {
        val source = "\n\n" + "Long **formatted** paragraph with details. ".repeat(300) + "\n\n" +
            (7..106).joinToString("\n") { "$it. Item $it **formatted**" } + "\n\n" +
            "| Name | Value |\n| :--- | ---: |\n" +
            (1..100).joinToString("\n") { "| Row $it | **$it** |" } + "\n\n"
        val document = document(source)
        val visible = document.blocks.filterNot { it.type == Token.EOL || it.type == Token.WHITE_SPACE }

        val groups = markdownInlineBlocks(document.blocks)

        assertSameNodes(document.blocks, groups.flatten())
        assertEquals(visible.size, groups.size)
        assertSameNodes(visible, groups.map { group ->
            group.single { it.type != Token.EOL && it.type != Token.WHITE_SPACE }
        })
        val paragraphs = visible.filter { it.type == Element.PARAGRAPH }
        assertTrue(paragraphs.size > 3)
        assertTrue(paragraphs.all { it.endOffset - it.startOffset <= MESSAGE_BLOCK_CHARS })
        val listItems = visible.filter { it.type == Element.ORDERED_LIST }
        assertEquals((7..106).toList(), listItems.map { (it as MarkdownBlockNode).listNumber })
        val tables = visible.filter { it.type == Gfm.TABLE }
        assertTrue(tables.size > 1)
        assertTrue(tables.all { node -> node.children.count { it.type == Gfm.ROW } in 1..16 })
        assertEquals(100, tables.sumOf { node -> node.children.count { it.type == Gfm.ROW } })
    }

    private fun assertSameNodes(expected: List<ASTNode>, actual: List<ASTNode>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index -> assertSame(expected[index], actual[index], "Node $index changed or moved") }
    }

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

    @Test fun fencedIndentedAndUnclosedCodeKeepOneVisualAndCopyUnit() {
        val code = (1..1000).joinToString("\n") { "println(\"line $it 😀\")" }
        for (source in listOf("```kotlin\n$code\n```", "```kotlin\n$code", code.lineSequence().joinToString("\n") { "    $it" })) {
            val doc = document(source)
            val original = doc.node.children.first { it.type == Element.CODE_FENCE || it.type == Element.CODE_BLOCK }
            val content = original.children.filter { it.type == Token.CODE_FENCE_CONTENT || it.type == Token.CODE_LINE }
            val expected = source.substring(content.first().startOffset, content.last().endOffset)
            val block = doc.blocks.single { it.type == Element.CODE_FENCE || it.type == Element.CODE_BLOCK }
            assertSame(original, block, "Code must not acquire duplicate cards or headers at performance boundaries")
            val renderedContent = block.children.filter { it.type == Token.CODE_FENCE_CONTENT || it.type == Token.CODE_LINE }
            assertEquals(expected, source.substring(renderedContent.first().startOffset, renderedContent.last().endOffset))
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
