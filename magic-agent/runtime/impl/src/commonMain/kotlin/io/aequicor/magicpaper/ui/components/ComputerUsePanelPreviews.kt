package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*

@Preview(name = "Background busy", group = "Automation session", widthDp = 390, heightDp = 220)
@Preview(name = "Background wide", group = "Automation session", widthDp = 900, heightDp = 200)
@Preview(name = "Background large text", group = "Automation session", widthDp = 390, heightDp = 360, fontScale = 1.5f)
@Composable
internal fun BackgroundApplicationPreview() {
    PaperTheme { renderComputerUsePanel(
        ComputerUseState("s", applicationAccess = ComputerAccess.CONTROL, busy = true, detail = "Приложение в фоне"),
        "s", true, {}, {}, {}, {},
    ) }
}

@Preview(name = "Permission error", group = "Automation session", widthDp = 390, heightDp = 360)
@Composable
internal fun ApplicationPermissionPreview() {
    PaperTheme { renderComputerUsePanel(
        ComputerUseState("s", applicationAccess = ComputerAccess.SCREEN, error = true,
            detail = "Разрешите запись экрана в системных настройках, затем отправьте новый запрос."),
        "s", true, {}, {}, {}, {},
    ) }
}

@Preview(name = "Off", group = "Automation session", widthDp = 390, heightDp = 160)
@Composable
internal fun AutomationOffPreview() {
    PaperTheme { renderComputerUsePanel(ComputerUseState(), "s", false, {}, {}, {}, {}) }
}
