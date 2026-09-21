package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.ui.components.*

/**
 * Root feature renderer assembly, also usable by isolated previews without application DI.
 * Additional presentations are supplied by their test owner, without a native default.
 */
@Composable
fun MagicPaperTheme(
    vararg providers: ProvidedValue<*>,
    content: @Composable () -> Unit,
) = PaperTheme {
    CompositionLocalProvider(LocalModelPresentation provides DefaultModelPresentation,
        LocalChatPresentation provides DefaultChatPresentation,
        *providers, content = content)
}
