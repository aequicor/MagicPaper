package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
public actual fun PaperVideo(localPath: String, description: String, aspectRatio: Float,
    onError: (Throwable?) -> Unit, modifier: Modifier) {
    PaperText("Воспроизведение доступно в macOS и Windows", modifier, role = PaperTextRole.LABEL)
}
