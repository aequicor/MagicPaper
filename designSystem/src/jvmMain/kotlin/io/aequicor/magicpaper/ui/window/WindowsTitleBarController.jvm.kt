package io.aequicor.magicpaper.ui.window

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Frame
import kotlin.math.abs

/** Bridges the Compose title bar to the native Windows non-client area. */
class WindowsTitleBarController private constructor(
    private val frame: Frame,
    private val decorations: WindowDecorations,
    private val titleBar: WindowDecorations.CustomTitleBar,
) {
    var leftInset by mutableFloatStateOf(0f)
        private set

    var rightInset by mutableFloatStateOf(0f)
        private set

    private var appliedHeight = 1f

    /** JBR measures title-bar geometry in AWT logical pixels (Compose dp). */
    fun updateHeight(height: Float) {
        if (height <= 0f || abs(height - appliedHeight) < 0.5f) return
        appliedHeight = height
        titleBar.height = height
        decorations.setCustomTitleBar(frame, titleBar)
        leftInset = titleBar.leftInset
        rightInset = titleBar.rightInset
    }

    fun forceClientHitTest(client: Boolean) {
        titleBar.forceHitTest(client)
    }

    fun dispose() {
        runCatching { decorations.setCustomTitleBar(frame, null) }
    }

    companion object {
        fun isSupported(): Boolean = JBR.isWindowDecorationsSupported()

        /** Returns null on a plain OpenJDK so the caller can keep the system title bar. */
        fun create(frame: Frame): WindowsTitleBarController? = runCatching {
            val decorations = JBR.getWindowDecorations() ?: return null
            val titleBar = decorations.createCustomTitleBar().apply {
                height = 1f
                putProperty("controls.visible", true)
                // MagicPaper uses a light parchment title bar, so native glyphs are dark.
                putProperty("controls.dark", false)
            }
            decorations.setCustomTitleBar(frame, titleBar)
            WindowsTitleBarController(frame, decorations, titleBar).apply {
                leftInset = titleBar.leftInset
                rightInset = titleBar.rightInset
            }
        }.getOrNull()
    }
}
