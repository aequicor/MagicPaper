package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow

/**
 * GFM tables keep every cell readable. The library default clips each cell to a
 * single ellipsized line, which hides comparison tables Paper documents rely on.
 * Cells wrap inside their column; tables wider than the viewport still scroll
 * horizontally because [MarkdownTable] owns that decision.
 */
@Composable
internal fun PaperMarkdownTable(model: MarkdownComponentModel) {
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, tableWidth, style ->
            MarkdownTableHeader(content, header, tableWidth, style, maxLines = Int.MAX_VALUE)
        },
        rowBlock = { content, row, tableWidth, style ->
            MarkdownTableRow(content, row, tableWidth, style, maxLines = Int.MAX_VALUE)
        },
    )
}
