package io.aequicor.magicpaper.designsystem

import org.intellij.markdown.MarkdownElementTypes as Element
import org.intellij.markdown.MarkdownTokenTypes as Token
import org.intellij.markdown.ast.ASTNode

internal data class PaperCodeInfo(val language: String?, val title: String?)

/** CommonMark leaves info-string metadata to the renderer; title= follows Docusaurus.
 * These values are labels only: they never open or write a file. */
internal fun paperCodeInfo(info: String?): PaperCodeInfo {
    val text = info.orEmpty().trim()
    val language = text.takeWhile { !it.isWhitespace() }.takeIf { it.isNotEmpty() && '=' !in it }
    val title = Regex("(?:^|\\s)(?:title|filename|file)=(?:\"([^\"]+)\"|'([^']+)'|(\\S+))")
        .find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        ?.takeIf { it.length <= 512 && it.none(Char::isISOControl) }
    return PaperCodeInfo(language, title)
}

internal class PaperNamedCodeNode(val original: ASTNode, val title: String) : ASTNode by original
private class PaperCodeContainer(original: ASTNode, override val children: List<ASTNode>) : ASTNode by original

/** Preserve source offsets and reference links. Only an unambiguous standalone file caption
 * immediately before a fence is moved; prose, links and conflicting captions stay untouched. */
internal fun paperCodeTitles(root: ASTNode, source: String): ASTNode {
    if (root.type !in setOf(Element.MARKDOWN_FILE, Element.LIST_ITEM, Element.ORDERED_LIST,
            Element.UNORDERED_LIST, Element.BLOCK_QUOTE)) return root
    val children = mutableListOf<ASTNode>()
    for (child in root.children) {
        var node = paperCodeTitles(child, source)
        if (node.type == Element.CODE_FENCE || node.type == Element.CODE_BLOCK) {
            val previous = children.indexOfLast { it.type != Token.EOL && it.type != Token.WHITE_SPACE }
            val caption = children.getOrNull(previous)?.takeIf { it.type == Element.PARAGRAPH }
                ?.let { paperStandaloneFilename(source.substring(it.startOffset, it.endOffset)) }
            val explicit = paperCodeInfo(node.children.firstOrNull { it.type == Token.FENCE_LANG }
                ?.let { source.substring(it.startOffset, it.endOffset) }).title
            if (caption != null && (explicit == null || explicit == caption)) {
                while (children.size > previous) children.removeAt(children.lastIndex)
                node = PaperNamedCodeNode(node, caption)
            }
        }
        children += node
    }
    return if (children.size == root.children.size && children.indices.all { children[it] === root.children[it] }) root
    else PaperCodeContainer(root, children)
}

internal fun paperStandaloneFilename(text: String): String? {
    var value = text.trim().removeSuffix(":").trimEnd()
    val quoted = value.startsWith('`') && value.endsWith('`') && value.count { it == '`' } == 2
    if (quoted) value = value.substring(1, value.lastIndex)
    if (value.isEmpty() || value.length > 512 || value.any(Char::isISOControl) || "://" in value) return null
    if (value.any { !(it.isLetterOrDigit() || it in "./\\_- " || it == ':') }) return null
    if (!quoted && value.any(Char::isWhitespace)) return null
    val filename = value.substringAfterLast('/').substringAfterLast('\\')
    val fileLike = Regex("[^. ]+\\.[A-Za-z0-9][A-Za-z0-9._-]*").matches(filename) ||
        Regex("\\.[A-Za-z][A-Za-z0-9._-]*").matches(filename) ||
        filename in setOf("Dockerfile", "Makefile", "Justfile", "Jenkinsfile", "Gemfile", "Procfile")
    return value.takeIf { fileLike }
}
