package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.*

@Preview(name = "Available and unavailable", group = "Session media tools", widthDp = 390, heightDp = 240)
@Preview(name = "Large text", group = "Session media tools", widthDp = 390, heightDp = 460, fontScale = 2f)
@Composable
internal fun SessionMediaToolOptionsPreview() {
    var permissions by remember { mutableStateOf(SessionMediaTools()) }
    PaperTheme { PaperSurface(Modifier.fillMaxSize()) { Column {
        SessionMediaToolOptions(permissions, mapOf(
            MediaKind.IMAGE to MediaConnectionStatus(MediaKind.IMAGE, MediaAvailability.AVAILABLE),
            MediaKind.VIDEO to MediaConnectionStatus(MediaKind.VIDEO, MediaAvailability.UNAVAILABLE)),
            { kind, enabled -> permissions = permissions.withEnabled(kind, enabled) }, {})
    } } }
}

@Preview(name = "Disabled and unchecked", group = "Session media tools", widthDp = 390, heightDp = 260)
@Composable
internal fun DisabledSessionMediaToolOptionsPreview() = PaperTheme { PaperSurface(Modifier.fillMaxSize()) { Column {
    SessionMediaToolOptions(SessionMediaTools(images = false), emptyMap(), { _, _ -> }, {}, enabled = false)
} } }
