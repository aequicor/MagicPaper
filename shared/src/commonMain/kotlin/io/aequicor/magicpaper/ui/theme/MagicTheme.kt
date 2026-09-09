package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import io.aequicor.magicpaper.designsystem.PaperTheme

/**
 * Deprecated compatibility entry point. Theme ownership, fonts and semantic
 * tokens live in :designSystem; new UI must enter through [PaperTheme].
 */
@Deprecated("Use PaperTheme from :designSystem", ReplaceWith("PaperTheme(content)"))
@Composable
fun MagicPaperTheme(content: @Composable () -> Unit) = PaperTheme(content)
