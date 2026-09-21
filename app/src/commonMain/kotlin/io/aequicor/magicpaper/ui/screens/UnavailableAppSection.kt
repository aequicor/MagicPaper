package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*

@Composable
internal fun UnavailableAppSection(onChat: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(LocalPaperSpacing.current.lg), verticalArrangement = Arrangement.spacedBy(LocalPaperSpacing.current.md)) {
        PaperText("Раздел недоступен на этом устройстве", role = PaperTextRole.TITLE)
        PaperButton("Открыть чат", onChat)
    }
}

@Preview(name = "Saved project route", group = "Platform sections", widthDp = 390, heightDp = 420)
@Preview(name = "Saved project route large text", group = "Platform sections", widthDp = 390, heightDp = 520, fontScale = 2f)
@Composable
internal fun UnavailableAppSectionPreview() = PaperTheme { PaperSurface { UnavailableAppSection {} } }
