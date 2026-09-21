package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*

/** A saved destination may belong to a host capability that is not installed here. */
@Composable
internal fun MissingSettingsPage(onOverview: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(LocalPaperSpacing.current.lg), verticalArrangement = Arrangement.spacedBy(LocalPaperSpacing.current.md)) {
        PaperText("Раздел недоступен на этом устройстве", role = PaperTextRole.TITLE)
        PaperButton("Открыть настройки", onOverview)
    }
}

@Preview(name = "Saved desktop section", group = "Settings availability", widthDp = 390, heightDp = 320)
@Preview(name = "Saved desktop section large text", group = "Settings availability", widthDp = 390, heightDp = 420, fontScale = 2f)
@Composable
internal fun MissingSettingsPagePreview() = PaperTheme { PaperSurface { MissingSettingsPage {} } }
