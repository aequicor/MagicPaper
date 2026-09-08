package io.aequicor.magicpaper.ui.components

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes as Element
import org.intellij.markdown.MarkdownTokenTypes as Token
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes as Gfm
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GfmToken

internal const val MESSAGE_BLOCK_CHARS = 1536
internal const val MESSAGE_PREVIEW_CHARS = 6000

/** Source ranges remain relative to the original document, including reference links. */
internal class MarkdownBlockNode(
    val original: ASTNode,
    override val children: List<ASTNode>,
    override val startOffset: Int = original.startOffset,
    override val endOffset: Int = original.endOffset,
    val listNumber: Int? = null,
    val listContinuation: Boolean = false,
    val code: IntRange? = null,
) : ASTNode {
    override val type: IElementType get() = original.type
    override val parent: ASTNode? get() = original.parent
}

/** Bound both a single very long line and thousands of short lines. No text is discarded. */
internal fun textBlockRanges(text: String, start: Int = 0, end: Int = text.length): List<IntRange> = buildList {
    var position = start
    while (position < end) {
        var limit = minOf(position + MESSAGE_BLOCK_CHARS, end)
        var lines = 0
        var cursor = position
        var boundary = -1
        while (cursor < limit) {
            if (text[cursor].isWhitespace()) boundary = cursor + 1
            if (text[cursor] == '\n' && ++lines == 32) { limit = cursor + 1; break }
            cursor++
        }
        if (limit < end && boundary > position + MESSAGE_BLOCK_CHARS / 2) limit = minOf(limit, boundary)
        if (limit < end && text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit--
        add(position until limit)
        position = limit
    }
}

/** Partition the parsed tree, rather than parsing arbitrary substrings as independent Markdown. */
internal fun markdownRenderBlocks(root: ASTNode, source: String): List<ASTNode> {
    fun split(node: ASTNode): List<ASTNode> {
        if (node.endOffset - node.startOffset <= MESSAGE_BLOCK_CHARS &&
            source.substring(node.startOffset, node.endOffset).count { it == '\n' } < 32) return listOf(node)
        if (node.type == Element.CODE_FENCE || node.type == Element.CODE_BLOCK) {
            val content = node.children.filter { it.type == Token.CODE_FENCE_CONTENT || it.type == Token.CODE_LINE }
            if (content.isEmpty()) return listOf(MarkdownBlockNode(node, emptyList(), code = node.endOffset until node.endOffset))
            return textBlockRanges(source, content.first().startOffset, content.last().endOffset).map {
                MarkdownBlockNode(node, emptyList(), it.first, it.last + 1, code = it)
            }
        }
        if (node.type == Element.ORDERED_LIST || node.type == Element.UNORDERED_LIST) {
            val items = node.children.filter { it.type == Element.LIST_ITEM }
            val number = source.substring(node.startOffset, minOf(node.startOffset + 12, node.endOffset))
                .trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
            return items.flatMapIndexed { index, item ->
                split(item).mapIndexed { partIndex, part -> MarkdownBlockNode(node, listOf(part),
                    part.startOffset, part.endOffset, listNumber = number + index, listContinuation = partIndex > 0) }
            }
        }
        if (node.type == Gfm.TABLE) {
            val header = node.children.firstOrNull { it.type == Gfm.HEADER } ?: return listOf(node)
            val headers = split(header)
            val separators = node.children.filter { it.type == GfmToken.TABLE_SEPARATOR }
            val rows = node.children.filter { it.type == Gfm.ROW }.flatMap(::split)
            return buildList {
                // Oversized column headings also remain readable in bounded pieces.
                if (headers.size > 1 || rows.isEmpty()) headers.forEach { part ->
                    add(MarkdownBlockNode(node, listOf(part) + separators, part.startOffset, part.endOffset))
                }
                var position = 0
                while (position < rows.size) {
                    val first = position
                    var chars = 0
                    while (position < rows.size && position - first < 16 && chars < MESSAGE_BLOCK_CHARS) {
                        val row = rows[position++]
                        chars += row.endOffset - row.startOffset
                    }
                    val group = rows.subList(first, position)
                    add(MarkdownBlockNode(node, listOf(headers.first()) + separators + group,
                        group.first().startOffset, group.last().endOffset))
                }
            }
        }
        if (node.type == Element.LIST_ITEM || node.type == Element.BLOCK_QUOTE) {
            val body = node.children.filter { it.type !in setOf(Token.LIST_NUMBER, Token.LIST_BULLET, Token.BLOCK_QUOTE, Token.EOL, Token.WHITE_SPACE) }
            if (body.isNotEmpty()) return body.flatMap { child -> split(child).map { part ->
                MarkdownBlockNode(node, listOf(part), part.startOffset, part.endOffset)
            } }
        }
        return textBlockRanges(source, node.startOffset, node.endOffset).mapNotNull {
            sliceMarkdownNode(node, it.first, it.last + 1)
        }
    }
    return root.children.flatMap(::split)
}

private val LINK_METADATA = setOf(Element.LINK_DESTINATION, Element.LINK_TITLE, Element.LINK_LABEL)

/** Keep inline containers (bold, links, etc.) around every intersecting text fragment. */
private fun sliceMarkdownNode(node: ASTNode, start: Int, end: Int): ASTNode? {
    if (node.endOffset <= start || node.startOffset >= end) return null
    if (node.startOffset >= start && node.endOffset <= end) return node
    // The renderer removes the first/last child of code spans and link text. Keep
    // those syntax delimiters even when a continuation starts inside their body.
    val delimited = node.type == Element.CODE_SPAN || node.type == Element.LINK_TEXT
    val tableRow = node.type == Gfm.ROW || node.type == Gfm.HEADER
    val children = if (tableRow) node.children.mapNotNull { child ->
        sliceMarkdownNode(child, start, end) ?: if (child.type == GfmToken.CELL)
            MarkdownBlockNode(child, emptyList(), child.startOffset, child.startOffset) else null
    } else {
        // Binary search avoids scanning a giant paragraph's entire child list for
        // every fragment. The parser's children are ordered by source position.
        var low = 0
        var high = node.children.size
        while (low < high) {
            val mid = (low + high) / 2
            if (node.children[mid].endOffset <= start) low = mid + 1 else high = mid
        }
        val selected = mutableSetOf<Int>()
        var index = low
        while (index < node.children.size && node.children[index].startOffset < end) selected.add(index++)
        if (delimited && node.children.isNotEmpty()) { selected.add(0); selected.add(node.children.lastIndex) }
        if (node.type == Element.INLINE_LINK || node.type == Element.FULL_REFERENCE_LINK)
            node.children.forEachIndexed { i, child -> if (child.type in LINK_METADATA) selected.add(i) }
        selected.sorted().mapNotNull { i ->
            val child = node.children[i]
            if (child.type in LINK_METADATA || delimited && (i == 0 || i == node.children.lastIndex)) child
            else sliceMarkdownNode(child, start, end)
        }
    }
    return MarkdownBlockNode(node, children, maxOf(start, node.startOffset), minOf(end, node.endOffset))
}

internal fun markdownPreviewBlocks(blocks: List<ASTNode>): List<ASTNode> {
    var budget = MESSAGE_PREVIEW_CHARS
    return blocks.takeWhile { node ->
        if (budget <= 0) false else { budget -= (node.endOffset - node.startOffset).coerceAtLeast(16); true }
    }
}
