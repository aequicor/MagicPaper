package io.aequicor.magicpaper

import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.magicpaper.ui.window.paperWindowFloatOnAllSpaces
import io.aequicor.magicpaper.ui.window.paperWindowIgnoreMouse
import java.awt.Window
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dock must be the platform's floating panel, not a second application window: on Windows a
 * plain frame gets its own taskbar button and Alt+Tab entry, and on macOS a window stays behind
 * on the Space it opened on. Isolated window only; no runtime or persisted user session is loaded.
 */
class DesktopAgentDockWindowTest {
    @Test fun theDockIsAFloatingPanelThatFollowsTheReaderAcrossSpaces() {
        assumeTrue(System.getProperty("magicpaper.window.native") == "true")
        var window: ComposeWindow? = null
        try {
            SwingUtilities.invokeAndWait {
                window = dockWindow().apply {
                    setBounds(40, 80, 288, 180)
                    isVisible = true
                }
            }
            val dock = window!!
            SwingUtilities.invokeAndWait {
                assertEquals(Window.Type.UTILITY, dock.type, "a utility window has no taskbar button or Alt+Tab entry")
                assertTrue(dock.isAlwaysOnTop)
                assertFalse(dock.isAutoRequestFocus, "appearing must not take focus from the reader's application")
                assertEquals(false, dock.rootPane.getClientProperty("Window.hidesOnDeactivate"),
                    "the panel exists for the time MagicPaper is inactive")
            }
            // Off the event thread, as the host asks: AppKit throws if it refuses the behaviour.
            val joined = paperWindowFloatOnAllSpaces(dock)
            assertEquals(System.getProperty("os.name").startsWith("Mac"), joined,
                "only macOS has Spaces to join")
            // The AppKit window lookup is shared with hit-test transparency; both must resolve it.
            SwingUtilities.invokeAndWait {
                paperWindowIgnoreMouse(dock, true)
                paperWindowIgnoreMouse(dock, false)
            }
        } finally {
            SwingUtilities.invokeAndWait { window?.dispose() }
        }
    }
}
