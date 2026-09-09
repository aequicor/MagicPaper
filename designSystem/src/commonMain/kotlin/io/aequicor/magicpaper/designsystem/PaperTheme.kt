package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.aequicor.magicpaper.designsystem.resources.Res
import io.aequicor.magicpaper.designsystem.resources.cormorant_garamond_bold
import io.aequicor.magicpaper.designsystem.resources.cormorant_garamond_medium
import io.aequicor.magicpaper.designsystem.resources.cormorant_garamond_semibold
import io.aequicor.magicpaper.designsystem.resources.jetbrains_mono_medium
import io.aequicor.magicpaper.designsystem.resources.jetbrains_mono_regular
import io.aequicor.magicpaper.designsystem.resources.literata_italic
import io.aequicor.magicpaper.designsystem.resources.literata_medium
import io.aequicor.magicpaper.designsystem.resources.literata_regular
import io.aequicor.magicpaper.designsystem.resources.literata_semibold
import org.jetbrains.compose.resources.Font

/** Semantic colours. Decorative values are never used as text defaults. */
@Immutable
public data class PaperColors(
    public val canvas: Color = Color(0xFFF3EBDD),
    public val surface: Color = Color(0xFFFFFDFA),
    public val raisedSurface: Color = Color(0xFFE5E2E3),
    public val text: Color = Color(0xFF35242D),
    public val secondaryText: Color = Color(0xFF625C70), // 4.5:1 on paper
    public val action: Color = Color(0xFF62558D), // 4.5:1 with white label
    public val actionOn: Color = Color(0xFFFFFBF3),
    public val selected: Color = Color(0xFFDDD2EB),
    public val border: Color = Color(0xFFB7AD9D),
    public val focus: Color = Color(0xFF62558D),
    public val error: Color = Color(0xFF864747),
    public val errorSurface: Color = Color(0xFFF5DDD7),
    public val disabled: Color = Color(0xFFAAA39B),
    public val success: Color = Color(0xFF38553C),
    public val successSurface: Color = Color(0xFFCCD8CB),
    public val accentSurface: Color = Color(0xFFEABBB1),
    public val hover: Color = Color(0x1235242D),
    public val pressed: Color = Color(0x2435242D),
    public val tooltipSurface: Color = Color(0xFF3B2D23),
    public val tooltipText: Color = Color(0xFFF4EBDD),
    public val composerHighlight: Color = Color(0xFFF0E8DE),
    public val composerFocused: Color = Color(0xFFECE2D6),
    public val composerSurface: Color = Color(0xFFE5DDD1),
    public val depthShadow: Color = Color(0xFF3B2415),
    public val userMessageSurface: Color = Color(0xFFD8C9E7),
    public val agentMessageSurface: Color = Color(0xFFDDD8E0),
    public val activityRed: Color = Color(0xFFE89B99),
    public val activityYellow: Color = Color(0xFFE8C66C),
    public val activityGreen: Color = Color(0xFF8FC7A2),
    public val activityYellowEdge: Color = Color(0xFF80621E),
    public val systemText: Color = Color(0xFF713A86),
    public val systemSurface: Color = Color(0xFFF0E2F4),
)

@Immutable
public data class PaperSpacing(
    public val xxs: androidx.compose.ui.unit.Dp = 4.dp,
    public val xs: androidx.compose.ui.unit.Dp = 8.dp,
    public val sm: androidx.compose.ui.unit.Dp = 12.dp,
    public val md: androidx.compose.ui.unit.Dp = 16.dp,
    public val lg: androidx.compose.ui.unit.Dp = 24.dp,
    public val xl: androidx.compose.ui.unit.Dp = 32.dp,
)

@Immutable
public data class PaperTypography(
    public val display: TextStyle,
    public val headline: TextStyle,
    public val title: TextStyle,
    public val body: TextStyle,
    public val label: TextStyle,
    public val code: TextStyle,
    public val chrome: TextStyle,
)

