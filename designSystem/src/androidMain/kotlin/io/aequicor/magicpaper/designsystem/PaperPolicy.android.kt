package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

@Composable
public actual fun rememberPaperPlatformPolicy(): PaperPlatformPolicy = remember {
    PaperPlatformPolicy(PaperPlatform.ANDROID, PaperDensity(48.dp, 48.dp, 48.dp, 24.dp), "Ctrl", "Ctrl+,", false)
}
