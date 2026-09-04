package io.aequicor.magicpaper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра «магической бумаги»: мягкие пастельные тона — пергамент,
 * припылённая лаванда, шалфей и чернильный текст.
 */
private val Parchment = Color(0xFFF5EFE3)
private val PaperSurface = Color(0xFFFBF7EE)
private val InkPrimary = Color(0xFF7C6FA7)
private val InkOnPrimary = Color(0xFFFFFBF3)
private val InkSecondary = Color(0xFF9AA98F)
private val InkTertiary = Color(0xFFC9A9A2)
private val InkText = Color(0xFF3E3950)
private val InkMuted = Color(0xFF7A7386)
private val InkOutline = Color(0xFFD8CFBE)
private val InkError = Color(0xFFA96A6A)
private val InkUserBubble = Color(0xFFE9E2F4)
private val InkAgentBubble = Color(0xFFFBF7EE)

val MagicColors = lightColorScheme(
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkUserBubble,
    onPrimaryContainer = InkText,
    secondary = InkSecondary,
    onSecondary = InkText,
    secondaryContainer = Color(0xFFEAEFDE),
    tertiary = InkTertiary,
    tertiaryContainer = Color(0xFFF3E3DF),
    background = Parchment,
    onBackground = InkText,
    surface = PaperSurface,
    onSurface = InkText,
    surfaceVariant = Color(0xFFEFE7D6),
    onSurfaceVariant = InkMuted,
    outline = InkOutline,
    outlineVariant = Color(0xFFE7DFCF),
    error = InkError,
)

val MagicTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

val MagicShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun MagicPaperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MagicColors,
        typography = MagicTypography,
        shapes = MagicShapes,
        content = content,
    )
}
