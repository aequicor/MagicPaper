package io.aequicor.magicpaper.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaperShaderTest {
    @Test
    fun shaderCompilesRendersAndMovesAtDesktopAndPhoneSizes() {
        DesktopPaperRenderer().use { renderer ->
            for ((width, height) in listOf(1000 to 700, 390 to 844)) {
                val first = render(renderer, width, height, 0f)
                val later = render(renderer, width, height, 4f)
                assertTrue(first.size > 1000, "Shader must produce a textured image")
                assertFalse(first.contentEquals(later), "Changing time must change the rendered paper")
                // Reviewable outputs from the real production renderer, not a recreation of the shader.
                val output = File("build/reports/paper-animation").apply { mkdirs() }
                File(output, "paper-${width}x$height-0.png").writeBytes(first)
                File(output, "paper-${width}x$height-4.png").writeBytes(later)

                val a = ImageIO.read(first.inputStream())
                val b = ImageIO.read(later.inputStream())
                var changed = 0
                var samples = 0
                for (y in 0 until height step 8) for (x in 0 until width step 8) {
                    val before = (a.getRGB(x, y) shr 8) and 255
                    val after = (b.getRGB(x, y) shr 8) and 255
                    if (kotlin.math.abs(before - after) >= 3) changed++
                    samples++
                }
                assertTrue(changed > samples / 4, "Movement must be visible across the sheet within four seconds")
            }
            if (System.getenv("MAGICPAPER_PAPER_PREVIEW") == "1") {
                writePaperPreview { time -> render(renderer, 640, 448, time) }
            }
        }
    }

    private fun render(renderer: DesktopPaperRenderer, width: Int, height: Int, time: Float): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            CanvasDrawScope().draw(
                Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(),
                Size(width.toFloat(), height.toFloat()),
            ) {
                renderer.draw(this, time)
            }
            surface.makeImageSnapshot().use { image ->
                requireNotNull(image.encodeToData()).use { it.bytes }
            }
        }
}
