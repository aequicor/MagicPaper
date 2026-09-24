package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Context inside progress contour", group = "Usage", widthDp = 360, heightDp = 150)
@Preview(name = "Context large text", group = "Usage", widthDp = 500, heightDp = 200, fontScale = 2f)
@Composable
internal fun PaperContextIndicatorPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((fraction, label) in listOf(0f to "0%", .01f to "≈1%", .24f to "24%", .9f to "90%",
                1f to "100%", 1f to "≈100%", null to "—")) {
                PaperContextIndicator(fraction, label, {})
            }
            PaperContextIndicator(.5f, "≈50%", {}, compacting = true)
        }
    }
}

@Preview(name = "Usage rows", group = "Usage", widthDp = 340, heightDp = 260)
@Preview(name = "Usage rows large text", group = "Usage", widthDp = 340, heightDp = 420, fontScale = 1.6f)
@Composable
internal fun PaperUsageRowPreview() = PaperTheme {
    PaperSurface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperUsageRow("Контекстное окно", "58%", .58f, detail = "584,8 тыс. / 1 млн", heading = true)
            PaperUsageRow("5 часов", "11%", .11f, detail = "Сброс через 4 ч 45 мин")
            PaperUsageRow("Неделя · все модели", "0%", 0f, detail = "Сброс в чт, 16:00")
            PaperUsageRow("Неделя · Opus", "96%", .96f, detail = "Сброс в пн, 09:00")
            PaperUsageRow("Сутки", "—", null)
        }
    }
}
