package io.aequicor.magicpaper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A running agent emits state on every streamed token. Those updates preserve a visible dock,
 * but may not show it over the main MagicPaper window while that window is foreground.
 */
class DockVisibilityTest {
    @Test fun aVisibleDockKeepsItsStateAcrossEmissions() {
        assertEquals(DockVisibility.KEEP, dockVisibility(wanted = true, ownerForeground = false,
            windowCreated = true, windowVisible = true),
            "a streamed token must not collapse the panel under the reader's pointer")
    }

    @Test fun aWantedAbsentDockAppearsAsTheCollapsedTab() {
        assertEquals(DockVisibility.SHOW_COLLAPSED, dockVisibility(true, ownerForeground = false,
            windowCreated = false, windowVisible = false))
        assertEquals(DockVisibility.SHOW_COLLAPSED, dockVisibility(true, ownerForeground = false,
            windowCreated = true, windowVisible = false),
            "a hidden dock reappears from the tab, never from the expanded card")
    }

    @Test fun foregroundMainWindowHidesTheDockEvenWhileAgentsStream() {
        for (created in listOf(true, false)) for (visible in listOf(true, false)) {
            assertEquals(DockVisibility.HIDDEN, dockVisibility(true, ownerForeground = true,
                windowCreated = created, windowVisible = visible))
        }
    }

    @Test fun anUnwantedDockIsHiddenWhateverItsWindowState() {
        for (created in listOf(true, false)) for (visible in listOf(true, false)) {
            assertEquals(DockVisibility.HIDDEN, dockVisibility(false, ownerForeground = false,
                windowCreated = created, windowVisible = visible))
        }
    }
}
