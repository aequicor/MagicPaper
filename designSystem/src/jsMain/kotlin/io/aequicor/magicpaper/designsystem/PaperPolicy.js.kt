package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
public actual fun rememberPaperPlatformPolicy(): PaperPlatformPolicy = remember { PaperPlatformPolicy.Fallback.copy(platform = PaperPlatform.WEB) }
