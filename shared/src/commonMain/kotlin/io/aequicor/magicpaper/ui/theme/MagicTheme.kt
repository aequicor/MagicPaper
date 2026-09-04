package io.aequicor.magicpaper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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

/**
 * Типографика «магической бумаги»: все 15 ролей заданы явно, чтобы ни одна
 * не проваливалась в дефолтный системный шрифт. Дисплейные роли (крупные
 * заголовки, бренд) — Cormorant Garamond; чтение и подписи — Literata;
 * код — JetBrains Mono. Кегли заголовков подняты на 1–2sp относительно
 * системного дефолта: у гарамона компактный кегль, компенсируем.
 *
 * Семейства загружаются из ресурсов композиции (@Composable), поэтому
 * типографика собирается в композиционном контексте.
 */
val MagicTypography: Typography
    @Composable get() = Typography(
        // Дисплейные роли — только для крупных размеров (Cormorant читается от ~17sp).
        displayLarge = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
        displayMedium = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
        displaySmall = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
        headlineLarge = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        headlineMedium = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = MagicFonts.display, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
        // titleSmall уже в «текстовой» зоне — сериф здесь мельчит, берём Literata.
        titleSmall = TextStyle(fontFamily = MagicFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        // Текст — Literata: «книжное» чтение на пергаменте.
        bodyLarge = TextStyle(fontFamily = MagicFonts.text, fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = MagicFonts.text, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = MagicFonts.text, fontSize = 13.sp, lineHeight = 19.sp),
        // Подписи и кнопки — Literata средним весом: единый стиль без серифной «тяжести».
        labelLarge = TextStyle(fontFamily = MagicFonts.text, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = MagicFonts.text, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        labelSmall = TextStyle(fontFamily = MagicFonts.text, fontWeight = FontWeight.Medium, fontSize = 12.sp),
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
