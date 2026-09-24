package io.aequicor.magicpaper

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A running agent emits state on every streamed token, and each emission reaches
 * [DesktopAgentPanel.applyVisibility]. Showing the dock resets the reader's expansion to the
 * collapsed tab, so an emission arriving while the dock is already visible must be a no-op —
 * otherwise hovering could never open the panel for the chat and the composer.
 */
class DockVisibilityTest {
    @Test fun aVisibleDockKeepsItsStateAcrossEmissions() {
        assertEquals(DockVisibility.KEEP, dockVisibility(wanted = true, windowCreated = true, windowVisible = true),
            "a streamed token must not collapse the panel under the reader's pointer")
    }

    @Test fun aWantedAbsentDockAppearsAsTheCollapsedTab() {
        assertEquals(DockVisibility.SHOW_COLLAPSED, dockVisibility(true, windowCreated = false, windowVisible = false))
        assertEquals(DockVisibility.SHOW_COLLAPSED, dockVisibility(true, windowCreated = true, windowVisible = false),
            "a hidden dock reappears from the tab, never from the expanded card")
    }

    @Test fun anUnwantedDockIsHiddenWhateverItsWindowState() {
        for (created in listOf(true, false)) for (visible in listOf(true, false)) {
            assertEquals(DockVisibility.HIDDEN, dockVisibility(false, created, visible))
        }
    }
}
