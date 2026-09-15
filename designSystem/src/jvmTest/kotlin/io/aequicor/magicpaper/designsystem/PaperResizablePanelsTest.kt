package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PaperResizablePanelsTest {

    /** The grip sits on the divider, and the divider keeps marking the edge where messages fade. */
    @Test fun grabPillIsCentredOnTheDividerLineAtTheTranscriptEdge() {
        val width = 800
        val height = 400
        val middle = height / 2
        val far = height - 40
        ImageComposeScene(width, height) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    PaperResizablePanels(sidebar = { modifier ->
                        Box(modifier.fillMaxHeight().background(Color.White))
                    }) {
                        // An opaque transcript: it begins exactly where messages blur away.
                        Box(Modifier.fillMaxSize().background(Color(0xFFC080C0)))
                    }
                }
            }
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L).close() }
            val bytes = scene.render(100_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
            File("build/reports/design-system").apply { mkdirs() }
                .resolve("resizable-panels-handle.png").writeBytes(bytes)
            val pixels = ImageIO.read(ByteArrayInputStream(bytes))
            fun isBorder(x: Int, y: Int) = pixels.closeTo(x, y, 0xB7, 0xAD, 0x9D)
            fun isTranscript(x: Int, y: Int) = pixels.closeTo(x, y, 0xC0, 0x80, 0xC0)

            val line = (0 until width).filter { isBorder(it, far) }
            assertEquals(1, line.size, "One vertical line must separate the panels")
            val lineX = line.single()
            assertTrue(isTranscript(lineX + 1, far), "The transcript must begin right behind the line so its blur starts there")

            val pill = (0 until width).filter { isBorder(it, middle) }
            assertEquals(listOf(lineX - 1, lineX, lineX + 1), pill,
                "The 3 dp grip must be centred on the line instead of leaning left of it")
            assertTrue(isBorder(lineX + 1, middle), "The grip must be painted above the transcript edge")
        }
    }

    /** A full-width panel divider runs into the vertical edge line instead of stopping short. */
    @Test fun fullWidthSidebarDividerReachesTheVerticalLine() {
        val width = 800
        val height = 400
        val dividerY = 80
        ImageComposeScene(width, height) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    PaperResizablePanels(sidebar = { modifier ->
                        Column(modifier.fillMaxHeight()) {
                            Spacer(Modifier.height(dividerY.dp))
                            PaperDivider()
                        }
                    }) {
                        Box(Modifier.fillMaxSize().background(Color(0xFFC080C0)))
                    }
                }
            }
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L).close() }
            val bytes = scene.render(100_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
            File("build/reports/design-system").apply { mkdirs() }
                .resolve("resizable-panels-divider-meet.png").writeBytes(bytes)
            val pixels = ImageIO.read(ByteArrayInputStream(bytes))
            fun isBorder(x: Int, y: Int) = pixels.closeTo(x, y, 0xB7, 0xAD, 0x9D)

            val lineX = (0 until width).filter { isBorder(it, height - 40) }.single()
            val gap = (0..lineX).filterNot { isBorder(it, dividerY) }
            assertTrue(gap.isEmpty(), "The horizontal divider must reach the vertical line, gap at $gap")
        }
    }

    private fun BufferedImage.closeTo(x: Int, y: Int, red: Int, green: Int, blue: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        val rgb = getRGB(x, y)
        return abs(((rgb shr 16) and 255) - red) <= 12 && abs(((rgb shr 8) and 255) - green) <= 12 &&
            abs((rgb and 255) - blue) <= 12
    }
}
