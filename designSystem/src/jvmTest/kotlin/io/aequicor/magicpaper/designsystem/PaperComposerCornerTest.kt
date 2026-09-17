package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperComposerCornerTest {
    @Test fun cornerRevealsOnHoverAndKeyboardFocusAndTogglesWithoutMoving() {
        for (platform in listOf(PaperPlatform.MACOS, PaperPlatform.WINDOWS))
        for ((density, scale) in listOf(1f to 1f, 2f to 1f, 1.25f to 2f)) {
            val case = "${platform.name.lowercase()}-$density-$scale"
            val focus = FocusRequester()
            lateinit var inputMode: InputModeManager
            val scene = onPaperUi { ImageComposeScene((360 * density).toInt(), (200 * density).toInt()) {
                inputMode = LocalInputModeManager.current
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    PaperComposerCornerPreview(Modifier.focusRequester(focus), platform)
                }
            } }
            var frame = 0L
            fun render() = onPaperUi { repeat(8) { scene.render(++frame * 32_000_000L).close() } }
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            fun button() = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single {
                it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty().any { label -> label.endsWith("поле ввода") }
            }
            fun capture(name: String): IntArray = onPaperUi {
                val bounds = button().boundsInRoot
                val bytes = scene.render(++frame * 32_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } }
                File("build/reports/composer-corner/$name-$case.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                try {
                    if (name == "idle") {
                        // Compare actual rendered pixels reflected across the corner bisector.
                        // A vertically displaced arc is asymmetric even when its hitbox is stable.
                        var difference = 0L
                        val span = (16 * density).toInt()
                        for (x in 0 until span) for (y in 0 until span) {
                            val first = image.getRGB(bounds.right.toInt() - 1 - x, bounds.top.toInt() + y)
                            val reflected = image.getRGB(bounds.right.toInt() - 1 - y, bounds.top.toInt() + x)
                            for (shift in listOf(0, 8, 16)) difference += kotlin.math.abs(
                                ((first shr shift) and 255) - ((reflected shr shift) and 255))
                        }
                        assertTrue(difference.toDouble() / (span * span * 3) < 3,
                            "The rendered arc and frame must share the corner center: $case")
                        ImageIO.write(image.getSubimage((bounds.right - 36 * density).toInt(), (bounds.top - 8 * density).toInt(),
                            (44 * density).toInt(), (44 * density).toInt()), "png", File("build/reports/composer-corner/detail-$case.png"))
                    }
                    image.getRGB(bounds.left.toInt(), bounds.top.toInt(),
                        bounds.width.toInt(), bounds.height.toInt(), null, 0, bounds.width.toInt())
                } finally { image.flush() }
            }
            try {
                render()
                val bounds = onPaperUi { button().boundsInRoot }
                assertEquals(24f * density, bounds.top, 1f, "The target starts at the frame, not at the editor inset")
                assertEquals(336f * density, bounds.right, 1f, "The target follows the physical right edge")
                val idle = capture("idle")
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, bounds.center, type = PointerType.Mouse) }
                render()
                val hovered = capture("hover")
                assertFalse(idle.contentEquals(hovered), "The arc becomes a visible circular action")
                onPaperUi {
                    scene.sendPointerEvent(PointerEventType.Press, bounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Release, bounds.center, type = PointerType.Mouse, button = PointerButton.Primary)
                }
                render()
                onPaperUi {
                    assertEquals("Развёрнуто", button().config[SemanticsProperties.StateDescription])
                    assertEquals(bounds, button().boundsInRoot, "Hover and expansion keep the corner target stable")
                }
                assertFalse(hovered.contentEquals(capture("expanded-hover")), "The arrows now indicate collapse")
                onPaperUi { scene.sendPointerEvent(PointerEventType.Move, Offset(2f, 2f), type = PointerType.Mouse) }
                render()
                assertContentEquals(idle, capture("leave"), "Mouse focus does not keep the action visible after leaving")
                onPaperUi {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    focus.requestFocus()
                    scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyUp))
                }
                render()
                assertFalse(idle.contentEquals(capture("keyboard-focus")))
                onPaperUi {
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown))
                    scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyUp))
                }
                render()
                onPaperUi { assertEquals("Свёрнуто", button().config[SemanticsProperties.StateDescription]) }
            } finally { onPaperUi { scene.close() } }
        }
    }
}
