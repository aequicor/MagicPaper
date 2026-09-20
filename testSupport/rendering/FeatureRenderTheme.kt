package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.domain.UnavailableCodingPresentation
import io.aequicor.magicpaper.ui.components.CodingPresentation
import io.aequicor.magicpaper.ui.components.*

/**
 * Root feature renderer assembly, also usable by isolated previews without application DI.
 * The coding panel defaults to the unavailable implementation so this fixture stays usable
 * on a host without coding; a module that owns the real one passes it in.
 */
@Composable
fun MagicPaperTheme(
    codingPresentation: CodingPresentation = UnavailableCodingPresentation,
    content: @Composable () -> Unit,
) = PaperTheme {
    CompositionLocalProvider(LocalModelPresentation provides DefaultModelPresentation,
        LocalChatPresentation provides DefaultChatPresentation,
        LocalCodingPresentation provides codingPresentation, content = content)
}
