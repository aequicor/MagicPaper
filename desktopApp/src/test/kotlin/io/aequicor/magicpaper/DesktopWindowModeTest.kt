package io.aequicor.magicpaper

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWindowModeTest {
    @Test
    fun windowsUsesComposeChrome() {
        assertEquals(
            DesktopWindowMode.LINUX_CUSTOM,
            desktopWindowMode("Windows 11"),
        )
    }

    @Test
    fun macKeepsSystemDecorations() {
        assertEquals(
            DesktopWindowMode.MAC_SYSTEM,
            desktopWindowMode("Darwin"),
        )
    }

    @Test
    fun otherDesktopPlatformsKeepComposeChrome() {
        assertEquals(
            DesktopWindowMode.LINUX_CUSTOM,
            desktopWindowMode("Linux"),
        )
    }
}
