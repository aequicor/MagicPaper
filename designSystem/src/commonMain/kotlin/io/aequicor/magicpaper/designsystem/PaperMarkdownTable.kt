package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.times
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/**
 * GFM tables keep every cell readable. The library default clips each cell to a
 * single ellipsized line, which hides comparison tables Paper documents rely on.
 * Cells wrap inside their column; tables wider than the viewport still scroll
 * horizontally. Paper owns the viewport because the renderer hides its scroll state;
 * the library still owns cell rendering, inline formatting and row semantics.
 */
@Composable
internal fun PaperMarkdownTable(model: MarkdownComponentModel) {
    val dimensions = LocalMarkdownDimens.current
    val columns = remember(model.node) { model.node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0 }
    val rows = remember(model.node) { model.node.children.count { it.type == ROW } + 1 }
    val width = columns * dimensions.tableCellWidth
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.background(LocalMarkdownColors.current.tableBackground, RoundedCornerShape(dimensions.tableCornerSize))
        .widthIn(max = dimensions.tableMaxWidth).semantics { collectionInfo = CollectionInfo(rows, columns) }) {
        val overflow = maxWidth <= width
        PaperScrollViewport(scroll, horizontal = true) {
            Column(if (overflow) Modifier.horizontalScroll(scroll).requiredWidth(width) else Modifier.fillMaxWidth()) {
                var rowIndex = 1
                model.node.children.forEach { node -> when (node.type) {
                    HEADER -> MarkdownTableHeader(model.content, node, width, model.typography.table, maxLines = Int.MAX_VALUE)
                    ROW -> MarkdownTableRow(model.content, node, width, model.typography.table, rowIndex = rowIndex++, maxLines = Int.MAX_VALUE)
                    TABLE_SEPARATOR -> MarkdownDivider()
                } }
            }
        }
    }
}
