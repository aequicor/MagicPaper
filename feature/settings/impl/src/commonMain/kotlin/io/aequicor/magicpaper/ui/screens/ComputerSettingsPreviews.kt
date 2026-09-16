package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.ComputerPermissionUi

internal fun permissionPreviewState(granted: Boolean = false) = ComputerPermissionUi(ComputerPermissionReport(
    PermissionPlatform.MACOS, ComputerPermission.entries.map {
        PermissionCheck(it, PermissionTarget("MagicPaper.app", "/Applications/MagicPaper.app"), granted)
    }))

@Preview(name = "macOS setup", group = "Computer settings", widthDp = 900, heightDp = 1100)
@Preview(name = "Narrow large text", group = "Computer settings", widthDp = 390, heightDp = 1100, fontScale = 2f)
@Composable
internal fun ComputerSettingsPreview() {
    PaperTheme { ComputerSettingsContent(AppSettings(computerAccess = ComputerAccess.CONTROL), true, true,
        false, permissionPreviewState(), {}, {}, {}, {}, {}) }
}

@Preview(name = "Missing permission", group = "Computer permissions", widthDp = 390, heightDp = 1000)
@Composable
internal fun ComputerPermissionMissingPreview() {
    PaperTheme { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        ComputerPermissionOnboarding(permissionPreviewState(), {}, {}, {})
    } }
}

@Preview(name = "Granted", group = "Computer permissions", widthDp = 640, heightDp = 900)
@Composable
internal fun ComputerPermissionGrantedPreview() {
    PaperTheme { ComputerPermissionOnboarding(permissionPreviewState(true), {}, {}, {}) }
}

@Preview(name = "Checking", group = "Computer permissions", widthDp = 390, heightDp = 300)
@Composable
internal fun ComputerPermissionLoadingPreview() {
    PaperTheme { ComputerPermissionOnboarding(ComputerPermissionUi(busy = true), {}, {}, {}) }
}

@Preview(name = "Error retry", group = "Computer permissions", widthDp = 390, heightDp = 300)
@Composable
internal fun ComputerPermissionErrorPreview() {
    PaperTheme { ComputerPermissionOnboarding(ComputerPermissionUi(error = "Не удалось проверить разрешения. Повторите проверку."), {}, {}, {}) }
}

@Preview(name = "Windows", group = "Computer permissions", widthDp = 390, heightDp = 600)
@Composable
internal fun ComputerPermissionWindowsPreview() {
    PaperTheme { ComputerPermissionOnboarding(ComputerPermissionUi(ComputerPermissionReport(PermissionPlatform.WINDOWS)), {}, {}, {}) }
}
