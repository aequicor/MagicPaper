package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.*

@Preview(name = "Off narrow", group = "Automation access", widthDp = 390, heightDp = 800)
@Preview(name = "Off wide", group = "Automation access", widthDp = 900, heightDp = 650)
@Preview(name = "Large text", group = "Automation access", widthDp = 390, heightDp = 1100, fontScale = 1.5f)
@Composable
internal fun AutomationSettingsPreview() {
    var settings by remember { mutableStateOf(AppSettings()) }
    PaperTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AutomationSettings(settings) { settings = it }
        }
    }
}

@Preview(name = "Independent scopes", group = "Automation access", widthDp = 390, heightDp = 800)
@Composable
internal fun AutomationSelectedPreview() {
    PaperTheme { AutomationSettings(AppSettings(computerAccess = ComputerAccess.SCREEN, applicationAccess = ComputerAccess.CONTROL)) {} }
}

@Preview(name = "Unsupported platform", group = "Automation access", widthDp = 390, heightDp = 850)
@Composable
internal fun AutomationUnavailablePreview() {
    PaperTheme { AutomationSettings(AppSettings(), computerEnabled = false, applicationEnabled = false) {} }
}

@Preview(name = "Saving", group = "Automation access", widthDp = 390, heightDp = 850)
@Composable
internal fun AutomationSavingPreview() {
    PaperTheme { AutomationSettings(AppSettings(), saving = true) {} }
}