public val LocalPaperColors = staticCompositionLocalOf { PaperColors() }
public val LocalPaperSpacing = staticCompositionLocalOf { PaperSpacing() }
public val LocalPaperTypography = staticCompositionLocalOf {
    PaperTypography(TextStyle.Default, TextStyle.Default, TextStyle.Default, TextStyle.Default, TextStyle.Default, TextStyle.Default, TextStyle.Default)
}

/** Brand font families, owned with their resources by :designSystem. */
public object PaperFonts {
    val display: FontFamily @Composable get() = FontFamily(
        Font(Res.font.cormorant_garamond_medium, FontWeight.Medium),
        Font(Res.font.cormorant_garamond_semibold, FontWeight.SemiBold),
        Font(Res.font.cormorant_garamond_bold, FontWeight.Bold),
    )
    val text: FontFamily @Composable get() = FontFamily(
        Font(Res.font.literata_regular, FontWeight.Normal),
        Font(Res.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.literata_medium, FontWeight.Medium),
        Font(Res.font.literata_semibold, FontWeight.SemiBold),
    )
    val code: FontFamily @Composable get() = FontFamily(
        Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

@Composable
private fun paperTypography(): PaperTypography = PaperTypography(
    display = TextStyle(fontFamily = PaperFonts.display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headline = TextStyle(fontFamily = PaperFonts.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    title = TextStyle(fontFamily = PaperFonts.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = PaperFonts.text, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    label = TextStyle(fontFamily = PaperFonts.text, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    code = TextStyle(fontFamily = PaperFonts.code, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    chrome = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
)

/**
 * Installs MagicPaper's public semantic tokens. Material is intentionally
 * contained here so consumers neither import nor receive Material types.
 */
@Composable
public fun PaperTheme(content: @Composable () -> Unit) {
    val colors = PaperColors()
    val typography = paperTypography()
    val policy = rememberPaperPlatformPolicy()
    val materialTypography = Typography(
        displayLarge = typography.display, headlineLarge = typography.headline,
        titleMedium = typography.title, bodyLarge = typography.body, bodyMedium = typography.body,
        labelMedium = typography.label, bodySmall = typography.body,
    )
    val materialColors = lightColorScheme(
        primary = colors.action, onPrimary = colors.actionOn, primaryContainer = colors.selected,
        onPrimaryContainer = colors.text, secondary = colors.success, onSecondary = colors.actionOn,
        secondaryContainer = colors.successSurface, onSecondaryContainer = colors.success,
        tertiary = colors.error, tertiaryContainer = colors.accentSurface, onTertiaryContainer = colors.text,
        background = colors.canvas, onBackground = colors.text, surface = colors.surface,
        onSurface = colors.text, surfaceVariant = colors.raisedSurface,
        onSurfaceVariant = colors.secondaryText, outline = colors.border, error = colors.error,
        errorContainer = colors.errorSurface,
    )
    CompositionLocalProvider(
        LocalPaperColors provides colors,
        LocalPaperSpacing provides PaperSpacing(),
        LocalPaperTypography provides typography,
        LocalPaperPlatformPolicy provides policy,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = materialTypography,
            shapes = Shapes(RoundedCornerShape(6.dp), RoundedCornerShape(10.dp), RoundedCornerShape(14.dp)),
            content = content,
        )
    }
}

@Composable
public fun paperTextStyle(role: PaperTextRole): TextStyle = when (role) {
    PaperTextRole.DISPLAY -> LocalPaperTypography.current.display
    PaperTextRole.HEADLINE -> LocalPaperTypography.current.headline
    PaperTextRole.TITLE -> LocalPaperTypography.current.title
    PaperTextRole.BODY -> LocalPaperTypography.current.body
    PaperTextRole.LABEL -> LocalPaperTypography.current.label
    PaperTextRole.CODE -> LocalPaperTypography.current.code
    PaperTextRole.CHROME -> LocalPaperTypography.current.chrome
}

/** Shared outline vocabulary for panels and controls. */
public object PaperShapes {
    public val control = RoundedCornerShape(6.dp)
    public val panel = RoundedCornerShape(10.dp)
    public val dialog = RoundedCornerShape(14.dp)
}
