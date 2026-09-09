package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import io.aequicor.magicpaper.designsystem.*

@Composable
internal fun FadingSingleLineText(text: String, modifier: Modifier = Modifier,
    color: Color = LocalPaperColors.current.text, fontWeight: FontWeight? = null,
    style: TextStyle = LocalPaperTypography.current.body) = PaperFadingText(text, modifier, color, fontWeight, style)
