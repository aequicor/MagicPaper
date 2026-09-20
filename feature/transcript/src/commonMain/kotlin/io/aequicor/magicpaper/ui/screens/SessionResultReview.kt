package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*

@Composable
fun SessionResultReview(verified: Boolean, onVerified: (Boolean) -> Unit) {
    val spacing = LocalPaperSpacing.current
    Column(Modifier.fillMaxWidth().padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        PaperText(if (verified) "Результат проверен" else "Рекомендуется ручное тестирование",
            style = LocalPaperTypography.current.chrome)
        Row(Modifier.fillMaxWidth().paperToggleable(value = verified, onValueChange = onVerified)
            .padding(vertical = spacing.xs), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PaperCheck(verified, onCheckedChange = null)
            PaperText("Проверено вручную", style = LocalPaperTypography.current.chrome)
        }
    }
}

@Preview(name = "Нужна проверка", group = "Session result", widthDp = 340)
@Composable
internal fun SessionResultPendingPreview() {
    PaperTheme { SessionResultReview(false) {} }
}

@Preview(name = "Проверено", group = "Session result", widthDp = 340)
@Composable
internal fun SessionResultVerifiedPreview() {
    PaperTheme { SessionResultReview(true) {} }
}
