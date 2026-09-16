package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Collapsed rails", group = "Research", widthDp = 160, heightDp = 460)
@Composable
internal fun PaperResearchRailsPreview() {
    PaperTheme {
        PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PaperResearchRail(
                    title = "Вопросы",
                    count = 3,
                    expandLabel = "Развернуть вопросы",
                    expandGlyph = "›",
                    onExpand = {},
                    modifier = Modifier.width(56.dp).fillMaxHeight(),
                ) {
                    PaperResearchRailAction("Новый вопрос", "+", {})
                }
                PaperResearchRail(
                    title = "Источники",
                    count = 4,
                    expandLabel = "Развернуть источники",
                    expandGlyph = "‹",
                    onExpand = {},
                    modifier = Modifier.width(56.dp).fillMaxHeight(),
                ) {
                    PaperResearchRailAction("Добавить ссылку", "URL", {})
                    PaperResearchRailAction("Добавить файлы", "▤", {})
                }
            }
        }
    }
}

@Preview(name = "Question and source rows", group = "Research", widthDp = 300, heightDp = 340)
@Preview(name = "Rows with large text", group = "Research", widthDp = 340, heightDp = 520, fontScale = 2f)
@Composable
internal fun PaperResearchRowsPreview() {
    PaperTheme {
        PaperResearchPane(Modifier.fillMaxSize()) {
            PaperResearchQuestionRow(1, "Как организовать исследование и проверить выводы по нескольким источникам?", true, {})
            PaperResearchQuestionRow(2, "Следующий вопрос", false, {})
            PaperResearchQuestionRow(3, "Недоступный вопрос", false, {}, enabled = false)
            PaperDivider()
            PaperResearchSourceRow("Документ с длинным названием для исследования.pdf", true, {}, detail = "PDF", file = true)
            PaperResearchSourceRow("Отключённый источник", false, {}, detail = "developer.android.com")
            PaperResearchSourceRow("Сохранение источника", true, {}, enabled = false, detail = "github.com")
        }
    }
}

@Preview(name = "Source controls", group = "Research", widthDp = 300, heightDp = 220)
@Preview(name = "Source controls large text", group = "Research", widthDp = 340, heightDp = 400, fontScale = 2f)
@Composable
internal fun PaperResearchSourceControlsPreview() = PaperTheme {
    var expanded by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(true) }
    PaperResearchPane(Modifier.fillMaxSize()) {
        PaperResearchSourceGroupHeader("Общие для чата", expanded, { expanded = !expanded },
            selectedCount = if (checked) 1 else 0, totalCount = 1, onSelectionChange = { checked = it }) {
            PaperIconButton("Добавить файлы", {}) { PaperText("+") }
        }
        if (expanded) PaperResearchSourceRow("system_design_replit.md · GitHub", checked, { checked = it }, keepActionsVisible = menu, detail = "github.com") {
            Box {
                PaperIconButton("Действия с источником", { menu = true }) { PaperText("⋯") }
                PaperMenuHost(menu, { menu = false }) {
                    PaperMenuAction("Открыть", { menu = false })
                }
            }
        }
    }
}

@Preview(name = "Source group selection", group = "Research", widthDp = 300, heightDp = 240)
@Preview(name = "Source group selection large text", group = "Research", widthDp = 340, heightDp = 460, fontScale = 2f)
@Composable
internal fun PaperResearchSourceSelectionPreview() = PaperTheme {
    PaperResearchPane(Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp)) {
            for ((selected, total) in listOf(0 to 3, 1 to 3, 3 to 3, 0 to 0)) {
                PaperResearchSourceGroupHeader("Общие для чата", expanded = selected != total, onToggle = {},
                    selectedCount = selected, totalCount = total, onSelectionChange = {}) {
                    PaperIconButton("Добавить файлы", {}) { PaperText("+") }
                }
            }
        }
    }
}
