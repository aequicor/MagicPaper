package io.aequicor.magicpaper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.awt.ComposeWindow
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.domain.ComputerActivity
import io.aequicor.magicpaper.ui.window.paperWindowIgnoreMouse
import java.awt.Color
import java.awt.Rectangle
import java.awt.Window

/** Host-only window geometry. It never owns the run or changes the selected session/draft. */
internal class DesktopComputerWindow(private val window: Window) : AutoCloseable {
    private val originalBounds = Rectangle(window.bounds)
    private val originalTop = window.isAlwaysOnTop
    init {
        val screen = window.graphicsConfiguration.bounds
        val insets = window.toolkit.getScreenInsets(window.graphicsConfiguration)
        val usable = Rectangle(screen.x + insets.left, screen.y + insets.top,
            screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
        val width = minOf(480, usable.width)
        val height = minOf(640, usable.height)
        window.setBounds(usable.x + usable.width - width, usable.y + usable.height - height, width, height)
        window.isAlwaysOnTop = true
    }
    override fun close() {
        if (!window.isDisplayable) return
        window.isAlwaysOnTop = originalTop
        window.bounds = originalBounds
    }
}

internal class DesktopComputerFeedback : AutoCloseable {
    private var activity by mutableStateOf<ComputerActivity?>(null)
    private var overlay: ComposeWindow? = null
    fun show(value: ComputerActivity) {
        val window = overlay ?: ComposeWindow().apply {
            name = "MagicPaperComputerOverlay"
            title = "MagicPaper · захват экрана"
            isUndecorated = true
            isTransparent = true
            background = Color(0, 0, 0, 0)
            focusableWindowState = false
            isAutoRequestFocus = false
            isAlwaysOnTop = true
            setContent { PaperTheme {
                activity?.let { current ->
                    PaperComputerFeedback(current.sequence,
                        current.cursorX?.let { Offset(it.toFloat(), current.cursorY!!.toFloat()) },
                        current.action in listOf("click", "double_click", "drag"), Modifier.fillMaxSize())
                }
            } }
            // AppKit creates the NSWindow asynchronously. Publish the empty, non-focusable
            // window before configuring its hit test; content is still fully transparent.
            setBounds(value.displayX, value.displayY, value.displayWidth, value.displayHeight)
            isVisible = true
            try { paperWindowIgnoreMouse(this, true) }
            catch (error: Exception) { dispose(); throw error }
        }.also { overlay = it }
        activity = value
        window.setBounds(value.displayX, value.displayY, value.displayWidth, value.displayHeight)
        window.isVisible = true
    }
    override fun close() { activity = null; overlay?.dispose(); overlay = null }
}
