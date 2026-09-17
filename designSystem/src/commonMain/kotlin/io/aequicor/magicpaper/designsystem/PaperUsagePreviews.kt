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
