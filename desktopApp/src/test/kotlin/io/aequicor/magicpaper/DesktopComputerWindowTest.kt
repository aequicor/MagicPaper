package io.aequicor.magicpaper

import io.aequicor.magicpaper.domain.ComputerActivity
import io.aequicor.magicpaper.ui.window.paperWindowIgnoreMouse
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Isolated windows only; no runtime or persisted user session is loaded. */
class DesktopComputerWindowTest {
    @Test fun realPointerPassesThroughPinnedWindowToFixtureButton() {
        assumeTrue(System.getProperty("magicpaper.computer.input.native") == "true")
        if (System.getProperty("os.name").startsWith("Mac")) {
            val libraryClass = Class.forName("com.sun.jna.NativeLibrary")
            val library = libraryClass.getMethod("getInstance", String::class.java)
                .invoke(null, "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
            val function = libraryClass.getMethod("getFunction", String::class.java).invoke(library, "AXIsProcessTrusted")
            val trusted = function.javaClass.getMethod("invokeInt", Array<Any>::class.java).invoke(function, emptyArray<Any>()) as Int
            assertTrue(trusted and 0xff != 0, "Native pointer acceptance requires Accessibility permission for the test JVM; no permission prompt is triggered")
        }
        var clicked = java.util.concurrent.CountDownLatch(1)
        val coverClicks = java.util.concurrent.atomic.AtomicInteger()
        var target: JFrame? = null
        var cover: JFrame? = null
        val pointer = java.awt.MouseInfo.getPointerInfo().location
        val robot = java.awt.Robot()
        try {
            SwingUtilities.invokeAndWait {
                target = JFrame("Pointer target fixture").apply {
                    isUndecorated = true
                    setBounds(100, 100, 320, 240)
                    add(javax.swing.JButton("Fixture only").apply { addActionListener { clicked.countDown() } })
                    isAlwaysOnTop = true; isVisible = true
                    toFront(); requestFocus()
                }
            }
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                java.awt.Desktop.getDesktop().requestForeground(true)
            }
            fun click() {
                robot.mouseMove(260, 220); robot.delay(200)
                try { robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.delay(50) }
                finally { robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }
            }
            click()
            assertTrue(clicked.await(3, java.util.concurrent.TimeUnit.SECONDS), "Positive control: Robot must click the exposed fixture button")
            clicked = java.util.concurrent.CountDownLatch(1)
            SwingUtilities.invokeAndWait {
                cover = JFrame("Pointer excluded fixture").apply {
                    isUndecorated = true; setBounds(100, 100, 320, 240)
                    add(javax.swing.JButton("Cover").apply { addActionListener { coverClicks.incrementAndGet() } })
                    isAutoRequestFocus = false
                    isAlwaysOnTop = true; isVisible = true
                }
                paperWindowIgnoreMouse(cover!!, true)
            }
            click()
            assertTrue(clicked.await(3, java.util.concurrent.TimeUnit.SECONDS), "Click must reach the button beneath the pinned chat window; cover received ${coverClicks.get()}")
        } finally {
            SwingUtilities.invokeAndWait { cover?.dispose(); target?.dispose() }
            robot.mouseMove(pointer.x, pointer.y)
        }
    }

    @Test fun compactWindowRestoresBoundsAndAlwaysOnTopAndOverlayDoesNotTakeFocus() {
        assumeTrue(System.getProperty("magicpaper.window.native") == "true")
        SwingUtilities.invokeAndWait {
            val window = JFrame("MagicPaper computer-use fixture")
            val feedback = DesktopComputerFeedback()
            try {
                window.setBounds(80, 100, 850, 700)
                window.isAutoRequestFocus = false
                window.isVisible = true
                val original = window.bounds
                DesktopComputerWindow(window).use {
                    assertTrue(window.isAlwaysOnTop)
                    assertTrue(window.width <= 480)
                    paperWindowIgnoreMouse(window, true)
                    paperWindowIgnoreMouse(window, false)
                    feedback.show(ComputerActivity(1, 0, 0, 640, 480, "click", 160, 100))
                    assertFalse(java.awt.Window.getWindows().single { it.name == "MagicPaperComputerOverlay" }.isFocusableWindow)
                }
                assertFalse(window.isAlwaysOnTop)
                assertEquals(original, window.bounds)
            } finally { feedback.close(); window.dispose() }
        }
    }
}
