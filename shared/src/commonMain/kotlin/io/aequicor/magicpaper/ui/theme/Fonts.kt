package io.aequicor.magicpaper.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import io.aequicor.magicpaper.designsystem.PaperFonts

/**
 * Source-compatible bridge for unmigrated feature code. The resources and
 * actual font families are owned by :designSystem; new code uses PaperFonts.
 */
@Deprecated("Use PaperFonts from :designSystem")
object MagicFonts {
    val display: FontFamily @Composable get() = PaperFonts.display
    val text: FontFamily @Composable get() = PaperFonts.text
    val code: FontFamily @Composable get() = PaperFonts.code
}
