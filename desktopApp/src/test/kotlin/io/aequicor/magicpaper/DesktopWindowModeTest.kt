package io.aequicor.magicpaper

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowModeTest {
    @Test fun onlyNativeFullscreenHidesToolbarAndLargeTextStillFits() {
        for (mode in DesktopWindowMode.entries) {
            assertEquals(0.dp, desktopToolbarHeight(mode, WindowPlacement.Fullscreen, 1f))
            assertEquals(0.dp, desktopToolbarHeight(mode, WindowPlacement.Fullscreen, 2f))
            val normal = if (mode == DesktopWindowMode.MAC_SYSTEM) 28.dp else 40.dp
            assertEquals(normal, desktopToolbarHeight(mode, WindowPlacement.Floating, 1f))
            assertEquals(normal, desktopToolbarHeight(mode, WindowPlacement.Maximized, 1f))
            assertEquals(40.dp, desktopToolbarHeight(mode, WindowPlacement.Floating, 2f))
        }
    }

    @Test
    fun windowsUsesJbrCustomTitleBarWhenAvailable() {
        assertEquals(
            DesktopWindowMode.WINDOWS_JBR_CUSTOM,
            desktopWindowMode("Windows 11", windowDecorationsSupported = true),
        )
    }

    @Test
    fun windowsFallsBackToSystemTitleBarWithoutJbr() {
        assertEquals(
            DesktopWindowMode.WINDOWS_SYSTEM_FALLBACK,
            desktopWindowMode("Windows 10", windowDecorationsSupported = false),
        )
    }

    @Test
    fun macKeepsSystemDecorations() {
        assertEquals(
            DesktopWindowMode.MAC_SYSTEM,
            desktopWindowMode("Darwin", windowDecorationsSupported = true),
        )
    }

    @Test
    fun otherDesktopPlatformsKeepComposeChrome() {
        assertEquals(
            DesktopWindowMode.LINUX_CUSTOM,
            desktopWindowMode("Linux", windowDecorationsSupported = false),
        )
    }
}
