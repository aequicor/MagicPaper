package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Scope navigation stays visible while the independent source list scrolls. */
@Composable
public fun PaperResearchSourceTabs(sharedCount: Int, questionCount: Int, questionSelected: Boolean,
    onSelect: (question: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalPaperColors.current
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        listOf("Для чата" to sharedCount, "Для вопроса" to questionCount).forEachIndexed { index, (label, count) ->
            val selected = questionSelected == (index == 1)
            Column(Modifier.weight(1f).fillMaxHeight().paperClickable(role = Role.Tab, onClick = { onSelect(index == 1) })
                .semantics { this.selected = selected; contentDescription = label }) {
                Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    PaperText(label, Modifier.weight(1f, fill = false), role = PaperTextRole.CHROME, color = if (selected) colors.action else colors.secondaryText)
                    Spacer(Modifier.width(4.dp))
                    PaperText(count.toString(), Modifier.background(colors.selected.copy(alpha = .45f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp), role = PaperTextRole.CHROME, color = colors.secondaryText)
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) colors.action else colors.border.copy(alpha = .45f)))
            }
        }
    }
}

/** Selection covers the complete active scope, even while the visible list is filtered. */
@Composable
public fun PaperResearchSourceSelection(title: String, selectedCount: Int, totalCount: Int,
    onSelectionChange: (Boolean) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, searching: Boolean = false) {
    val all = totalCount > 0 && selectedCount == totalCount
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PaperCheck(all, onSelectionChange, Modifier.semantics {
            contentDescription = if (all) "Снять выбор со всех: $title" else "Выбрать все: $title"
        }, enabled = enabled && totalCount > 0, indeterminate = selectedCount in 1 until totalCount)
        PaperText("Выбрано $selectedCount из $totalCount", Modifier.weight(1f), role = PaperTextRole.CHROME)
        PaperToolbarButton(PaperToolbarIcon.Search, if (searching) "Скрыть поиск по источникам" else "Поиск по источникам",
            32.dp, onClick = onSearch)
    }
}

@Composable
public fun PaperResearchSourcesEmpty(question: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperNoteAddIcon()
            PaperText(if (question) "Добавьте источники для этого вопроса" else "Добавьте источники для чата",
                role = PaperTextRole.CHROME, color = LocalPaperColors.current.secondaryText)
        }
        PaperButton("Добавить источник", onAdd, Modifier.fillMaxWidth(), enabled = enabled, kind = PaperButtonKind.SECONDARY)
    }
}
