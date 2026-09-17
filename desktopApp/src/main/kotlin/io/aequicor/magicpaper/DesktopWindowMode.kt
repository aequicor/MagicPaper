package io.aequicor.magicpaper

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement

internal enum class DesktopWindowMode {
    /** macOS: прозрачный нативный тайтлбар, системные декорации. */
    MAC_SYSTEM,
    /** Windows + JBR: decorated окно, Compose-тайтлбар встроен через JBR WindowDecorations. */
    WINDOWS_JBR_CUSTOM,
    /** Windows без JBR: системные декорации, стандартный тайтлбар. */
    WINDOWS_SYSTEM_FALLBACK,
    /** Linux: Compose рисует весь хром окна, нативных декораций нет. */
    LINUX_CUSTOM,
}

internal fun desktopWindowMode(
    osName: String,
    windowDecorationsSupported: Boolean,
): DesktopWindowMode {
    val normalized = osName.lowercase()
    return when {
        normalized.startsWith("mac") || normalized.contains("darwin") -> DesktopWindowMode.MAC_SYSTEM
        normalized.startsWith("windows") && windowDecorationsSupported -> DesktopWindowMode.WINDOWS_JBR_CUSTOM
        normalized.startsWith("windows") -> DesktopWindowMode.WINDOWS_SYSTEM_FALLBACK
        else -> DesktopWindowMode.LINUX_CUSTOM
    }
}

/** Native fullscreen is distinct from maximizing or resizing to the display bounds. */
internal fun desktopToolbarHeight(mode: DesktopWindowMode, placement: WindowPlacement, fontScale: Float): Dp =
    if (placement == WindowPlacement.Fullscreen) 0.dp
    else maxOf(if (mode == DesktopWindowMode.MAC_SYSTEM) 28.dp else 40.dp, (16f * fontScale + 8f).dp)
