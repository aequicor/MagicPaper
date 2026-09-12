package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

@Composable
public actual fun rememberPaperPlatformPolicy(): PaperPlatformPolicy = remember {
    when (System.getProperty("os.name").lowercase()) {
        "mac os x", "macos" -> PaperPlatformPolicy(PaperPlatform.MACOS, PaperDensity(28.dp, 28.dp, 30.dp, 16.dp), "⌘", "⌘,", true)
        else -> when {
            System.getProperty("os.name").contains("Windows", ignoreCase = true) ->
                PaperPlatformPolicy(PaperPlatform.WINDOWS, PaperDensity(32.dp, 32.dp, 32.dp, 16.dp), "Ctrl", "Ctrl+,", false)
            System.getProperty("os.name").contains("Linux", ignoreCase = true) ->
                PaperPlatformPolicy(PaperPlatform.LINUX, PaperDensity(32.dp, 32.dp, 32.dp, 16.dp), "Ctrl", "Ctrl+,", false)
            else -> PaperPlatformPolicy.Fallback
        }
    }
}
