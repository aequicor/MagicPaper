package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.data.coding.backendCatalog
import io.aequicor.magicpaper.designsystem.PaperDialog
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.CodingEngine

@Composable
internal fun SessionEngineSettingsDialog(
    engine: CodingEngine,
    canChange: Boolean,
    onChange: (CodingEngine) -> Unit,
    onDismiss: () -> Unit,
    unavailableReason: String = "Дождитесь завершения текущей работы, чтобы сменить движок.",
) {
    var selected by remember(engine) { mutableStateOf(engine) }
    PaperDialog(
        title = "Параметры сессии",
        onDismissRequest = onDismiss,
        confirmLabel = "Сменить движок",
        onConfirm = { onChange(selected); onDismiss() },
        confirmEnabled = canChange && selected != engine,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperText("Движок", role = PaperTextRole.TITLE)
            NativeEngineChoices(backendCatalog.descriptors, selected, enabled = canChange) { selected = it }
            backendCatalog.descriptors.firstOrNull { it.engine == selected }?.let {
                PaperText(it.summary, role = PaperTextRole.LABEL)
            }
            PaperText(
                if (canChange) "История сессии сохранится. Следующий запрос продолжит работу с новым движком."
                else unavailableReason,
                role = PaperTextRole.LABEL,
            )
        }
    }
}

@Preview(name = "Idle", group = "Session settings", widthDp = 520, heightDp = 440)
@Preview(name = "Idle narrow", group = "Session settings", widthDp = 390, heightDp = 500)
@Composable
private fun SessionEngineSettingsIdlePreview() {
    PaperTheme {
        SessionEngineSettingsDialog(CodingEngine.PI, true, {}, {})
    }
}

@Preview(name = "Unavailable", group = "Session settings", widthDp = 390, heightDp = 500)
@Composable
private fun SessionEngineSettingsUnavailablePreview() {
    PaperTheme {
        SessionEngineSettingsDialog(CodingEngine.PI, false, {}, {})
    }
}
