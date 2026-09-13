package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

@Composable
public actual fun rememberPaperPlatformPolicy(): PaperPlatformPolicy = remember {
    when (System.getProperty("os.name").lowercase()) {
        "mac os x", "macos" -> PaperPlatformPolicy.desktop(PaperPlatform.MACOS)
        else -> when {
            System.getProperty("os.name").contains("Windows", ignoreCase = true) ->
                PaperPlatformPolicy.desktop(PaperPlatform.WINDOWS)
            System.getProperty("os.name").contains("Linux", ignoreCase = true) ->
                PaperPlatformPolicy(PaperPlatform.LINUX, PaperDensity(32.dp, 32.dp, 32.dp, 16.dp), "Ctrl", "Ctrl+,", false)
            else -> PaperPlatformPolicy.Fallback
        }
    }
}
