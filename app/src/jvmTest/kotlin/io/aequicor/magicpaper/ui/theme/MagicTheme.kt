package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.ui.components.*

/** Renderer assembly for integration previews without application DI. */
@Composable
fun MagicPaperTheme(content: @Composable () -> Unit) = PaperTheme {
    CompositionLocalProvider(LocalModelPresentation provides DefaultModelPresentation,
        LocalChatPresentation provides DefaultChatPresentation,
        LocalCodingPresentation provides DefaultCodingPresentation, content = content)
}
