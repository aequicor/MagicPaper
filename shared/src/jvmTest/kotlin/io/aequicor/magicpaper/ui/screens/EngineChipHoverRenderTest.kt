package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.use
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.domain.CodingEngine
import io.aequicor.magicpaper.ui.theme.MagicPaperTheme
import io.aequicor.magicpaper.ui.components.MagicFilterChip
import androidx.compose.material3.Text
import java.io.File
import java.io.ByteArrayInputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class EngineChipHoverRenderTest {
    @Test fun hoverAndPressCoverLabelAndPaddingInTheCreationDialog() = capture(true)

    @Test fun hoverAndPressCoverLabelAndPaddingInEngineSettings() = capture(false)

    private fun capture(dialog: Boolean) {
        val selected = mutableStateOf(CodingEngine.PI)
        val output = File("build/reports/chip-hover/${if (dialog) "dialog" else "inline"}").apply { mkdirs() }
        ImageComposeScene(780, 900, density = Density(2f)) {
            MagicPaperTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(8.dp)) {
                    if (dialog) NewCodingSessionDialog(CodingEngine.PI, {}, {})
                    else EngineChoices(selected.value) { selected.value = it }
                }
            }
        }.use { scene ->
            var frame = 0L
            fun settle() { repeat(16) { scene.render(++frame * 16_000_000L).close(); Thread.sleep(5) } }
            fun snapshot(name: String): BufferedImage {
                settle()
                val bytes = scene.render(++frame * 16_000_000L).use { image ->
                    image.encodeToData()!!.use { it.bytes }
                }
                File(output, "$name.png").writeBytes(bytes)
                return ImageIO.read(ByteArrayInputStream(bytes))
            }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun text() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Codex" } == true
            }.boundsInRoot
            fun pointer(type: PointerEventType, point: Offset) {
                scene.sendPointerEvent(type, point, type = PointerType.Mouse)
                settle()
            }
            val idle = snapshot("idle")
            val label = text()
            pointer(PointerEventType.Move, label.center)
            val hover = snapshot("hover-label")
            assertUniformBackground(hover, label)
            assertNotEquals(paddingPixel(idle, label), paddingPixel(hover, label), "Hover must reach the padding")
            pointer(PointerEventType.Move, Offset(label.left - 16f, label.center.y))
            val edgeHover = snapshot("hover-padding")
            assertUniformBackground(edgeHover, label)
            assertEquals(paddingPixel(hover, label), paddingPixel(edgeHover, label), "Moving from label to padding must retain the same highlight")
            val edge = Offset(label.left - 16f, label.center.y)
            pointer(PointerEventType.Press, edge)
            val pressed = snapshot("pressed")
            assertUniformBackground(pressed, label)
            assertNotEquals(paddingPixel(hover, label), paddingPixel(pressed, label), "Press must have visible feedback")
            pointer(PointerEventType.Release, edge)
            assertUniformBackground(snapshot("selected-hover"), text())
            val chosen = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
            assertTrue(walk(chosen).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Codex" } == true },
                "Clicking the padded edge must select Codex")
            pointer(PointerEventType.Move, Offset(770f, 890f))
            assertUniformBackground(snapshot("selected-idle"), text())
        }
    }
    private fun paddingPixel(image: BufferedImage, label: Rect): Int =
        image.getRGB((label.left - 16f).toInt(), (label.top + 2f).toInt())

    private fun assertUniformBackground(image: BufferedImage, label: Rect) {
        val color = paddingPixel(image, label)
        // Sample empty space inside the text bounds as well as all four padded sides.
        // A rectangular patch behind the label must not have a different fill.
        listOf(
            Offset(label.left + 2f, label.top + 2f),
            Offset(label.right + 16f, label.top + 2f),
            Offset(label.center.x, label.top - 4f),
            Offset(label.center.x, label.bottom + 4f),
        ).forEach { point ->
            assertEquals(color, image.getRGB(point.x.toInt(), point.y.toInt()), "Uneven chip background at $point")
        }
    }

    @Test fun disablingAHoveredChipClearsFeedbackAndPreventsClicks() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        ImageComposeScene(320, 160, density = Density(2f)) {
            MagicPaperTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                    MagicFilterChip(false, { clicks++ }, label = { Text("Codex") }, enabled = enabled.value)
                }
            }
        }.use { scene ->
            var frame = 0L
            fun snapshot(): ByteArray {
                repeat(16) { scene.render(++frame * 16_000_000L).close(); Thread.sleep(5) }
                return scene.render(++frame * 16_000_000L).use { image -> image.encodeToData()!!.use { it.bytes } }
            }
            val idle = snapshot()
            scene.sendPointerEvent(PointerEventType.Move, Offset(50f, 60f), type = PointerType.Mouse)
            assertFalse(idle.contentEquals(snapshot()), "Enabled chip must react to hover")
            enabled.value = false
            val disabled = snapshot()
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 140f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(50f, 60f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, Offset(50f, 60f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(50f, 60f), type = PointerType.Mouse)
            assertContentEquals(disabled, snapshot(), "Disabled chip must not retain or draw interaction feedback")
            assertEquals(0, clicks)
        }
    }
}
