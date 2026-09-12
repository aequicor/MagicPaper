package io.aequicor.magicpaper

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowModeTest {
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
