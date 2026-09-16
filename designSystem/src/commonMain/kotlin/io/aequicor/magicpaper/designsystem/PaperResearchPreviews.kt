package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
